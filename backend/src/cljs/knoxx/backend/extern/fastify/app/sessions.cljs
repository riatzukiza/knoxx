(ns knoxx.backend.extern.fastify.app.sessions
  (:require-macros [knoxx.backend.macros :refer [defroute]])
  (:require [clojure.string :as str]
            [knoxx.backend.domain.action.run-state :as run-state :refer [runs*]]
            [knoxx.backend.domain.app-agent-requests :as requests]
            [knoxx.backend.domain.error-observatory :as observatory]
            [knoxx.backend.domain.time :refer [now-iso]]
            [knoxx.backend.domain.voice.turn-control :as turn-control]
            [knoxx.backend.extern.fastify.app.activity :as activity]
            [knoxx.backend.infra.agent.service :refer [active-agent-session queue-agent-control! resume-recovered-session!]]
            [knoxx.backend.infra.agent.turn :refer [ensure-conversation-access!]]
            [knoxx.backend.infra.http :refer [json-response! request-body error-response!]]
            [knoxx.backend.infra.stores.mongo-session-store :as session-store]
            [knoxx.backend.shape.agent :refer [streaming? current-turn]]
            [knoxx.backend.shape.app-activity-wire :as wire]
            [knoxx.backend.shape.app-shapes :refer [normalize-control-body]]))

(defonce ^:private current-turn-observation-unavailable* (atom nil))

(defn- observe-current-turn-error!
  [phase error]
  (try
    (observatory/log-error! "app.session-current-turn" {:phase phase} error)
    (catch :default observer-error
      (let [diagnostic {:observed? false :observation-unavailable? true
                        :boundary "app.session-current-turn" :phase phase
                        :primary-error? (some? error) :observer-error? (some? observer-error)}]
        (reset! current-turn-observation-unavailable* diagnostic)
        diagnostic))))

(def SESSION_RECOVERY_STALE_MS 60000)

(defn- clear-ghost-turn!
  "If turn-control has an entry for conversation-id but the underlying Proxx
   session shows no active streaming or current turn, the entry is a ghost
   from a previous hung run. Unregister it so zombie recovery can proceed."
  [conversation-id]
  (let [agent-session (active-agent-session conversation-id)
        active-streaming? (and agent-session (streaming? agent-session))
        active-turn? (and agent-session
                          (try
                            (some? (current-turn agent-session))
                            (catch js/Error err
                               (observe-current-turn-error! :clear-ghost-turn err)
                               false)))]
    (when (and (not active-streaming?) (not active-turn?))
      (turn-control/unregister-active-turn! conversation-id))))

(defn- runtime-processing-session?
  [conversation-id]
  (let [agent-session (active-agent-session conversation-id)
        active-streaming? (and agent-session (streaming? agent-session))
        active-turn? (and agent-session
                          (try
                            (some? (current-turn agent-session))
                            (catch js/Error err
                               (observe-current-turn-error! :runtime-processing-session err)
                               false)))
        registered-turn? (some? (turn-control/active-turn conversation-id))]
    (or active-streaming? active-turn? registered-turn?)))

(defn- parse-iso-ms
  [value]
  (let [parsed (js/Date.parse (str (or value "")))]
    (when-not (js/isNaN parsed)
      parsed)))

(defn- stale-running-session?
  [session latest-event]
  (let [stamp (or (:at latest-event)
                  (:updated_at session)
                  (:created_at session))
        stamp-ms (parse-iso-ms stamp)]
    (or (nil? stamp-ms)
        (> (- (.now js/Date) stamp-ms) SESSION_RECOVERY_STALE_MS))))

(defn- undo-session-err [reply err]
  (json-response! reply 500 {:ok false :error (str err)}))

(defn ^:async detect-zombies
  [conversation-id session session-id queue-turn! can-send-result reply latest-event]
  (clear-ghost-turn! conversation-id)
  (let [stalled? (and (= "running" (:status session))
                      (not (runtime-processing-session? conversation-id))
                      (stale-running-session? session latest-event))]
    (if stalled?
      (try
        (await (session-store/complete-session! session-id
                                                conversation-id
                                                {:status "failed"
                                                 :error "Session was stale/zombie; auto-aborted before new turn."
                                                 :messages (:messages session)}))
        (await (queue-turn! "Async direct agent chat failed (recovered from zombie)"))
        (catch :default err
          (.error js/console "Failed to abort zombie session" err)
          (json-response! reply 409 {:ok false
                                     :error (str "Agent is already processing. Zombie recovery failed: " err)
                                     :code "agent_already_processing"
                                     :has_active_stream false
                                     :can_send false})))
      (json-response! reply 409 {:ok false
                                 :error (str "Agent is already processing. " (or (:reason can-send-result) ""))
                                 :code "agent_already_processing"
                                 :has_active_stream (boolean (:has_active_stream session))
                                 :can_send false}))))

(defn- requested-abort-target [request]
  (let [raw (request-body request)
        conversation-id (str (or (aget raw "conversation_id") (aget raw "conversationId") ""))
        session-id (str (or (aget raw "session_id") (aget raw "sessionId") ""))
        run-id (str (or (aget raw "run_id") (aget raw "runId") ""))
        reason (str (or (aget raw "reason") "operator_abort"))
        run (when-not (str/blank? run-id) (get @runs* run-id))]
    {:conversation-id conversation-id :session-id session-id :run-id run-id
     :reason reason :run run :resolved-session-id (or (some-> session-id not-empty) (:session_id run))}))

(defn- ^:async resolve-abort-target! [target]
  (let [{:keys [conversation-id run-id reason run resolved-session-id]} target
        session (await (if (not (str/blank? (str resolved-session-id)))
                         (session-store/get-session resolved-session-id) (js/Promise.resolve nil)))]
    {:conversation-id (str (or (some-> conversation-id not-empty) (:conversation_id run) (:conversation_id session) ""))
     :session-id (str (or resolved-session-id (:session_id session) ""))
     :run-id (str (or run-id (:run_id run) (:run_id session) ""))
     :reason reason}))

(defn- ^:async abort-target! [reply target]
  (let [{:keys [conversation-id session-id run-id reason]} target]
    (if (str/blank? conversation-id)
      (json-response! reply 400 {:ok false :error "conversation_id, session_id, or run_id is required"})
      (let [abort-result (await (turn-control/abort-active-turn! conversation-id reason))]
        (when-not (str/blank? run-id)
          (run-state/update-run! run-id
            (fn [run] (assoc run :status "aborted" :error reason :updated_at (now-iso)))))
        (await (if (not (str/blank? session-id))
                 (session-store/update-session! session-id {:status "aborted" :error reason :has_active_stream false})
                 (js/Promise.resolve nil)))
        (json-response! reply 200 (assoc abort-result :ok true :conversation_id conversation-id
                                        :session_id session-id :run_id run-id :marked_aborted true))))))

(defn- ^:async handle-admin-abort [reply _ctx request]
  (let [requested (requested-abort-target request)]
    (try
      (await (abort-target! reply (await (resolve-abort-target! requested))))
      (catch :default err (error-response! reply err 409)))))

(defn- request-session-recovery! [runtime config session]
  ((^:async fn []
     (try
       (await (resume-recovered-session! runtime config session))
       (catch :default err (js/console.error "On-demand session recovery failed" err))))))

(defn- ^:async send-existing-session-status!
  [runtime config reply session-id conversation-id session]
  (let [conversation-id' (str (or (:conversation_id session) conversation-id ""))
        runtime-active? (runtime-processing-session? conversation-id')
        can-send (session-store/session-can-send? session)
        latest-event (await (if (= "running" (:status session))
                             (activity/latest-run-event! (:run_id session)) (js/Promise.resolve nil)))
        stalled? (and (= "running" (:status session)) (not runtime-active?)
                      (stale-running-session? session latest-event))]
    (when stalled? (request-session-recovery! runtime config session))
    (json-response! reply 200
      (wire/session-status-running-response session-id session runtime-active? can-send stalled? latest-event))))

(defn- ^:async handle-session-status [runtime config reply request]
  (let [session-id (or (aget request "query" "session_id") (aget request "query" "sessionId") "")
        conversation-id (or (aget request "query" "conversation_id") (aget request "query" "conversationId") "")]
    (if (str/blank? session-id)
      (json-response! reply 400 {:error "session_id is required"})
      (try
        (if-let [session (await (session-store/get-session session-id))]
          (await (send-existing-session-status! runtime config reply session-id conversation-id session))
          (json-response! reply 200
            (wire/missing-session-status session-id conversation-id (runtime-processing-session? conversation-id))))
        (catch :default err
          (js/console.error "Session status check failed" err)
          (json-response! reply 500 {:error (str err)}))))))

(defn- steer-ok [reply resp]
  (json-response! reply 200 resp))

(defn- steer-err [reply err]
  (error-response! reply err 409))

(defroute api-knoxx-steer! []
  "POST" "/api/knoxx/steer"
  (when ctx (ensure-permission! ctx "agent.controls.steer"))
  (let [body (assoc (normalize-control-body (request-body request)) :kind "steer")
        actor-ctx (requests/auth-context-with-actor ctx (:actor-id body))]
    (ensure-conversation-access! actor-ctx (:conversation-id body))
    (try
      (steer-ok reply (await (queue-agent-control! runtime config body)))
      (catch :default err
        (steer-err reply err)))))

(defroute api-knoxx-follow-up! []
  "POST" "/api/knoxx/follow-up"
  (when ctx (ensure-permission! ctx "agent.controls.follow_up"))
  (let [body (assoc (normalize-control-body (request-body request)) :kind "follow_up")
        actor-ctx (requests/auth-context-with-actor ctx (:actor-id body))]
    (ensure-conversation-access! actor-ctx (:conversation-id body))
    (try
      (steer-ok reply (await (queue-agent-control! runtime config body)))
      (catch :default err
        (steer-err reply err)))))

(defn- abort-ok [reply resp]
  (json-response! reply (if (:ok resp) 200 409) resp))

;; Abort / interrupt the current running turn for a conversation.
;; This is stronger than steer(): it cancels the current operation immediately.
(defroute api-knoxx-abort! []
  "POST" "/api/knoxx/abort"
  (when ctx (ensure-permission! ctx "agent.controls.steer"))
  (let [raw (request-body request)
        conversation-id (or (aget raw "conversation_id") (aget raw "conversationId") "")
        actor-id (or (aget raw "actor_id") (aget raw "actorId") (aget raw "actor-id"))
        actor-ctx (requests/auth-context-with-actor ctx actor-id)
        reason (or (aget raw "reason") "aborted_by_user")]
    (if (str/blank? (str conversation-id))
      (json-response! reply 400 {:ok false :error "conversation_id is required"})
      (do
        (ensure-conversation-access! actor-ctx conversation-id)
        (try
          (abort-ok reply (await (turn-control/abort-active-turn! conversation-id reason)))
          (catch :default err
            (steer-err reply err)))))))

(defn- ^:async perform-session-undo!
  [reply actor-ctx session-id provided-conversation-id turns session]
  (let [conversation-id (str (or (:conversation_id session) provided-conversation-id ""))
        current-messages (vec (or (:messages session) []))
        rewound-messages (session-store/rewind-messages current-messages turns)
        removed-count (- (count current-messages) (count rewound-messages))]
    (when (and actor-ctx (not (str/blank? conversation-id)))
      (ensure-conversation-access! actor-ctx conversation-id))
    (if (zero? removed-count)
      (json-response! reply 409 {:ok false :error "No user turns available to undo"})
      (do
        (await (session-store/undo-session-turns! session-id turns))
        (json-response! reply 200 {:ok true :session_id session-id :conversation_id conversation-id
                                    :removed_count removed-count :remaining_messages (count rewound-messages)})))))

(defroute api-knoxx-session-undo! []
  "POST" "/api/knoxx/session/undo"
  (when ctx (ensure-permission! ctx "agent.chat.use"))
  (let [raw (request-body request)
        session-id (str (or (aget raw "session_id") (aget raw "sessionId") ""))
        conversation-id (str (or (aget raw "conversation_id") (aget raw "conversationId") ""))
        actor-id (or (aget raw "actor_id") (aget raw "actorId") (aget raw "actor-id"))
        actor-ctx (requests/auth-context-with-actor ctx actor-id)
        turns-raw (or (aget raw "turns") 1)
        turns (let [parsed (js/parseInt (str turns-raw) 10)] (if (js/isNaN parsed) 1 (max 1 parsed)))]
    (if (str/blank? session-id)
      (json-response! reply 400 {:ok false :error "session_id is required"})
      (try
        (let [session (await (session-store/get-session session-id))]
          (cond
            (nil? session) (json-response! reply 404 {:ok false :error "Session not found or expired"})
            (= "running" (:status session)) (json-response! reply 409 {:ok false :error "Cannot undo while a turn is still running"})
            :else (await (perform-session-undo! reply actor-ctx session-id conversation-id turns session))))
        (catch :default err (undo-session-err reply err))))))

(defroute api-admin-agents-abort! []
  "POST" "/api/admin/agents/abort"
  (ensure-permission! ctx "org.events.control")
  (handle-admin-abort reply ctx request))

;; Session status endpoint for frontend resume detection
(defroute api-knoxx-session-status! []
  "GET" "/api/knoxx/session/status"
  (handle-session-status runtime config reply request))

;; Run event catch-up endpoint for WS reconnect recovery
