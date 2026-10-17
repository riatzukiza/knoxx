(ns knoxx.frontend.pages.translations.lifecycle-interaction-test
  "Causal current-view boundaries through the actual translation page controls."
  (:require ["@testing-library/react" :as rtl]
            [cljs.test :as test]
            [helix.core :as hx]
            [knoxx.frontend.pages.translations.api :as api]
            [knoxx.frontend.pages.translations.lifecycle-fixture :as fixture]
            [knoxx.frontend.pages.translations.review-controller :as controller]
            [knoxx.frontend.pages.translations.view :as view]))

(test/use-fixtures :each fixture/api-fixture)

(test/deftest ^:async project-boundary-excludes-installed-review-before-B-request-returns
  (let [fresh-reviews (fixture/deferred)
        rendered (atom nil)
        checkpoint (atom nil)]
    (set! api/list-publication-reviews
          (fn []
            (fixture/record! :publication-reviews true)
            (if-let [^js r @rendered]
              (if (= "fresh" (.-value (.getByPlaceholderText r "devel")))
                (do
                  (when-not @checkpoint
                    (reset! checkpoint
                            {:project (.-value (.getByPlaceholderText r "devel"))
                             :filter (.-value (.getByLabelText r "Target Lang"))
                             :old-rows (count (.queryAllByText r "Doc One"))
                             :old-detail (count (.queryAllByText r "Hola mundo"))
                             :old-form (some? (.queryByLabelText r "Corrected translation"))
                             :old-submit (some? (.queryByRole r "button" #js {:name "Submit review"}))
                             :old-manifest (some? (.queryByText r "Español: 1/2 approved"))
                             :old-notice (some? (.queryByText r "Document review: reject (2 segments)"))
                             :old-error (some? (.queryByText r "Installed A export failure"))}))
                  (:promise fresh-reviews))
                (js/Promise.resolve {:reviews []}))
              (js/Promise.resolve {:reviews []}))))
    (set! api/sft-export
          (fn [params]
            (fixture/record! :sft params)
            (js/Promise.reject (js/Error. "Installed A export failure"))))
    (let [r (rtl/render (hx/$ view/translation-review-page))]
      (reset! rendered r)
      (try
        (await (fixture/select-first-document r))
        (await (fixture/wait-until "old language option"
                           #(some? (.queryByText r "Español: 1/2 approved"))))
        (.change rtl/fireEvent (.getByLabelText r "Target Lang")
                 #js {:target #js {:value "es"}})
        (await (fixture/wait-until "es compatibility list"
                           #(some (fn [p] (= "es" (:target-lang p))) (:list @fixture/calls))))
        ;; Use the list's first Doc One match whether the preceding filter
        ;; retained A (old source) or correctly cleared A (repaired source).
        (.click rtl/fireEvent (aget (.getAllByText r "Doc One") 0))
        (await (fixture/wait-until "reselected A detail"
                           #(some? (.queryByText r "Hola mundo"))))
        (.click rtl/fireEvent (.getByRole r "button" #js {:name "Reject All"}))
        (await (fixture/wait-until "installed A notice"
                           #(some? (.queryByText r "Document review: reject (2 segments)"))))
        (.click rtl/fireEvent (.getByRole r "button" #js {:name "Export SFT"}))
        (await (fixture/wait-until "installed A error"
                           #(some? (.queryByText r "Installed A export failure"))))
        (.click rtl/fireEvent (.getByText r "Hola mundo"))
        (await (fixture/wait-until "installed A form"
                           #(some? (.queryByLabelText r "Corrected translation"))))
        (.change rtl/fireEvent (.getByLabelText r "Corrected translation")
                 #js {:target #js {:value "Unsubmitted A correction"}})
        (.change rtl/fireEvent (.getByPlaceholderText r "devel")
                 #js {:target #js {:value "fresh"}})
        ;; Inspect the snapshot captured inside the first B GET, never a later
        ;; waitFor/after-passive DOM. A missing checkpoint is a failure.
        (test/is (some? @checkpoint) "the real B load was reached")
        (test/is (= {:project "fresh" :filter "" :old-rows 0 :old-detail 0
                :old-form false :old-submit false :old-manifest false
                :old-notice false :old-error false}
               @checkpoint)
            "B's first committed view has no installed A authority")
        (finally
          ;; Own and settle this synthetic promise before normal fixture cleanup.
          (await (rtl/act
                  (fn []
                    ((:resolve fresh-reviews) {:project "fresh" :reviews []})
                    (js/Promise.resolve nil)))))))))

(test/deftest ^:async stale-review-completion-cannot-reload-or-cancel-B-original-load
  (let [review-a (fixture/deferred)
        reviews-b (fixture/deferred)
        inventory-call (atom 0)]
    (set! api/list-publication-reviews
          (fn []
            (fixture/record! :publication-reviews true)
            (if (= 1 (swap! inventory-call inc))
              (js/Promise.resolve {:reviews []})
              (:promise reviews-b))))
    (set! api/list-documents
          (fn [{:keys [project] :as params}]
            (fixture/record! :list params)
            (js/Promise.resolve
             {:documents [(if (= project "fresh")
                            (assoc fixture/doc-summary :project "fresh"
                                   :document_id "docs/fresh" :title "Original B response")
                            fixture/doc-summary)]
              :total 1})))
    (set! api/review-document
          (fn
            ([id lang payload]
             (fixture/record! :review [id lang nil payload])
             (:promise review-a))
            ([id lang scope payload]
             (fixture/record! :review [id lang scope payload])
             (:promise review-a))))
    (let [r (rtl/render (hx/$ view/translation-review-page))]
      (try
        (await (fixture/select-first-document r))
        (.click rtl/fireEvent (.getByRole r "button" #js {:name "Approve All"}))
        (test/is (= [["docs/doc-1" "es"
                 {:project "devel" :garden-id "gardens/sonic"}
                 {:overall "approve"}]]
               (:review @fixture/calls))
            "exactly the original A payload was issued")
        (.change rtl/fireEvent (.getByPlaceholderText r "devel")
                 #js {:target #js {:value "fresh"}})
        (await (fixture/wait-until "B's original held inventory request"
                           #(= 2 @inventory-call)))
        (await (rtl/act
                (fn []
                  ((:resolve review-a) {:ok true :segments_reviewed 2 :overall "approve"})
                  (js/Promise.resolve nil))))
        (test/is (= 2 @inventory-call) "old-project completion starts no captured reload")
        (test/is (= 1 (count (:get @fixture/calls))) "old-project completion starts no detail read")
        (test/is (nil? (.queryByText r "Document review: approve (2 segments)"))
            "old completion installs no B feedback")
        (await (rtl/act
                (fn []
                  ((:resolve reviews-b) {:project "fresh" :reviews []})
                  (js/Promise.resolve nil))))
        (test/is (some? (.queryByText r "Original B response"))
            "B's original response remains authorized to install")
        (test/is (= ["devel" "fresh"] (mapv :project (:list @fixture/calls))))
        (test/is (= 1 (count (:review @fixture/calls))) "no retargeted or reissued mutation")
        (finally
          (await (rtl/act
                  (fn []
                    ((:resolve review-a) {:ok true :segments_reviewed 2})
                    ((:resolve reviews-b) {:project "fresh" :reviews []})
                    (js/Promise.resolve nil)))))))))

(test/deftest ^:async actual-project-boundary-excludes-old-manifest-before-any-B-loader
  (doseq [mode [:success :error]]
    (let [held (fixture/deferred)
          calls (atom [])
          events (atom [])
          manifest {:languages {:es {:approved 1 :total_segments 2}}}
          original-error (js/Error. "Controlled old manifest failure")
          context #js {:current (controller/initial-view "devel" "")}
          documents #js {:current 0}
          manifest-load #js {:current 0}
          detail #js {:current 0}
          setters (into {} (map (fn [setter-key]
                                 [setter-key #(swap! events conj [setter-key %])])
                               [:set-draft-work! :set-detail! :set-detail-loading!
                                :set-seg-idx! :set-form! :set-notice! :set-error!
                                :set-saving! :set-documents! :set-loading!
                                :set-selected! :set-manifest! :set-target-lang! :set-project!]))
          factory @#'view/boundary-setters
          boundaries (factory context documents manifest-load detail setters)]
      (set! api/get-manifest
            (fn [project] (swap! calls conj project) (:promise held)))
      (try
        (let [load-id (controller/load-manifest! "devel" manifest-load (:set-manifest! setters))]
          (test/is (= ["devel"] @calls) "the original real manifest loader is pending")
          ;; Invoke the actual page wiring and boundary, with no React passive
          ;; cleanup or replacement loader available to invalidate A for it.
          ((:set-project! boundaries) "fresh")
          (test/is (= "fresh" (:project (.-current context))))
          (test/is (= (inc load-id) (.-current manifest-load))
                   "the actual project boundary invalidates the manifest generation")
          (test/is (= [[:set-manifest! nil]]
                      (filterv #(= :set-manifest! (first %)) @events)))
          (let [boundary-events @events]
            (if (= mode :success)
              ((:resolve held) manifest)
              ((:reject held) original-error))
            ;; The real loader subscribed first and its compiled continuation has
            ;; no intervening await before its guarded manifest setter/catch.
            (let [settled (try (await (:promise held)) (catch :default error error))]
              (test/is (identical? (if (= mode :success) manifest original-error) settled)))
            (test/is (= ["devel"] @calls) "no B manifest loader or API request has run")
            (test/is (= boundary-events @events)
                     "old success cannot install and old rejection cannot clear after the actual boundary")))
        (finally
          ;; Settle this owned promise even when an unexpected assertion path
          ;; throws; the expected rejection is checked above, not hidden here.
          ((:resolve held) manifest)
          (try (await (:promise held)) (catch :default _ nil)))))))
