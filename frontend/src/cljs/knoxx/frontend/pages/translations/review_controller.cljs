(ns knoxx.frontend.pages.translations.review-controller
  "Generation-guarded async orchestration for the translation review page."
  (:require [knoxx.frontend.pages.translations.api :as api]
            [knoxx.frontend.pages.translations.logic :as logic]
            [knoxx.frontend.pages.translations.split-review :as split-review]))

(defn- next-load-id!
  [^js load-seq]
  (let [load-id (inc (.-current load-seq))]
    (set! (.-current load-seq) load-id)
    load-id))

(defn- current-load?
  [^js load-seq load-id]
  (= load-id (.-current load-seq)))

(defn cancel!
  "Invalidate one in-flight generation if it is still current."
  [^js load-seq load-id]
  (when (current-load? load-seq load-id)
    (set! (.-current load-seq) (inc load-id))
    (set! (.-pending load-seq) false)))

(defn- preserve-current-selection!
  [documents set-selected!]
  ;; Resolve against state current when the setter runs. A request can start on
  ;; row A and finish after the reviewer chose B; closing over A moves backward.
  (set-selected!
   (fn [current]
     (when current
       (let [updated (first (filter #(logic/same-work? % current) documents))]
         (if (= updated current) current updated))))))

(defn- install-projection!
  [documents-response reviews {:keys [set-documents! set-selected!]}]
  (let [documents (logic/attach-publication-reviews
                   (:documents documents-response)
                   reviews)]
    (set-documents! documents)
    (preserve-current-selection! documents set-selected!)))

(defn- ^:async load-legacy-projection!
  [reviews-response project target-lang load-seq load-id setters]
  (let [all-reviews (:reviews reviews-response)
        reviews (cond->> all-reviews
                  (seq target-lang) (filter #(= target-lang (:locale %))))
        effective-project (logic/effective-project reviews-response project)]
    (if (and effective-project (not= effective-project project))
      ((:set-project! setters) effective-project)
      (try
        (let [documents-response
              (await (api/list-documents {:project effective-project
                                          :target-lang target-lang}))]
          (when (current-load? load-seq load-id)
            (install-projection! documents-response reviews setters)))
        (catch :default err
          (if (seq reviews)
            ;; OpenPlanner rows are compatibility enrichment once the resource
            ;; inventory exists. Their outage must not erase the CMS-owned work
            ;; list or make the new review flow unavailable.
            (when (current-load? load-seq load-id)
              (install-projection! {:documents []} reviews setters))
            ;; With no resource work, legacy documents are still the only
            ;; inventory. Preserve the established visible failure instead of
            ;; turning an unavailable legacy service into an empty list.
            (throw err)))))))

(defn- ^:async load-documents-generation!
  [project target-lang load-seq load-id
   {:keys [set-loading! set-error! set-documents!] :as setters}]
  (try
    (let [reviews-response (await (api/list-publication-reviews))]
      (when (current-load? load-seq load-id)
        (await (load-legacy-projection!
                reviews-response project target-lang load-seq load-id setters))))
    (catch :default err
      (when (current-load? load-seq load-id)
        (set-error! (or (.-message err) (str err)))
        (set-documents! [])))
    (finally
      (when (current-load? load-seq load-id)
        (set! (.-pending ^js load-seq) false)
        (set-loading! false)))))

(defn load-documents!
  "Load resource work first, then its project-scoped legacy compatibility rows."
  [project target-lang load-seq
   {:keys [set-loading! set-error!] :as setters}]
  (let [load-id (next-load-id! load-seq)]
    (set! (.-pending ^js load-seq) true)
    (set-loading! true)
    (set-error! nil)
    (load-documents-generation! project target-lang load-seq load-id setters)
    load-id))

(defn- ^:async load-manifest-generation!
  [project load-seq load-id set-manifest!]
  (try
    (let [manifest (await (api/get-manifest project))]
      (when (current-load? load-seq load-id)
        (set-manifest! manifest)))
    (catch :default _
      (when (current-load? load-seq load-id)
        (set-manifest! nil)))))

(defn load-manifest!
  "Load a project manifest without allowing an older project to win."
  [project load-seq set-manifest!]
  (let [load-id (next-load-id! load-seq)]
    (load-manifest-generation! project load-seq load-id set-manifest!)
    load-id))

(defn- empty-work-detail
  [selected]
  {:document {:title (:title selected) :source_lang (:source_lang selected)}
   :target_lang (:target_lang selected)
   :summary {:total_segments (or (:total_segments selected) 0)
             :approved (or (:approved selected) 0)
             :overall_status (or (:work_state selected) "pending")}
   :segments []})

(defn- detail-request
  [selected project]
  (cond
    (logic/blocked-resource-candidate? selected)
    (js/Promise.resolve (empty-work-detail selected))

    (split-review/review selected)
    (js/Promise.resolve (split-review/detail selected))

    (:contract_content selected)
    (js/Promise.resolve (logic/authored-detail selected))

    (logic/legacy-candidate? selected)
    (api/get-document (:document_id selected) (:target_lang selected)
                      (logic/legacy-review-scope selected project))

    :else
    (js/Promise.resolve (empty-work-detail selected))))

(defn- ^:async load-detail-generation!
  [selected project load-seq load-id
   {:keys [set-detail-loading! set-detail! set-seg-idx! set-form! set-error!]}]
  (try
    (let [detail (await (detail-request selected project))]
      (when (current-load? load-seq load-id)
        (set-detail! detail)
        (set-seg-idx! nil)
        (set-form! logic/default-label)))
    (catch :default err
      (when (current-load? load-seq load-id)
        (set-error! (or (.-message err) (str err)))
        (set-detail! nil)))
    (finally
      (when (current-load? load-seq load-id)
        (set-detail-loading! false)))))

(defn load-detail!
  "Load only the newest selection's detail; never substitute legacy bytes."
  [selected project load-seq
   {:keys [set-detail-loading!] :as setters}]
  (let [load-id (next-load-id! load-seq)]
    (set-detail-loading! true)
    (load-detail-generation! selected project load-seq load-id setters)
    load-id))


(defn initial-view
  "Describe the mounted translation view before its first requests."
  [project target-lang]
  {:project project :target-lang target-lang :selected nil
   :project-generation 0 :filter-generation 0 :work-generation 0
   :lifetime 0 :mounted? true :operation nil})

(defn change-project!
  "Detach installed state and old completions at an effective project boundary."
  [^js view project]
  (let [current (.-current view)]
    (when (not= project (:project current))
      (set! (.-current view)
            (-> current
                (assoc :project project :target-lang "" :selected nil :operation nil)
                (update :project-generation inc)
                (update :filter-generation inc)
                (update :work-generation inc)))
      true)))

(defn change-filter!
  "Advance every effective filter transition, including an ABA return."
  [^js view target-lang]
  (let [current (.-current view)]
    (when (not= target-lang (:target-lang current))
      (set! (.-current view)
            (-> current
                (assoc :target-lang target-lang :selected nil :operation nil)
                (update :filter-generation inc)
                (update :work-generation inc)))
      true)))

(defn change-work!
  "Install the latest row while distinguishing identity from a refreshed map."
  [^js view selected]
  (let [current (.-current view)
        old (:selected current)
        boundary? (not (or (= old selected) (logic/same-work? old selected)))]
    (set! (.-current view)
          (cond-> (assoc current :selected selected)
            boundary? (assoc :operation nil)
            boundary? (update :work-generation inc)))
    boundary?))

(defn mount-view!
  "Start a distinct mounted lifetime, including a development effect remount."
  [^js view]
  (set! (.-current view)
        (-> (.-current view) (assoc :mounted? true) (update :lifetime inc))))

(defn unmount-view!
  "Invalidate abandoned callbacks without calling any React state setter."
  [^js view]
  (set! (.-current view)
        (-> (.-current view)
            (assoc :mounted? false :operation nil)
            (update :lifetime inc))))

(defn- project-current?
  "Whether an original completion still belongs to this mounted project."
  [current captured]
  (and (:mounted? current)
       (= (select-keys current [:project :project-generation :lifetime])
          (select-keys captured [:project :project-generation :lifetime]))))

(defn export-current?
  "Fence shared export feedback without changing its captured download."
  [^js view captured]
  (let [current (.-current view)]
    (and (project-current? current captured)
         (= (select-keys current [:target-lang :filter-generation])
            (select-keys captured [:target-lang :filter-generation])))))

(defn current-view?
  "Require the actual filter/work generation and mounted lifetime to match."
  [^js view captured]
  (let [current (.-current view)]
    (and (export-current? view captured)
         (= (:work-generation current) (:work-generation captured))
         (or (= (:selected current) (:selected captured))
             (logic/same-work? (:selected current) (:selected captured))))))

(defn- guarded-setters
  "Fence named UI callbacks with the operation's captured current-view check."
  [current? setters setter-keys]
  (into {}
        (map (fn [setter-key]
               [setter-key (fn [value]
                             (when (current?)
                               ((get setters setter-key) value)))])
             setter-keys)))

(defn export-setters
  "Guard only shared export feedback; no saving owner is acquired."
  [^js view setters]
  (let [captured (.-current view)]
    (guarded-setters #(export-current? view captured) setters
                     [:set-notice! :set-error!])))

(defn begin-operation!
  "Bind six-runner UI completion and saving release to one current operation."
  [^js view ^js document-load-seq setters]
  (let [captured (.-current view)
        owner (js-obj)
        current? (fn []
                   (and (current-view? view captured)
                        (identical? owner (:operation (.-current view)))))
        guarded (guarded-setters current? setters
                                 [:set-error! :set-notice! :set-detail! :set-form!])]
    (set! (.-current view) (assoc captured :operation owner))
    (assoc guarded
           :current-view? current?
           :set-saving! (fn [saving?]
                          (when (current?)
                            (when-not saving?
                              (set! (.-current view)
                                    (assoc (.-current view) :operation nil)))
                            ((:set-saving! setters) saving?)))
           :refresh-documents!
           (fn []
             ;; Preserve an already pending current load. The separately owned
             ;; successor supplies post-snapshot catch-up, not a replacement here.
             (let [current (.-current view)]
               (when (and (project-current? current captured)
                          (not (.-pending document-load-seq)))
                 (load-documents! (:project current) (:target-lang current)
                                  document-load-seq setters)))))))
