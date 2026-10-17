(ns knoxx.frontend.pages.translations.draft-refresh-test
  "A conserved pre-completion snapshot installs before one necessary fresh read."
  (:require ["@testing-library/react" :as rtl]
            [cljs.test :as test]
            [helix.core :as hx]
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
(test/deftest ^:async several-completions-coalesce-after-the-original-snapshot-installs
  (let [held (fixture/deferred) calls (atom 0) installs (atom [])
        state (atom {:selected nil}) setters (state-setters state installs)
        load #js {:current 0} context #js {:current (controller/initial-view "devel" "es")}
        op (controller/begin-operation! context load setters)]
    (set! api/list-publication-reviews
          (fn [] (if (= 1 (swap! calls inc)) (:promise held)
                   (js/Promise.resolve {:project "devel" :reviews []}))))
    (set! api/list-documents (fn [_] (js/Promise.resolve {:documents []})))
    (let [original-id (controller/load-documents! "devel" "es" load setters)]
      ((:refresh-documents! op))
      ((:refresh-documents! op))
      (test/is (= 1 @calls))
      (test/is (= original-id (.-current load)))
      ((:resolve held) {:project "devel" :reviews []})
      (await (settle))
      (test/is (= 2 @calls))
      (test/is (= [[] []] @installs)))))

(test/deftest ^:async a-read-started-for-completion-needs-no-third-read
  (let [held (fixture/deferred) calls (atom 0) installs (atom [])
        state (atom {:selected nil}) setters (state-setters state installs)
        load #js {:current 0} context #js {:current (controller/initial-view "devel" "es")}
        op (controller/begin-operation! context load setters)]
    (set! api/list-publication-reviews (fn [] (swap! calls inc) (:promise held)))
    (set! api/list-documents (fn [_] (js/Promise.resolve {:documents []})))
    ((:refresh-documents! op))
    ((:resolve held) {:project "devel" :reviews []})
    (await (settle))
    (test/is (= 1 @calls))
    (test/is (= [[]] @installs))))

(test/deftest ^:async queued-refresh-does-not-outlive-context-or-retry-read-failure
  (doseq [boundary [:project :filter-aba :work :unmount :failure]]
    (let [held (fixture/deferred) calls (atom 0) installs (atom []) params (atom [])
          state (atom {:selected nil}) setters (state-setters state installs)
          load #js {:current 0} context #js {:current (controller/initial-view "devel" "es")}
          op (controller/begin-operation! context load setters)]
      (set! api/list-publication-reviews
            (fn [] (if (= 1 (swap! calls inc)) (:promise held)
                     (if (= boundary :failure)
                       (js/Promise.reject (js/Error. "owned refresh read failure"))
                       (js/Promise.resolve {:project "devel" :reviews []})))))
      (set! api/list-documents
            (fn [p] (swap! params conj p) (js/Promise.resolve {:documents []})))
      (controller/load-documents! "devel" "es" load setters)
      ((:refresh-documents! op))
      (case boundary
        :project (do (controller/change-project! context "fresh")
                     (controller/cancel! load (.-current load)))
        :filter-aba (do (controller/change-filter! context "fr")
                        (controller/change-filter! context "es")
                        (controller/cancel! load (.-current load)))
        :work (controller/change-work! context {:publication "publications/new"})
        :unmount (do (controller/unmount-view! context)
                     (controller/cancel! load (.-current load)))
        nil)
      ((:resolve held) {:project "devel" :reviews []})
      (await (settle))
      (test/is (= (if (contains? #{:work :failure} boundary) 2 1) @calls))
      (when (= boundary :work)
        (test/is (= [[] []] @installs) "work navigation retains current list freshness")
        (test/is (= [{:project "devel" :target-lang "es"}
                     {:project "devel" :target-lang "es"}] @params)))
      (when (contains? #{:project :filter-aba :unmount} boundary)
        (test/is (empty? @installs) "normal boundary invalidation suppresses abandoned installs"))
      (when (= boundary :failure)
        (test/is (= "owned refresh read failure" (:set-error! @state)))))))

(test/deftest ^:async a-failed-held-read-ends-the-bounded-attempt-without-a-trailing-retry
  (let [held (fixture/deferred) calls (atom 0) installs (atom [])
        state (atom {:selected nil}) setters (state-setters state installs)
        load #js {:current 0} context #js {:current (controller/initial-view "devel" "es")}
        op (controller/begin-operation! context load setters)]
    (set! api/list-publication-reviews (fn [] (swap! calls inc) (:promise held)))
    (controller/load-documents! "devel" "es" load setters)
    ((:refresh-documents! op))
    ((:reject held) (js/Error. "owned original snapshot failure"))
    (await (settle))
    (test/is (= 1 @calls))
    (test/is (= "owned original snapshot failure" (:set-error! @state)))))

(defn- ^:async release-detail!
  "Settle one actual GET before inspecting the other still-pending producer."
  [pending detail]
  (await (rtl/act (fn [] ((:resolve pending) detail) (js/Promise.resolve nil)))))

(test/deftest ^:async document-review-keeps-S2-D2-with-either-real-detail-order
  (doseq [order [:direct-first :inventory-first]]
    ((:before fixture/api-fixture))
    (let [mutation (fixture/deferred) direct (fixture/deferred) inventory (fixture/deferred)
          gets (atom 0) done? (atom false)
          canonical (update fixture/doc-detail :segments
                            #(mapv (fn [segment]
                                     (assoc segment :labels [{:id "review/new" :corrected_text "canonical"}])) %))]
      (set! api/review-document
            (fn ([id lang payload] (fixture/record! :review [id lang nil payload]) (:promise mutation))
                ([id lang scope payload] (fixture/record! :review [id lang scope payload]) (:promise mutation))))
      (set! api/list-documents
            (fn [_] (js/Promise.resolve
                     {:documents [(cond-> fixture/doc-summary @done? (assoc :approved 2))]})))
      (let [next-detail! (fn []
                           (case (swap! gets inc)
                             1 (js/Promise.resolve fixture/doc-detail)
                             2 (:promise direct)
                             (:promise inventory)))]
        (set! api/get-document
              (fn ([_id _lang] (next-detail!))
                  ([_id _lang _scope] (next-detail!)))))
      (let [r (rtl/render (hx/$ view/translation-review-page))]
        (try
          (await (fixture/select-first-document r))
          (.click rtl/fireEvent (.getByText r "Hola mundo"))
          (.click rtl/fireEvent (.getByRole r "button" #js {:name "Approve All"}))
          (.click rtl/fireEvent (.getByText r "Adiós"))
          (.change rtl/fireEvent (.getByLabelText r "Corrected translation")
                   #js {:target #js {:value "S2 D2"}})
          (reset! done? true)
          (await (rtl/act (fn [] ((:resolve mutation) {:segments_reviewed 2}) (js/Promise.resolve nil))))
          (await (fixture/wait-until "both actual legacy detail producers" #(= 3 @gets)))
          (await (release-detail! (if (= order :direct-first) direct inventory) canonical))
          (test/is (= "S2 D2" (some-> (.queryByLabelText r "Corrected translation") .-value)))
          (await (release-detail! (if (= order :direct-first) inventory direct) canonical))
          (test/is (= "S2 D2" (some-> (.queryByLabelText r "Corrected translation") .-value)))
          (test/is (some? (.queryByRole r "heading" #js {:name "Segment 1"})))
          (test/is (= [["docs/doc-1" "es" {:project "devel" :garden-id "gardens/sonic"}
                       {:overall "approve"}]] (:review @fixture/calls)))
          (test/is (false? (some-> (.queryByRole r "button" #js {:name "Submit review"}) .-disabled)))
          (finally ((:resolve mutation) {:segments_reviewed 2})
                   ((:resolve direct) canonical) ((:resolve inventory) canonical)
                   (rtl/cleanup)))))))
