(ns knoxx.backend.extern.openplanner-sdk-vectors
  "Recorded coverage and vector-only repair for opaque SDK handles; source rows are never reinserted."
  (:require ["@open-hax/openplanner-sdk" :as sdk-mod]
            ["@open-hax/openplanner-sdk/mongo-vectors" :as mongo-vectors]
            [knoxx.backend.extern.embedding :as embedding]
            [knoxx.backend.extern.json :as xjson]
            [knoxx.backend.law.embedding :as embedding-law]))

(defn- configured-embedding-dimensions!
  []
  (:dimensions (embedding/configuration!)))

(defn- checked-event-ids!
  [event-ids]
  (let [ids (vec (distinct event-ids))]
    (when-let [invalid-input-ids
               (seq (filterv #(not (and (string? %) (seq (.trim %)))) ids))]
      (throw (ex-info "OpenPlanner vector verification requires event ids"
                      {:status 500
                       :code "openplanner_event_ids_invalid"
                       :invalid-event-ids (vec invalid-input-ids)})))
    ids))

(defn- ^:async hot-vector-rows!
  [^js sdk ids]
  (let [hot-vectors (some-> sdk (.-mongo) (.-hotVectors))]
    (when-not (and hot-vectors (fn? (.-find hot-vectors)))
      (throw (ex-info "OpenPlanner hot-vector collection is unavailable"
                      {:status 503
                       :code "openplanner_hot_vectors_unavailable"
                       :event-ids ids})))
    (let [cursor (.find hot-vectors
                        (clj->js {:parent_id {:$in ids}})
                        (clj->js {:projection {:_id 1
                                               :parent_id 1
                                               :embedding 1
                                               :embedding_dimensions 1
                                               :embedding_model 1 :text 1
                                               :chunk_id 1 :chunk_index 1 :chunk_count 1
                                               :source_text_redacted 1 :source_ref 1}}))]
      (xjson/to-cljs (await (.toArray cursor))))))

(defn- vector-coverage
  [ids _expected-dimensions rows]
  (let [rows-by-event (group-by :parent_id rows)
        missing (filterv #(empty? (get rows-by-event %)) ids)
        invalid (filterv (fn [id]
                           (not (embedding-law/complete-chunks? (get rows-by-event id))))
                         ids)]
    {:missing-event-ids missing
     :invalid-event-ids invalid}))

(defn- assert-vector-coverage!
  [ids expected-dimensions rows]
  (let [{:keys [missing-event-ids invalid-event-ids]}
        (vector-coverage ids expected-dimensions rows)]
    (when (or (seq missing-event-ids) (seq invalid-event-ids))
      (throw (ex-info
              "OpenPlanner event vector indexing did not materialize the configured embedding shape"
              {:status 503
               :code "openplanner_event_vector_verification_failed"
               :event-ids ids
               :expected-dimensions expected-dimensions
               :missing-event-ids missing-event-ids
               :invalid-event-ids invalid-event-ids})))
    {:ok true
     :event-ids ids
     :event-count (count ids)
     :vector-count (count rows)
     :embedding-dimensions expected-dimensions}))

(defn ^:async verify-event-vectors!
  "Validate recorded coverage for the existing opaque SDK handle."
  [^js sdk event-ids]
  (let [ids (checked-event-ids! event-ids)]
    (if (empty? ids)
      {:ok true :event-ids [] :event-count 0 :vector-count 0}
      (let [expected-dimensions (configured-embedding-dimensions!)
            rows (await (hot-vector-rows! sdk ids))]
        (assert-vector-coverage! ids expected-dimensions rows)))))

(defn- events-collection!
  [^js sdk event-id]
  (let [events (some-> sdk (.-mongo) (.-events))]
    (when-not (and events (fn? (.-find events)))
      (throw (ex-info "OpenPlanner source collection is unavailable"
                      {:status 503 :code "openplanner_events_unavailable" :event-id event-id})))
    events))

(defn- ^:async raw-event-rows!
  [^js events ids]
  (let [cursor (.find events
                      (clj->js {:id {:$in ids}})
                      (clj->js
                       {:projection {:id 1 :ts 1 :source 1 :kind 1
                                     :project 1 :session 1 :message 1
                                     :role 1 :author 1 :model 1 :text 1
                                     :extra 1}}))]
    (await (.toArray cursor))))

(defn- raw-events-in-id-order!
  [ids raw-rows]
  (let [by-id (into {} (map (fn [^js row] [(aget row "id") row]))
                    (array-seq raw-rows))
        missing (filterv #(nil? (get by-id %)) ids)]
    (when (seq missing)
      (throw (ex-info "OpenPlanner base event is missing during vector repair"
                      {:status 503
                       :code "openplanner_event_missing"
                       :missing-event-ids missing})))
    (mapv by-id ids)))

(defn- unindexable-event-reason
  [^js event]
  (let [text (aget event "text")
        kind (aget event "kind")]
    (cond
      (not (string? text)) :text-not-string
      (empty? (.trim text)) :blank-text
      (contains? #{"graph.node" "graph.edge"} kind) :excluded-kind
      (not (sdk-mod/shouldIndexEventHotVectors event)) :sdk-declined
      :else nil)))

(defn- raw-event-labels
  [^js extra]
  (let [labels (some-> extra (aget "openplanner_labels") (aget "labels"))]
    (if (js/Array.isArray labels)
      (->> (array-seq labels)
           (map #(-> (or % "") str .trim))
           (remove empty?)
           distinct
           clj->js)
      #js [])))

(defn- nullish-default
  [value fallback]
  (if (nil? value) fallback value))

(defn- embedding-scope
  [^js event]
  (let [scope #js {:source (aget event "source")
                   :kind (aget event "kind")}
        project (aget event "project")]
    (when-not (nil? project)
      (aset scope "project" project))
    scope))

(defn- index-metadata
  [^js event ^js extra embedding-model]
  (let [labels-root (some-> extra (aget "openplanner_labels"))]
    #js {:ts (aget event "ts")
         :source (aget event "source")
         :kind (aget event "kind")
         :project (aget event "project")
         :session (aget event "session")
         :author (nullish-default (aget event "author") "")
         :role (nullish-default (aget event "role") "")
         :model (nullish-default (aget event "model") "")
         :embedding_model (nullish-default embedding-model "")
         :search_tier "hot"
         :visibility (nullish-default (some-> extra (aget "visibility"))
                                      "internal")
         :quality_label (nullish-default
                         (some-> labels-root (aget "quality")) "")
         :labels (raw-event-labels extra)
         :title (nullish-default
                 (some-> extra (aget "title"))
                 (nullish-default (aget event "message")
                                  (aget event "id")))}))

(defn- assert-indexable-event!
  [^js event]
  (when-let [reason (unindexable-event-reason event)]
    (throw (ex-info "OpenPlanner base event cannot produce a hot vector"
                    {:status 422
                     :code "openplanner_event_not_vector_indexable"
                     :event-id (aget event "id")
                     :kind (aget event "kind")
                     :reason reason}))))

(defn- ^:async index-event-vector!
  [^js sdk checked-runtime ^js event]
  (assert-indexable-event! event)
  (let [scope (embedding-scope event)
        hot (.-hot (:handle checked-runtime))
        extra (or (aget event "extra") #js {})
        embedding-model (.getModel hot scope)
        embedding-function (.getBackgroundEmbeddingFunction hot scope)]
    (try
      (await
       (mongo-vectors/indexTextInMongoVectors
        #js {:mongo (.-mongo sdk)
             :tier "hot"
             :parentId (aget event "id")
             :text (aget event "text")
             :extra extra
             :metadata (index-metadata event extra embedding-model)
             :embeddingFunction embedding-function}))
      (catch :default err
        (throw (ex-info "OpenPlanner event vector repair failed"
                        {:status 503
                         :code "openplanner_event_vector_repair_failed"
                         :event-id (aget event "id")
                         :detail (or (.-message err) (str err))}
                        err))))))

(defn- ^:async repair-event-vectors!
  [^js sdk checked-runtime raw-events]
  (loop [remaining raw-events]
    (when-let [event (first remaining)]
      (await (index-event-vector! sdk checked-runtime event))
      (recur (next remaining)))))

(defn ^:async ensure-event-vectors!
  "Repair only detached vectors from complete existing source rows."
  [^js sdk event-ids]
  (let [ids (checked-event-ids! event-ids)]
    (if (empty? ids)
      {:ok true :event-ids [] :event-count 0 :vector-count 0
       :repaired-event-ids []}
      (let [configuration (embedding/configuration!)
            checked-runtime (embedding/runtime (.-embeddingRuntime sdk) configuration)
            dimensions (:dimensions configuration)
            rows (await (hot-vector-rows! sdk ids))
            coverage (vector-coverage ids dimensions rows)
            repair-set (set (concat (:missing-event-ids coverage)
                                    (:invalid-event-ids coverage)))
            repair-ids (filterv repair-set ids)]
        (when (seq repair-ids)
          (let [events (events-collection! sdk (first repair-ids))
                raw-rows (await (raw-event-rows! events repair-ids))]
            (await (repair-event-vectors!
                    sdk checked-runtime (raw-events-in-id-order! repair-ids raw-rows)))))
        (embedding/assert-complete! checked-runtime {:repaired-event-ids repair-ids})
        (assoc (await (verify-event-vectors! sdk ids))
               :repaired-event-ids repair-ids)))))
