(ns knoxx.backend.infra.routes.app.registration
  (:require [knoxx.backend.domain.realtime :refer [broadcast-ws!]]
            [knoxx.backend.domain.text :refer [count-occurrences replace-first clip-text]]
            [knoxx.backend.domain.time :refer [now-iso]]
            [knoxx.backend.extern.fastify.app.activity :as activity]
            [knoxx.backend.extern.fastify.app.chat :as chat]
            [knoxx.backend.extern.fastify.app.core :as core]
            [knoxx.backend.extern.fastify.app.data :as data]
            [knoxx.backend.extern.fastify.app.ingestion :as ingestion]
            [knoxx.backend.extern.fastify.app.sessions :as sessions]
            [knoxx.backend.extern.fastify.cms-documents :as cms-document-routes]
            [knoxx.backend.extern.fastify.cms-publication :as cms-publication-routes]
            [knoxx.backend.extern.fastify.document-admission :as document-admission-routes]
            [knoxx.backend.extern.fastify.publications :as publication-routes]
            [knoxx.backend.extern.fastify.translation-config :as translation-config-routes]
            [knoxx.backend.extern.fastify.translation-dispatch :as translation-dispatch-routes]
            [knoxx.backend.extern.fastify.translation-review :as translation-review-routes]
            [knoxx.backend.infra.agent.hydration :refer [ensure-settings!]]
            [knoxx.backend.infra.agent.runtime :refer [resolve-workspace-path]]
            [knoxx.backend.infra.auth.authz :refer [policy-db policy-db-promise with-request-context! ensure-permission! ensure-tool! ensure-any-permission! ensure-org-scope! ctx-permitted? system-admin? ctx-user-id ctx-user-email ctx-org-id]]
            [knoxx.backend.infra.clients.openplanner :as openplanner-client]
            [knoxx.backend.infra.core-memory :refer [fetch-openplanner-session-rows! session-visible? session-matches-page-actor-filter? filter-authorized-memory-hits! authorized-session-ids!]]
            [knoxx.backend.infra.http :refer [json-response! bearer-headers fetch-json openai-auth-error send-fetch-response! request-query-string http-error error-response!]]
            [knoxx.backend.infra.openplanner.memory :refer [openplanner-memory-search! openplanner-graph-export!]]
            [knoxx.backend.infra.publication-admission-hook :as publication-admission-hook]
            [knoxx.backend.infra.routes.actors :as actor-routes]
            [knoxx.backend.infra.routes.admin :as admin-routes]
            [knoxx.backend.infra.routes.document-admission :as document-admission]
            [knoxx.backend.infra.routes.documents :as document-routes]
            [knoxx.backend.infra.routes.memory :as memory-routes]
            [knoxx.backend.infra.routes.models :as model-routes]
            [knoxx.backend.infra.routes.publication-reconcile :as reconcile-routes]
            [knoxx.backend.infra.routes.resources :as resource-routes]
            [knoxx.backend.infra.routes.studio :as studio-routes]
            [knoxx.backend.infra.routes.tools :as tool-routes]
            [knoxx.backend.infra.routes.translation :as translation-routes]
            [knoxx.backend.infra.routes.voice :as voice-routes]
            [knoxx.backend.infra.routes.workspace-media :as workspace-media-routes]
            [knoxx.backend.infra.stores.session-titles :refer [start-session-title-backfill! session-title-backfill* session-titles* get-cached-session-title! session-title-seed-text heuristic-session-title stored-session-title-entry cache-session-title-entry! resolve-session-title! cache-session-title! normalize-session-title]]
            [knoxx.backend.infra.stores.translation-evidence-registry :as translation-evidence-registry]
            [knoxx.backend.infra.stores.translation-split-registry :as translation-split-registry]
            [knoxx.backend.infra.tooling :refer [tool-catalog ensure-role-can-use!]]
            [knoxx.backend.infra.translation-event-writer :as translation-event-writer]
            [knoxx.backend.law.guards :as guards]
            [knoxx.backend.shape.app-shapes :refer [route!]]
            [knoxx.backend.shape.parse :refer [parse-positive-int truthy-param?]]))

(defn dependencies
  "Return the existing fixed production route helper map."
  []
  {:route! route!
   :json-response! json-response!
   :error-response! error-response!
   :ensure-permission! ensure-permission!
   :clip-text clip-text
   :with-request-context! with-request-context!
   :send-fetch-response! send-fetch-response!
   :bearer-headers bearer-headers
   :fetch-json fetch-json
   :request-query-string request-query-string
   :session-guard nil
   :optional-session-guard nil})

(defn- register-core-routes!
  [app runtime config deps]
  (core/health! app runtime config deps)
  (core/dev-hmr! app runtime config deps)
  (core/config! app runtime config deps)
  (core/api-knoxx-agents-catalog! app runtime config deps)
  (core/api-auth-context! app runtime config deps))

(defn- memory-title-ports []
  {:start-session-title-backfill! start-session-title-backfill!
   :session-title-backfill* session-title-backfill* :session-titles* session-titles*
   :get-cached-session-title! get-cached-session-title!
   :session-title-seed-text session-title-seed-text :heuristic-session-title heuristic-session-title
   :stored-session-title-entry stored-session-title-entry :cache-session-title-entry! cache-session-title-entry!
   :resolve-session-title! resolve-session-title! :cache-session-title! cache-session-title!
   :normalize-session-title normalize-session-title})

(defn- memory-view-ports [lounge-messages*]
  {:route! route! :json-response! json-response! :error-response! error-response!
   :with-request-context! with-request-context! :ensure-permission! ensure-permission!
   :parse-positive-int parse-positive-int :truthy-param? truthy-param?
   :fetch-openplanner-session-rows! fetch-openplanner-session-rows!
   :session-visible? session-visible? :session-matches-page-actor-filter? session-matches-page-actor-filter?
   :openplanner-memory-search! openplanner-memory-search! :filter-authorized-memory-hits! filter-authorized-memory-hits!
   :ctx-permitted? ctx-permitted? :system-admin? system-admin? :http-error http-error
   :now-iso now-iso :broadcast-ws! broadcast-ws! :lounge-messages* lounge-messages*
   :authorized-session-ids! authorized-session-ids!})

(defn- register-admin-and-memory-routes! [app runtime config lounge-messages*]
  (admin-routes/register-admin-routes! app runtime
    {:route! route! :json-response! json-response! :with-request-context! with-request-context!
     :ensure-permission! ensure-permission! :ensure-any-permission! ensure-any-permission!
     :ensure-org-scope! ensure-org-scope! :policy-db policy-db :policy-db-promise policy-db-promise :http-error http-error})
  (memory-routes/register-memory-routes! app runtime config
    (merge (memory-view-ports lounge-messages*) (memory-title-ports))))

(defn- register-tooling-route-groups!
  [app runtime config]
  (let [session-guard          (guards/make-session-guard runtime)
        optional-session-guard (guards/make-optional-session-guard runtime)]
    (tool-routes/register-tool-routes! app runtime config
                                       {:route! route!
                                        :json-response! json-response!
                                        :error-response! error-response!
                                        :with-request-context! with-request-context!
                                        :ensure-permission! ensure-permission!
                                        :tool-catalog tool-catalog
                                        :ensure-role-can-use! ensure-role-can-use!
                                        :resolve-workspace-path resolve-workspace-path
                                        :count-occurrences count-occurrences
                                        :replace-first replace-first
                                        :clip-text clip-text
                                        :session-guard session-guard
                                        :optional-session-guard optional-session-guard})
    (actor-routes/register-actor-routes! app runtime config
                                         {:route! route!
                                          :json-response! json-response!
                                          :error-response! error-response!
                                          :with-request-context! with-request-context!
                                          :ensure-permission! ensure-permission!
                                          :session-guard session-guard})))

(defn- ^:async repair-publication-translation-events!
  [client scope]
  (let [evidence-store (translation-evidence-registry/current)
        split-store (translation-split-registry/current)]
    (when-not evidence-store
      (throw (ex-info "translation evidence persistence is not configured"
                      {:status 503
                       :code "translation_evidence_unavailable"})))
    (when-not split-store
      (throw (ex-info "translation split persistence is not configured"
                      {:status 503
                       :code "translation_split_persistence_unavailable"})))
    (await
     (translation-event-writer/repair-completed-event-projections!
      {:evidence-store evidence-store
       :openplanner-client client
       :split-store split-store}
      (select-keys scope [:org-id :project])))))

(defn- register-internal-publication-admission! [config client]
  (let [admission-dependencies {:client client :repair-translation-events! (partial repair-publication-translation-events! client)}
        internal-admission!
        (fn [scope selection]
          (document-admission/admit-documents! config
            (assoc admission-dependencies :dispatch-document!
              (fn [document-id snapshot-deps]
                (translation-dispatch-routes/dispatch-selection-for-scope!
                  config scope {:document document-id} (assoc snapshot-deps :client client))))
            scope selection))]
    (publication-admission-hook/register! internal-admission!)
    admission-dependencies))

(defn- register-publication-surface-routes! [app runtime config]
  (let [client (openplanner-client/client config)
        helpers {:route! route! :json-response! json-response!
                 :with-request-context! with-request-context! :ensure-permission! ensure-permission!}
        admission-dependencies (register-internal-publication-admission! config client)]
    (publication-routes/register-publication-routes! app runtime config
      (select-keys helpers [:with-request-context! :ensure-permission!]))
    (cms-publication-routes/register-cms-publication-routes! app runtime config helpers)
    (cms-document-routes/register! app runtime helpers)
    (reconcile-routes/register-publication-reconcile-routes! app runtime config
      (select-keys helpers [:with-request-context! :ensure-permission!]))
    (translation-config-routes/register-translation-config-routes! app runtime config helpers)
    (document-admission-routes/register-document-admission-routes! app runtime config
      (select-keys helpers [:with-request-context! :ensure-permission!]) admission-dependencies)
    (translation-dispatch-routes/register-translation-dispatch-routes! app runtime config
      (select-keys helpers [:with-request-context! :ensure-permission!]))
    (translation-review-routes/register-translation-review-routes! app runtime config
      (select-keys helpers [:with-request-context! :ensure-permission!]))))

(defn- route-authority-ports []
  {:route! route! :json-response! json-response! :error-response! error-response!
   :with-request-context! with-request-context! :ensure-tool! ensure-tool! :ensure-permission! ensure-permission!})

(defn- document-ports []
  (merge (select-keys (route-authority-ports) [:route! :json-response! :error-response! :with-request-context! :ensure-permission!])
         {:clip-text clip-text :openplanner-graph-export! openplanner-graph-export!
          :send-fetch-response! send-fetch-response! :bearer-headers bearer-headers
          :fetch-json fetch-json :openai-auth-error openai-auth-error :request-query-string request-query-string}))

(defn- register-resource-and-media-routes! [app runtime config]
  (resource-routes/register-resource-routes! app runtime config
    (select-keys (route-authority-ports) [:route! :json-response! :error-response! :with-request-context! :ensure-permission!]))
  (register-publication-surface-routes! app runtime config)
  (model-routes/register-model-routes! app runtime config)
  (voice-routes/register-voice-routes! app runtime config
    (select-keys (route-authority-ports) [:route! :json-response! :with-request-context! :ensure-tool!]))
  (document-routes/register-document-routes! app runtime config (document-ports))
  (workspace-media-routes/register-workspace-media-routes! app runtime config
    (select-keys (route-authority-ports) [:route! :json-response! :error-response! :with-request-context! :ensure-tool!]))
  (studio-routes/register-studio-routes! app runtime config
    (merge (select-keys (route-authority-ports) [:route! :json-response! :error-response! :with-request-context! :ensure-tool! :ensure-permission!])
           {:policy-db policy-db :policy-db-promise policy-db-promise})))

(defn- register-ingestion-routes!
  [app runtime config deps]
  (ingestion/api-knoxx-proxy-get! app runtime config deps)
  (ingestion/api-knoxx-proxy-post! app runtime config deps)
  (ingestion/api-knoxx-proxy-put! app runtime config deps)
  (ingestion/api-knoxx-proxy-patch! app runtime config deps)
  (ingestion/api-knoxx-proxy-delete! app runtime config deps)
  (ingestion/api-ingestion-browse! app runtime config deps)
  (ingestion/api-ingestion-file! app runtime config deps)
  (ingestion/api-ingestion-sources! app runtime config deps)
  (ingestion/api-ingestion-jobs-get! app runtime config deps)
  (ingestion/api-ingestion-jobs-post! app runtime config deps)
  (ingestion/api-ingestion-proxy-get! app runtime config deps)
  (ingestion/api-ingestion-proxy-post! app runtime config deps)
  (ingestion/api-ingestion-proxy-delete! app runtime config deps))

(defn- register-data-routes!
  [app runtime config deps]
  (data/api-data-op-get! app runtime config deps)
  (data/api-data-op-post! app runtime config deps)
  (data/api-data-op-patch! app runtime config deps)
  (data/api-data-op-delete! app runtime config deps)
  (data/api-data-health! app runtime config deps)
  (data/api-data-mongo-collections! app runtime config deps)
  (data/api-data-mongo-list! app runtime config deps)
  (data/api-data-mongo-query! app runtime config deps)
  (data/api-data-pg-tables! app runtime config deps)
  (data/api-data-jobs-build-semantic-edges! app runtime config deps)
  (data/api-data-pg-query! app runtime config deps)
  (data/api-data-browse! app runtime config deps)
  (data/api-data-file! app runtime config deps)
  (data/api-data-graphql! app runtime config deps)
  (data/api-data-graph-status! app runtime config deps)
  (data/api-data-graph-view-url! app runtime config deps))

(defn- register-knoxx-run-routes!
  [app runtime config deps]
  (core/api-knoxx-health! app runtime config deps)
  (chat/api-knoxx-chat! app runtime config deps)
  (chat/api-knoxx-chat-start! app runtime config deps)
  (chat/api-knoxx-direct! app runtime config deps)
  (chat/api-knoxx-direct-start! app runtime config deps)
  (sessions/api-knoxx-steer! app runtime config deps)
  (sessions/api-knoxx-follow-up! app runtime config deps)
  (sessions/api-knoxx-abort! app runtime config deps)
  (sessions/api-knoxx-session-undo! app runtime config deps)
  (activity/api-knoxx-agents-active! app runtime config deps)
  (activity/api-admin-agents-active! app runtime config deps)
  (sessions/api-admin-agents-abort! app runtime config deps)
  (sessions/api-knoxx-session-status! app runtime config deps)
  (activity/api-knoxx-run-events! app runtime config deps)
  (activity/api-knoxx-run-get! app runtime config deps)
  (ingestion/api-shibboleth-handoff! app runtime config deps))

(defn- register-translation-route-group!
  [app runtime config]
  (translation-routes/register-translation-routes! app runtime config
                                                   {:json-response! json-response!
                                                    :error-response! error-response!
                                                    :with-request-context! with-request-context!
                                                    :ensure-permission! ensure-permission!
                                                    :ctx-user-id ctx-user-id
                                                    :ctx-user-email ctx-user-email
                                                    :ctx-org-id ctx-org-id}))

(defn register-routes!
  [runtime app config lounge-messages* deps]
  (ensure-settings! config)
  (register-core-routes! app runtime config deps)
  (register-admin-and-memory-routes! app runtime config lounge-messages*)
  (register-tooling-route-groups! app runtime config)
  (register-resource-and-media-routes! app runtime config)
  (register-ingestion-routes! app runtime config deps)
  (register-data-routes! app runtime config deps)
  (register-knoxx-run-routes! app runtime config deps)
  (register-translation-route-group! app runtime config))
