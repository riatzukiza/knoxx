(ns knoxx.backend.domain.models.config
  (:require [clojure.string :as str]))

(def thinking-levels
  #{"off" "minimal" "low" "medium" "high" "xhigh"})

(def ^:private input-kinds
  #{"text" "image" "audio" "video" "document"})

(defn normalize-thinking-level
  [value]
  (let [normalized (some-> value str str/trim str/lower-case not-empty)]
    (when (contains? thinking-levels normalized)
      normalized)))

(defn normalize-input-kind
  [value]
  (let [normalized (cond
                     (keyword? value) (some-> value name str/trim str/lower-case not-empty)
                     :else (some-> value str str/trim str/lower-case not-empty))]
    (when (contains? input-kinds normalized)
      normalized)))

(defn- normalize-boolean
  [value]
  (cond
    (true? value) true
    (false? value) false
    :else nil))

(defn- normalize-string-seq
  [values]
  (->> (or values [])
       (map (fn [value]
              (cond
                (keyword? value) (some-> value name str/trim not-empty)
                :else (some-> value str str/trim not-empty))))
       (remove nil?)
       distinct
       vec))

(defn- normalize-thinking-level-seq
  [values]
  (->> (or values [])
       (map normalize-thinking-level)
       (remove nil?)
       distinct
       vec))

(defn- normalize-input-kind-seq
  [values]
  (->> (or values [])
       (map normalize-input-kind)
       (remove nil?)
       distinct
       vec))

(defn normalize-provider-id
  [value]
  (cond
    (keyword? value) (some-> value name str/trim not-empty)
    :else (some-> value str str/trim not-empty)))

(defn normalize-model-api
  [value]
  (let [normalized (some-> value name str/trim str/lower-case not-empty)]
    (case normalized
      "openai-responses" "openai-responses"
      "openai/completions" "openai-completions"
      "openai-completions" "openai-completions"
      nil)))

(defn normalize-model-family-contract
  [contract]
  (when-let [family-id (some-> (:model-family/id contract) str str/trim not-empty)]
    {:id family-id
     :provider (normalize-provider-id (:model-family/provider contract))
     :api (normalize-model-api (:model-family/api contract))
     :compat (when (map? (:model-family/compat contract)) (:model-family/compat contract))
     :prefixes (normalize-string-seq (:model-family/prefixes contract))
     :allowlisted (normalize-boolean (:model-family/allowlisted contract))
     :reasoning (normalize-boolean (:model-family/reasoning contract))
     :default-thinking (normalize-thinking-level (:model-family/default-thinking contract))
     :thinking-levels (normalize-thinking-level-seq (:model-family/thinking-levels contract))
     :context-window (when (number? (:model-family/context-window contract)) (:model-family/context-window contract))
     :max-tokens (when (number? (:model-family/max-tokens contract)) (:model-family/max-tokens contract))
     :input (normalize-input-kind-seq (:model-family/input contract))}))

(defn normalize-model-contract
  [contract]
  (when-let [model-id (some-> (:model/id contract) str str/trim not-empty)]
    {:id model-id
     :family-id (some-> (:model-family/id contract) str str/trim not-empty)
     :provider (normalize-provider-id (:model/provider contract))
     :api (normalize-model-api (:model/api contract))
     :compat (when (map? (:model/compat contract)) (:model/compat contract))
     :default (normalize-boolean (:model/default contract))
     :allowlisted (normalize-boolean (:model/allowlisted contract))
     :reasoning (normalize-boolean (:model/reasoning contract))
     :default-thinking (normalize-thinking-level (:model/default-thinking contract))
     :thinking-levels (normalize-thinking-level-seq (:model/thinking-levels contract))
     :context-window (when (number? (:model/context-window contract)) (:model/context-window contract))
     :max-tokens (when (number? (:model/max-tokens contract)) (:model/max-tokens contract))
     :input (normalize-input-kind-seq (:model/input contract))
     :label (some-> (:model/label contract) str str/trim not-empty)}))

(defn provider-openai-base-url
  [base-url]
  (let [base (or base-url "")]
    (cond
      (str/blank? base) nil
      (str/ends-with? base "/v1") base
      (str/ends-with? base "/") (str base "v1")
      :else (str base "/v1"))))

(def ^:private proxx-affinity-compat
  {:sendSessionAffinityHeaders true
   :supportsLongCacheRetention true})

(defn- configured-provider-settings
  [configured-base-urls configured-auth-tokens configured-auth-headers provider-id]
  (let [base-url (provider-openai-base-url (get configured-base-urls provider-id))
        configured-api-key (get configured-auth-tokens provider-id)
        api-key (or configured-api-key
                    (when (= "ollama" provider-id)
                      "ollama"))
        auth-header-raw (some-> (get configured-auth-headers provider-id) str str/trim str/lower-case)
        auth-header? (if (some? auth-header-raw)
                       (not (#{"false" "0" "no" "off"} auth-header-raw))
                       (or (not= "ollama" provider-id)
                           (some? configured-api-key)))]
    (when base-url
      (cond-> {:baseUrl base-url
               :authHeader auth-header?}
        (some? api-key)
        (assoc :apiKey api-key)))))

(defn- configured-provider-map
  [config]
  (let [configured-base-urls (or (:provider-base-urls config) {})
        configured-auth-tokens (or (:provider-auth-tokens config) {})
        configured-auth-headers (or (:provider-auth-headers config) {})
        configured-provider-ids (->> (concat (keys configured-base-urls)
                                             (keys configured-auth-tokens)
                                             (keys configured-auth-headers))
                                     (map normalize-provider-id)
                                     (remove nil?)
                                     distinct)]
    (reduce (fn [acc provider-id]
              (if-let [settings (configured-provider-settings configured-base-urls
                                                              configured-auth-tokens
                                                              configured-auth-headers
                                                              provider-id)]
                (assoc acc provider-id settings)
                acc))
            {}
            configured-provider-ids)))

(defn provider-settings-map
  [config]
  (let [configured-providers (configured-provider-map config)]
    (merge-with merge
     (cond->
      {"proxx" {:baseUrl (provider-openai-base-url (:proxx-base-url config))
                 :apiKey "PROXX_AUTH_TOKEN"
                 :authHeader true
                 :compat proxx-affinity-compat}}
       (not (str/blank? (:ollama-base-url config)))
       (assoc "ollama" {:baseUrl (provider-openai-base-url (:ollama-base-url config))
                         ;; Eta-mu requires an apiKey value for custom providers.
                         ;; Its OpenAI client sends this harmless dummy bearer;
                         ;; local Ollama ignores authentication headers.
                         :apiKey "ollama"
                         :authHeader false}))
     configured-providers)))
