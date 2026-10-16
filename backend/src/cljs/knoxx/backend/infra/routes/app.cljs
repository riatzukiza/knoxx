(ns knoxx.backend.infra.routes.app
  (:require [clojure.string :as str]
            [knoxx.backend.extern.fastify :as fastify]
            [knoxx.backend.extern.fastify.app.activity :as activity]
            [knoxx.backend.extern.fastify.app.chat :as chat]
            [knoxx.backend.extern.fastify.app.core :as core]
            [knoxx.backend.extern.fastify.app.data :as data]
            [knoxx.backend.extern.fastify.app.ingestion :as ingestion]
            [knoxx.backend.extern.fastify.app.sessions :as sessions]
            [knoxx.backend.infra.auth.authz :refer [ctx-org-id]]
            [knoxx.backend.infra.http :refer [http-error]]
            [knoxx.backend.infra.routes.app.registration :as registration]))

(defn- canonical-openplanner-proxy-path
  [path]
  (let [decoded (loop [value (str path)
                       attempts 0]
                  (if (or (not (str/includes? value "%"))
                          (= attempts 16))
                    value
                    (let [next-value (try
                                       (fastify/decode-uri-component value)
                                       (catch :default _ value))]
                      (if (= value next-value)
                        value
                        (recur next-value (inc attempts))))))
        segments (->> (str/split (-> decoded
                                      str/lower-case
                                      (str/replace "\\" "/"))
                                  #"/+")
                      (reduce (fn [result segment]
                                (case segment
                                  "" result
                                  "." result
                                  ".." (if (seq result) (pop result) result)
                                  (conj result segment)))
                              []))]
    (if (= "v1" (first segments))
      (vec (rest segments))
      segments)))

(defn authorize-openplanner-proxy-post!
  "Refuse authenticated vector search until OpenPlanner can enforce org scope.

  The current SDK only accepts a fixed non-tenant `where` allowlist and drops
  `org_id` from stored vector documents. Response-side filtering would disclose
  rows to this process and truncate top-k results, so the service-credential
  compatibility proxy fails closed before forwarding. Policy-disabled local
  mode remains the existing explicitly trusted workstation boundary."
  [ctx path body]
  (when (and ctx
             (= ["search" "vector"]
                (canonical-openplanner-proxy-path path)))
    (throw (http-error 403
                       "openplanner_vector_search_scope_unavailable"
                       "Vector search is unavailable until tenant filtering is supported")))
  body)

(def data-mongo-read-permission
  "org.datalakes.read")

(defn authorize-mongo-query!
  "Authorize raw data exploration and tenant-scope publication source events.

   Policy-disabled local mode is an explicitly trusted workstation boundary.
   Once an authenticated context exists, however, even a caller-supplied
   `extra.org_id` is replaced with the server-derived tenant before the query
   reaches OpenPlanner. Other collections retain their established query shape;
   this predicate closes the source-event disclosure introduced by document
   admission without guessing at unrelated collection schemas."
  [ctx ensure-permission-fn body]
  (if-not ctx
    body
    (do
      (ensure-permission-fn ctx data-mongo-read-permission)
      (if-not (= "events" (:collection body))
        body
        (let [org-id (some-> (ctx-org-id ctx) str str/trim not-empty)]
          (when-not org-id
            (throw (http-error 403 "org_scope_denied"
                               "Event queries require an authenticated organization")))
          (assoc body :filter
                 (assoc (or (:filter body) {}) :extra.org_id org-id)))))))

(def deps
  (registration/dependencies))

(def ^{:arglists '([runtime config body log-label]) :async true}
  send-agent-turn-best-effort! chat/send-agent-turn-best-effort!)
(def ^{:arglists '([runtime config reply agent-ctx policy-model body accepted-response]) :async true}
  queue-chat-start! chat/queue-chat-start!)
(def ^{:arglists '([runtime config reply agent-ctx policy-model body accepted-response log-label]) :async true}
  queue-direct-start! chat/queue-direct-start!)
(def ^{:arglists '([limit include-all?]) :async true}
  live-active-agent-summaries! activity/live-active-agent-summaries!)
(def ^{:arglists '([run-id]) :async true}
  latest-run-event! activity/latest-run-event!)
(def ^{:arglists '([config request reply method path]) :async true}
  send-knoxx-proxy! ingestion/send-knoxx-proxy!)
(def ^{:arglists '([reply target-url opts error-handler]) :async true}
  send-fetch-json! ingestion/send-fetch-json!)
(def ^{:arglists '([reply target-url opts prefix]) :async true}
  send-fetch-json-detail! ingestion/send-fetch-json-detail!)
(def ^{:arglists '([config reply method path body]) :async true}
  send-openplanner-v1-json! data/send-openplanner-v1-json!)
(def ^{:arglists '([reply request-promise]) :async true}
  send-json-promise! data/send-json-promise!)
(def ^{:arglists '([config reply]) :async true}
  send-mongo-collections! data/send-mongo-collections!)
(def ^{:arglists '([reply target-url]) :async true}
  send-data-browse! data/send-data-browse!)
(def ^{:arglists '([reply absolute-path content safe-path]) :async true}
  write-ingestion-file! ingestion/write-ingestion-file!)
(def ^{:arglists '([url headers]) :async true}
  service-health-check! core/service-health-check!)
(def ^{:arglists '([config]) :async true}
  openplanner-health-check! core/openplanner-health-check!)
(def ^{:arglists '([config]) :async true}
  proxx-health-check! core/proxx-health-check!)
(def ^{:arglists '([config]) :async true}
  ollama-health-check! core/ollama-health-check!)
(def ^{:arglists '([config reply]) :async true}
  send-data-health! core/send-data-health!)
(def ^{:arglists '([config reply]) :async true}
  send-knoxx-health! core/send-knoxx-health!)
(def ^{:arglists '([conversation-id session session-id queue-turn! can-send-result reply latest-event]) :async true}
  detect-zombies sessions/detect-zombies)
(def ^{:arglists '([runtime config reply ctx request]) :async true}
  handle-chat-start chat/handle-chat-start)
(def ^{:arglists '([runtime config reply ctx request]) :async true}
  handle-direct-start chat/handle-direct-start)

(def SESSION_RECOVERY_STALE_MS sessions/SESSION_RECOVERY_STALE_MS)

(def ^{:arglists '([app runtime config deps])}
  health! core/health!)
(def ^{:arglists '([app runtime config deps])}
  dev-hmr! core/dev-hmr!)
(def ^{:arglists '([app runtime config deps])}
  config! core/config!)
(def ^{:arglists '([app runtime config deps])}
  api-knoxx-agents-catalog! core/api-knoxx-agents-catalog!)
(def ^{:arglists '([app runtime config deps])}
  api-auth-context! core/api-auth-context!)
(def ^{:arglists '([app runtime config deps])}
  api-knoxx-proxy-get! ingestion/api-knoxx-proxy-get!)
(def ^{:arglists '([app runtime config deps])}
  api-knoxx-proxy-post! ingestion/api-knoxx-proxy-post!)
(def ^{:arglists '([app runtime config deps])}
  api-knoxx-proxy-put! ingestion/api-knoxx-proxy-put!)
(def ^{:arglists '([app runtime config deps])}
  api-knoxx-proxy-patch! ingestion/api-knoxx-proxy-patch!)
(def ^{:arglists '([app runtime config deps])}
  api-knoxx-proxy-delete! ingestion/api-knoxx-proxy-delete!)
(def ^{:arglists '([app runtime config deps])}
  api-ingestion-browse! ingestion/api-ingestion-browse!)
(def ^{:arglists '([app runtime config deps])}
  api-ingestion-file! ingestion/api-ingestion-file!)
(def ^{:arglists '([app runtime config deps])}
  api-ingestion-file-put! ingestion/api-ingestion-file-put!)
(def ^{:arglists '([app runtime config deps])}
  api-ingestion-sources! ingestion/api-ingestion-sources!)
(def ^{:arglists '([app runtime config deps])}
  api-ingestion-jobs-get! ingestion/api-ingestion-jobs-get!)
(def ^{:arglists '([app runtime config deps])}
  api-ingestion-jobs-post! ingestion/api-ingestion-jobs-post!)
(def ^{:arglists '([app runtime config deps])}
  api-ingestion-proxy-get! ingestion/api-ingestion-proxy-get!)
(def ^{:arglists '([app runtime config deps])}
  api-ingestion-proxy-post! ingestion/api-ingestion-proxy-post!)
(def ^{:arglists '([app runtime config deps])}
  api-ingestion-proxy-delete! ingestion/api-ingestion-proxy-delete!)
(def ^{:arglists '([app runtime config deps])}
  api-data-op-get! data/api-data-op-get!)
(defn api-data-op-post! [app runtime config deps]
  (data/api-data-op-post! app runtime config (assoc deps :authorize-openplanner-proxy-post! authorize-openplanner-proxy-post!)))

(def ^{:arglists '([app runtime config deps])}
  api-data-op-delete! data/api-data-op-delete!)
(def ^{:arglists '([app runtime config deps])}
  api-data-op-patch! data/api-data-op-patch!)
(def ^{:arglists '([app runtime config deps])}
  api-data-health! data/api-data-health!)
(def ^{:arglists '([app runtime config deps])}
  api-data-mongo-collections! data/api-data-mongo-collections!)
(def ^{:arglists '([app runtime config deps])}
  api-data-mongo-list! data/api-data-mongo-list!)
(defn api-data-mongo-query! [app runtime config deps]
  (data/api-data-mongo-query! app runtime config (assoc deps :authorize-mongo-query! authorize-mongo-query!)))

(def ^{:arglists '([app runtime config deps])}
  api-data-pg-tables! data/api-data-pg-tables!)
(def ^{:arglists '([app runtime config deps])}
  api-data-jobs-build-semantic-edges! data/api-data-jobs-build-semantic-edges!)
(def ^{:arglists '([app runtime config deps])}
  api-data-pg-query! data/api-data-pg-query!)
(def ^{:arglists '([app runtime config deps])}
  api-data-browse! data/api-data-browse!)
(def ^{:arglists '([app runtime config deps])}
  api-data-file! data/api-data-file!)
(def ^{:arglists '([app runtime config deps])}
  api-data-graphql! data/api-data-graphql!)
(def ^{:arglists '([app runtime config deps])}
  api-data-graph-status! data/api-data-graph-status!)
(def ^{:arglists '([app runtime config deps])}
  api-data-graph-view-url! data/api-data-graph-view-url!)
(def ^{:arglists '([app runtime config deps])}
  api-knoxx-health! core/api-knoxx-health!)
(def ^{:arglists '([app runtime config deps])}
  api-knoxx-chat! chat/api-knoxx-chat!)
(def ^{:arglists '([app runtime config deps])}
  api-knoxx-chat-start! chat/api-knoxx-chat-start!)
(def ^{:arglists '([app runtime config deps])}
  api-knoxx-direct! chat/api-knoxx-direct!)
(def ^{:arglists '([app runtime config deps])}
  api-knoxx-direct-start! chat/api-knoxx-direct-start!)
(def ^{:arglists '([app runtime config deps])}
  api-knoxx-steer! sessions/api-knoxx-steer!)
(def ^{:arglists '([app runtime config deps])}
  api-knoxx-follow-up! sessions/api-knoxx-follow-up!)
(def ^{:arglists '([app runtime config deps])}
  api-knoxx-abort! sessions/api-knoxx-abort!)
(def ^{:arglists '([app runtime config deps])}
  api-knoxx-session-undo! sessions/api-knoxx-session-undo!)
(def ^{:arglists '([app runtime config deps])}
  api-knoxx-agents-active! activity/api-knoxx-agents-active!)
(def ^{:arglists '([app runtime config deps])}
  api-admin-agents-active! activity/api-admin-agents-active!)
(def ^{:arglists '([app runtime config deps])}
  api-admin-agents-abort! sessions/api-admin-agents-abort!)
(def ^{:arglists '([app runtime config deps])}
  api-knoxx-session-status! sessions/api-knoxx-session-status!)
(def ^{:arglists '([app runtime config deps])}
  api-knoxx-run-events! activity/api-knoxx-run-events!)
(def ^{:arglists '([app runtime config deps])}
  api-knoxx-run-get! activity/api-knoxx-run-get!)
(def ^{:arglists '([app runtime config deps])}
  api-shibboleth-handoff! ingestion/api-shibboleth-handoff!)

(defn register-routes!
  [runtime app config lounge-messages*]
  (registration/register-routes! runtime app config lounge-messages*
    (assoc deps :authorize-openplanner-proxy-post! authorize-openplanner-proxy-post!
                :authorize-mongo-query! authorize-mongo-query!)))
