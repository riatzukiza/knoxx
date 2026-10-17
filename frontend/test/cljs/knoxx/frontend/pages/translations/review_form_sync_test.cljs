(ns knoxx.frontend.pages.translations.review-form-sync-test
  "Same-split form synchronization follows immutable review identity."
  (:require ["@testing-library/react" :as rtl]
            [cljs.test :as test]
            [helix.core :as hx]
            [helix.dom :as d]
            [helix.hooks :as hooks]
            [knoxx.frontend.pages.translations.logic :as logic]
            [knoxx.frontend.pages.translations.review-draft :as draft]
            [knoxx.frontend.pages.translations.review-form-sync :as form-sync]))

(test/use-fixtures :each
  {:after rtl/cleanup})

(hx/defnc form-sync-harness
  "Preserve the existing pristine review rehydration harness."
  [{:keys [split]}]
  (let [[form set-form!] (hooks/use-state logic/default-label)]
    (form-sync/use-review-form-sync! split set-form!)
    (d/div
     (d/output {:data-testid "adequacy"} (:adequacy form))
     (d/output {:data-testid "fluency"} (:fluency form))
     (d/output {:data-testid "terminology"} (:terminology form))
     (d/output {:data-testid "risk"} (:risk form))
     (d/output {:data-testid "correction"} (:corrected_text form))
     (d/output {:data-testid "notes"} (:editor_notes form)))))

(defn- wait-for-value
  "Wait for the existing pristine hydration control to settle."
  [^js rendered test-id expected]
  (rtl/waitFor
   (fn []
     (when-not (= expected (.-textContent (.getByTestId rendered test-id)))
       (throw (js/Error. (str "still waiting for " test-id " = " expected)))))))

(test/deftest ^:async same-split-external-review-rehydrates-correction-and-scores
  (let [initial {:id "split/0"
                 :split_id "split/0"
                 :resource_split true
                 :candidate_set_id "candidate-set/7"
                 :review_id "review/initial"
                 :adequacy "adequate"
                 :fluency "good"
                 :terminology "minor_errors"
                 :risk "sensitive"
                 :corrected_text "Initial correction"
                 :editor_notes "Initial note"}
        remote (merge initial
                      {:review_id "review/remote"
                       :adequacy "poor"
                       :fluency "adequate"
                       :terminology "major_errors"
                       :risk "safe"
                       :corrected_text "Remote correction"
                       :editor_notes "Remote note"})
        rendered (rtl/render (hx/$ form-sync-harness {:split initial}))]
    (await (wait-for-value rendered "correction" "Initial correction"))
    (.rerender rendered (hx/$ form-sync-harness {:split remote}))
    (await (wait-for-value rendered "correction" "Remote correction"))
    (test/is (= "poor" (.-textContent (.getByTestId rendered "adequacy"))))
    (test/is (= "adequate" (.-textContent (.getByTestId rendered "fluency"))))
    (test/is (= "major_errors"
           (.-textContent (.getByTestId rendered "terminology"))))
    (test/is (= "safe" (.-textContent (.getByTestId rendered "risk"))))
    (test/is (= "Remote note" (.-textContent (.getByTestId rendered "notes"))))))

(hx/defnc dirty-form-harness
  "Use the actual dirty-edit setter and real form-sync effect."
  [{:keys [split]}]
  (let [[form set-form!] (hooks/use-state logic/default-label)
        state (hooks/use-ref (draft/initial-state))]
    (form-sync/use-review-form-sync! split set-form! state)
    (hx/<>
     (d/input {:aria-label "draft" :value (:corrected_text form)
              :on-change #(let [next-form (assoc form :corrected_text (.. % -target -value))]
                            (draft/edit! state next-form) (set-form! next-form))})
     (d/output {:data-testid "notes"} (:editor_notes form)))))

(test/deftest ^:async dirty-same-authority-review-keeps-newer-edit-but-new-candidate-resets
  (let [initial {:id "split/0" :candidate_set_id "candidate/1" :manifest_id "manifest/1"
                 :resource_split true :review_id "review/1" :corrected_text "canonical D1"}
        remote (assoc initial :review_id "review/2" :corrected_text "remote" :editor_notes "remote notes")
        r (rtl/render (hx/$ dirty-form-harness {:split initial}))]
    (await (rtl/waitFor #(when-not (= "canonical D1" (.-value ^js (.getByLabelText r "draft")))
                          (throw (js/Error. "initial canonical form pending")))))
    (.change rtl/fireEvent (.getByLabelText r "draft") #js {:target #js {:value "D2"}})
    (.rerender r (hx/$ dirty-form-harness {:split remote}))
    (await (rtl/act (fn [] (js/Promise.resolve nil))))
    (test/is (= "D2" (.-value ^js (.getByLabelText r "draft"))))
    (.rerender r (hx/$ dirty-form-harness {:split (assoc remote :candidate_set_id "candidate/2")}))
    (await (rtl/waitFor #(when-not (= "remote" (.-value ^js (.getByLabelText r "draft")))
                          (throw (js/Error. "new authority reset pending")))))
    (test/is (= "remote notes" (.-textContent ^js (.getByTestId r "notes"))))))

(test/deftest submitted-draft-acknowledgement-cannot-clean-a-later-edit
  (let [state #js {:current (draft/initial-state)}]
    (draft/edit! state {:corrected_text "D1"})
    (let [submitted (draft/capture state)]
      (draft/edit! state {:corrected_text "D2"})
      (draft/reviewed! state submitted)
      (test/is (true? (:dirty? (.-current state))))
      (draft/reviewed! state (draft/capture state))
      (test/is (false? (:dirty? (.-current state)))))))


(def ^:private split-authority-row
  {:publication "publications/doc-1-es" :project "devel" :target_lang "es"
   :revision "source-1" :translation_revision "translation-review-state-before"
   :contract_candidate true :hydration_state "displayable"
   :split_review {:candidate_set_id "candidate-set/1" :manifest_id "manifest/1"
                  :splits [{:split_id "split/0" :split_index 0
                            :source_text "Hello" :candidate_text "Hola"
                            :review_status "in-review"}]}})

(test/deftest same-split-reviewed-output-revision-keeps-current-editor-authority
  (let [state #js {:current (draft/initial-state)}]
    (draft/select-work! state split-authority-row)
    (draft/edit! state {:corrected_text "D2"})
    (let [submitted (draft/capture state)
          reviewed (assoc split-authority-row
                          :translation_revision "translation-effective-revision-after")]
      (test/is (not (draft/select-work! state reviewed)))
      (test/is (= submitted (draft/capture state)))
      (test/is (true? (:dirty? (.-current state)))))))

(test/deftest actual-candidate-source-and-nonsplit-revision-changes-reset-editor-authority
  (doseq [[before after]
          [[split-authority-row (assoc split-authority-row :revision "source-2")]
           [split-authority-row (assoc-in split-authority-row [:split_review :candidate_set_id] "candidate-set/2")]
           [split-authority-row (assoc-in split-authority-row [:split_review :manifest_id] "manifest/2")]
           [split-authority-row (assoc split-authority-row :publication "publications/other")]
           [split-authority-row (assoc split-authority-row :project "other")]
           [split-authority-row (assoc split-authority-row :target_lang "fr")]
           [split-authority-row (assoc split-authority-row :hydration_state "content_missing")]
           [(dissoc split-authority-row :split_review)
            (assoc (dissoc split-authority-row :split_review) :translation_revision "legacy-replacement")]
           [(-> split-authority-row
                (dissoc :split_review :contract_candidate :hydration_state)
                (assoc :legacy_candidate true))
            (-> split-authority-row
                (dissoc :split_review :contract_candidate :hydration_state)
                (assoc :legacy_candidate true :translation_revision "legacy-replacement"))]
           [(assoc-in split-authority-row [:split_review :splits] [])
            (-> split-authority-row
                (assoc-in [:split_review :splits] [])
                (assoc :translation_revision "unadmitted-replacement"))]]]
    (let [state #js {:current (draft/initial-state)}]
      (draft/select-work! state before)
      (draft/edit! state {:corrected_text "D2"})
      (test/is (true? (draft/select-work! state after)))
      (test/is (false? (:dirty? (.-current state)))))))
