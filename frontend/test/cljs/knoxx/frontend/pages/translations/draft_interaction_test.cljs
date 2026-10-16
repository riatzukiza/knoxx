(ns knoxx.frontend.pages.translations.draft-interaction-test
  "Real editor callbacks survive their original mutation and canonical reload."
  (:require ["@testing-library/react" :as rtl]
            [cljs.test :as test]
            [helix.core :as hx]
            [knoxx.frontend.pages.translations.api :as api]
            [knoxx.frontend.pages.translations.lifecycle-fixture :as fixture]
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
