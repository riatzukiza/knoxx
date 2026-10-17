(ns knoxx.frontend.pages.translations.draft-interaction-test
  "Real editor callbacks survive their original mutation and canonical reload."
  (:require ["@testing-library/react" :as rtl]
            [cljs.test :as test]
            [helix.core :as hx]
            [knoxx.frontend.pages.translations.api :as api]
            [knoxx.frontend.pages.translations.lifecycle-fixture :as fixture]
            [knoxx.frontend.pages.translations.logic :as logic]
            [knoxx.frontend.pages.translations.review-controller :as controller]
            [knoxx.frontend.pages.translations.view :as view]))

(test/use-fixtures :each fixture/api-fixture)

(defn- value
  "Observe a missing editor as nil rather than throwing a setup exception."
  [^js rendered label]
  (some-> (.queryByLabelText rendered label) .-value))

(defn- edit!
  "Use the same controlled inputs as a reviewer typing all scores and text."
  [^js rendered text]
  (.change rtl/fireEvent (.getByLabelText rendered "Corrected translation")
           #js {:target #js {:value text}})
  (.change rtl/fireEvent (.getByLabelText rendered "Editor notes")
           #js {:target #js {:value (str text " notes")}})
  (doseq [[label choice] (if (= text "D1")
                          {"adequacy" "good" "fluency" "good" "terminology" "correct" "risk" "safe"}
                          {"adequacy" "poor" "fluency" "adequate" "terminology" "major_errors" "risk" "sensitive"})]
    (.change rtl/fireEvent (.getByLabelText rendered label)
             #js {:target #js {:value choice}})))

(defn- reviewed-aggregate
  "Change immutable review identity while retaining the same admitted candidate."
  []
  (update fixture/resource-split-review :splits
          #(mapv (fn [split]
                   (assoc split :review_id "review/new" :review_status "approved"
                          :corrected_text "D1" :editor_notes "D1 notes" :adequacy "good"
                          :fluency "good" :terminology "correct" :risk "safe")) %)))

(test/deftest ^:async resource-D2-survives-the-actual-detail-and-form-sync-reset
  (let [pending (fixture/deferred)
        inventory (atom (fixture/resource-review-row fixture/resource-split-review))]
    (set! api/list-publication-reviews
          (fn [] (fixture/record! :publication-reviews true)
            (js/Promise.resolve {:project "devel" :reviews [@inventory]})))
    (set! api/submit-publication-split-review
          (fn [payload] (fixture/record! :publication-split-review payload) (:promise pending)))
    (let [r (rtl/render (hx/$ view/translation-review-page))]
      (try
        (await (fixture/select-first-document r))
        (.click rtl/fireEvent (.getByText r "Hola mundo"))
        (await (fixture/wait-until "S1 editor" #(some? (.queryByLabelText r "Corrected translation"))))
        (edit! r "D1")
        (.click rtl/fireEvent (.getByRole r "button" #js {:name "Approve split"}))
        (let [original (:publication-split-review @fixture/calls)]
          (test/is (= "D1" (:corrected_text (first original))))
          (edit! r "D2")
          (reset! inventory (fixture/resource-review-row (reviewed-aggregate)))
          (await (rtl/act (fn [] ((:resolve pending) {:status "recorded"})
                           (js/Promise.resolve nil))))
          (await (fixture/wait-until "canonical whole-output approval enabled"
                   #(when-let [^js button (.queryByRole r "button" #js {:name "Approve whole output"})]
                      (not (.-disabled button)))))
          (test/is (= "D2" (value r "Corrected translation")))
          (test/is (= "D2 notes" (value r "Editor notes")))
          (test/is (= "poor" (value r "adequacy")))
          (test/is (= "adequate" (value r "fluency")))
          (test/is (= "major_errors" (value r "terminology")))
          (test/is (= "sensitive" (value r "risk")))
          (test/is (some? (.queryByRole r "heading" #js {:name "Split 0"})))
          (test/is (= original (:publication-split-review @fixture/calls)))
          (test/is (= 2 (count (:publication-reviews @fixture/calls)))))
        (finally
          (await (rtl/act (fn [] ((:resolve pending) {:status "recorded"})
                           (js/Promise.resolve nil)))))))))

(def ^:private entries
  [{:kind :label :button "Submit review" :resource? false}
   {:kind :publication-split-review :button "Approve split" :resource? true}
   {:kind :publication-bulk-review :button "Approve All" :resource? true}
   {:kind :review :button "Approve All" :resource? false}
   {:kind :publication-approval :button "Approve whole output" :resource? true}
   {:kind :dispatch :button "Dispatch" :resource? false}
   {:kind :retry :button "Retry" :resource? false}])

(defn- initial-row
  "Prove actual editable authority alongside each admitted normal UI action."
  [{:keys [kind resource?]}]
  (if resource?
    (fixture/resource-review-row
     (if (= kind :publication-approval)
       (update fixture/resource-split-review :splits
               #(mapv (fn [split] (assoc split :review_status "approved" :review_id "review/initial")) %))
       fixture/resource-split-review))
    (when (contains? #{:dispatch :retry} kind)
      (-> fixture/publication-review
          (dissoc :translation_revision :translated_at)
          (assoc :work_state (if (= kind :retry) "failed" "missing")
                 :allowed_actions [(name kind)])))))

(defn- hold-clients!
  "Keep source-declared arities and all seven entries on one original request."
  [pending]
  (set! api/submit-label
        (fn ([id payload] (fixture/record! :label [id nil payload]) (:promise pending))
            ([id scope payload] (fixture/record! :label [id scope payload]) (:promise pending))))
  (set! api/review-document
        (fn ([id lang payload] (fixture/record! :review [id lang nil payload]) (:promise pending))
            ([id lang scope payload] (fixture/record! :review [id lang scope payload]) (:promise pending))))
  (set! api/submit-publication-split-review
        (fn [p] (fixture/record! :publication-split-review p) (:promise pending)))
  (set! api/submit-publication-bulk-review
        (fn [p] (fixture/record! :publication-bulk-review p) (:promise pending)))
  (set! api/approve-publication-translation
        (fn [p] (fixture/record! :publication-approval p) (:promise pending)))
  (set! api/dispatch-publication-translation
        (fn [p] (fixture/record! :publication-dispatch p) (:promise pending))))

(defn- canonical-legacy-detail
  "Exercise the real newest-label form projection after direct and indirect GETs."
  []
  (update (assoc fixture/doc-detail :summary {:total_segments 2 :approved 2 :overall_status "fully_approved"}) :segments
          #(mapv (fn [segment]
                   (assoc segment :status "approved"
                          :labels [{:id "review/new" :adequacy "good"
                                    :corrected_text "D1" :editor_notes "D1 notes"}])) %)))

(defn- expected-request
  "Bind original input coordinates and D1, never later S2/D2."
  [kind row]
  (let [form (assoc logic/default-label :adequacy "good"
                    :corrected_text "D1" :editor_notes "D1 notes")
        scope {:project "devel" :garden-id "gardens/sonic"}]
    (case kind
      :label [["seg-a" scope (logic/prepare-label-payload form "approve")]]
      :review [["docs/doc-1" "es" scope {:overall "approve"}]]
      :publication-split-review [{:candidate_set_id "candidate-set/doc-1-es"
                                 :split_id "split/doc-1/0" :status "approved"
                                 :adequacy "good" :fluency "good" :terminology "correct"
                                 :risk "safe" :corrected_text "D1" :editor_notes "D1 notes"}]
      :publication-bulk-review [{:candidate_set_id "candidate-set/doc-1-es" :status "approved"
                                :adequacy "good" :fluency "good" :terminology "correct"
                                :risk "safe" :editor_notes "D1 notes"}]
      :publication-approval [(logic/approval-request row)]
      ["publications/doc-1-es"])))

(defn- assert-current-draft!
  "Check the later text, notes and every score after the real effects settle."
  [r]
  (test/is (= "D2" (value r "Corrected translation")))
  (test/is (= "D2 notes" (value r "Editor notes")))
  (test/is (= "poor" (value r "adequacy")))
  (test/is (= "adequate" (value r "fluency")))
  (test/is (= "major_errors" (value r "terminology")))
  (test/is (= "sensitive" (value r "risk"))))

(defn- install-case-clients!
  "Change real canonical projections and retain genuine GET/reconcile producers."
  [pending row detail outcome]
  (hold-clients! pending)
  (set! api/list-publication-reviews
        (fn [] (fixture/record! :publication-reviews true)
          (js/Promise.resolve {:project "devel" :reviews (if @row [@row] [])})))
  (set! api/get-document
        (fn ([id lang] (fixture/record! :get [id lang nil]) (js/Promise.resolve @detail))
            ([id lang scope] (fixture/record! :get [id lang scope]) (js/Promise.resolve @detail))))
  (set! api/list-documents
        (fn [params]
          (fixture/record! :list params)
          (js/Promise.resolve
           {:documents [(if (= @detail fixture/doc-detail) fixture/doc-summary
                          (assoc fixture/doc-summary :approved 2 :overall_status "fully_approved"))]})))
  (when (= outcome :reconcile-failure)
    (set! api/reconcile-publication
          (fn [id] (fixture/record! :reconcile id)
            (js/Promise.reject (js/Error. "owned reconcile failure"))))))

(defn- advance-canonical!
  "Supply changed same-authority review data; never manufacture a new candidate."
  [{:keys [kind resource?]} row detail]
  (reset! detail (canonical-legacy-detail))
  (when resource?
    (reset! row (cond-> (fixture/resource-review-row (reviewed-aggregate))
                  (= kind :publication-approval) (assoc :approved true))))
  (when (contains? #{:dispatch :retry} kind)
    (swap! row assoc :title "Doc One refreshed" :work_state "running" :allowed_actions [])))

(defn- ^:async flush-view
  "Let the actual detail and form effects finish inside React's act boundary."
  []
  (await (rtl/act
          (fn []
            (js/Promise. (fn [resolve! _reject]
                           (js/setTimeout resolve! 0)))))))

(defn- ^:async finish-case!
  "Finish the original request, then assert its canonical and editor effects."
  [^js r {:keys [kind resource?]} pending original request-key outcome]
  (await (rtl/act
          (fn []
            (if (= outcome :rejection)
              ((:reject pending) (js/Error. "owned mutation rejection"))
              ((:resolve pending) {:segments_reviewed 2 :dispatched [{:outcome "dispatch/accepted"}]}))
            (js/Promise.resolve nil))))
  (await (flush-view))
  (let [label (if resource? "Approve split" "Submit review")]
    (test/is (false? (some-> (.queryByRole r "button" #js {:name label}) .-disabled))))
  (assert-current-draft! r)
  (test/is (= original (get @fixture/calls request-key)))
  (test/is (= (if (and (= kind :publication-approval) (not= outcome :rejection))
               ["publications/doc-1-es"] nil) (:reconcile @fixture/calls)))
  (test/is (= (if (= outcome :success) 2 1) (count (:publication-reviews @fixture/calls))))
  (when (= outcome :success)
    (if resource?
      (test/is (some? (.queryByRole r "button" #js {:name "Approve All"})))
      (test/is (> (count (:get @fixture/calls)) 1) "real legacy reload installed new review identity"))))

(defn- ^:async run-entry-case!
  "Drive one admitted UI entry, editable case and original payload schedule."
  [{:keys [kind button] :as entry} other-segment? outcome]
  ((:before fixture/api-fixture))
  (let [pending (fixture/deferred) row (atom (initial-row entry))
        detail (atom fixture/doc-detail)
        request-key (if (contains? #{:dispatch :retry} kind) :publication-dispatch kind)]
    (install-case-clients! pending row detail outcome)
    (let [r (rtl/render (hx/$ view/translation-review-page))]
      (try
        (await (fixture/select-first-document r))
        (.click rtl/fireEvent (.getByText r "Hola mundo"))
        (await (fixture/wait-until "real editable S1" #(some? (.queryByLabelText r "Corrected translation"))))
        (edit! r "D1")
        (test/is (false? (.-disabled ^js (.getByRole r "button" #js {:name button}))))
        (.click rtl/fireEvent (.getByRole r "button" #js {:name button}))
        (let [original (get @fixture/calls request-key)]
          (test/is (= (expected-request kind (or @row fixture/publication-review)) original))
          (when other-segment? (.click rtl/fireEvent (.getByText r "Adiós")))
          (edit! r "D2")
          (advance-canonical! entry row detail)
          (await (finish-case! r entry pending original request-key outcome)))
        (finally ((:resolve pending) {:segments_reviewed 2}) (rtl/cleanup))))))

(test/deftest ^:async seven-normal-UI-entries-preserve-current-S1-or-S2-draft
  (doseq [entry entries other-segment? [false true] outcome [:success :rejection]
          :when (not (and (= (:kind entry) :publication-split-review)
                          (not other-segment?) (= outcome :success)))]
    (await (run-entry-case! entry other-segment? outcome)))
  (doseq [other-segment? [false true]]
    (await (run-entry-case! (nth entries 4) other-segment? :reconcile-failure))))

(test/deftest ^:async refreshed-candidate-authority-hides-old-editor-before-detail-effects
  (doseq [replacement [:new-candidate :absent]]
    ((:before fixture/api-fixture))
    (let [pending (fixture/deferred) stage (atom false) checkpoint (atom nil)
          rendered (atom nil) original-load controller/load-detail!
          old (fixture/resource-review-row fixture/resource-split-review)
          next-row (if (= replacement :new-candidate)
                     (assoc old :split_review
                            (assoc fixture/resource-split-review
                                   :candidate_set_id "candidate-set/new" :manifest_id "manifest/new"))
                     (-> old (dissoc :split_review :translation_revision :translated_at)
                         (assoc :work_state "missing" :allowed_actions ["dispatch"])))]
      (set! api/list-publication-reviews
            (fn [] (js/Promise.resolve {:project "devel" :reviews [(if @stage next-row old)]})))
      (set! api/submit-publication-bulk-review (fn [_] (:promise pending)))
      (set! controller/load-detail!
            (fn [selected project load setters]
              (when (and @stage (nil? @checkpoint))
                (let [^js r @rendered ^js stale (.queryByRole r "button" #js {:name "Approve split"})]
                  (reset! checkpoint {:old-editor (some? (.queryByLabelText r "Corrected translation"))
                                      :old-segment (some? (.queryByRole r "heading" #js {:name "Split 0"}))})
                  (when stale (.click rtl/fireEvent stale))))
              (original-load selected project load setters)))
      (let [r (rtl/render (hx/$ view/translation-review-page))]
        (reset! rendered r)
        (try
          (await (fixture/select-first-document r))
          (.click rtl/fireEvent (.getByText r "Hola mundo"))
          (await (fixture/wait-until "old editable candidate" #(some? (.queryByLabelText r "Corrected translation"))))
          (edit! r "D2")
          (.click rtl/fireEvent (.getByRole r "button" #js {:name "Approve All"}))
          (reset! stage true)
          (await (rtl/act (fn [] ((:resolve pending) {:status "recorded"}) (js/Promise.resolve nil))))
          (test/is (= {:old-editor false :old-segment false} @checkpoint))
          (test/is (empty? (:publication-split-review @fixture/calls)) "zero mixed C2-row/C1-split requests")
          (test/is (nil? (.queryByLabelText r "Corrected translation")) "new authority is not auto-selected")
          (finally (set! controller/load-detail! original-load)
                   ((:resolve pending) {:status "recorded"}) (rtl/cleanup)))))))

(test/deftest ^:async same-candidate-reviewed-output-revision-keeps-bulk-approval-feedback
  (let [inventory (atom (fixture/resource-review-row fixture/resource-split-review))]
    (set! api/list-publication-reviews
          (fn []
            (fixture/record! :publication-reviews true)
            (js/Promise.resolve {:project "devel" :reviews [@inventory]})))
    (set! api/submit-publication-bulk-review
          (fn [payload]
            (fixture/record! :publication-bulk-review payload)
            (swap! inventory update-in [:split_review :splits]
                   #(mapv (fn [split] (assoc split :review_status (:status payload))) %))
            (when (= "approved" (:status payload))
              (swap! inventory assoc
                     :translation_revision "translation-effective-revision-after-bulk"))
            (js/Promise.resolve {:status "recorded"})))
    (let [r (rtl/render (hx/$ view/translation-review-page))]
      (await (fixture/select-first-document r))
      (.click rtl/fireEvent (.getByText r "Hola mundo"))
      (await (fixture/wait-until "resource evaluation form"
                                #(some? (.queryByLabelText r "fluency"))))
      (.change rtl/fireEvent (.getByLabelText r "fluency")
               #js {:target #js {:value "poor"}})
      (.change rtl/fireEvent (.getByLabelText r "Corrected translation")
               #js {:target #js {:value "Must remain split-local"}})
      (.change rtl/fireEvent (.getByLabelText r "Editor notes")
               #js {:target #js {:value "  Apply to every persisted split.  "}})
      (.click rtl/fireEvent (.getByRole r "button" #js {:name "Needs Edit"}))
      (await (fixture/wait-until "bulk review notice"
                                #(some? (.queryByText r "All splits in-review."))))
      (test/is (= [{:candidate_set_id "candidate-set/doc-1-es"
                   :status "in-review" :adequacy "adequate" :fluency "poor"
                   :terminology "minor_errors" :risk "sensitive"
                   :editor_notes "Apply to every persisted split."}]
                 (:publication-bulk-review @fixture/calls))
               "the server owns split enumeration and document scope cannot carry a correction")
      (test/is (empty? (:publication-split-review @fixture/calls)))
      (.click rtl/fireEvent (.getByRole r "button" #js {:name "Approve All"}))
      (await (fixture/wait-until "refreshed all-approved split aggregate"
                                #(pos? (.-length (.queryAllByText r "2/2")))))
      (test/is (some? (.queryByText r "All splits approved."))
               "same-candidate reviewed-output revision keeps successful bulk feedback"))))
