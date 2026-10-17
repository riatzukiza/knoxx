(ns knoxx.frontend.pages.translations.lifecycle-operation-test
  "Actual six-runner and captured-export completion boundaries."
  (:require [cljs.test :as test]
            [knoxx.frontend.pages.translations.api :as api]
            [knoxx.frontend.pages.translations.lifecycle-fixture :as fixture]
            [knoxx.frontend.pages.translations.logic :as logic]
            [knoxx.frontend.pages.translations.review-controller :as controller]
            [knoxx.frontend.pages.translations.split-review :as split-review]
            [knoxx.frontend.pages.translations.view :as view]))

(test/use-fixtures :each fixture/api-fixture)

(defn- hold-six-mutation-clients!
  "Hold actual runner dependencies with their source-declared arity shapes."
  [pending]
  (set! api/submit-label
        (fn
          ([id payload] (fixture/record! :label [id nil payload]) (:promise pending))
          ([id scope payload] (fixture/record! :label [id scope payload]) (:promise pending))))
  (set! api/review-document
        (fn
          ([id lang payload] (fixture/record! :review [id lang nil payload]) (:promise pending))
          ([id lang scope payload] (fixture/record! :review [id lang scope payload]) (:promise pending))))
  (set! api/submit-publication-split-review
        (fn [payload] (fixture/record! :publication-split-review payload) (:promise pending)))
  (set! api/submit-publication-bulk-review
        (fn [payload] (fixture/record! :publication-bulk-review payload) (:promise pending)))
  (set! api/approve-publication-translation
        (fn [payload] (fixture/record! :publication-approval payload) (:promise pending)))
  (set! api/dispatch-publication-translation
        (fn [publication] (fixture/record! :publication-dispatch publication) (:promise pending))))

(defn- invoke-owned-runner!
  "Invoke the same six UI runners with fixed original-coordinate fixtures."
  [kind operation]
  (let [row (assoc (fixture/resource-review-row fixture/resource-split-review)
                   :document_id "docs/doc-1" :target_lang "es"
                   :publication_review fixture/publication-review)
        segment (first (:segments (split-review/detail row)))]
    (case kind
      :label (view/run-segment-submit! (first (:segments fixture/doc-detail)) logic/default-label
                                      "approve" fixture/doc-summary "devel" operation)
      :review (view/run-document-review! fixture/doc-summary "approve" "devel" operation)
      :publication-split-review (view/run-resource-split-submit!
                                 row segment logic/default-label "approved" operation)
      :publication-bulk-review (view/run-resource-bulk-submit!
                                row logic/default-label "approved" operation)
      :publication-approval (view/run-publication-approval! row operation)
      :publication-dispatch (view/run-translation-dispatch! row operation))))

(test/deftest ^:async six-runners-preserve-original-server-work-with-no-abandoned-ui-callbacks
  (doseq [kind [:label :review :publication-split-review :publication-bulk-review
               :publication-approval :publication-dispatch]
          outcome [:success :rejection]]
    (reset! fixture/calls {})
    (let [pending (fixture/deferred)
          events (atom [])
          view-state #js {:current (controller/initial-view "devel" "es")}
          setters (into {} (map (fn [setter-key] [setter-key #(swap! events conj [setter-key %])])
                               [:set-saving! :set-notice! :set-error! :set-detail! :set-form!]))
          operation (controller/begin-operation! view-state #js {:current 0} setters)]
      (hold-six-mutation-clients! pending)
      (let [work (invoke-owned-runner! kind operation)]
        (test/is (= 1 (count (get @fixture/calls kind))) (str kind " has one original request"))
        (controller/unmount-view! view-state)
        (reset! events [])
        (if (= outcome :success)
          ((:resolve pending) {:segments_reviewed 2 :dispatched [{:outcome "dispatch/accepted"}]})
          ((:reject pending) (js/Error. "owned synthetic rejection")))
        (await work)
        (test/is (empty? @events) (str kind " " outcome " invokes no abandoned setters/finally"))
        (test/is (empty? (:publication-reviews @fixture/calls)) (str kind " starts no abandoned reload"))
        (test/is (empty? (:get @fixture/calls)) (str kind " starts no abandoned detail"))
        (test/is (= (if (and (= kind :publication-approval) (= outcome :success))
                 ["publications/doc-1-es"] nil)
               (:reconcile @fixture/calls))
            "successful approval still reconciles its original publication")
        (test/is (= 1 (count (get @fixture/calls kind))) "no retarget/reissue")))))

(test/deftest ^:async captured-export-download-survives-boundary-but-shared-feedback-does-not
  (let [create-url js/URL.createObjectURL
        revoke-url js/URL.revokeObjectURL
        ^js prototype (.-prototype (.-HTMLAnchorElement js/window))
        original-click (.-click prototype)]
    (try
      (doseq [boundary [:project :filter-aba :unmount]
              outcome [:success :rejection]]
        (let [pending (fixture/deferred)
              requests (atom [])
              feedback (atom [])
              downloads (atom [])
              blobs (atom [])
              state #js {:current (controller/initial-view "devel" "es")}
              setters {:set-notice! #(swap! feedback conj [:notice %])
                       :set-error! #(swap! feedback conj [:error %])}]
          (set! js/URL.createObjectURL (fn [blob] (swap! blobs conj blob) "blob:owned-sft"))
          (set! js/URL.revokeObjectURL (fn [_]))
          (set! (.-click prototype)
                (fn [] (this-as anchor (swap! downloads conj (.-download ^js anchor)))))
          (set! api/sft-export (fn [params] (swap! requests conj params) (:promise pending)))
          (let [work (view/run-export! "devel" "es" (controller/export-setters state setters))]
            (case boundary
              :project (controller/change-project! state "fresh")
              :filter-aba (do (controller/change-filter! state "fr")
                              (controller/change-filter! state "es"))
              :unmount (controller/unmount-view! state))
            (if (= outcome :success)
              ((:resolve pending) "owned exact captured export bytes\n")
              ((:reject pending) (js/Error. "owned export rejection")))
            (await work)
            (test/is (= [{:project "devel" :target-lang "es"}] @requests))
            (test/is (empty? @feedback) (str boundary " " outcome " has no stale feedback"))
            (test/is (nil? (:operation (.-current state))) "export never owns mutation saving")
            (if (= outcome :success)
              (do
                (test/is (= ["devel-es-translations.jsonl"] @downloads))
                (let [^js blob (first @blobs)]
                  (test/is (= "owned exact captured export bytes\n" (await (.text blob))))))
              (test/is (and (empty? @downloads) (empty? @blobs)))))))
      (finally
        (set! js/URL.createObjectURL create-url)
        (set! js/URL.revokeObjectURL revoke-url)
        (set! (.-click prototype) original-click)))))
