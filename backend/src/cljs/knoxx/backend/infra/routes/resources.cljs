(ns knoxx.backend.infra.routes.resources
  "Resource API facade preserving resource-native and legacy contract callers."
  (:require [knoxx.backend.infra.resources.index :as index]
            [knoxx.backend.infra.resources.validation :as validation]
            [knoxx.backend.infra.resources.writes :as writes]
            [knoxx.backend.infra.routes.resources.handlers :as handlers]
            [knoxx.backend.infra.routes.resources.registration :as registration]))

(defn validate-resource-edn
  "Preserve the resource API entrypoint and its original result semantics."
  [resource-class edn-text]
  (validation/validate-resource-edn resource-class edn-text))

(defn validate-contract-edn
  "Preserve the resource API entrypoint and its original result semantics."
  [contract-class edn-text]
  (validation/validate-contract-edn contract-class edn-text))

(defn update-resource-id-in-edn-text
  "Preserve the resource API entrypoint and its original result semantics."
  [resource-class edn-text new-id]
  (validation/update-resource-id-in-edn-text resource-class edn-text new-id))

(defn sync-resource-index!
  "Preserve the resource API entrypoint and its original result semantics."
  [config]
  (index/sync-resource-index! config))

(defn sync-contract-index!
  "Preserve the resource API entrypoint and its original result semantics."
  [config]
  (index/sync-contract-index! config))

(defn start-resource-watcher!
  "Preserve the resource API entrypoint and its original result semantics."
  [config]
  (index/start-resource-watcher! config))

(defn start-contract-watcher!
  "Preserve the resource API entrypoint and its original result semantics."
  [config]
  (index/start-contract-watcher! config))

(defn stop-resource-watcher!
  "Preserve the resource API entrypoint and its original result semantics."
  []
  (index/stop-resource-watcher!))

(defn stop-contract-watcher!
  "Preserve the resource API entrypoint and its original result semantics."
  []
  (index/stop-contract-watcher!))

(defn handle-list-resources
  "Preserve the resource API entrypoint and its original result semantics."
  [do-json config resource-kind]
  (handlers/handle-list-resources do-json config resource-kind))

(defn handle-list-contracts
  "Preserve the resource API entrypoint and its original result semantics."
  [do-json config contract-class]
  (handlers/handle-list-contracts do-json config contract-class))

(defn admission-document-id
  "Preserve the resource API entrypoint and its original result semantics."
  [resource-class resource]
  (handlers/admission-document-id resource-class resource))

(defn admission-scope
  "Preserve the resource API entrypoint and its original result semantics."
  [config ctx]
  (handlers/admission-scope config ctx))

(defn admit-saved-publication-resource!
  "Preserve the resource API entrypoint and its original result semantics."
  [config ctx resource-class resource admit!]
  (handlers/admit-saved-publication-resource! config ctx resource-class resource admit!))

(defn register-resource-routes!
  "Preserve the resource API entrypoint and its original result semantics."
  [app runtime config helpers]
  (registration/register-resource-routes! app runtime config helpers))

(defn register-contracts-routes!
  "Preserve the resource API entrypoint and its original result semantics."
  [app runtime config helpers]
  (registration/register-contracts-routes! app runtime config helpers))

(defn write-resource-and-admit!
  "Preserve both original API arities and delegated Promise identity."
  ([config file-path edn-text admission!]
   (writes/write-resource-and-admit! config file-path edn-text admission!))
  ([config file-path edn-text admission! deps]
   (writes/write-resource-and-admit! config file-path edn-text admission! deps)))

(defn handle-save-resource
  "Preserve both original API arities and delegated Promise identity."
  ([do-json config resource-kind resource-id edn-text]
   (handlers/handle-save-resource do-json config resource-kind resource-id edn-text))
  ([do-json config resource-kind resource-id edn-text ctx admit!]
   (handlers/handle-save-resource do-json config resource-kind resource-id edn-text ctx admit!)))

(defn handle-agent-put-contract-edn
  "Preserve both original API arities and delegated Promise identity."
  ([do-text config contract-class contract-id edn-text]
   (handlers/handle-agent-put-contract-edn do-text config contract-class contract-id edn-text))
  ([do-text config contract-class contract-id edn-text ctx admit!]
   (handlers/handle-agent-put-contract-edn do-text config contract-class contract-id edn-text ctx admit!)))
