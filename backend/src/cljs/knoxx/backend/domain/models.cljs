(ns knoxx.backend.domain.models
  (:require [clojure.string :as str]
            [knoxx.backend.domain.contracts.loader :as contract-loader]
            [knoxx.backend.domain.models.config :as model-config]))

(def ^:private default-model-prefix-allowlist
  ["glm-5" "gpt-5" "qwen3" "gemma4:" "gemma3:" "deepseek" "kimi-k2" "nemotron" "cogito" "devstral" "minimax" "ministral" "mistral-large"])

(def ^:private thinking-levels
  model-config/thinking-levels)

(def ^:private eta-mu-registry-input-kinds
  #{"text" "image" "audio"})

(defn- parse-prefix-allowlist
  [raw]
  (-> (str (or raw ""))
      (str/split #",")
      (->> (map (fn [v] (some-> v str str/trim not-empty)))
           (remove nil?)
           vec)))

(defn normalize-thinking-level
  [value]
  (model-config/normalize-thinking-level value))

(defn normalize-input-kind
  [value]
  (model-config/normalize-input-kind value))

(defn- normalize-provider-id
  [value]
  (model-config/normalize-provider-id value))

(declare normalize-model-api)

(defn- normalize-model-family-contract
  [contract]
  (model-config/normalize-model-family-contract contract))

(defn- normalize-model-contract
  [contract]
  (model-config/normalize-model-contract contract))

(defn model-family-contracts
  [config]
  (->> (contract-loader/load-all-contracts-sync config)
       (filter #(= "model_families" (:contractClass %)))
       (map :contract)
       (map normalize-model-family-contract)
       (remove nil?)
       vec))

(defn model-contracts
  [config]
  (->> (contract-loader/load-all-contracts-sync config)
       (filter #(= "models" (:contractClass %)))
       (map :contract)
       (map normalize-model-contract)
       (remove nil?)
       vec))

(defn resolve-model-family
  [config model-id]
  (let [id (some-> model-id str str/trim not-empty)]
    (when id
      (->> (model-family-contracts config)
           (filter (fn [family]
                     (some (fn [prefix]
                             (str/starts-with? id prefix))
                           (:prefixes family))))
           (sort-by (fn [family]
                      (- (apply max 0 (map count (:prefixes family))))))
           first))))

(defn resolve-model-contract
  [config model-id]
  (let [id (some-> model-id str str/trim not-empty)
        exact (when id
                (some (fn [contract]
                        (when (= id (:id contract))
                          contract))
                      (model-contracts config)))
        family (or (when-let [family-id (:family-id exact)]
                     (some (fn [contract]
                             (when (= family-id (:id contract))
                               contract))
                           (model-family-contracts config)))
                   (resolve-model-family config id))]
    (merge family exact)))

(defn- fallback-prefix-allowlisted?
  [config model-id]
  (let [prefixes (let [configured (seq (:model-prefix-allowlist config))]
                   (or configured default-model-prefix-allowlist))
        id (str (or model-id ""))]
    (boolean
     (some (fn [prefix]
             (str/starts-with? id (str prefix)))
           prefixes))))

(defn allowlisted-model-id?
  "Returns true if model-id should be visible/selectable in Knoxx.

   Contract model overrides win. Fallback is env-configured prefix allowlist."
  [config model-id]
  (let [model-spec (resolve-model-contract config model-id)]
    (if (some? (:allowlisted model-spec))
      (boolean (:allowlisted model-spec))
      (fallback-prefix-allowlisted? config model-id))))

(defn model-supports-reasoning?
  [config model-id]
  (let [model-spec (resolve-model-contract config model-id)]
    (if (some? (:reasoning model-spec))
      (boolean (:reasoning model-spec))
      (let [normalized-model (some-> model-id str str/trim str/lower-case)
            prefixes (->> (str/split (or (:reasoning-model-prefixes config) "") #",")
                          (map str/trim)
                          (remove str/blank?))]
        (boolean
         (and normalized-model
              (some (fn [prefix]
                      (let [normalized-prefix (-> prefix
                                                  str/lower-case
                                                  (str/replace #"\\*$" ""))]
                        (str/starts-with? normalized-model normalized-prefix)))
                    prefixes)))))))

(defn- normalize-model-api
  [value]
  (model-config/normalize-model-api value))

(defn model-prefers-responses?
  [config model-id]
  (let [model-spec (resolve-model-contract config model-id)
        explicit-api (normalize-model-api (:api model-spec))]
    (cond
      explicit-api (= explicit-api "openai-responses")
      :else
      (let [normalized-model (some-> model-id str str/trim str/lower-case)
            prefixes (->> (str/split (or (:responses-model-prefixes config) "") #",")
                          (map str/trim)
                          (remove str/blank?))]
        (boolean
         (and normalized-model
              (some (fn [prefix]
                      (let [normalized-prefix (-> prefix
                                                  str/lower-case
                                                  (str/replace #"\\*$" ""))]
                        (str/starts-with? normalized-model normalized-prefix)))
                    prefixes)))))))

(defn effective-thinking-level
  [config model-id requested-thinking-level]
  (let [requested (normalize-thinking-level requested-thinking-level)
        model-spec (resolve-model-contract config model-id)
        allowed-levels (let [contract-levels (seq (:thinking-levels model-spec))]
                         (cond
                           contract-levels (set contract-levels)
                           (false? (:reasoning model-spec)) #{"off"}
                           :else thinking-levels))
        default-level (or (:default-thinking model-spec)
                          (:agent-thinking-level config)
                          "off")]
    (if (and requested (contains? allowed-levels requested))
      requested
      default-level)))

(defn model-thinking-format
  [model-id]
  (let [normalized-model (some-> model-id str str/trim str/lower-case)]
    (cond
      (and normalized-model (str/starts-with? normalized-model "glm-")) "zai"
      :else nil)))

(defn model-input-modes
  [config model-id]
  (let [model-spec (resolve-model-contract config model-id)
        inputs (->> (or (:input model-spec) [])
                    (map normalize-input-kind)
                    (remove nil?)
                    distinct
                    vec)]
    (if (seq inputs)
      inputs
      ["text"])))

(defn model-supports-input?
  [config model-id input-kind]
  (let [wanted (or (normalize-input-kind input-kind) "text")]
    (boolean
     (some #(= wanted %)
           (model-input-modes config model-id)))))

(defn tool-cost
  []
  {:input 0 :output 0 :cacheRead 0 :cacheWrite 0})

(defn provider-model-config
  [config model-id]
  (let [model-spec (resolve-model-contract config model-id)
        reasoning? (model-supports-reasoning? config model-id)
        api (or (normalize-model-api (:api model-spec))
                (if (model-prefers-responses? config model-id)
                  "openai-responses"
                  "openai-completions"))
        ;; Eta-mu model registry accepts text/image/audio input kinds.
        ;; Keep Knoxx's richer contract input metadata for request validation, but
        ;; down-project unsupported inputs (for example video/document) so models.json stays loadable.
        registry-inputs (->> (model-input-modes config model-id)
                             (filter eta-mu-registry-input-kinds)
                             distinct
                             vec)]
    {:id model-id
     :name (or (:label model-spec) model-id)
     :api api
     :reasoning reasoning?
     :input (if (seq registry-inputs) registry-inputs ["text"])
     :contextWindow (or (:context-window model-spec) 128000)
     :maxTokens (or (:max-tokens model-spec) 8192)
     :cost (tool-cost)}))

(defn provider-openai-base-url
  [base-url]
  (model-config/provider-openai-base-url base-url))

(defn- provider-settings-map
  [config]
  (model-config/provider-settings-map config))

(defn per-model-compat
  "Compute per-model compat so reasoning/thinking settings aren't
   incorrectly shared across models that don't support them. Contract-declared
   compat keys win over inferred defaults."
  [config model-id]
  (let [model-spec (resolve-model-contract config model-id)
        inferred (cond-> {:supportsDeveloperRole false}
                   (model-supports-reasoning? config model-id)
                   (assoc :supportsReasoningEffort true)
                   (some? (model-thinking-format model-id))
                   (assoc :thinkingFormat (model-thinking-format model-id)))]
    (merge inferred (or (:compat model-spec) {}))))

(defn- configured-model-ids
  "Model ids Knoxx may select from contracts/config even when Proxx /v1/models
   is unavailable or omits a contract-backed local model. Eta-mu requires every
   selected model to exist in models.json before a session can be created."
  [config]
  (->> (concat [(:proxx-default-model config)
                 (:ollama-default-model config)]
               (keep (fn [contract]
                       (when (not= false (:allowlisted contract))
                         (:id contract)))
                     (model-contracts config)))
       (map (fn [m] (some-> m str str/trim not-empty)))
       (remove nil?)
       distinct
       vec))

(defn- group-models-by-provider
  [config models]
  (reduce (fn [acc model-id]
            (let [provider-id (or (some-> (resolve-model-contract config model-id) :provider normalize-provider-id)
                                  "proxx")]
              (update acc provider-id (fnil conj []) model-id)))
          {}
          models))

(defn models-config
  ([config]
   (models-config config nil))
  ([config model-ids]
   (let [normalized-models (->> (concat model-ids (configured-model-ids config))
                                (map (fn [m] (some-> m str str/trim not-empty)))
                                (remove nil?)
                                distinct
                                vec)
         models (if (seq normalized-models)
                  normalized-models
                  ["glm-5"])
         base-compat {:supportsDeveloperRole false}
         provider-settings (provider-settings-map config)
         models-by-provider (group-models-by-provider config models)
         providers (reduce-kv (fn [acc provider-id provider-model-ids]
                                (if-let [settings (get provider-settings provider-id)]
                                  (let [provider-compat (merge base-compat (:compat settings))]
                                    (assoc acc (keyword provider-id)
                                           (merge (dissoc settings :compat)
                                                  {:compat provider-compat
                                                   :models (mapv (fn [model-id]
                                                                   (merge (provider-model-config config model-id)
                                                                          {:compat (per-model-compat config model-id)}))
                                                                 provider-model-ids)})))
                                  acc))
                              {}
                              models-by-provider)]
     {:providers providers})))

(defn- default-model-from-contracts
  [config]
  (or (some->> (model-contracts config)
               (some (fn [contract]
                       (when (:default contract)
                         (:id contract)))))
      (some-> (model-contracts config) first :id)))

(defn enrich-config
  "Augment an env-only config map with derived model config fields.

   Keeps knoxx.backend.infra.config strictly env-only, while ensuring legacy
   call sites continue to find these keys."
  [config]
  (merge
   {:model-prefix-allowlist
    (parse-prefix-allowlist
     (or (:model-prefix-allowlist config)
         (aget js/process.env "KNOXX_MODEL_PREFIX_ALLOWLIST")
         "glm-5,gpt-5,qwen3,gemma4:,gemma3:,deepseek,kimi-k2,nemotron,cogito,devstral,minimax,ministral,mistral-large"))

    :proxx-default-model
    (or (:proxx-default-model config)
        (default-model-from-contracts config)
        "glm-5")

    :agent-thinking-level
    (or (normalize-thinking-level
         (or (:agent-thinking-level config)
             (aget js/process.env "KNOXX_THINKING_LEVEL")
             "off"))
        "off")

    :reasoning-model-prefixes
    (or (:reasoning-model-prefixes config)
        (aget js/process.env "KNOXX_REASONING_MODEL_PREFIXES")
        "glm-")

    :responses-model-prefixes
    (or (:responses-model-prefixes config)
        (aget js/process.env "KNOXX_RESPONSES_MODEL_PREFIXES")
        "gpt-")}
   config))
