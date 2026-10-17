(ns knoxx.backend.extern.node-fs-cleanup-test
  "Public sandbox cleanup fallbacks, fixed observations and owned native restoration."
  (:require ["node:fs/promises" :as native-fs]
            [cljs.test :refer [deftest is]]
            [knoxx.backend.domain.error-observatory :as observatory]
            [knoxx.backend.extern.clio-store-fixture :as disk]
            [knoxx.backend.extern.node-fs :as files]))

(def ^:private removals
  [{:operation :remove-metadata :remove! files/remove-sandbox-metadata!
    :options {:force true}}
   {:operation :remove-directory :remove! files/remove-sandbox-directory!
    :options {:recursive true :force true}}])

(defn- restore-rm! [descriptor replacement]
  (let [actual (aget native-fs "rm")]
    (.defineProperty js/Object native-fs "rm" descriptor)
    (is (identical? replacement actual) "Only the installed native replacement was active")
    (let [restored (.getOwnPropertyDescriptor js/Object native-fs "rm")]
      (is (= (mapv #(aget descriptor %) ["value" "writable" "enumerable" "configurable"])
             (mapv #(aget restored %) ["value" "writable" "enumerable" "configurable"]))
          "Restore the full original public descriptor"))))

(defn- ^:async with-native-removal! [replacement observer action]
  (let [descriptor (.getOwnPropertyDescriptor js/Object native-fs "rm")]
    (when-not (and descriptor (aget descriptor "configurable") (fn? (aget descriptor "value")))
      (throw (ex-info "Unsupported Node rm fixture descriptor" {})))
    (try
      (.defineProperty js/Object native-fs "rm"
                       (.assign js/Object #js {} descriptor #js {:value replacement}))
      (with-redefs [observatory/log-warning! observer]
        (await (action)))
      (finally (restore-rm! descriptor replacement)))))

(defn- warning-recorder [seen mode]
  (when-not (= :absent mode)
    (fn [boundary context]
      (swap! seen conj [boundary context])
      (case mode
        :throws-error (throw (ex-info "Private observer error" {}))
        :throws-nil (throw nil)
        nil))))

(defn- refuse! [mode primary]
  (case mode :throws (throw primary) :rejects (js/Promise.reject primary)))

(defn- expected-warning [operation primary]
  ["sandbox-cleanup.degraded"
   {:operation operation :fallback :nil :primary-error-present? (some? primary)}])

(deftest ^:async successful-native-cleanup-keeps-result-options-and-quiet-observation
  (doseq [{:keys [remove! options]} removals]
    (let [seen (atom []) calls (atom []) target "/inert-owned-fixture/private-sandbox"
          native-remove (fn [supplied-path supplied-options]
                          (swap! calls conj [supplied-path (js->clj supplied-options :keywordize-keys true)])
                          (js/Promise.resolve :host-removed))
          action (fn [] (remove! target))]
      (is (= :host-removed
             (await (with-native-removal! native-remove (warning-recorder seen :records) action))))
      (is (= [[target options]] @calls))
      (is (= [] @seen)))))

(deftest ^:async real-owned-absent-cleanup-is-idempotent-and-quiet
  (let [root (disk/temp-directory!) seen (atom [])]
    (try
      (with-redefs [observatory/log-warning! (warning-recorder seen :records)]
        (is (nil? (await (files/remove-sandbox-metadata! (str root "/missing.json")))))
        (is (nil? (await (files/remove-sandbox-directory! (str root "/missing-directory")))))
        (is (nil? (await (files/remove-sandbox-directory! (str root "/missing-directory"))))))
      (is (= [] @seen) "force:true handles expected absence without a catch warning")
      (finally (await (files/remove-sandbox-directory! root))))))

(deftest ^:async cleanup-errors-and-nil-preserve-fallback-even-with-broken-observation
  (doseq [{:keys [operation remove! options]} removals
          fault-mode [:throws :rejects]
          primary [(ex-info "Private cleanup path must not be logged" {:private "opaque"}) nil]
          observer-mode [:records :throws-error :throws-nil :absent]]
    (let [seen (atom []) calls (atom []) target "/inert-owned-fixture/private-sandbox"
          native-remove (fn [supplied-path supplied-options]
                          (swap! calls conj [supplied-path (js->clj supplied-options :keywordize-keys true)])
                          (refuse! fault-mode primary))
          action (fn [] (remove! target))]
      (is (nil? (await (with-native-removal! native-remove
                                            (warning-recorder seen observer-mode) action))))
      (is (= [[target options]] @calls))
      (is (= (if (= :absent observer-mode) [] [(expected-warning operation primary)]) @seen)
          "Only the fixed scalar failure context reaches the observer"))))

(deftest ^:async broken-observation-cannot-prevent-following-owned-cleanup
  (doseq [observer-mode [:throws-error :throws-nil]]
    (let [seen (atom []) calls (atom []) primary (ex-info "Private metadata removal failure" {})
          native-remove (fn [target options]
                          (swap! calls conj [target (js->clj options :keywordize-keys true)])
                          (if (= target "/inert-owned-fixture/metadata.json")
                            (js/Promise.reject primary) (js/Promise.resolve :directory-removed)))
          action (^:async fn []
                   (is (nil? (await (files/remove-sandbox-metadata! "/inert-owned-fixture/metadata.json"))))
                   (is (= :directory-removed
                          (await (files/remove-sandbox-directory! "/inert-owned-fixture/workspace")))))]
      (await (with-native-removal! native-remove (warning-recorder seen observer-mode) action))
      (is (= [["/inert-owned-fixture/metadata.json" {:force true}]
              ["/inert-owned-fixture/workspace" {:recursive true :force true}]] @calls))
      (is (= [(expected-warning :remove-metadata primary)] @seen)))))
