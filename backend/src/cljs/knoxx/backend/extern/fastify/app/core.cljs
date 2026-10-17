(ns knoxx.backend.extern.fastify.app.core
  (:require-macros [knoxx.backend.macros :refer [defroute]])
  (:require [clojure.string :as str]
            [knoxx.backend.domain.time :refer [now-iso]]
            [knoxx.backend.extern.promise :as promise]
            [knoxx.backend.infra.agent.hydration :refer [settings-state*]]
            [knoxx.backend.infra.auth.authz :refer [policy-db-enabled? primary-context-role system-admin? ctx-role-slugs]]
            [knoxx.backend.infra.clients.openplanner :as openplanner-client]
            [knoxx.backend.infra.clients.proxx :as proxx-client]
            [knoxx.backend.infra.http :refer [json-response! rewrite-localhost-url fetch-json]]
            [knoxx.backend.infra.tooling :refer [email-enabled? effective-agent-contract agent-contract-catalog actor-catalog default-agent-contract-id default-actor-id]]))

(defn- dev-hmr-response
  []
  {:ok true
   :version "v2"
   :at (now-iso)})

;; ── Common promise handlers ───────────────────────────────────────────

(defn- dependency-status
  [configured reachable result]
  {:configured configured
   :reachable (boolean reachable)
   :status_code (:status result)
   :detail (:body result)})

(defn- health-deps-ok [reply proxx-configured openplanner-configured ollama-configured
                       [proxx-res openplanner-res ollama-res]]
  (let [proxx-ok       (and proxx-configured (:ok proxx-res))
        openplanner-ok (and openplanner-configured (:ok openplanner-res))
        ollama-ok      (and ollama-configured (:ok ollama-res))
        healthy        (and proxx-ok
                            openplanner-ok
                            (or (not ollama-configured) ollama-ok))]
    (json-response!
     reply
     (if healthy 200 503)
     {:status (if healthy "ok" "unhealthy")
      :service "knoxx-backend-cljs"
      :dependencies {:proxx (dependency-status proxx-configured proxx-ok proxx-res)
                     :openplanner (dependency-status openplanner-configured openplanner-ok openplanner-res)
                     :ollama (dependency-status ollama-configured ollama-ok ollama-res)}})))

(defn- health-deps-err [reply err]
  (json-response! reply 503 {:status "unhealthy"
                             :service "knoxx-backend-cljs"
                             :error (str err)}))

(defn- knoxx-health-ok [reply config proxx-configured openplanner-configured ollama-configured
                        [proxx-res openplanner-res ollama-res]]
  (let [proxx-ok       (and proxx-configured (:ok proxx-res))
        openplanner-ok (and openplanner-configured (:ok openplanner-res))
        ollama-ok      (and ollama-configured (:ok ollama-res))
        ;; Healthy only when every configured dependency is reachable.
        healthy        (and (or (not proxx-configured) proxx-ok)
                            (or (not openplanner-configured) openplanner-ok)
                            (or (not ollama-configured) ollama-ok)
                            (or proxx-configured openplanner-configured ollama-configured))]
    (json-response!
     reply
     (if healthy 200 503)
     {:reachable healthy
      :configured (boolean (or proxx-configured openplanner-configured ollama-configured))
      :base_url (:knoxx-base-url config)
      :status_code (if healthy 200 503)
      :status (if healthy "ok" "unhealthy")
      :details {:mode "shadow-cljs-eta-mu-sdk"
                :status (if healthy "ok" "unhealthy")
                :project (:project-name config)
                :collection {:name (:collection-name config)
                             :pointsCount nil}
                :dependencies
                {:proxx (dependency-status proxx-configured proxx-ok proxx-res)
                 :openplanner (dependency-status openplanner-configured openplanner-ok openplanner-res)
                 :ollama (dependency-status ollama-configured ollama-ok ollama-res)}}})))

(defn- knoxx-health-err [reply config err]
  (json-response! reply 503 {:reachable false
                             :configured true
                             :base_url (:knoxx-base-url config)
                             :status_code 503
                             :status "unhealthy"
                             :error (str err)}))

(defn- data-health-ok [reply results]
  (json-response! reply 200
                  {:ok true
                   :services {:openplanner (nth results 0)
                              :proxx (nth results 1)
                              :ingestion (nth results 2)
                              :graph-weaver (nth results 3)
                              :shuvcrawl (nth results 4)
                              :vexx (nth results 5)
                              :eros-eris-field-app (nth results 6)
                              :myrmex (nth results 7)
                              :ollama (nth results 8)}}))

(defn- data-health-err [reply err]
  (json-response! reply 500 {:error (.-message err)}))

(defn- health-result
  [url resp]
  {:ok (:ok resp) :status (:status resp) :url url :detail (:body resp)})

(defn- health-error-result
  [url err]
  {:ok false :error (.-message err) :url url})

(defn ^:async service-health-check!
  [url headers]
  (try
    (health-result url (await (fetch-json url {:headers (or headers {}) :method "GET"})))
    (catch :default err
      (health-error-result url err))))

(defn ^:async openplanner-health-check!
  [config]
  (try
    (health-result "openplanner:/v1/health"
                   (await (openplanner-client/health! (openplanner-client/client config))))
    (catch :default err
      (health-error-result "openplanner:/v1/health" err))))

(defn ^:async proxx-health-check!
  [config]
  (let [url (str (:proxx-base-url config) "/health")]
    (try
      (health-result url (await (proxx-client/health! (proxx-client/client config))))
      (catch :default err
        (health-error-result url err)))))

(defn ^:async ollama-health-check!
  [config]
  (if (str/blank? (:ollama-base-url config))
    {:ok false :configured false :url nil :detail {:status "not configured"}}
    (service-health-check! (str (str/replace (:ollama-base-url config) #"/+$" "")
                                "/api/version")
                           nil)))

(defn- unavailable-health
  [service-name]
  (js/Promise.resolve {:ok false
                       :status 503
                       :body {:detail (str service-name " is not configured")}}))

(defn- dependency-probes
  [config]
  (let [proxx-configured (and (not (str/blank? (:proxx-base-url config)))
                              (not (str/blank? (:proxx-auth-token config))))
        ollama-configured (not (str/blank? (:ollama-base-url config)))
        openplanner (openplanner-client/client config)
        openplanner-configured (openplanner-client/enabled? openplanner)]
    {:proxx-configured proxx-configured
     :openplanner-configured openplanner-configured
     :ollama-configured ollama-configured
     :promises [(if proxx-configured
                  (proxx-client/health! (proxx-client/client config))
                  (unavailable-health "Proxx"))
                (if openplanner-configured
                  (openplanner-client/health! openplanner)
                  (unavailable-health "OpenPlanner"))
                (if ollama-configured
                  (ollama-health-check! config)
                  (unavailable-health "Ollama"))]}))

(defn ^:async send-data-health!
  [config reply]
  (let [ingestion-base (:ingestion-base-url config)]
    (try
      (data-health-ok reply
                      (await (promise/all-vec
                              [(openplanner-health-check! config)
                               (proxx-health-check! config)
                               (service-health-check! (str ingestion-base "/health") nil)
                               (service-health-check! "http://127.0.0.1:8796/api/status" nil)
                               (service-health-check! "http://127.0.0.1:3777/health" nil)
                               (service-health-check! "http://127.0.0.1:8787/v1/health" nil)
                               (service-health-check! "http://127.0.0.1:8786/health" nil)
                               (service-health-check! "http://127.0.0.1:8801/health" nil)
                               (ollama-health-check! config)])))
      (catch :default err
        (data-health-err reply err)))))

(defn ^:async send-knoxx-health!
  [config reply]
  (let [{:keys [proxx-configured openplanner-configured ollama-configured promises]}
        (dependency-probes config)]
    (try
      (knoxx-health-ok reply
                       config
                       proxx-configured
                       openplanner-configured
                       ollama-configured
                       (await (promise/all-vec promises)))
      (catch :default err
        (knoxx-health-err reply config err)))))

(defroute health! []
  "GET" "/health"
  (let [{:keys [proxx-configured openplanner-configured ollama-configured promises]}
        (dependency-probes config)]
    (try
      (health-deps-ok reply proxx-configured openplanner-configured ollama-configured
                      (await (promise/all-vec promises)))
      (catch :default err
        (health-deps-err reply err)))))

;; Dev-only endpoint used to verify shadow-cljs hot-reload against a live
;; long-running Node runtime (shadow owns the process).
(defroute dev-hmr! []
  "GET" "/api/dev/hmr" []
  (json-response! reply 200 (dev-hmr-response)))

(defn- speech-config [config request]
  {:stt_enabled (not (str/blank? (:stt-base-url config)))
   :stt_base_url (if (str/blank? (:stt-base-url config)) "" (rewrite-localhost-url (:stt-base-url config) request))
   :tts_enabled (not (str/blank? (str/trim (or (:voxx-api-key config) ""))))
   :tts_provider (if (not (str/blank? (str/trim (or (:voxx-api-key config) "")))) "voxx" "")
   :tts_default_voice_id (or (:voxx-voice-id config) "af_jessica")
   :tts_default_model_id (or (:voxx-model-id config) "kokoro")
   :tts_default_speed (or (:voxx-default-speed config) "1.15")
   :tts_default_postprocess_enabled true
   :tts_default_postprocess_profile "sports-commentator-v1"})

(defn- config-response [runtime config request]
  (merge
   {:knoxx_admin_url (rewrite-localhost-url (:knoxx-admin-url config) request)
    :knoxx_base_url (rewrite-localhost-url (:knoxx-base-url config) request)
    :knoxx_enabled true
    :proxx_enabled (and (not (str/blank? (:proxx-base-url config))) (not (str/blank? (:proxx-auth-token config))))
    :ollama_enabled (not (str/blank? (:ollama-base-url config)))
    :ollama_default_model (:ollama-default-model config)
    :proxx_default_model (:llmModel @settings-state*)
    :shibboleth_ui_url (if (str/blank? (:shibboleth-ui-url config)) "" (rewrite-localhost-url (:shibboleth-ui-url config) request))
    :shibboleth_enabled (and (not (str/blank? (:shibboleth-base-url config))) (not (str/blank? (:shibboleth-ui-url config))))
    :default_role (:knoxx-default-role config)
    :default_actor_id (default-actor-id config)
    :default_agent_contract (default-agent-contract-id config (default-actor-id config))
    :email_enabled (email-enabled? config)
    :rbac_enabled (policy-db-enabled? runtime)}
   (speech-config config request)))

(defroute config! []
  "GET" "/api/config"
  (json-response! reply 200 (config-response runtime config request)))

(defroute api-knoxx-agents-catalog! []
  "GET" "/api/knoxx/agents/catalog"
  (when ctx (ensure-permission! ctx "agent.chat.use"))
  (let [actor-id (some-> (or (aget request "query" "actorId")
                             (aget request "query" "actor"))
                         str
                         str/trim
                         not-empty)
        effective-actor-id (or actor-id (default-actor-id config))
        agents (agent-contract-catalog config effective-actor-id)
        default-agent-id (default-agent-contract-id config effective-actor-id)
        default-agent (when default-agent-id
                        (effective-agent-contract config default-agent-id effective-actor-id))
        catalog (cond-> agents
                  (and default-agent
                       (not (some #(= (:id %) (:id default-agent)) agents)))
                  (conj default-agent))]
    (json-response! reply 200 {:actor_id effective-actor-id
                               :actors (mapv (fn [actor]
                                               {:id (:id actor)
                                                :kind (:kind actor)
                                                :defaultAgent (:default-agent actor)
                                                :roleSlugs (vec (or (:role-slugs actor) []))})
                                             (actor-catalog config))
                               :agents (vec (sort-by :id catalog))
                               :default_actor_id (default-actor-id config)
                               :default_agent_contract default-agent-id})))

(defroute api-auth-context! []
  "GET" "/api/auth/context"
  (if-not (policy-db-enabled? runtime)
    (json-response! reply 503 {:detail "Knoxx policy database is not configured"})
    (json-response! reply 200 {:user (:user ctx)
                               :actor (:actor ctx)
                               :org (:org ctx)
                               :membership (:membership ctx)
                               :roles (vec (or (:roles ctx) []))
                               :roleSlugs (vec (ctx-role-slugs ctx))
                               :permissions (vec (or (:permissions ctx) []))
                               :toolPolicies (vec (or (:tool-policies ctx) (:toolPolicies ctx) []))
                               :membershipToolPolicies (vec (or (:membership-tool-policies ctx) (:membershipToolPolicies ctx) []))
                               :isSystemAdmin (system-admin? ctx)
                               :primaryRole (primary-context-role ctx)})))

(defroute api-knoxx-health! []
  "GET" "/api/knoxx/health"
  (send-knoxx-health! config reply))
