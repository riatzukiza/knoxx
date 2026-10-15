(ns knoxx.backend.extern.checked-embedding-verifier
  "Owned HTTP tour of the real checked adapter with explicit hermetic SDK/provider fixtures."
  (:require ["@open-hax/openplanner-sdk" :as sdk]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [knoxx.backend.extern.embedding :as embedding]
            [knoxx.backend.extern.fastify :as fastify]
            [knoxx.backend.extern.json :as xjson]
            [knoxx.backend.extern.openplanner-sdk :as planner]
            [knoxx.backend.infra.http-server :as http]))

(defn- digest [bytes] (.digest (.update (.createHash crypto "sha256") bytes) "hex"))

(defn- proof! [file]
  (let [proof (into {} (map (fn [[key value]] [(keyword key) value]))
                    (js->clj (.parse js/JSON (fs/readFileSync file "utf8"))))
        checkout (fs/realpathSync (:checkout proof))]
    (when-not (= checkout (:checkout proof)) (throw (ex-info "Expected physical checkout" {})))
    (doseq [[relative expected] (:files proof)]
      (when-not (= expected (digest (fs/readFileSync (path/resolve checkout relative))))
        (throw (ex-info "Source proof mismatch" {:path relative}))))
    (doseq [[file expected] (:runtime-files proof)]
      (when-not (= expected (digest (fs/readFileSync file)))
        (throw (ex-info "Concrete compiled runtime proof mismatch" {:path file}))))
    (when-not (= (:compiled-sha256 proof) (digest (fs/readFileSync (aget js/process.argv 1))))
      (throw (ex-info "Serving loader proof mismatch" {})))
    proof))

(defn- event [id text]
  {:schema "openplanner.event.v1" :id id :ts "2026-10-14T00:00:00Z"
   :source "owned-embedding-verifier" :kind "docs" :text text})

(defn- partitions! [model]
  (sdk/__setPartitions (clj->js (mapv (fn [tier] {:tier tier :model model :dimensions 1024
                                                :collectionName (str "owned-" tier)}) ["hot" "compact"]))))

(defn- ^:async ingest! [id text]
  {:result (await (planner/events! [(event id text)] {:await-index? true}))
   :coverage (await (planner/verify-event-vectors! [id]))
   :stored (await (planner/mongo-query {:collection "events" :filter {:id id}}))})

(defn- ^:async repair! [id text]
  (sdk/__setCheckedRuntimeMode false)
  (sdk/__setEventVectorMode "missing")
  (try
    (await (planner/events! [(event id text)]))
    (finally (sdk/__setCheckedRuntimeMode true) (sdk/__setEventVectorMode "valid")))
  {:coverage (await (planner/ensure-event-vectors! [id]))
   :stored (await (planner/mongo-query {:collection "events" :filter {:id id}}))})

(defn- ^:async compact! [text]
  (let [native (await (sdk/createOpenPlannerSdk))
        runtime (embedding/runtime (.-embeddingRuntime native) (embedding/configuration!))
        provider (.getBackgroundEmbeddingFunction (.-compact (:handle runtime)))
        vectors (await (.generate provider (clj->js [text])))]
    {:count (.-length vectors) :width (.-length (aget vectors 0))}))

(defn- ^:async legacy! []
  (partitions! "gte-large-en-v1.5")
  (try
    (await (planner/vector-search! {:q "owned legacy query" :tier "both"}))
    {:refused false}
    (catch :default error {:refused true :code (:code (ex-data error))})
    (finally (partitions! "qwen3-embedding:8b"))))

(defn- ^:async malformed! [id text]
  (let [original js/fetch
        request (atom nil)]
    (set! js/fetch (fn [url opts]
                    (when-not (= "http://127.0.0.1:11434/v1/embeddings" url)
                      (throw (ex-info "Unexpected tour transport" {})))
                    (reset! request (xjson/to-cljs (.parse js/JSON (aget opts "body"))))
                    (js/Promise.resolve
                     (js/Response. "{\"model\":\"qwen3-embedding:8b\",\"data\":[{\"index\":0,\"embedding\":[1,2,3]}]}"
                                   #js {:status 200}))))
    (try
      (await (planner/events! [(event id text)] {:await-index? true}))
      {:refused false}
      (catch :default error
        (let [native (await (sdk/createOpenPlannerSdk))]
          {:refused true :code (:code (ex-data error)) :cause-code (:cause-code (ex-data error))
           :request @request :partial-progress (:partial-progress (ex-data error))
           :vectors (xjson/to-cljs (await (.toArray (.find (.-hotVectors (.-mongo native))
                                                         (clj->js {:parent_id {:$in [id]}})))))
           :stored (await (planner/mongo-query {:collection "events" :filter {:id id}}))}))
      (finally (set! js/fetch original)))))

(defn- transport-since [before]
  (->> (drop before (array-seq sdk/__calls))
       (filter #(= "checkedEmbeddingTransport" (aget % "name")))
       (mapv #(xjson/to-cljs (aget % "args")))))

(defn- route! [app method url token operation]
  (fastify/route! app {:method method :url url
                      :handler (^:async fn [request reply]
                                 (if-not (= token (fastify/request-header request "x-verify-token"))
                                   (fastify/send-json! reply 401 {:error "Owned fixture token required"})
                                   (let [before (count (array-seq sdk/__calls))
                                         result (await (operation))]
                                     (fastify/send-json! reply 200
                                                         {:result result :requests (transport-since before)}))))}))

(defn ^:async main
  "Serve an empty owned directory and memory-only fixtures; no external provider or Mongo."
  [& args]
  (try
    (let [[root proof-file] args
          proof (proof! proof-file)
          token (str (random-uuid))
          prefix (str "owned-tour-" (random-uuid))
          text (str (apply str (repeat 150 "same complete prefix ")) "retained full source tail")
          app (http/create-app!)]
      (when-not (and (= 2 (count args)) (empty? (array-seq (fs/readdirSync root))))
        (throw (ex-info "Expected an empty owned runtime directory" {})))
      (sdk/__resetOpenPlannerStub)
      (sdk/__setCheckedRuntimeMode true)
      (partitions! "qwen3-embedding:8b")
      (route! app "GET" "/verify/proof" token (fn [] proof))
      (route! app "POST" "/verify/ingest" token #(ingest! (str prefix "-ingest") text))
      (route! app "POST" "/verify/query" token #(planner/vector-search! {:q text :tier "both"}))
      (route! app "POST" "/verify/compact" token #(compact! text))
      (route! app "POST" "/verify/repair" token #(repair! (str prefix "-repair") text))
      (route! app "POST" "/verify/legacy" token legacy!)
      (route! app "POST" "/verify/malformed" token #(malformed! (str prefix "-malformed") text))
      (await (http/listen! app "127.0.0.1" 0))
      (fs/writeFileSync (path/join root "ready.json")
                        (xjson/stringify {:origin (str "http://127.0.0.1:" (.-port (.address (.-server app))))
                                          :token token :proof proof :source text}))
      (println "Owned checked-embedding HTTP fixture ready; provider and SDK persistence are hermetic"))
    (catch :default error (js/console.error error) (js/process.exit 1))))
