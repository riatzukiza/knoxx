(ns knoxx.backend.domain.resources.loader
  "Resource loader facade over the legacy EDN contract loader.

   EDN files describe resources. They still must satisfy boundary contracts
   (Malli schemas), but callers should talk about resource kinds and resource
   registries rather than treating every file as a contract entity."
  (:require [clojure.string :as str]
            [knoxx.backend.domain.contracts.loader :as contract-loader]))

(def resource-kind->class
  {:action "actions"
   :actor "actors"
   :agent "agents"
   :authentication "authentication"
   :capability "capabilities"
   :cms "cms"
   :document "documents"
   :garden "gardens"
   :generator "generators"
   :ingest-source "ingest_sources"
   :model "models"
   :model-family "model_families"
   :mcp-server "mcp_servers"
   :policy "policies"
   :publication "publications"
   :role "roles"
   :rule "rules"
   :runtime-feature "runtime_features"
   :schedule "schedules"
   :source "sources"
   :source-mode "source_modes"
   :store "stores"
   :sub-agent "sub_agents"
   :trigger "triggers"
   :user "users"
   :workflow "workflows"})

(def class->resource-kind
  (reduce-kv (fn [acc kind class-name]
               (assoc acc class-name kind))
             {}
             resource-kind->class))

(defn- token
  [value]
  (some-> value
          (cond-> (keyword? value) name
                  (not (keyword? value)) str)
          str/trim
          str/lower-case))

(def ^:private resource-kind-aliases
  {nil :agent
   "" :agent
   "action" :action
   "actions" :action
   "actor" :actor
   "actors" :actor
   "agent" :agent
   "agents" :agent
   "agent-spec" :agent
   "agent-specs" :agent
   "contract" :agent
   "contracts" :agent
   "authentication" :authentication
   "auth" :authentication
   "auth-method" :authentication
   "auth-methods" :authentication
   "auth_method" :authentication
   "auth_methods" :authentication
   "cap" :capability
   "caps" :capability
   "capability" :capability
   "capabilities" :capability
   "cms" :cms
   "cms-config" :cms
   "cms-configs" :cms
   "cms-block-registry" :cms
   "cms-template-registry" :cms
   "cms-templates" :cms
   "document" :document
   "documents" :document
   "garden" :garden
   "gardens" :garden
   "generator" :generator
   "generators" :generator
   "ingest-source" :ingest-source
   "ingest-sources" :ingest-source
   "ingest_source" :ingest-source
   "ingest_sources" :ingest-source
   "model" :model
   "models" :model
   "model-family" :model-family
   "model-families" :model-family
   "model_family" :model-family
   "model_families" :model-family
   "mcp-server" :mcp-server
   "mcp-servers" :mcp-server
   "mcp_server" :mcp-server
   "mcp_servers" :mcp-server
   "pipeline" :pipeline
   "pipelines" :pipeline
   "policy" :policy
   "policies" :policy
   "publication" :publication
   "publications" :publication
   "role" :role
   "roles" :role
   "rule" :rule
   "rules" :rule
   "runtime-feature" :runtime-feature
   "runtime-features" :runtime-feature
   "runtime_feature" :runtime-feature
   "runtime_features" :runtime-feature
   "runtime" :runtime-feature
   "schedule" :schedule
   "schedules" :schedule
   "source" :source
   "sources" :source
   "runtime-source" :source
   "runtime-sources" :source
   "runtime_source" :source
   "runtime_sources" :source
   "source-mode" :source-mode
   "source-modes" :source-mode
   "source_mode" :source-mode
   "source_modes" :source-mode
   "store" :store
   "stores" :store
   "sub-agent" :sub-agent
   "sub-agents" :sub-agent
   "sub_agent" :sub-agent
   "sub_agents" :sub-agent
   "trigger" :trigger
   "triggers" :trigger
   "user" :user
   "users" :user
   "human" :user
   "humans" :user
   "workflow" :workflow
   "workflows" :workflow})

(defn normalize-resource-kind
  [value]
  (or (get resource-kind-aliases (token value))
      (throw (js/Error. (str "Unknown resource kind: " value)))))

(defn resource-class
  [resource-kind]
  (get resource-kind->class (normalize-resource-kind resource-kind)))

(defn- resource-kind-from-class
  "The resource kind a contract class names, or nil when it names none.

   A record rejected before its identity could be read carries no class, and
   `(keyword (str nil))` would render that absence as the empty keyword — a
   kind that matches nothing yet is not nil, so callers could not tell
   \"unknown kind\" from \"no kind\". Absence stays absent."
  [class-name]
  (when-let [class-name (some-> class-name str str/trim not-empty)]
    (or (get class->resource-kind class-name)
        (keyword (str/replace class-name #"_" "-")))))

(defn resource-record
  [record]
  {:resource/id (:id record)
   :resource/kind (resource-kind-from-class (:contractClass record))
   :resource/class (:contractClass record)
   :resource/definition (:contract record)
   :resource/file-path (:file-path record)
   :resource/edn-text (:edn-text record)
   :id (:id record)
   :resourceClass (:contractClass record)
   ;; Transitional compatibility keys for old callers.
   :contractClass (:contractClass record)
   :contract (:contract record)
   :file-path (:file-path record)
   :edn-text (:edn-text record)
   :ok? (:ok? record)})

(defn load-all-resources-sync
  [config]
  (mapv resource-record (contract-loader/load-all-contracts-sync config)))

(defn ^:async load-all-resources!
  [config]
  (mapv resource-record (await (contract-loader/load-all-contracts! config))))

(defn ^:async load-all-resource-records!
  "Every resource record without `[kind id]` dedup, for callers that must
   detect a colliding canonical identity instead of silently inheriting
   whichever file the filesystem enumerated first."
  [config]
  (mapv resource-record (await (contract-loader/load-all-contract-records! config))))

(defn resource-record-sync
  [config resource-kind resource-id]
  (let [class-name (resource-class resource-kind)
        wanted-id (some-> resource-id contract-loader/qualified-keyword->str str/trim not-empty)]
    (some (fn [record]
            (when (and (= class-name (:resource/class record))
                       (= wanted-id (:resource/id record)))
              record))
          (load-all-resources-sync config))))

(defn resource-sync
  [config resource-kind resource-id]
  (some-> (resource-record-sync config resource-kind resource-id)
          :resource/definition))

(defn list-resource-ids-sync
  [config resource-kind]
  (let [class-name (resource-class resource-kind)]
    (->> (load-all-resources-sync config)
         (filter #(= class-name (:resource/class %)))
         (map :resource/id)
         distinct
         sort
         vec)))

(defn ^:async list-resource-ids!
  [config resource-kind]
  (let [class-name (resource-class resource-kind)
        records (await (load-all-resources! config))]
    (->> records
         (filter #(= class-name (:resource/class %)))
         (map :resource/id)
         distinct
         sort
         vec)))

(defn safe-resource-id!
  [resource-id]
  (contract-loader/safe-path-segment! resource-id "resource-id"))

(defn resource-root-paths
  [config]
  (contract-loader/contract-root-paths config))

(defn resource-file-path
  ([config resource-id]
   (resource-file-path config :agent resource-id))
  ([config resource-kind resource-id]
   (or (some-> (resource-record-sync config resource-kind resource-id)
               :resource/file-path)
       (contract-loader/contract-file-path
        config
        (resource-class resource-kind)
        resource-id))))

(defn write-edn-file!
  [file-path edn-text]
  (contract-loader/write-edn-file! file-path edn-text))

(defn read-edn-file!
  [file-path]
  (contract-loader/read-edn-file! file-path))

(defn invalidate-sync-resource-cache!
  []
  (contract-loader/invalidate-sync-contract-cache!))
