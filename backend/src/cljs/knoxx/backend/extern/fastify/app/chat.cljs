(ns knoxx.backend.extern.fastify.app.chat
  (:require-macros [knoxx.backend.macros :refer [defroute]])
  (:require [knoxx.backend.domain.app-agent-requests :as requests]
            [knoxx.backend.domain.contracts.sources :as contract-sources]
            [knoxx.backend.extern.fastify.app.activity :as activity]
            [knoxx.backend.extern.fastify.app.sessions :as sessions]
            [knoxx.backend.infra.agent.hydration :refer [settings-state*]]
            [knoxx.backend.infra.agent.policy :refer [validate-chat-policy!]]
            [knoxx.backend.infra.agent.runner :as agent-runner]
            [knoxx.backend.infra.agent.service :refer [active-agent-session send-agent-turn!]]
            [knoxx.backend.infra.agent.turn :refer [ensure-session-id]]
            [knoxx.backend.infra.auth.authz :refer [system-admin?]]
            [knoxx.backend.infra.http :refer [json-response! request-body error-response!]]
            [knoxx.backend.infra.stores.mongo-session-store :as session-store]
            [knoxx.backend.infra.tooling :refer [effective-agent-contract default-agent-contract-id default-actor-id]]
            [knoxx.backend.shape.agent :refer [streaming?]]
            [knoxx.backend.shape.app-shapes :refer [normalize-chat-body]]
            ["node:crypto" :as crypto]))

(defn- effective-auth-context [ctx parsed]
  (requests/effective-auth-context ctx parsed (system-admin? ctx)))

(defn ^:async send-agent-turn-best-effort!
  [runtime config body log-label]
  (try
    (await (send-agent-turn! runtime config body))
    (catch :default err
      (.error js/console log-label err)
      (agent-runner/log-and-record-async-spawn-error! body err))))

(defn ^:async queue-chat-start!
  [runtime config reply agent-ctx policy-model body accepted-response]
  (try
    (await (validate-chat-policy! agent-ctx policy-model))
    (send-agent-turn-best-effort! runtime config body "Async agent chat failed")
    (json-response! reply 202 accepted-response)
    (catch :default err
      (error-response! reply err 429))))

(defn ^:async queue-direct-start!
  [runtime config reply agent-ctx policy-model body accepted-response log-label]
  (try
    (await (validate-chat-policy! agent-ctx policy-model))
    (send-agent-turn-best-effort! runtime config body log-label)
    (json-response! reply 202 accepted-response)
    (catch :default err
      (error-response! reply err 429))))


(defn- merged-agent-spec
  [config parsed]
  (let [requested (requests/compact-agent-spec-overrides (or (:agent-spec parsed) {}))
        requested-actor-id (or (get requested :actor-id)
                               (default-actor-id config))
        requested-contract-id (or (get requested :contract-id)
                                  (default-agent-contract-id config requested-actor-id))
        resolved (effective-agent-contract config requested-contract-id requested-actor-id)
        resolved-id (:id resolved)
        merged (merge (select-keys resolved [:role :model :system-prompt :task-prompt :thinking-level :tool-policies :contract-actor-ids :memory-hydration :context-policy :sources])
                      requested)
        runtime-sources (contract-sources/compose-source-refs config
                                                              (:sources resolved)
                                                              (:sources requested))]
    (cond-> (assoc merged :sources runtime-sources)
      requested-actor-id (assoc :actor-id requested-actor-id)
      (seq (:contract-actor-ids resolved)) (assoc :contract-actors (:contract-actor-ids resolved))
      resolved-id (assoc :contract-id resolved-id))))

(defn- start-identities [parsed]
  {:session-id (ensure-session-id (:session-id parsed))
   :conversation-id (or (:conversation-id parsed) (.randomUUID crypto))
   :run-id (or (:run-id parsed) (.randomUUID crypto))})

(defn- prepare-start [config ctx request mode aliases?]
  (let [parsed0 (normalize-chat-body (request-body request))
        parsed (assoc parsed0 :agent-spec (merged-agent-spec config parsed0))
        agent-ctx (effective-auth-context ctx parsed)
        policy-model (or (:model parsed) (get-in parsed [:agent-spec :model]) (:llmModel @settings-state*))
        identities (start-identities parsed)
        body (assoc (merge parsed identities) :mode mode :auth-context agent-ctx)]
    {:agent-ctx agent-ctx :policy-model policy-model :body body
     :provided-session-id (:session-id parsed)
     :accepted-response (requests/queued-response body policy-model aliases?)}))

(defn- ^:async queue-existing-start!
  [reply body queue-turn! label conflict-response]
  (let [{:keys [session-id conversation-id]} body]
    (try
      (let [session (await (session-store/get-session session-id))
            can-send-result (session-store/session-can-send? session)]
        (if (:can-send can-send-result)
          (let [agent-session (active-agent-session conversation-id)
                actively-streaming? (and agent-session (streaming? agent-session))]
            (if actively-streaming?
              (json-response! reply 409 conflict-response)
              (await (queue-turn! label))))
          (let [latest-event (when (= "running" (:status session))
                               (await (activity/latest-run-event! (:run_id session))))]
            (await (sessions/detect-zombies conversation-id session session-id queue-turn!
                                            can-send-result reply latest-event)))))
      (catch :default err
        (.error js/console "Session status check failed" err)
        (await (queue-turn! label))))))

(defn ^:async handle-chat-start [runtime config reply ctx request]
  (let [{:keys [agent-ctx policy-model body provided-session-id accepted-response]}
        (prepare-start config ctx request "rag" true)
        queue-turn! (fn [_log-label]
                      (queue-chat-start! runtime config reply agent-ctx policy-model body accepted-response))]
    (if-not provided-session-id
      (await (queue-turn! "Async agent chat failed"))
      (await (queue-existing-start!
              reply body queue-turn! "Async agent chat failed"
              {:ok false :error "Agent is already processing. Specify streamingBehavior steer or followUp to queue the message."
               :code "agent-already-processing" :has-active-stream true :can-send false})))))

(defn ^:async handle-direct-start [runtime config reply ctx request]
  (let [{:keys [agent-ctx policy-model body provided-session-id accepted-response]}
        (prepare-start config ctx request "direct" false)
        queue-turn! (fn [log-label]
                      (queue-direct-start! runtime config reply agent-ctx policy-model body accepted-response log-label))]
    (if-not provided-session-id
      (await (queue-turn! "Async direct agent chat failed"))
      (await (queue-existing-start!
              reply body queue-turn! "Async direct agent chat failed"
              {:ok false :error "Agent is already processing. Specify streamingBehavior ('steer' or 'followUp') to queue the message."
               :code "agent_already_processing" :has_active_stream true :can_send false})))))

(defn- chat-turn-ok [reply resp]
  (json-response! reply 200 resp))

(defn- chat-turn-err [reply err]
  (error-response! reply err 502))

(defroute api-knoxx-chat! []
  "POST" "/api/knoxx/chat"
  (when ctx (ensure-permission! ctx "agent.chat.use"))
  (let [parsed0 (normalize-chat-body (request-body request))
        parsed (assoc parsed0 :agent-spec (merged-agent-spec config parsed0))
        agent-ctx (effective-auth-context ctx parsed)
        body (assoc parsed
                    :mode "rag"
                    :auth-context agent-ctx)]
    (try
      (chat-turn-ok reply (await (send-agent-turn! runtime config body)))
      (catch :default err
        (chat-turn-err reply err)))))

(defroute api-knoxx-chat-start! []
  "POST" "/api/knoxx/chat/start"
  (when ctx
    (ensure-permission! ctx "agent.chat.use"))
  (handle-chat-start runtime config reply ctx request))

(defroute api-knoxx-direct! []
  "POST" "/api/knoxx/direct"
  (when ctx (ensure-permission! ctx "agent.chat.use"))
  (let [parsed0 (normalize-chat-body (request-body request))
        parsed (assoc parsed0 :agent-spec (merged-agent-spec config parsed0))
        agent-ctx (effective-auth-context ctx parsed)
        body (assoc parsed
                    :mode "direct"
                    :auth-context agent-ctx)]
    (try
      (chat-turn-ok reply (await (send-agent-turn! runtime config body)))
      (catch :default err
        (chat-turn-err reply err)))))

(defroute api-knoxx-direct-start! []
  "POST" "/api/knoxx/direct/start"
  (when ctx (ensure-permission! ctx "agent.chat.use"))
  (handle-direct-start runtime config reply ctx request))
