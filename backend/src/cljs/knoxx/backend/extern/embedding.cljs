(ns knoxx.backend.extern.embedding
  "Direct Ollama HTTP and native SDK runtime boundary; no embedding cache."
  (:require [clojure.string :as str]
            [knoxx.backend.extern.fetch :as fetch]
            [knoxx.backend.extern.json :as xjson]
            [knoxx.backend.law.embedding :as law]
            [knoxx.backend.shape.embedding :as shape]))

(defn- endpoint!
  [base]
  (let [url (try (js/URL. base) (catch :default _ nil))]
    (when-not (and url (= "http:" (.-protocol url)) (= "11434" (.-port url))
                   (str/blank? (.-username url)) (str/blank? (.-password url))
                   (str/blank? (.-search url)) (str/blank? (.-hash url))
                   (contains? #{"/" "/v1" "/v1/"} (.-pathname url)))
      (throw (ex-info "Embedding route must select direct host Ollama on port 11434"
                      {:status 503 :code "knoxx_embedding_route_invalid"})))
    (str (.-origin url) "/v1/embeddings")))

(defn configuration!
  "Validate operator selection before any native SDK or provider operation."
  []
  (let [raw (aget js/process.env "EMBED_PROVIDER_DIMENSIONS")
        width (when (and (string? raw) (re-matches #"[1-9][0-9]*" raw)) (js/Number raw))]
    (when-not (= law/dimensions width)
      (throw (ex-info "EMBED_PROVIDER_DIMENSIONS must select 1024"
                      {:status 503 :code "openplanner_embedding_dimensions_invalid"
                       :configured-value raw})))
    (let [host (or (aget js/process.env "OLLAMA_BASE_URL") "http://127.0.0.1:11434")
          base (or (aget js/process.env "EMBED_PROVIDER_BASE_URL") host)
          target (or (aget js/process.env "EMBED_PROVIDER_MODEL") law/model)
          compact (or (aget js/process.env "EMBED_PROVIDER_COMPACT_MODEL") target)
          endpoint (endpoint! base)]
      (when-not (= endpoint (endpoint! host))
        (throw (ex-info "Embedding route differs from selected host Ollama"
                        {:status 503 :code "knoxx_embedding_route_invalid"})))
      (law/assert-valid! ::law/configuration
                         {:model target :compact-model compact :dimensions width :base-url endpoint}))))

(defn- ^:async generate!
  [configuration failures inputs]
  (when-let [failed (first @failures)] (throw failed))
  (try
    (let [request (law/assert-valid! ::law/request
                                     (shape/request configuration (xjson/to-cljs inputs)))
          response (await (fetch/json! fetch/default-client
                                       {:url (:base-url configuration) :method "POST"
                                        :redirect "error" :timeout-ms 30000 :json request}))]
      (when-not (:ok response)
        (throw (ex-info "Direct Ollama embedding request failed"
                        {:status 503 :code "knoxx_embedding_http_failed"
                         :upstream-status (:status response)})))
      (clj->js (shape/ordered-vectors (law/assert-response! request (:body response)))))
    (catch :default error
      (swap! failures conj error)
      (throw error))))

(defn- selected-model!
  [failures select]
  (try (law/assert-model! (select))
       (catch :default error (swap! failures conj error) (throw error))))

(defn runtime
  "Use SDK-owned scope resolution with one checked, uncached generation function.
  The native factory providers and parallel pools are never invoked."
  [^js sdk-runtime configuration]
  (law/assert-valid! ::law/configuration configuration)
  (law/assert-valid! ::law/sdk-capabilities
                    {:hot-model-resolver? (fn? (some-> sdk-runtime (.-hot) (.-getModel)))
                     :compact-model-resolver? (fn? (some-> sdk-runtime (.-compact) (.-getModel)))})
  (let [failures (atom [])
        provider #js {:generate (^:async fn [inputs] (await (generate! configuration failures inputs)))}
        by-model (fn [model] (selected-model! failures #(identity model)) provider)
        hot-model (fn [scope] (selected-model! failures #(.getModel (.-hot sdk-runtime) scope)))
        compact-model (fn [] (selected-model! failures #(.getModel (.-compact sdk-runtime))))
        pool (fn [& _]
               (let [error (ex-info "No checked parallel embedding pool is qualified"
                                    {:status 503 :code "knoxx_embedding_parallel_pool_unavailable"})]
                 (swap! failures conj error) (throw error)))
        tier (fn [select]
               #js {:getModel select
                    :getEmbeddingFunction (fn [& scope] (apply select scope) provider)
                    :getBackgroundEmbeddingFunction (fn [& scope] (apply select scope) provider)
                    :getEmbeddingFunctionForModel by-model
                    :getBackgroundEmbeddingFunctionForModel by-model
                    :getParallelPool pool :getParallelPoolForModel pool})]
    {:handle #js {:hot (tier hot-model) :compact (tier compact-model)} :failures failures}))

(defn retain-failure!
  "Retain the same boundary refusal before an SDK catch can hide it."
  [{:keys [failures]} error]
  (swap! failures conj error)
  error)

(defn assert-complete!
  "A swallowed SDK generation or recorded-row error refuses this invocation's completion.
  Successful independent batches and SDK source writes are not rolled back."
  [{:keys [failures]} progress]
  (when-let [error (first @failures)]
    (throw (ex-info "Checked embedding invocation failed; partial SDK progress retained"
                    {:status 503 :code "knoxx_embedding_invocation_failed"
                     :failure-count (count @failures) :cause-code (:code (ex-data error))
                     :partial-progress progress} error)))
  progress)
