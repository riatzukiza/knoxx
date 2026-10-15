(ns knoxx.backend.extern.openplanner-sdk
  "JS boundary for the OpenPlanner SDK (@open-hax/openplanner-sdk).

   The SDK is OpenPlanner's data plane as a library: direct MongoDB plus
   self-sourced embeddings (EMBED_PROVIDER_*), no REST hop. This adapter owns
   the singleton SDK instance and all interop; callers pass and receive CLJS
   data shaped exactly like the corresponding /v1 REST response bodies."
  (:require ["@open-hax/openplanner-sdk" :as sdk-mod]
            [knoxx.backend.extern.embedding :as embedding]
            [knoxx.backend.extern.json :as xjson]
            [knoxx.backend.extern.openplanner-sdk-vectors :as vectors]
            [knoxx.backend.law.embedding :as embedding-law]))

(defonce ^:private sdk-promise* (atom nil))

(defn- resolve-mongo-uri
  "Resolve the MongoDB connection string from the same env lineage the rest of
   the backend uses (see knoxx.backend.infra.mongo-client)."
  []
  (or (aget js/process.env "MONGODB_URI")
      (aget js/process.env "OPENPLANNER_MONGODB_URI")
      "mongodb://localhost:27017"))

(defn- resolve-mongo-db-name
  []
  (or (aget js/process.env "MONGODB_DB")
      (aget js/process.env "OPENPLANNER_MONGODB_DB")
      "openplanner"))

(defn- create-sdk!
  []
  (sdk-mod/createOpenPlannerSdk
   #js {:config #js {:mongodb #js {:uri (resolve-mongo-uri)
                                   :dbName (resolve-mongo-db-name)}}
        :log #js {:warn (fn [obj msg]
                          (js/console.warn "[openplanner-sdk]" msg
                                           (or (some-> obj (aget "err") (aget "message")) "")))}}))

(defn- get-sdk!
  "Shared SDK instance as a js Promise; created on first use."
  ^js []
  (or @sdk-promise*
      (let [created (create-sdk!)]
        (reset! sdk-promise* created)
        created)))

(defn ^:async close-sdk!
  "Close the shared SDK instance (mongo connection). Safe to call when unused."
  []
  (when-let [pending @sdk-promise*]
    (reset! sdk-promise* nil)
    (let [sdk (await pending)]
      (await (.close sdk)))))

(defn- mongo-ctx
  ^js [^js sdk]
  #js {:mongo (.-mongo sdk)})

(defn ^:async verify-event-vectors!
  "Fail closed unless every named event has materialized hot-vector chunks.

  This is public so an idempotent caller that finds an already-durable base
  event can verify its detached projection too; replay must not equate an
  event row with a usable embedding. Every chunk must carry a finite, nonempty
  vector whose length and recorded dimension equal 1024, the admitted Qwen model,
  and complete recorded chunk identity. SDK-owned source redaction remains explicit."
  [event-ids]
  (embedding/configuration!)
  (let [sdk (await (get-sdk!))]
    (await (vectors/verify-event-vectors! sdk event-ids))))

(defn- events-collection!
  [^js sdk event-id]
  (let [events (some-> sdk (.-mongo) (.-events))]
    (when-not (and events
                   (fn? (.-find events))
                   (fn? (.-findOne events))
                   (fn? (.-updateOne events)))
      (throw (ex-info "OpenPlanner event collection is unavailable"
                      {:status 503
                       :code "openplanner_events_unavailable"
                       :event-id event-id})))
    events))

(defn- ^:async event-row!
  [^js events event-id]
  (xjson/to-cljs
   (await (.findOne events
                    (clj->js {:id event-id})
                    (clj->js {:projection {:id 1 :extra 1}})))))

(defn- extra-state!
  [event-id row required-extra]
  (when-not row
    (throw (ex-info "OpenPlanner event disappeared during metadata repair"
                    {:status 503
                     :code "openplanner_event_missing"
                     :event-id event-id})))
  (let [extra (:extra row)
        conflicts (into {}
                        (keep (fn [[key expected]]
                                (let [actual (get extra key)]
                                  (when (and (some? actual)
                                             (not= expected actual))
                                    [key {:expected expected
                                          :actual actual}]))))
                        required-extra)]
    (when (seq conflicts)
      (throw (ex-info "OpenPlanner event metadata conflicts with immutable translation evidence"
                      {:status 409
                       :code "openplanner_event_extra_conflict"
                       :event-id event-id
                       :conflicts conflicts})))
    {:extra extra
     :missing (into {}
                    (filter (fn [[key _expected]] (nil? (get extra key))))
                    required-extra)}))

(defn- dotted-extra-set
  [fields]
  (into {} (map (fn [[key value]] [(str "extra." (name key)) value])) fields))

(defn- missing-field-guard
  [[key expected]]
  (let [path (str "extra." (name key))]
    {:$or [{path {:$exists false}}
           {path nil}
           {path expected}]}))

(defn- ^:async write-missing-extra!
  [^js events event-id extra missing]
  (if (map? extra)
    (await (.updateOne events
                       (clj->js {:id event-id
                                 :$and (mapv missing-field-guard missing)})
                       (clj->js {:$set (dotted-extra-set missing)})))
    (await (.updateOne events
                       (clj->js {:id event-id
                                 :$or [{:extra nil}
                                       {:extra {:$exists false}}]})
                       (clj->js {:$set {:extra missing}})))))

(defn ^:async ensure-event-extra-fields!
  "Backfill deterministic missing fields on one stable existing event.

  A conflicting non-nil value is immutable evidence drift and is refused. The
  guarded update fills only absent/nil fields, then re-reads the row so a lost
  race or an adapter that ignored the update still fails closed."
  [event-id required-extra]
  (when-not (map? required-extra)
    (throw (ex-info "OpenPlanner event extra repair requires a field map"
                    {:status 500 :code "openplanner_event_extra_invalid"})))
  (let [sdk (await (get-sdk!))
        events (events-collection! sdk event-id)
        before (extra-state! event-id (await (event-row! events event-id))
                             required-extra)]
    (when (seq (:missing before))
      (await (write-missing-extra! events event-id (:extra before)
                                   (:missing before))))
    (let [after (extra-state! event-id (await (event-row! events event-id))
                              required-extra)]
      (when (seq (:missing after))
        (throw (ex-info "OpenPlanner event metadata repair was not persisted"
                        {:status 503
                         :code "openplanner_event_extra_repair_failed"
                         :event-id event-id
                         :missing-fields (vec (keys (:missing after)))})))
      {:ok true
       :event-id event-id
       :updated-fields (vec (keys (:missing before)))})))

(defn ^:async ensure-event-vectors!
  "Materialize missing/invalid hot vectors from immutable base event rows.

  No base event is reinserted. SDK scope resolution must select the admitted model;
  its indexing core receives the same checked provider used for ingest and query.
  SDK metadata/chunking and transactional vector replacement stay in the SDK,
  followed by model, dimensional and complete chunk verification. Blank text and SDK-excluded event kinds fail explicitly."
  [event-ids]
  (embedding/configuration!)
  (let [sdk (await (get-sdk!))]
    (await (vectors/ensure-event-vectors! sdk event-ids))))

(defn ^:async events!
  "Ingest event envelopes. Same response shape as POST /v1/events.

  Embedded callers that need read-your-writes may pass
  `{:await-index? true}`. This awaits the SDK's detached embedding work before
  removing its transport-only promise from the returned response."
  ([events]
   (await (events! events {})))
  ([events {:keys [await-index?]}]
   (let [configuration (embedding/configuration!)
         sdk (await (get-sdk!))
         checked-runtime (embedding/runtime (.-embeddingRuntime sdk) configuration)
         result (await (sdk-mod/ingestEvents
                        #js {:mongo (.-mongo sdk) :embeddingRuntime (:handle checked-runtime)
                             :log #js {:warn (fn [_obj msg] (js/console.warn "[openplanner-sdk]" msg))}}
                        (clj->js events)))
         background-indexing (aget result "backgroundIndexing")]
     ;; The SDK catches some detached generation failures. Waiting plus retained
     ;; runtime evidence prevents those failures from becoming successful completion.
     (when background-indexing (await background-indexing))
     (embedding/assert-complete! checked-runtime
                                 (select-keys (xjson/to-cljs result)
                                              [:count :ids :projectedGraphEdges :queuedEventVectors :queuedGraphNodeEmbeddings]))
     (when await-index?
       (await (vectors/ensure-event-vectors! sdk (mapv :id events))))
     (js-delete result "acceptedEvents")
     (js-delete result "backgroundIndexing")
     (xjson/to-cljs result))))

(defn- checked-read-cursor
  "Decorate read cursors only; retain SDK queries, ordering, limits and native rows."
  [^js cursor checked-runtime check-row!]
  (let [wrapped #js {:toArray (^:async fn []
                              (let [rows (await (.toArray cursor))]
                                (doseq [row (xjson/to-cljs rows)]
                                  (try (check-row! row)
                                       (catch :default error
                                         (throw (embedding/retain-failure! checked-runtime error)))))
                                rows))}]
    (doseq [method ["sort" "limit" "project"]]
      (aset wrapped method
            (fn [& args]
              (.apply (aget cursor method) cursor (clj->js args))
              wrapped)))
    wrapped))

(defn- checked-query-collection
  [^js collection checked-runtime check-row!]
  (let [wrapped (js/Object.create collection)]
    (doseq [method ["find" "aggregate"]]
      (aset wrapped method
            (fn [& args]
              (checked-read-cursor
               (.apply (aget collection method) collection (clj->js args)) checked-runtime check-row!))))
    wrapped))

(defn- checked-query-mongo
  "Validate actual partition and vector reads in the SDK's existing query core.
  This includes the core's second read and avoids a racy separate preflight."
  [^js mongo checked-runtime]
  (let [wrapped (js/Object.create mongo)
        db (.-db mongo)
        checked-db (js/Object.create db)]
    (aset wrapped "vectorPartitions"
          (checked-query-collection (.-vectorPartitions mongo) checked-runtime
                                    #(embedding-law/assert-valid! ::embedding-law/partition %)))
    (aset checked-db "collection"
          (fn [name opts]
            (checked-query-collection (.collection db name opts) checked-runtime embedding-law/assert-row!)))
    (aset wrapped "db" checked-db)
    wrapped))

(defn ^:async vector-search!
  "Vector search. Same response shape as POST /v1/search/vector."
  [payload]
  (embedding-law/assert-query! payload)
  (let [configuration (embedding/configuration!)
        sdk (await (get-sdk!))
        checked-runtime (embedding/runtime (.-embeddingRuntime sdk) configuration)
        result (await (sdk-mod/vectorSearchWithQuality
                       #js {:mongo (checked-query-mongo (.-mongo sdk) checked-runtime)
                            :embeddingRuntime (:handle checked-runtime)}
                       (clj->js payload)))]
    (embedding/assert-complete! checked-runtime {:operation :query})
    (xjson/to-cljs result)))

(defn ^:async session
  "Session detail. Same response shape as GET /v1/sessions/:id."
  [session-id opts]
  (let [sdk (await (get-sdk!))]
    (xjson/to-cljs (await (sdk-mod/getSessionResponse (mongo-ctx sdk) (str session-id) (clj->js (or opts {})))))))

(defn ^:async sessions
  "Session list. Same response shape as GET /v1/sessions."
  [opts]
  (let [sdk (await (get-sdk!))]
    (xjson/to-cljs (await (sdk-mod/listSessionsResponse (mongo-ctx sdk) (clj->js (or opts {})))))))

(defn ^:async mongo-collections
  "Collection inventory. Same response shape as GET /v1/mongo/collections."
  []
  (let [sdk (await (get-sdk!))]
    (xjson/to-cljs (await (sdk-mod/listCollectionsResponse (mongo-ctx sdk))))))

(defn ^:async mongo-query
  "Raw collection query. Same response shape as POST /v1/mongo/query."
  [payload]
  (let [sdk (await (get-sdk!))]
    (xjson/to-cljs (await (sdk-mod/queryCollectionResponse (mongo-ctx sdk) (clj->js (or payload {})))))))

(defn ^:async health
  "Mongo-mode health probe shaped like the REST client's health! result:
   {:ok bool :status int :body map}."
  []
  (try
    (let [sdk (await (get-sdk!))
          db (.-db (.-mongo sdk))]
      (await (.command (.admin db) #js {:ping 1}))
      {:ok true
       :status 200
       :body {:ok true :storageBackend "mongodb" :transport "sdk"}})
    (catch :default err
      {:ok false
       :status 503
       :body {:detail (str "OpenPlanner SDK mongo ping failed: " (.-message err))}})))
