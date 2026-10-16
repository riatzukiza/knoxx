(ns knoxx.frontend.pages.translations.draft-refresh-test
  "A conserved pre-completion snapshot installs before one necessary fresh read."
  (:require [cljs.test :as test]
            [knoxx.frontend.pages.translations.api :as api]
            [knoxx.frontend.pages.translations.lifecycle-fixture :as fixture]
            [knoxx.frontend.pages.translations.review-controller :as controller]
            [knoxx.frontend.pages.translations.view :as view]))

(test/use-fixtures :each fixture/api-fixture)

(defn- ^:async settle
  "Flush the bounded promise chain without waiting for an absent expected event."
  []
  (dotimes [_ 16] (await (js/Promise.resolve nil))))

(defn- state-setters
  "Retain actual installs and selected-row updater semantics in their order."
  [state installs]
  (merge
   (into {} (map (fn [k] [k #(swap! state assoc k %)])
                 [:set-saving! :set-notice! :set-error! :set-detail! :set-form! :set-loading!]))
   {:set-project! #(swap! state assoc :project %)
    :set-documents! (fn [rows] (swap! state assoc :documents rows)
                      (swap! installs conj (mapv :title rows)))
    :set-selected! (fn [next-value]
                     (swap! state update :selected #(if (fn? next-value) (next-value %) next-value)))}))

(defn- work-row
  "Use the existing resource inventory wire shape without a fabricated candidate."
  [title]
  (-> fixture/publication-review
      (dissoc :translation_revision :translated_at)
      (assoc :title title :work_state "missing" :allowed_actions ["dispatch"])))

(test/deftest ^:async held-old-snapshot-installs-before-exactly-one-needed-catchup
  (let [held (fixture/deferred) mutation (fixture/deferred)
        old (work-row "Old snapshot") fresh (work-row "Fresh after mutation")
        calls (atom 0) installs (atom []) params (atom [])
        state (atom {:selected {:publication (:publication old)} :documents []})
        setters (state-setters state installs)
        load #js {:current 0}
        context #js {:current (assoc (controller/initial-view "devel" "es")
                                    :selected (:selected @state))}
        op (controller/begin-operation! context load setters)]
    (set! api/list-publication-reviews
          (fn [] (if (= 1 (swap! calls inc)) (:promise held)
                   (js/Promise.resolve {:project "devel" :reviews [fresh]}))))
    (set! api/list-documents (fn [p] (swap! params conj p)
                              (js/Promise.resolve {:documents []})))
    (set! api/dispatch-publication-translation
          (fn [publication] (fixture/record! :publication-dispatch publication) (:promise mutation)))
    (let [running (view/run-translation-dispatch! old op)
          original-id (controller/load-documents! "devel" "es" load setters)]
      (try
        ((:resolve mutation) {:dispatched [{:outcome "dispatch/accepted"}]})
        (await running)
        (test/is (= 1 @calls) "no catch-up while the original snapshot is held")
        (test/is (= original-id (.-current load)) "the admitted generation is conserved")
        ((:resolve held) {:project "devel" :reviews [old]})
        (await (settle))
        (test/is (= [["Old snapshot"] ["Fresh after mutation"]] @installs))
        (test/is (= 2 @calls) "exactly one trailing fresh read")
        (test/is (= "Fresh after mutation" (:title (:selected @state))))
        (test/is (= (:publication old) (:publication (:selected @state))))
        (test/is (= [(:publication old)] (:publication-dispatch @fixture/calls)))
        (test/is (= [{:project "devel" :target-lang "es"}
                     {:project "devel" :target-lang "es"}] @params))
        (finally
          ((:resolve held) {:project "devel" :reviews [old]})
          ((:resolve mutation) {:dispatched []})
          (await (settle)))))))


(test/deftest ^:async snapshot-omission-preserves-one-current-input-freshness-read
  (let [held (fixture/deferred) mutation (fixture/deferred)
        old (work-row "Selected before snapshot") fresh (work-row "Fresh after omission")
        calls (atom 0) installs (atom []) install-ids (atom []) params (atom [])
        state (atom {:selected old :documents [old]})
        context #js {:current (assoc (controller/initial-view "devel" "es") :selected old)}
        load #js {:current 0}
        setters (assoc (state-setters state installs)
                       :set-documents!
                       (fn [rows]
                         (swap! state assoc :documents rows)
                         (swap! installs conj (mapv :title rows))
                         (swap! install-ids conj (.-current load)))
                       :set-selected!
                       (fn [next-value]
                         (let [current (:selected @state)
                               selected (if (fn? next-value) (next-value current) next-value)]
                           ;; The actual public work boundary advances its generation
                           ;; synchronously before the corresponding React state setter.
                           (controller/change-work! context selected)
                           (swap! state assoc :selected selected))))
        op (controller/begin-operation! context load setters)]
    (set! api/list-publication-reviews
          (fn [] (if (= 1 (swap! calls inc)) (:promise held)
                   (js/Promise.resolve {:project "devel" :reviews [fresh]}))))
    (set! api/list-documents
          (fn [p] (swap! params conj p) (js/Promise.resolve {:documents []})))
    (set! api/dispatch-publication-translation
          (fn [publication]
            (fixture/record! :publication-dispatch publication) (:promise mutation)))
    (let [running (view/run-translation-dispatch! old op)
          original-id (controller/load-documents! "devel" "es" load setters)
          original-work-generation (:work-generation (.-current context))]
      (try
        ((:resolve mutation) {:dispatched [{:outcome "dispatch/accepted"}]})
        (await running)
        (test/is (= 1 @calls) "no fresh read while the admitted snapshot is pending")
        (test/is (= original-id (.-current load)))
        ((:resolve held) {:project "devel" :reviews []})
        (await (settle))
        (test/is (= [[] ["Fresh after omission"]] @installs)
                 "the genuine omitted snapshot installs before the fresh result")
        (test/is (= [original-id (inc original-id)] @install-ids)
                 "work invalidation neither cancels nor replaces the admitted load")
        (test/is (= 2 @calls) "exactly one post-completion current-input read")
        (test/is (> (:work-generation (.-current context)) original-work-generation))
        (test/is (nil? (:selected @state)) "fresh inventory chooses no ambient work")
        (test/is (nil? (:selected (.-current context))))
        (test/is (= [(:publication old)] (:publication-dispatch @fixture/calls)))
        (test/is (= [{:project "devel" :target-lang "es"}
                     {:project "devel" :target-lang "es"}] @params))
        (finally
          ((:resolve held) {:project "devel" :reviews []})
          ((:resolve mutation) {:dispatched []})
          (await (settle)))))))
