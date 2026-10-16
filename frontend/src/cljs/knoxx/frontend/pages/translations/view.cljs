(ns knoxx.frontend.pages.translations.view
  "Translation review lifecycle and the existing source-bound UI runners."
  (:require [helix.core :as hx]
            [helix.hooks :as hooks]
            [knoxx.frontend.pages.translations.api :as api]
            [knoxx.frontend.pages.translations.logic :as logic]
            [knoxx.frontend.pages.translations.review-controller :as controller]
            [knoxx.frontend.pages.translations.review-form-sync :as form-sync]
            [knoxx.frontend.pages.translations.review-presentation :as presentation]
            [knoxx.frontend.pages.translations.split-review :as split-review]))

(def document-actions
  "Retain the existing public component identity used by interaction controls."
  presentation/document-actions)

;; ── mutation runners ─────────────────────────────────────────────────────────

(defn ^:async run-segment-submit!
  "Submit the captured legacy label; fence only its UI completion."
  [segment form overall selected project
   {:keys [set-saving! set-error! set-notice! set-detail! set-form!
           current-view? refresh-documents!]}]
  (set-saving! true)
  (set-error! nil)
  (let [scope (logic/legacy-review-scope selected project)]
    (try
      (await (api/submit-label (:id segment) scope
                               (logic/prepare-label-payload form overall)))
      (when (current-view?)
        (set-notice! (str "Segment " (:segment_index segment) ": " overall))
        (set-detail! (await (api/get-document (:document_id selected)
                                              (:target_lang selected) scope)))
        (set-form! logic/default-label))
      (refresh-documents!)
      (catch :default err
        (set-error! (or (.-message err) (str err))))
      (finally
        (set-saving! false)))))

(defn ^:async run-resource-split-submit!
  "Submit the captured resource split and refresh only the current view."
  [selected segment form status
   {:keys [set-saving! set-error! set-notice! refresh-documents!]}]
  (set-saving! true)
  (set-error! nil)
  (try
    (await (api/submit-publication-split-review
            (split-review/review-payload selected segment status form)))
    (set-notice! (str "Split " (:segment_index segment) " " status "."))
    (refresh-documents!)
    (catch :default err
      (set-error! (or (.-message err) (str err))))
    (finally
      (set-saving! false))))

(defn ^:async run-resource-bulk-submit!
  "Submit the captured candidate set without transferring UI ownership."
  [selected form status
   {:keys [set-saving! set-error! set-notice! refresh-documents!]}]
  (set-saving! true)
  (set-error! nil)
  (try
    (await (api/submit-publication-bulk-review
            (split-review/bulk-review-payload selected status form)))
    (set-notice! (str "All splits " status "."))
    (refresh-documents!)
    (catch :default err
      (set-error! (or (.-message err) (str err))))
    (finally
      (set-saving! false))))

(defn ^:async run-document-review!
  "Complete one captured legacy review without leaking stale UI effects."
  [selected overall project
   {:keys [set-saving! set-error! set-notice! set-detail!
           current-view? refresh-documents!]}]
  (set-saving! true)
  (set-error! nil)
  (let [scope (logic/legacy-review-scope selected project)]
    (try
      (let [result (await (api/review-document (:document_id selected)
                                               (:target_lang selected)
                                               scope {:overall overall}))]
        (set-notice! (str "Document review: " overall
                          " (" (:segments_reviewed result) " segments)"))
        (refresh-documents!)
        (when (current-view?)
          (set-detail! (await (api/get-document (:document_id selected)
                                                (:target_lang selected) scope)))))
      (catch :default err
        (set-error! (or (.-message err) (str err))))
      (finally
        (set-saving! false)))))

(defn ^:async run-publication-approval!
  "Preserve original approval and reconciliation despite view navigation."
  [selected {:keys [set-saving! set-error! set-notice! refresh-documents!]}]
  (set-saving! true)
  (set-error! nil)
  (try
    (let [review (:publication_review selected)]
      (await (api/approve-publication-translation (logic/approval-request review)))
      ;; This is authorized server work, independent of the view's lifetime.
      (let [receipt (await (api/reconcile-publication (:publication review)))]
        (set-notice! (str "Translation approved; publication reconciliation: "
                          (or (:type receipt) "recorded") "."))
        (refresh-documents!)))
    (catch :default err
      (set-error! (or (.-message err) (str err))))
    (finally
      (set-saving! false))))

(defn ^:async run-translation-dispatch!
  "Dispatch the original work once while fencing its presentation effects."
  [selected {:keys [set-saving! set-error! set-notice! refresh-documents!]}]
  (set-saving! true)
  (set-error! nil)
  (try
    (let [result (await (api/dispatch-publication-translation (:publication selected)))
          outcome (or (some-> result :dispatched first :dispatch/outcome name)
                      (some-> result :dispatched first :outcome name)
                      "recorded")]
      (set-notice! (str "Translation dispatch: " outcome "."))
      (refresh-documents!))
    (catch :default err
      (set-error! (or (.-message err) (str err))))
    (finally
      (set-saving! false))))

(defn ^:async run-export!
  "Download the captured export unchanged and fence its shared feedback."
  [project target-lang {:keys [set-notice! set-error!]}]
  (try
    (let [text (await (api/sft-export {:project project :target-lang target-lang}))
          blob (js/Blob. #js [text] #js {:type "application/x-ndjson"})
          url (js/URL.createObjectURL blob)
          a (.createElement js/document "a")]
      (set! (.-href a) url)
      (set! (.-download a) (logic/sft-filename project target-lang))
      (.click a)
      (js/URL.revokeObjectURL url)
      (set-notice! "SFT export downloaded."))
    (catch :default err
      (set-error! (or (.-message err) (str err))))))

;; ── page ─────────────────────────────────────────────────────────────────────

(defn- use-review-loads!
  [{:keys [project target-lang selected selected-segment setters
           document-load-seq manifest-load-seq detail-load-seq
           set-manifest! set-detail! set-seg-idx! set-form!]}]
  (hooks/use-effect
   [project target-lang]
   (let [load-id (controller/load-documents! project target-lang
                                             document-load-seq setters)]
     (fn [] (controller/cancel! document-load-seq load-id))))
  (hooks/use-effect
   [project]
   (let [load-id (controller/load-manifest! project manifest-load-seq set-manifest!)]
     (fn [] (controller/cancel! manifest-load-seq load-id))))
  (hooks/use-effect
   [selected project]
   (if selected
     (let [load-id (controller/load-detail! selected project detail-load-seq setters)]
       (fn [] (controller/cancel! detail-load-seq load-id)))
     (do
       (controller/cancel! detail-load-seq (.-current ^js detail-load-seq))
       (set-detail! nil)
       (set-seg-idx! nil)
       nil)))
  (form-sync/use-review-form-sync! selected-segment set-form!))

(defn- clear-review-state!
  "Synchronously exclude installed detail, draft, feedback and pending ownership."
  [{:keys [set-detail! set-detail-loading! set-seg-idx! set-form!
           set-notice! set-error! set-saving!]}]
  (set-detail! nil)
  (set-detail-loading! false)
  (set-seg-idx! nil)
  (set-form! logic/default-label)
  (set-notice! nil)
  (set-error! nil)
  (set-saving! false))

(defn- invalidate-load!
  "Detach the current load synchronously before replacement effects start."
  [^js load-seq]
  (controller/cancel! load-seq (.-current load-seq)))

(defn- project-boundary-setter
  "Detach all project-owned state before requesting the replacement project."
  [view loads setters]
  (fn [project]
    (when (controller/change-project! view project)
      (doseq [load loads] (invalidate-load! load))
      (clear-review-state! setters)
      ((:set-documents! setters) [])
      ((:set-loading! setters) true)
      ((:set-selected! setters) nil)
      ((:set-manifest! setters) nil)
      ((:set-target-lang! setters) "")
      ((:set-project! setters) project))))

(defn- filter-boundary-setter
  "Retain the project manifest while excluding old filtered work immediately."
  [view document-load-seq detail-load-seq setters]
  (fn [target-lang]
    (when (controller/change-filter! view target-lang)
      (invalidate-load! document-load-seq)
      (invalidate-load! detail-load-seq)
      (clear-review-state! setters)
      ((:set-documents! setters) [])
      ((:set-loading! setters) true)
      ((:set-selected! setters) nil)
      ((:set-target-lang! setters) target-lang))))

(defn- work-boundary-setter
  "Resolve pure selection updates before any boundary or React setter effects."
  [view detail-load-seq setters]
  (fn [next-value]
    (let [current (:selected (.-current ^js view))
          selected (if (fn? next-value) (next-value current) next-value)]
      (when (controller/change-work! view selected)
        (invalidate-load! detail-load-seq)
        (clear-review-state! setters))
      ((:set-selected! setters) selected))))

(defn- boundary-setters
  "Wire synchronous boundaries without putting effects in React updaters."
  [view document-load-seq manifest-load-seq detail-load-seq setters]
  {:set-project! (project-boundary-setter
                   view [document-load-seq manifest-load-seq detail-load-seq] setters)
   :set-target-lang! (filter-boundary-setter view document-load-seq detail-load-seq setters)
   :set-selected! (work-boundary-setter view detail-load-seq setters)})

(defn- use-review-data
  "Keep the existing work, selection and draft state in one data hook."
  []
  (let [[project set-project!] (hooks/use-state "devel")
        [target-lang set-target-lang!] (hooks/use-state "")
        [documents set-documents!] (hooks/use-state [])
        [selected set-selected!] (hooks/use-state nil)
        [detail set-detail!] (hooks/use-state nil)
        [seg-idx set-seg-idx!] (hooks/use-state nil)
        [form set-form!] (hooks/use-state logic/default-label)
        [manifest set-manifest!] (hooks/use-state nil)]
    {:project project :target-lang target-lang :documents documents
     :selected selected :detail detail :seg-idx seg-idx :form form :manifest manifest
     :setters {:set-project! set-project! :set-target-lang! set-target-lang!
               :set-documents! set-documents! :set-selected! set-selected!
               :set-detail! set-detail! :set-seg-idx! set-seg-idx!
               :set-form! set-form! :set-manifest! set-manifest!}}))

(defn- use-review-feedback
  "Keep loading, feedback and configuration visibility separate from work data."
  []
  (let [[loading set-loading!] (hooks/use-state true)
        [detail-loading set-detail-loading!] (hooks/use-state false)
        [saving set-saving!] (hooks/use-state false)
        [notice set-notice!] (hooks/use-state nil)
        [error set-error!] (hooks/use-state nil)
        [show-config? set-show-config!] (hooks/use-state false)]
    {:loading loading :detail-loading detail-loading :saving saving
     :notice notice :error error :show-config? show-config?
     :setters {:set-loading! set-loading! :set-detail-loading! set-detail-loading!
               :set-saving! set-saving! :set-notice! set-notice! :set-error! set-error!
               :set-show-config! set-show-config!}}))

(defn- use-view-lifetime!
  "Invalidate every load and callback when the owning page unmounts."
  [current-view loads]
  (hooks/use-effect
   :once
   (controller/mount-view! current-view)
   (fn []
     (controller/unmount-view! current-view)
     (doseq [load (vals loads)] (invalidate-load! load)))))

(defn- use-review-context
  "Wire actual boundary setters and load identities to the mounted page."
  [data feedback]
  (let [document-load-seq (hooks/use-ref 0)
        manifest-load-seq (hooks/use-ref 0)
        detail-load-seq (hooks/use-ref 0)
        current-view (hooks/use-ref (controller/initial-view "devel" ""))
        raw-setters (merge (:setters data) (:setters feedback))
        boundaries (boundary-setters current-view document-load-seq
                                     manifest-load-seq detail-load-seq raw-setters)
        loads {:document-load-seq document-load-seq :manifest-load-seq manifest-load-seq
               :detail-load-seq detail-load-seq}]
    (use-view-lifetime! current-view loads)
    {:current-view current-view :loads loads :boundaries boundaries
     :setters (merge raw-setters boundaries)}))

(defn- mutation-callbacks
  "Capture unchanged request inputs while giving each operation its own UI fence."
  [{:keys [project selected form detail seg-idx]} {:keys [current-view loads setters]}]
  (let [start! #(controller/begin-operation! current-view (:document-load-seq loads) setters)
        selected-segment (logic/find-segment detail seg-idx)]
    {:on-review #(run-document-review! selected % project (start!))
     :on-resource-review #(run-resource-bulk-submit! selected form % (start!))
     :on-publication-approval #(run-publication-approval! selected (start!))
     :on-translation-dispatch #(run-translation-dispatch! selected (start!))
     :on-segment-submit
     #(if (:resource_split selected-segment)
        (run-resource-split-submit! selected selected-segment form % (start!))
        (run-segment-submit! selected-segment form % selected project (start!)))}))

(defn- layout-props
  "Retain existing props and normal controls while replacing boundary callbacks."
  [{:keys [project target-lang detail seg-idx] :as data} feedback
   {:keys [current-view boundaries setters] :as context}]
  (let [next-split-index (split-review/next-split-index (:segments detail) seg-idx)]
    (merge (dissoc data :setters) (dissoc feedback :setters)
           (mutation-callbacks data context)
           {:set-project (:set-project! boundaries)
            :set-target-lang (:set-target-lang! boundaries)
            :set-selected (:set-selected! boundaries)
            :set-seg-idx (:set-seg-idx! setters) :set-form (:set-form! setters)
            :selected-segment (logic/find-segment detail seg-idx)
            :toggle-config #((:set-show-config! setters) not)
            :dismiss-notice #((:set-notice! setters) nil)
            :dismiss-error #((:set-error! setters) nil)
            :on-export #(run-export! project target-lang
                                     (controller/export-setters current-view setters))
            :on-segment-skip (when (some? next-split-index)
                               #((:set-seg-idx! setters) next-split-index))})))

(hx/defnc translation-review-page
  "Render the translation page with source-bound lifecycle and operation guards."
  []
  (let [data (use-review-data)
        feedback (use-review-feedback)
        context (use-review-context data feedback)]
    (use-review-loads!
     (merge data (:loads context) (:setters data)
            {:setters (:setters context)
             :selected-segment (logic/find-segment (:detail data) (:seg-idx data))}))
    (hx/$ presentation/translation-review-layout {& (layout-props data feedback context)})))
