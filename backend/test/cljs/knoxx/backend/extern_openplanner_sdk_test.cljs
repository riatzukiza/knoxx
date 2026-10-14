(ns knoxx.backend.extern-openplanner-sdk-test
  "Conversion regression tests for the @open-hax/openplanner-sdk extern
   boundary (stubbed module — see test/js/openplanner_sdk_test_stub.mjs) and
   the mode-selecting OpenPlanner client factory."
  (:require ["@open-hax/openplanner-sdk" :as sdk-mod]
            [cljs.test :refer [deftest is testing]]
            [knoxx.backend.extern.openplanner-sdk :as xsdk]
            [knoxx.backend.infra.clients.openplanner :as openplanner-client]
            [knoxx.backend.infra.clients.openplanner-mongo :as openplanner-mongo]))

(deftest ^:async events-conversion
  (testing "ingest result arrives as keywordized CLJS without transport-only keys"
    (let [result (await (xsdk/events! [{:schema "openplanner.event.v1"
                                        :id "ev-1"
                                        :ts "2026-01-01T00:00:00Z"
                                        :source "test"
                                        :kind "knoxx.message"
                                        :text "hello"}]))]
      (is (map? result))
      (is (true? (:ok result)))
      (is (= 1 (:count result)))
      (is (= ["ev-1"] (:ids result)))
      (is (not (contains? result :acceptedEvents)))
      (is (not (contains? result :backgroundIndexing))))))

(deftest ^:async events-can-await-background-indexing
  (testing "read-your-writes callers use the optional arity without leaking the promise"
    (let [result (await (xsdk/events! [{:schema "openplanner.event.v1"
                                        :id "ev-await-index"
                                        :ts "2026-01-01T00:00:00Z"
                                        :source "test"
                                        :kind "docs"
                                        :text "await me"}]
                                      {:await-index? true}))]
      (is (true? (:ok result)))
      (is (= ["ev-await-index"] (:ids result)))
      (is (not (contains? result :backgroundIndexing)))
      (is (= {:ok true
              :event-ids ["ev-await-index"]
              :event-count 1
              :vector-count 1
              :embedding-dimensions 3}
             (await (xsdk/verify-event-vectors! ["ev-await-index"])))))))

(deftest ^:async event-extra-is-the-durable-arbitrary-metadata-shape
  (testing "SDK persistence drops arbitrary meta but retains queryable extra"
    (let [canonical {:source_lang "en"
                     :target_lang "fr"
                     :source_text "hello"
                     :mt_model "gemma4:e2b"
                     :status "in_review"}
          id "ev-durable-translation-extra"]
      (await (xsdk/events! [{:schema "openplanner.event.v1"
                            :id id
                            :ts "2026-01-01T00:00:00Z"
                            :source "mt"
                            :kind "translation.segment"
                            :text "bonjour"
                            :meta canonical
                            :extra canonical}]))
      (let [row (-> (await (xsdk/mongo-query
                            {:collection "events"
                             :filter {:id id}
                             :limit 1}))
                    :rows first)]
        (is (nil? (:meta row)))
        (is (= canonical (select-keys (:extra row) (keys canonical))))))))

(deftest ^:async stable-existing-events-backfill-only-missing-extra-fields
  (let [id "ev-backfill-translation-extra"
        canonical {:source_lang "en"
                   :target_lang "es"
                   :source_text "hello"
                   :mt_model "gemma4:e2b"
                   :status "in_review"}]
    (await (xsdk/events! [{:schema "openplanner.event.v1"
                          :id id
                          :ts "2026-01-01T00:00:00Z"
                          :source "mt"
                          :kind "translation.segment"
                          :text "hola"
                          :meta canonical
                          :extra {:candidate_set_id "candidate-set-1"}}]))
    (let [result (await (xsdk/ensure-event-extra-fields! id canonical))
          row (-> (await (xsdk/mongo-query
                          {:collection "events" :filter {:id id} :limit 1}))
                  :rows first)]
      (is (= (set (keys canonical)) (set (:updated-fields result))))
      (is (= "candidate-set-1" (get-in row [:extra :candidate_set_id])))
      (is (= canonical (select-keys (:extra row) (keys canonical)))))))

(deftest ^:async stable-existing-events-refuse-extra-field-conflicts
  (let [id "ev-conflicting-translation-extra"
        required {:source_lang "en" :target_lang "fr"}]
    (await (xsdk/events! [{:schema "openplanner.event.v1"
                          :id id
                          :ts "2026-01-01T00:00:00Z"
                          :source "mt"
                          :kind "translation.segment"
                          :text "bonjour"
                          :extra {:source_lang "de"}}]))
    (try
      (await (xsdk/ensure-event-extra-fields! id required))
      (is false "immutable existing metadata must not be overwritten")
      (catch :default err
        (is (= "openplanner_event_extra_conflict" (:code (ex-data err))))
        (is (= {:expected "en" :actual "de"}
               (get-in (ex-data err) [:conflicts :source_lang])))))))

(deftest ^:async awaited-event-ingest-repairs-a-missing-vector
  (sdk-mod/__setEventVectorMode "missing")
  (try
    (let [id "ev-missing-vector"
          result (await (xsdk/events! [{:schema "openplanner.event.v1"
                                       :id id
                                       :ts "2026-01-01T00:00:00Z"
                                       :source "test"
                                       :kind "docs"
                                       :text "must be indexed"}]
                                      {:await-index? true}))
          verified (await (xsdk/verify-event-vectors! [id]))
          stored (await (xsdk/mongo-query
                         {:collection "events" :filter {:id id}}))]
      (is (true? (:ok result)))
      (is (= [id] (:event-ids verified)))
      (is (= 1 (:vector-count verified)))
      (is (= 1 (:total stored))
          "vector repair must not reinsert the immutable base event"))
    (finally
      (sdk-mod/__setEventVectorMode "valid"))))

(deftest ^:async existing-event-ensure-replaces-the-wrong-vector-shape
  (sdk-mod/__setEventVectorMode "wrong-dimensions")
  (try
    (let [id "ev-wrong-vector-shape"]
      (await (xsdk/events! [{:schema "openplanner.event.v1"
                            :id id
                            :ts "2026-01-01T00:00:00Z"
                            :source "test"
                            :kind "docs"
                            :text "materialize the wrong shape"}]))
      ;; Await the stub's detached projection through a second ingest-free turn.
      (await (js/Promise.resolve))
      (try
        (await (xsdk/verify-event-vectors! [id]))
        (is false "strict verification must identify the wrong vector shape")
        (catch :default err
          (is (= 3 (:expected-dimensions (ex-data err))))
          (is (= [id] (:invalid-event-ids (ex-data err))))))
      (let [ensured (await (xsdk/ensure-event-vectors! [id]))
            stored (await (xsdk/mongo-query
                           {:collection "events" :filter {:id id}}))]
        (is (= [id] (:repaired-event-ids ensured)))
        (is (= 3 (:embedding-dimensions ensured)))
        (is (= 1 (:vector-count ensured)))
        (is (= 1 (:total stored))
            "shape repair must replace vectors without reinserting the event")))
    (finally
      (sdk-mod/__setEventVectorMode "valid"))))

(deftest ^:async blank-existing-events-fail-vector-repair-clearly
  (let [id "ev-blank-vector-source"]
    (await (xsdk/events! [{:schema "openplanner.event.v1"
                          :id id
                          :ts "2026-01-01T00:00:00Z"
                          :source "test"
                          :kind "docs"
                          :text "  \n  "}]))
    (await (js/Promise.resolve))
    (try
      (await (xsdk/ensure-event-vectors! [id]))
      (is false "a blank immutable event cannot be silently marked indexed")
      (catch :default err
        (is (= "openplanner_event_not_vector_indexable"
               (:code (ex-data err))))
        (is (= id (:event-id (ex-data err))))
        (is (= :blank-text (:reason (ex-data err))))))))

(deftest ^:async sdk-excluded-existing-events-fail-vector-repair-clearly
  (let [id "ev-excluded-vector-source"]
    (await (xsdk/events! [{:schema "openplanner.event.v1"
                          :id id
                          :ts "2026-01-01T00:00:00Z"
                          :source "test"
                          :kind "graph.node"
                          :text "graph projection"}]))
    (await (js/Promise.resolve))
    (try
      (await (xsdk/ensure-event-vectors! [id]))
      (is false "SDK-excluded event kinds cannot be repaired as hot vectors")
      (catch :default err
        (is (= "openplanner_event_not_vector_indexable"
               (:code (ex-data err))))
        (is (= id (:event-id (ex-data err))))
        (is (= :excluded-kind (:reason (ex-data err))))))))

(deftest ^:async event-vector-verification-requires-configured-dimensions
  (let [previous (aget js/process.env "EMBED_PROVIDER_DIMENSIONS")]
    (try
      (aset js/process.env "EMBED_PROVIDER_DIMENSIONS" "not-an-integer")
      (try
        (await (xsdk/verify-event-vectors! ["ev-any-existing-row"]))
        (is false "invalid configured dimensions must fail before querying")
        (catch :default err
          (is (= "openplanner_embedding_dimensions_invalid"
                 (:code (ex-data err))))))
      (finally
        (if (nil? previous)
          (js-delete js/process.env "EMBED_PROVIDER_DIMENSIONS")
          (aset js/process.env "EMBED_PROVIDER_DIMENSIONS" previous))))))

(deftest ^:async vector-search-conversion
  (testing "vector search result matches the /v1/search/vector body shape"
    (let [result (await (xsdk/vector-search! {:q "hello" :k 5}))]
      (is (true? (:ok result)))
      (is (= [["stub-1"]] (get-in result [:result :ids])))
      (is (= "mongodb" (:storageBackend result))))))

(deftest ^:async sessions-conversion
  (testing "session list rows keywordize like the REST body"
    (let [result (await (xsdk/sessions {:project "p" :limit 10 :offset 0}))]
      (is (true? (:ok result)))
      (is (= "s" (-> result :rows first :session))))))

(deftest ^:async health-shape
  (testing "health resolves the REST client's {:ok :status :body} shape"
    (let [result (await (xsdk/health))]
      (is (true? (:ok result)))
      (is (= 200 (:status result)))
      (is (map? (:body result))))))

(deftest client-factory-mode-selection
  (testing "mongo mode (default) returns the direct-mongo record"
    (let [built (openplanner-client/client {:openplanner-base-url "http://x"
                                            :openplanner-api-key "k"})]
      (is (instance? openplanner-mongo/MongoOpenPlannerClient built))
      (is (openplanner-client/event-projection-repair-supported? built))))
  (testing "rest mode returns the fetch client"
    (let [built (openplanner-client/client {:openplanner-base-url "http://x"
                                            :openplanner-api-key "k"
                                            :openplanner-client-mode "rest"})]
      (is (instance? openplanner-client/FetchOpenPlannerClient built))
      (is (not (openplanner-client/event-projection-repair-supported? built)))))
  (testing "explicit opts mode overrides config"
    (let [built (openplanner-client/client {:openplanner-client-mode "mongo"}
                                           {:mode "rest"})]
      (is (instance? openplanner-client/FetchOpenPlannerClient built)))))

(deftest ^:async mongo-client-delegates-data-plane
  (testing "the direct-mongo record serves data-plane calls via the SDK"
    (let [rest-client (openplanner-client/client {:openplanner-base-url "http://x"
                                                  :openplanner-api-key "k"
                                                  :openplanner-client-mode "rest"})
          built (openplanner-mongo/client {} rest-client)
          result (await (openplanner-client/vector-search! built {:q "hello" :k 3}))]
      (is (true? (:ok result)))
      (is (= [["stub-1"]] (get-in result [:result :ids]))))))

;; Private EC1.01 runtime-contract RED. These fixtures exercise the current
;; public Knoxx API and the existing hermetic SDK handle, not a real provider,
;; Mongo store, source migration or accepted SDK adoption.

(defn- fixture-projection
  "Honor the inclusive Mongo projection and its default _id field."
  [row projection]
  (if-not projection row
    (let [result #js {}
          fields (->> (array-seq (js/Object.keys projection))
                      (filter #(= 1 (aget projection %))))]
      (doseq [field (cond-> (vec fields) (not= 0 (aget projection "_id")) (conj "_id"))]
        (when (.call (.-hasOwnProperty js/Object.prototype) row field)
          (aset result field (aget row field))))
      result)))

(defn- fixture-find
  "Return only selected owned parent rows and record the exact native query."
  [rows calls]
  (fn [filter opts]
    (swap! calls conj {:filter (js->clj filter :keywordize-keys true)
                      :projection (js->clj (some-> opts (aget "projection")) :keywordize-keys true)})
    (let [ids (some-> filter (aget "parent_id") (aget "$in"))
          selected (if ids (filterv #(.includes ids (aget % "parent_id")) rows) rows)]
      #js {:toArray (^:async fn []
                      (clj->js (mapv #(fixture-projection % (some-> opts (aget "projection"))) selected)))})))

(defn- fixture-env!
  "Temporarily bind the existing model/dimension settings without credentials."
  []
  (let [keys ["EMBED_PROVIDER_MODEL" "EMBED_PROVIDER_DIMENSIONS"]
        previous (into {} (map (fn [key] [key (aget js/process.env key)])) keys)]
    (aset js/process.env "EMBED_PROVIDER_MODEL" "qwen3-embedding:8b")
    (aset js/process.env "EMBED_PROVIDER_DIMENSIONS" "1024")
    (fn []
      (doseq [[key value] previous]
        (if (nil? value) (js-delete js/process.env key) (aset js/process.env key value))))))

(defn- ^:async fixture-outcome
  "Keep actual rejection separate from an accepted API result."
  [operation]
  (try {:outcome :accepted :value (await (operation))}
       (catch :default error {:outcome :refused :error (ex-data error)})))

(deftest ^:async recorded-model-is-required-in-addition-to-1024-dimensional-coverage
  (let [restore-env! (fixture-env!)
        sdk (await (sdk-mod/createOpenPlannerSdk))
        collection (.-hotVectors (.-mongo sdk))
        original-find (.-find collection)
        calls (atom [])
        event-id "owned-qwen-model-coverage"
        vector (vec (repeat 1024 0.25))
        row (fn [id model] (clj->js {:_id id :parent_id event-id :text "owned immutable source"
                                     :chunk_index 0 :embedding vector :embedding_dimensions 1024
                                     :embedding_model model}))]
    (try
      (aset collection "find" (fixture-find [(row "owned-qwen-chunk" "qwen3-embedding:8b")] calls))
      (let [valid (await (xsdk/verify-event-vectors! [event-id]))]
        (is (true? (:ok valid)) "otherwise-valid complete Qwen/1024 row is the positive neighbor")
        (is (= 1024 (:embedding-dimensions valid)))
        (is (= 1 (:vector-count valid)))
        (is (= {:parent_id {:$in [event-id]}} (:filter (last @calls))))
        (is (= 1 (get-in (last @calls) [:projection :embedding_model]))
            "the actual collection projection must retain recorded model identity"))
      (doseq [[label rows] [["same-width old model" [(row "owned-old-chunk" "gte-large-en-v1.5")]]
                            ["missing model" [(doto (row "owned-absent-chunk" nil) (js-delete "embedding_model"))]]
                            ["mixed model chunks" [(row "owned-current-chunk" "qwen3-embedding:8b")
                                                   (row "owned-foreign-chunk" "gte-large-en-v1.5")]]]]
        (aset collection "find" (fixture-find rows calls))
        (let [result (await (fixture-outcome #(xsdk/verify-event-vectors! [event-id])))]
          (is (= :refused (:outcome result)) label)))
      (finally
        (aset collection "find" original-find)
        (restore-env!)
        (await (xsdk/close-sdk!))))))

(deftest ^:async missing-vector-repair-cannot-use-the-unvalidated-factory-runtime
  (let [restore-env! (fixture-env!)
        sdk (await (sdk-mod/createOpenPlannerSdk))
        hot (.-hot (.-embeddingRuntime sdk))
        collection (.-events (.-mongo sdk))
        vectors (.-hotVectors (.-mongo sdk))
        original-find (.-find collection)
        original-vector-find (.-find vectors)
        original-model (.-getModel hot)
        original-provider (.-getBackgroundEmbeddingFunction hot)
        original-fetch js/fetch
        generation (atom [])
        queries (atom [])
        blocked-fetch (atom [])
        event-id "owned-unvalidated-factory-repair"
        source "same prefix; complete immutable repair source tail"
        base-row (clj->js {:_id event-id :id event-id :ts "2026-10-14T00:00:00Z"
                          :source "owned-fixture" :kind "docs" :text source :extra {}})
        before-calls (count (array-seq sdk-mod/__calls))]
    (try
      ;; Native collection reads only. This does not claim source admission or
      ;; use ingest to seed/reinsert an historical event.
      (aset collection "find" (fn [_filter opts]
                                #js {:toArray (^:async fn [] #js [(fixture-projection base-row (some-> opts (aget "projection")))])}))
      (aset vectors "find" (fn [filter opts]
                              (let [cursor (.call original-vector-find vectors filter opts)]
                                (swap! queries conj (js->clj opts :keywordize-keys true))
                                #js {:toArray (^:async fn []
                                               (let [rows (await (.toArray cursor))]
                                                 (clj->js (mapv #(fixture-projection % (some-> opts (aget "projection"))) (array-seq rows)))))})))
      (aset hot "getModel" (fn [_] "gte-large-en-v1.5"))
      (aset hot "getBackgroundEmbeddingFunction"
            (fn [_] #js {:generate (^:async fn [texts]
                                    (swap! generation conj (js->clj texts))
                                    (clj->js (mapv (fn [_] (vec (repeat 1024 0.5))) (array-seq texts))))}))
      (set! js/fetch (fn [& _] (swap! blocked-fetch conj {:blocked true}) (throw (js/Error. "Owned RED forbids provider network traffic"))))
      (let [result (await (fixture-outcome #(xsdk/ensure-event-vectors! [event-id])))
            new-calls (drop before-calls (array-seq sdk-mod/__calls))
            vector-writes (filter #(= "indexTextInMongoVectors" (aget % "name")) new-calls)
            wrong-model-writes (filter #(not= "qwen3-embedding:8b"
                                              (some-> % (aget "args") (aget "metadata") (aget "embedding_model")))
                                       vector-writes)
            raw-cursor (.call original-vector-find vectors (clj->js {:parent_id {:$in [event-id]}}))
            raw-rows (vec (array-seq (await (.toArray raw-cursor))))
            qwen-coverage? (and (seq raw-rows)
                                 (every? (fn [row]
                                           (and (= "qwen3-embedding:8b" (aget row "embedding_model"))
                                                (= 1024 (aget row "embedding_dimensions"))
                                                (js/Array.isArray (aget row "embedding"))
                                                (= 1024 (.-length (aget row "embedding")))
                                                (every? js/Number.isFinite (array-seq (aget row "embedding")))))
                                         raw-rows))]
        (is (or (= :refused (:outcome result)) qwen-coverage?)
            "repair must refuse or retain Qwen/1024 coverage; finite old-model/1024 cannot qualify")
        (is (empty? @generation) "the unvalidated closed factory provider must not generate")
        (is (empty? wrong-model-writes) "unvalidated old-model generation cannot dispatch vector indexing")
        (is (every? :blocked @blocked-fetch) "any provider fetch attempt is stopped before actual network")
        (is (= source (aget base-row "text")) "the immutable owned source is preserved")
        (is (= event-id (aget base-row "id")))
        (is (seq @queries) "the test reached the existing collection-backed public repair API"))
      (finally
        (set! js/fetch original-fetch)
        (aset collection "find" original-find)
        (aset vectors "find" original-vector-find)
        (aset hot "getModel" original-model)
        (aset hot "getBackgroundEmbeddingFunction" original-provider)
        (restore-env!)
        (await (xsdk/close-sdk!))))))
