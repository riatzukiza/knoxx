(ns knoxx.frontend.pages.translations.lifecycle-fixture
  "Bounded restoring API fixtures for the added current-view tests only."
  (:require ["@testing-library/react" :as rtl]
            [knoxx.frontend.pages.translations.api :as api]
            [knoxx.frontend.pages.translations.page-interaction-test :as original]))

(def doc-summary
  "Reuse the original registered interaction fixture data."
  original/doc-summary)

(def publication-review
  "Reuse the original registered interaction fixture data."
  original/publication-review)

(def resource-split-review
  "Reuse the original registered interaction fixture data."
  original/resource-split-review)

(def doc-detail
  "Reuse the original registered interaction fixture data."
  original/doc-detail)

(def calls
  "Reuse the original registered interaction fixture data."
  original/calls)

(defn record!
  "Record only synthetic source API requests."
  [kind value]
  (swap! calls update kind (fnil conj []) value))

(def ^:private originals
  {:list api/list-documents :get api/get-document :review api/review-document
   :list-publication-reviews api/list-publication-reviews
   :dispatch-publication api/dispatch-publication-translation
   :approve-publication api/approve-publication-translation
   :split-review api/submit-publication-split-review
   :bulk-review api/submit-publication-bulk-review
   :reconcile-publication api/reconcile-publication
   :label api/submit-label :manifest api/get-manifest :sft api/sft-export
   :config api/pipeline-config :update-config api/update-pipeline-config
   :models api/list-proxx-models})

(def api-fixture
  "Restore every source API after each isolated lifecycle case."
  {:before (fn []
             (reset! calls {})
             (set! api/list-documents
                   (fn [params]
                     (record! :list params)
                     (js/Promise.resolve
                      {:documents [(assoc doc-summary :project (:project params))]
                       :total 1})))
             (set! api/get-document
                   (fn
                     ([id lang]
                      (record! :get [id lang nil])
                      (js/Promise.resolve doc-detail))
                     ([id lang scope]
                      (record! :get [id lang scope])
                      (js/Promise.resolve doc-detail))))
             (set! api/list-publication-reviews
                   (fn []
                     (record! :publication-reviews true)
                     (js/Promise.resolve {:project "devel" :reviews []})))
             (set! api/dispatch-publication-translation
                   (fn [publication-id]
                     (record! :publication-dispatch publication-id)
                     (js/Promise.resolve
                      {:dispatched [{:outcome "dispatch/accepted"}]})))
             (set! api/approve-publication-translation
                   (fn [payload]
                     (record! :publication-approval payload)
                     (js/Promise.resolve {:approved true :status "recorded"})))
             (set! api/submit-publication-split-review
                   (fn [payload]
                     (record! :publication-split-review payload)
                     (js/Promise.resolve {:status "recorded"})))
             (set! api/submit-publication-bulk-review
                   (fn [payload]
                     (record! :publication-bulk-review payload)
                     (js/Promise.resolve {:status "recorded"})))
             (set! api/reconcile-publication
                   (fn [publication-id]
                     (record! :reconcile publication-id)
                     (js/Promise.resolve {:type "publication/materialized"})))
             (set! api/review-document
                   (fn
                     ([id lang payload]
                      (record! :review [id lang nil payload])
                      (js/Promise.resolve
                       {:ok true :segments_reviewed 2 :overall (:overall payload)}))
                     ([id lang scope payload]
                      (record! :review [id lang scope payload])
                      (js/Promise.resolve
                       {:ok true :segments_reviewed 2 :overall (:overall payload)}))))
             (set! api/submit-label
                   (fn
                     ([seg-id payload]
                      (record! :label [seg-id nil payload])
                      (js/Promise.resolve {:ok true :new_status "approved"}))
                     ([seg-id scope payload]
                      (record! :label [seg-id scope payload])
                      (js/Promise.resolve {:ok true :new_status "approved"}))))
             (set! api/get-manifest
                   (fn [project]
                     (record! :manifest project)
                     (js/Promise.resolve {:languages {:es {:approved 1 :total_segments 2}}})))
             (set! api/pipeline-config
                   (fn []
                     (record! :config true)
                     (js/Promise.resolve {:model "glm-5" :updated_at nil})))
             (set! api/update-pipeline-config
                   (fn [model]
                     (record! :update-config model)
                     (js/Promise.resolve {:model model :updated_at "2026-06-11"})))
             (set! api/list-proxx-models
                   (fn []
                     (record! :models true)
                     (js/Promise.resolve [{:id "glm-5"} {:id "gpt-5.4"}]))))
   :after (fn []
            (rtl/cleanup)
            (set! api/list-documents (:list originals))
            (set! api/get-document (:get originals))
            (set! api/list-publication-reviews (:list-publication-reviews originals))
            (set! api/dispatch-publication-translation (:dispatch-publication originals))
            (set! api/approve-publication-translation (:approve-publication originals))
            (set! api/submit-publication-split-review (:split-review originals))
            (set! api/submit-publication-bulk-review (:bulk-review originals))
            (set! api/reconcile-publication (:reconcile-publication originals))
            (set! api/review-document (:review originals))
            (set! api/submit-label (:label originals))
            (set! api/get-manifest (:manifest originals))
            (set! api/sft-export (:sft originals))
            (set! api/pipeline-config (:config originals))
            (set! api/update-pipeline-config (:update-config originals))
            (set! api/list-proxx-models (:models originals)))})

(defn wait-until
  "Wait for a named real DOM or captured-call condition."
  [message predicate]
  (rtl/waitFor (fn [] (when-not (predicate)
                       (throw (js/Error. (str "still waiting: " message)))))))

(defn deferred
  "Own one synthetic API promise and its explicit completion controls."
  []
  (let [resolve! (atom nil) reject! (atom nil)
        promise (js/Promise. (fn [deliver! refuse!]
                               (reset! resolve! deliver!)
                               (reset! reject! refuse!)))]
    {:promise promise :resolve #(@resolve! %) :reject #(@reject! %)}))

(defn ^:async select-first-document
  "Select the normal work row and wait for its admitted detail."
  [^js rendered]
  (await (wait-until "doc list" #(some? (.queryByText rendered "Doc One"))))
  (.click rtl/fireEvent (.getByText rendered "Doc One"))
  (await (wait-until "detail" #(some? (.queryByText rendered "Hola mundo")))))

(defn resource-review-row
  "Reuse the original resource row shape for bounded UI runner controls."
  [aggregate]
  (assoc publication-review :work_state "ready" :split_review aggregate))
