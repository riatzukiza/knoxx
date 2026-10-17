(ns knoxx.backend.extern.openplanner-translation-mongo.verification-fixture
  "Owned verification operations with one validated native client lifecycle.

   The script supplies only closed CLJS request data. The native constructor
   seam is an explicit authority for the named test extern, never request data;
   it cannot skip validation, ownership checks or converted-result validation."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [knoxx.backend.domain.node.crypto :as crypto]
            [knoxx.backend.domain.node.fs :as files]
            [knoxx.backend.extern.clock :as clock]
            [knoxx.backend.extern.openplanner-translation-mongo.common :as common]
            [knoxx.backend.extern.openplanner-translation-mongo.verification-resource :as resource]
            [knoxx.backend.infra.publication-source-revision :as source-revision]
            [knoxx.backend.infra.stores.mongo-translation-evidence :as evidence-mongo]
            [knoxx.backend.law.openplanner-translation :as translation-law]
            [knoxx.backend.law.translation-dispatch :as dispatch-law]
            [knoxx.backend.law.translation-review-verification-fixture :as law]
            [knoxx.backend.law.translation-review-verification-resource :as resource-data]
            [openplanner.translations.core :as translation]
            ["mongodb" :refer [MongoClient ObjectId]]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def ^:dynamic *client-factory*
  "Native-only construction seam. Only the owning extern/test extern may use it."
  (fn [uri options] (MongoClient. uri options)))

(def ^:private source-text "Owned legacy source for authorization verification.")
(def ^:private translated-text "Traducción sintética de verificación de autorización.")

(defn- refuse! [code]
  (throw (ex-info "Translation verification fixture refused" {:code code})))

(defn- collection [db name] (.collection db name))

(defn- ^:async rows! [db name selector]
  (vec (array-seq (await (.toArray (.find (collection db name) (clj->js selector)))))))

(defn- id-string [value]
  (when (instance? ObjectId value) (.toHexString value)))

(defn- sync-directory! [filename]
  (let [descriptor (.openSync fs (.dirname path filename) "r")]
    (try (.fsyncSync fs descriptor) (finally (.closeSync fs descriptor)))))

(defn- create-intent! [filename ownership]
  (law/assert-valid! ::law/ownership ownership)
  (let [descriptor (.openSync fs filename "wx" 384)]
    ;; Never remove a partial intent. Failure retains evidence and prevents a
    ;; later seed from adopting an insertion whose outcome was not established.
    (try (.writeFileSync fs descriptor (js/JSON.stringify (clj->js ownership)))
         (.fsyncSync fs descriptor)
         (finally (.closeSync fs descriptor))))
  (sync-directory! filename)
  ownership)

(defn- load-intent! [filename]
  (let [stat (.lstatSync fs filename)]
    (when-not (and (.isFile stat) (not (.isSymbolicLink stat)))
      (refuse! :verification-ownership-file-invalid)))
  (law/assert-valid!
   ::law/ownership
   (js->clj (js/JSON.parse (.readFileSync fs filename "utf8")) :keywordize-keys true)))

(defn- ownership-for! [{:keys [action settings scope] :as request}]
  (let [uri-digest (crypto/sha256-hex (:mongo-uri settings))
        ownership (if (= "legacy-seed" action)
                    (create-intent!
                     (:ownership-file settings)
                     {:version 1 :run_id (:run-id scope) :source_commit (:source-commit settings)
                      :mongo_uri_sha256 uri-digest :mongo_db (:mongo-db settings)
                      :document_id (:document-id scope) :segment_id (.toHexString (ObjectId.))
                      :org_id (:org-id scope) :project (:project scope)
                      :garden_id (:garden-id scope) :target_lang (name (:target-locale scope))})
                    (load-intent! (:ownership-file settings)))]
    (when-not (law/ownership-matches? request ownership uri-digest)
      (refuse! :verification-ownership-mismatch))
    ownership))

(defn- relation-selector [ownership]
  {:document_id (:document_id ownership) :org_id (:org_id ownership)
   :project (:project ownership) :garden_id (:garden_id ownership)
   :target_lang (:target_lang ownership)})

(defn- segment-selector [ownership]
  (assoc (relation-selector ownership) :_id (ObjectId. (:segment_id ownership))))

(defn- label-selector [ownership]
  {:segment_id (:segment_id ownership) :org_id (:org_id ownership)
   :project (:project ownership)})

(defn- result-identity [{:keys [settings]} ownership]
  (assoc (select-keys ownership [:run_id :document_id :segment_id :org_id :project :garden_id :target_lang])
         :ownership_file (:ownership-file settings)))

(defn- row-scope-matches? [row ownership]
  (and (= (:segment_id ownership) (id-string (aget row "_id")))
       (every? (fn [[field value]]
                 (and (js/Object.hasOwn row (name field)) (= value (aget row (name field)))))
               (relation-selector ownership))))

(defn- ^:async owned-row! [db ownership]
  (let [rows (await (rows! db common/segment-collection-name
                           {:_id (ObjectId. (:segment_id ownership))}))]
    (when (> (count rows) 1) (refuse! :verification-owned-row-ambiguous))
    (when-let [row (first rows)]
      (when-not (row-scope-matches? row ownership)
        (refuse! :verification-owned-row-scope-mismatch))
      row)))

(defn- ^:async legacy-read! [db request ownership]
  (let [row (await (owned-row! db ownership))
        relation (await (rows! db common/segment-collection-name (relation-selector ownership)))
        labels (await (rows! db common/label-collection-name (label-selector ownership)))]
    (when-not row (refuse! :verification-owned-row-missing))
    (when-not (= 1 (count relation)) (refuse! :verification-owned-relation-ambiguous))
    (merge (result-identity request ownership)
           {:action "legacy-read" :segment_count 1 :label_count (count labels)
            :label_version_counter (aget row "label_version_counter") :status (aget row "status")
            :source_text_matches (= source-text (aget row "source_text"))
            :translated_text_matches (= translated-text (aget row "translated_text"))})))

(def ^:private segment-fields
  [[:source_text :source-text] [:translated_text :translated-text]
   [:source_lang :source-lang] [:target_lang :target-lang] [:document_id :document-id]
   [:segment_index :segment-index] [:status :status] [:garden_id :garden-id]
   [:org_id :org-id] [:project :project]])

(defn- checked-segment-input! [input]
  (try
    (translation-law/assert-valid! :translation-verification/legacy-segment
                                   translation-law/CreateTranslationSegmentRequest input)
    (catch :default error
      (throw (ex-info "Verification legacy segment contract refused"
                      {:code :verification-legacy-segment-invalid} error)))))

(defn- normalized-segment-input! [input]
  (let [checked (checked-segment-input! input)
        normalized (translation/normalize-segment
                    (into {} (map (fn [[wire field]] [field (get checked wire)])) segment-fields))]
    (when (seq (translation/segment-errors normalized))
      (refuse! :verification-legacy-segment-invalid))
    (checked-segment-input!
     (into {} (map (fn [[wire field]] [wire (if (= wire :status)
                                            (translation/status-wire (get normalized field))
                                            (get normalized field))])) segment-fields))))

(defn- ^:async legacy-seed! [db request ownership]
  (when (seq (await (rows! db common/segment-collection-name (relation-selector ownership))))
    (refuse! :verification-legacy-collision))
  (let [input (normalized-segment-input!
               (merge (relation-selector ownership)
                      {:source_text source-text :translated_text translated-text
                       :source_lang "en" :segment_index 0 :status "pending"}))
        id (ObjectId. (:segment_id ownership))
        at (js/Date. (clock/instant-iso))
        row (assoc input :_id id :label_version_counter 0 :created_at at :updated_at at)
        _ (when-not (= (relation-selector ownership) (select-keys input (keys (relation-selector ownership))))
            (refuse! :verification-legacy-normalized-scope-mismatch))
        result (await (.insertOne (collection db common/segment-collection-name) (clj->js row)))]
    (when-not (and (true? (aget result "acknowledged"))
                   (= (:segment_id ownership) (id-string (aget result "insertedId"))))
      (refuse! :verification-legacy-insert-uncertain))
    (assoc (await (legacy-read! db request ownership)) :action "legacy-seeded")))

(defn- ^:async delete-owned! [db name selector]
  (let [result (await (.deleteMany (collection db name) (clj->js selector)))
        count (aget result "deletedCount")]
    (when-not (and (true? (aget result "acknowledged")) (integer? count) (not (neg? count)))
      (refuse! :verification-owned-delete-uncertain))
    count))

(defn- ^:async legacy-cleanup! [db request ownership]
  (await (owned-row! db ownership))
  (let [removed-labels (await (delete-owned! db common/label-collection-name (label-selector ownership)))
        _ (await (owned-row! db ownership))
        removed-segments (await (delete-owned! db common/segment-collection-name (segment-selector ownership)))
        remaining-row (await (owned-row! db ownership))
        labels (await (rows! db common/label-collection-name (label-selector ownership)))]
    (merge (result-identity request ownership)
           {:action "legacy-cleaned" :removed_segment_count removed-segments
            :removed_label_count removed-labels :remaining_segment_count (if remaining-row 1 0)
            :remaining_label_count (count labels)})))

(defn- row-value [row field]
  (aget row (name field)))

(defn- safe-ids [rows field]
  (->> rows (keep #(row-value % field)) sort vec))

(defn- dispatch-binding! [row expected]
  (let [binding (reader/read-string (aget row "binding_edn"))
        checked (dispatch-law/assert-record!
                 (cond-> (assoc binding :dispatch/outcome (keyword (aget row "outcome")))
                   (aget row "batch_id") (assoc :dispatch/batch-id (aget row "batch_id"))))]
    (when-not (= (dispatch-law/attempt-binding expected) (dispatch-law/attempt-binding checked))
      (refuse! :verification-dispatch-binding-mismatch))
    checked))

(defn- state-digest [dispatches batches receipts]
  (let [ordered (fn [rows] (sort-by #(str (row-value % :_id)) rows))]
    (crypto/sha256-hex
     (js/JSON.stringify
      (clj->js {:dispatches (ordered dispatches) :batches (ordered batches)
                :receipts (ordered receipts)})))))

(defn- ^:async dispatch-read! [db {:keys [settings scope]}]
  (let [opts (merge settings scope)
        revision (source-revision/content-revision (await (files/read-file! (:source-file settings))))
        key (resource-data/dispatch-key opts revision)
        dispatches (await (rows! db evidence-mongo/DISPATCHES_COLLECTION {:dispatch_key key}))
        expected (resource-data/accepted-dispatch opts revision
                                                  (or (some-> (first dispatches) (aget "binding_edn")
                                                              reader/read-string :dispatch/at)
                                                      (clock/instant-iso)))
        checked (mapv #(dispatch-binding! % expected) dispatches)
        batches (await (rows! db common/batch-collection-name
                              {:dispatch_key key :org_id (:org-id scope)
                               :project (or (:project scope) "devel")
                               :garden_id (resource-data/keyword-wire (:garden scope))
                               :target_lang (name (:target-locale scope))}))
        receipts (await (rows! db evidence-mongo/RECEIPTS_COLLECTION
                              {:dispatch_key key :document (pr-str (:document scope))
                               :garden (pr-str (:garden scope)) :locale (name (:target-locale scope))
                               :org_id (:org-id scope) :project (or (:project scope) "\u0000none")
                               :source_revision revision}))]
    {:action "dispatch-read" :run_id (:run-id scope) :org_id (:org-id scope)
     :project (:project scope) :document_id (resource-data/keyword-wire (:document scope))
     :source_revision revision :dispatch_count (count dispatches)
     :claim_count (count (filter #(some? (aget % "completion_owner")) dispatches))
     :batch_count (count batches) :receipt_count (count receipts)
     :dispatch_statuses (safe-ids dispatches :outcome) :batch_statuses (safe-ids batches :status)
     :dispatch_ids (vec (sort (map :dispatch/attempt-id checked)))
     :batch_ids (safe-ids batches :batch_id) :receipt_ids (safe-ids receipts :receipt_key)
     :state_sha256 (state-digest dispatches batches receipts)}))

(defn- ^:async perform! [db request ownership]
  (case (:action request)
    "legacy-seed" (await (legacy-seed! db request ownership))
    "legacy-read" (await (legacy-read! db request ownership))
    "legacy-cleanup" (await (legacy-cleanup! db request ownership))
    "dispatch-read" (await (dispatch-read! db request))
    (await (resource/execute! db request))))

(defn- lifecycle-failure! [primary cleanup]
  (cond
    (and primary cleanup)
    (throw (ex-info "Verification operation failed and client cleanup also failed"
                    {:code :verification-primary-and-cleanup-failed :primary-thrown? true
                     :primary-error-present? (some? (:error primary)) :cleanup-failed? true}
                    (:error primary)))
    primary (throw (:error primary))
    cleanup (throw (ex-info "Verification client cleanup failed"
                           {:code :verification-cleanup-failed :cleanup-failed? true}
                           (:error cleanup)))))

(defn ^:async execute!
  "Admit plain request data before construction and plain results before return.

   An exclusively created intent survives any partial legacy seed. Every client
   actually constructed is closed once; a nil primary throw stays distinguishable
   from success and cannot be replaced by a secondary cleanup failure."
  [request]
  (law/assert-request! request)
  (when (str/starts-with? (:action request) "legacy-")
    (translation-law/assert-valid!
     :translation-verification/legacy-scope translation-law/TenantScopeRequest
     {:org_id (get-in request [:scope :org-id]) :project (get-in request [:scope :project])
      :garden_id (get-in request [:scope :garden-id])}))
  (let [ownership (when (str/starts-with? (:action request) "legacy-") (ownership-for! request))
        client (*client-factory* (get-in request [:settings :mongo-uri])
                                #js {:serverSelectionTimeoutMS 5000})
        primary* (atom nil) cleanup* (atom nil) result* (atom nil)]
    (try (await (.connect client))
         (reset! result* (law/assert-result!
                         request (await (perform! (.db client (get-in request [:settings :mongo-db]))
                                                 request ownership))))
         (catch :default error (reset! primary* {:error error}))
         (finally
           (try (await (.close client))
                (catch :default error (reset! cleanup* {:error error})))))
    (lifecycle-failure! @primary* @cleanup*)
    @result*))
