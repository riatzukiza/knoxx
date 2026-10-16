(ns knoxx.backend.infra.translation-dispatch-observation-test
  "Original-public-API controls for the two inherited nil-fallback catches."
  (:require [cljs.test :refer [deftest is]]
            [knoxx.backend.extern.translation-dispatch-fixture :as fixture]
            [knoxx.backend.infra.translation-dispatch :as dispatch]
            [knoxx.backend.infra.translation-evidence-store :as store]))

(defn- ^:async binding-fallback!
  "Exercise the actual bounded two-bind path without another worker send."
  [failure]
  (let [{:keys [deps batches]} (fixture/fixture)
        attempts (atom 0)
        fault (fixture/binding-fault-store (:evidence-store deps) attempts failure)
        result (await (dispatch/dispatch-work! (assoc deps :evidence-store fault)
                                               (fixture/work) (fixture/context)))]
    (is (= :dispatch/accepted (:dispatch/outcome result)))
    (is (= 2 @attempts))
    (is (= 1 (count @batches)))
    (is (nil? (:dispatch/batch-id (:dispatch/record result))))
    (is (empty? (await (store/completed-translations! fault fixture/evidence-scope))))
    result))

(defn- ^:async read-fallback!
  "Exercise one actual unreadable in-flight batch and retain the same claim."
  [read-batch]
  (let [reads (atom 0)
        {:keys [deps batches]} (fixture/fixture
                                :batch-read (fn [batch-id opts]
                                              (swap! reads inc)
                                              (is (= "batch-1" batch-id))
                                              (is (= {:org_id "org-1"} opts))
                                              (read-batch)))
        original (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
        result (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (is (= :dispatch/duplicate (:dispatch/outcome result)))
    (is (= (:dispatch/record original) (:dispatch/record result)))
    (is (= 1 @reads (count @batches)))
    (is (empty? (await (store/completed-translations! (:evidence-store deps)
                                                       fixture/evidence-scope))))
    result))

(defn- expected-warning
  "The complete fixed context, excluding every opaque error property."
  [operation present?]
  ["translation-dispatch.degraded"
   {:operation operation :fallback :nil :primary-error-present? present?}])

(deftest ^:async successful-and-missing-nonthrowing-results-do-not-warn
  (let [seen (atom [])]
    (await (fixture/with-warning-observer! (fixture/warning-recorder seen)
             (^:async fn []
               (let [{:keys [deps]} (fixture/fixture :batch-view {:status "in_progress"})]
                 (is (= :dispatch/accepted
                        (:dispatch/outcome (await (dispatch/dispatch-work!
                                                   deps (fixture/work) (fixture/context))))))
                 (is (= :dispatch/duplicate
                        (:dispatch/outcome (await (dispatch/dispatch-work!
                                                   deps (fixture/work) (fixture/context)))))))
               (await (read-fallback! (constantly nil))))))
    (is (= [] @seen))))

(deftest ^:async both-primary-failures-emit-only-fixed-safe-context
  (let [seen (atom []) failure (fixture/error)]
    (await (fixture/with-warning-observer! (fixture/warning-recorder seen)
             (^:async fn []
               (await (binding-fallback! failure))
               (await (read-fallback! (fn [] (throw failure)))))))
    (is (= [(expected-warning :bind-batch true)
            (expected-warning :bind-batch true)
            (expected-warning :read-batch true)] @seen))))

(deftest ^:async observer-errors-and-nil-throws-cannot-replace-primary-fallbacks
  (doseq [failure [(fixture/error) nil]]
    (let [seen (atom []) primary (fixture/error)]
      (await (fixture/with-warning-observer! (fixture/warning-failure seen failure)
               (^:async fn []
                 (await (binding-fallback! primary))
                 (await (read-fallback! (fn [] (throw primary)))))))
      (is (= [(expected-warning :bind-batch true)
              (expected-warning :bind-batch true)
              (expected-warning :read-batch true)] @seen)))))

(deftest ^:async absent-observer-cannot-replace-primary-fallbacks
  (let [primary (fixture/error)]
    (await (fixture/with-warning-observer! nil
             (^:async fn []
               (await (binding-fallback! primary))
               (await (read-fallback! (fn [] (throw primary)))))))))

(deftest ^:async nil-primary-throw-is-still-an-observed-failure
  (let [seen (atom [])]
    (await (fixture/with-warning-observer! (fixture/warning-recorder seen)
             (^:async fn []
               (await (binding-fallback! nil))
               (await (read-fallback! (fn [] (fixture/throw-value! nil)))))))
    (is (= [(expected-warning :bind-batch false)
            (expected-warning :bind-batch false)
            (expected-warning :read-batch false)] @seen))))
