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
