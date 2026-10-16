(ns knoxx.frontend.pages.translations.review-controller-test
  "Availability boundaries between resource work and legacy enrichment."
  (:require [cljs.test :as test]
            [knoxx.frontend.pages.translations.api :as api]
            [knoxx.frontend.pages.translations.review-controller :as controller]))

(def ^:private original-list-documents api/list-documents)
(def ^:private original-list-publication-reviews api/list-publication-reviews)

(test/use-fixtures
  :each
  {:after (fn []
            (set! api/list-documents original-list-documents)
            (set! api/list-publication-reviews
                  original-list-publication-reviews))})

(defn- completion
  []
  (let [resolve! (atom nil)
        promise (js/Promise. (fn [deliver! _reject]
                               (reset! resolve! deliver!)))]
    {:promise promise :resolve! #(when-let [deliver! @resolve!] (deliver! %))}))

(defn- controller-state
  [completed]
  (let [state (atom {:documents nil
                     :selected nil
                     :loading nil
                     :error :unset
                     :project "devel"})]
    {:state state
     :setters
     {:set-loading! (fn [loading?]
                      (swap! state assoc :loading loading?)
                      (when-not loading? ((:resolve! completed) true)))
      :set-error! #(swap! state assoc :error %)
      :set-documents! #(swap! state assoc :documents %)
      :set-selected! (fn [next-value]
                       (swap! state update :selected
                              #(if (fn? next-value)
                                 (next-value %)
                                 next-value)))
      :set-project! #(swap! state assoc :project %)}}))

(def ^:private resource-work
  {:publication "publications/resource-es"
   :document "docs/resource"
   :garden "gardens/sonic"
   :project "devel"
   :source_locale "en"
   :locale "es"
   :title "Resource survives"
   :revision "source-1"
   :work_state "missing"
   :reviewable false
   :approved false
   :allowed_actions ["dispatch"]})

(test/deftest ^:async resource-inventory-survives-legacy-list-failure
  (let [completed (completion)
        {:keys [state setters]} (controller-state completed)]
    (set! api/list-publication-reviews
          (fn []
            (js/Promise.resolve {:project "devel"
                                 :reviews [resource-work]})))
    (set! api/list-documents
          (fn [_]
            (js/Promise.reject (js/Error. "OpenPlanner unavailable"))))
    (controller/load-documents! "devel" "" #js {:current 0} setters)
    (await (:promise completed))
    (test/is (= "Resource survives"
           (:title (first (:documents @state)))))
    (test/is (= "publications/resource-es"
           (:publication (first (:documents @state)))))
    (test/is (nil? (:error @state)))
    (test/is (false? (:loading @state)))))

(test/deftest ^:async legacy-only-list-failure-remains-visible
  (let [completed (completion)
        {:keys [state setters]} (controller-state completed)]
    (set! api/list-publication-reviews
          (fn []
            (js/Promise.resolve {:project "devel" :reviews []})))
    (set! api/list-documents
          (fn [_]
            (js/Promise.reject (js/Error. "OpenPlanner unavailable"))))
    (controller/load-documents! "devel" "" #js {:current 0} setters)
    (await (:promise completed))
    (test/is (= [] (:documents @state)))
    (test/is (= "OpenPlanner unavailable" (:error @state)))
    (test/is (false? (:loading @state)))))

(defn- observed-setters
  "Record the actual completion callbacks an operation can reach."
  [events]
  (into {}
        (map (fn [setter-key] [setter-key #(swap! events conj [setter-key %])])
             [:set-saving! :set-notice! :set-error! :set-detail! :set-form!])))

(test/deftest project-filter-and-work-generations-exclude-old-completion
  (let [view #js {:current (controller/initial-view "devel" "es")}
        events (atom [])
        setters (observed-setters events)
        row {:document_id "docs/a" :target_lang "es" :project "devel"}]
    (controller/change-work! view row)
    (let [captured (.-current view)
          old-operation (controller/begin-operation! view #js {:current 0} setters)]
      (controller/change-filter! view "fr")
      (controller/change-filter! view "es")
      ((:set-notice! old-operation) "old filter")
      (test/is (false? (controller/export-current? view captured)) "filter ABA is stale")
      (test/is (empty? @events))
      (controller/change-project! view "other")
      (controller/change-project! view "devel")
      (test/is (false? (controller/current-view? view captured)) "project ABA is stale"))))

(test/deftest same-work-refresh-keeps-operation-but-work-aba-does-not
  (let [view #js {:current (controller/initial-view "devel" "es")}
        events (atom [])
        a {:document_id "docs/a" :target_lang "es" :project "devel"}
        b (assoc a :document_id "docs/b")]
    (controller/change-work! view a)
    (let [op (controller/begin-operation! view #js {:current 0} (observed-setters events))]
      (test/is (false? (controller/change-work! view (assoc a :title "Refreshed A"))))
      ((:set-notice! op) "current")
      (test/is (= [[:set-notice! "current"]] @events))
      (reset! events [])
      (controller/change-work! view b)
      (controller/change-work! view a)
      ((:set-notice! op) "stale A")
      ((:set-saving! op) false)
      (test/is (empty? @events)))))

(test/deftest old-finally-cannot-release-the-new-current-operation
  (let [view #js {:current (controller/initial-view "devel" "")}
        events (atom [])
        setters (observed-setters events)
        old-op (controller/begin-operation! view #js {:current 0} setters)]
    (controller/change-project! view "fresh")
    (let [current-op (controller/begin-operation! view #js {:current 0} setters)]
      ((:set-saving! current-op) true)
      ((:set-error! old-op) "old rejection")
      ((:set-notice! old-op) "old success")
      ((:set-saving! old-op) false)
      (test/is (= [[:set-saving! true]] @events))
      ((:set-saving! current-op) false)
      (test/is (= [[:set-saving! true] [:set-saving! false]] @events)))))

(test/deftest unmount-invalidates-all-operation-and-export-feedback-callbacks
  (let [view #js {:current (controller/initial-view "devel" "es")}
        events (atom [])
        setters (observed-setters events)
        op (controller/begin-operation! view #js {:current 0} setters)
        export (controller/export-setters view setters)]
    (controller/unmount-view! view)
    (doseq [setter-key [:set-saving! :set-notice! :set-error! :set-detail! :set-form!]]
      ((get op setter-key) :abandoned))
    ((:set-notice! export) "abandoned download")
    ((:set-error! export) "abandoned rejection")
    (test/is (empty? @events))
    (controller/mount-view! view)
    ((:set-notice! op) "old lifetime")
    (test/is (empty? @events))))

(test/deftest ^:async stale-project-response-is-invalid-before-any-new-project-request
  (let [held (completion)
        completed (completion)
        {:keys [state setters]} (controller-state completed)
        load-seq #js {:current 0}]
    (set! api/list-publication-reviews (fn [] (:promise held)))
    (set! api/list-documents
          (fn [_] (throw (js/Error. "old response must not start legacy reads"))))
    (controller/load-documents! "devel" "" load-seq setters)
    (controller/cancel! load-seq (.-current load-seq))
    ((:resolve! held) {:project "devel" :reviews [resource-work]})
    (await (js/Promise.resolve nil))
    (await (js/Promise.resolve nil))
    (test/is (nil? (:documents @state)))
    (test/is (nil? (:selected @state)))
    (test/is (nil? (:error @state)))
    (test/is (true? (:loading @state)) "no old finally releases replacement loading")))

(test/deftest ^:async old-completion-preserves-the-original-current-pending-load
  (let [held (completion)
        completed (completion)
        {:keys [state setters]} (controller-state completed)
        calls (atom 0)
        load-seq #js {:current 0}
        view #js {:current (controller/initial-view "devel" "es")}
        op (controller/begin-operation! view load-seq (merge setters (observed-setters (atom []))))]
    (set! api/list-publication-reviews
          (fn [] (swap! calls inc) (:promise held)))
    (set! api/list-documents
          (fn [params] (js/Promise.resolve {:documents [(assoc resource-work :locale (:target-lang params))]})))
    (controller/change-filter! view "fr")
    (controller/load-documents! "devel" "fr" load-seq setters)
    (let [current-id (.-current load-seq)]
      ((:refresh-documents! op))
      (test/is (= current-id (.-current load-seq)))
      (test/is (= 1 @calls)))
    ((:resolve! held) {:project "devel" :reviews []})
    (await (:promise completed))
    (test/is (= 1 (count (:documents @state))))
    (test/is (false? (:loading @state)))))
