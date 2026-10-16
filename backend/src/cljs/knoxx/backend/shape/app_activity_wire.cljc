(ns knoxx.backend.shape.app-activity-wire
  (:require [clojure.string :as str]))

(defn- safe-content-parts [user-message]
  (mapv (fn [part] (-> part (dissoc :data) (select-keys [:type :url :mimeType :filename :text])))
        (or (:content-parts user-message) [])))

(defn- safe-tool-receipts [run]
  (mapv #(select-keys % [:id :tool_name :status :input_preview :result_preview :started_at :ended_at])
        (or (:tool_receipts run) [])))

(defn- safe-trace-blocks [run]
  (mapv #(select-keys % [:id :kind :status :toolName :toolCallId :content :at :inputPreview :outputPreview :isError])
        (or (:trace_blocks run) [])))

(defn active-run-summary
  "Project the existing safe active-run wire row from observed runtime state."
  [run session active-turn-registered?]
  (let [messages   (vec (or (:request_messages run) []))
        user-msg   (some #(when (= "user" (some-> (:role %) str/lower-case)) %) (reverse messages))]
    {:run_id (:run_id run)
     :session_id (:session_id run)
     :conversation_id (:conversation_id run)
     :status (:status run)
     :model (:model run)
     :created_at (:created_at run)
     :updated_at (:updated_at run)
     :ttft_ms (:ttft_ms run)
     :total_time_ms (:total_time_ms run)
     :input_tokens (:input_tokens run)
     :output_tokens (:output_tokens run)
     :tokens_per_s (:tokens_per_s run)
     :error (:error run)
     :event_count (count (or (:events run) []))
     :tool_receipt_count (count (or (:tool_receipts run) []))
     :has_active_stream (boolean (:has_active_stream session))
     :active_turn_registered (boolean active-turn-registered?)
     :agent_spec (get-in run [:settings :agentSpec])
     :resource_policies (get-in run [:resources :agentResourcePolicies])
     :latest_user_message (:content user-msg)
     :content_parts (safe-content-parts user-msg)
     :tool_receipts (safe-tool-receipts run)
     :trace_blocks (safe-trace-blocks run)
     :latest_event (some-> (:events run) last (select-keys [:type :status :tool_name :preview :at]))}))

(defn active-session-summary
  "Project the existing active-session wire row from observed runtime state."
  [session active-turn-registered?]
  (let [messages (vec (or (:messages session) []))
        user-msg (some #(when (= "user" (some-> (:role %) str/lower-case)) %) (reverse messages))
        conversation-id (:conversation_id session)]
    {:run_id (:run_id session)
     :session_id (:session_id session)
     :conversation_id conversation-id
     :status (:status session)
     :model (:model session)
     :created_at (:created_at session)
     :updated_at (:updated_at session)
     :error (:error session)
     :event_count 0
     :tool_receipt_count 0
     :has_active_stream (boolean (:has_active_stream session))
     :active_turn_registered (boolean active-turn-registered?)
     :agent_spec (or (:agent_spec session) (:agentSpec session))
     :resource_policies (:resource_policies session)
     :latest_user_message (:content user-msg)
     :latest_event nil}))

(defn live-session-rows
  "Select active session rows absent from the run overlay before native observation."
  [run-items include-all? sessions]
  (let [run-session-ids (set (keep :session_id run-items))]
    (->> sessions
         (filter some?)
         (filter #(or include-all?
                      (contains? #{"queued" "running" "waiting_input"} (:status %))))
         (remove #(contains? run-session-ids (:session_id %)))
         vec)))

(defn session-status-running-response
  "Project the existing stored-session status and recovery wire response."
  [session-id session runtime-active? can-send stalled? latest-event]
  {:session_id session-id
   :conversation_id (:conversation_id session)
   :run_id (:run_id session)
   :status (:status session)
   :has_active_stream (boolean (or (:has_active_stream session) runtime-active?))
   :can_send (if stalled? false (:can-send can-send))
   :reason (cond
             stalled? "Session looked stalled after restart; recovery requested."
             runtime-active? "Session is already processing. Use steer, follow-up, abort, or wait."
             :else (:reason can-send))
   :model (:model session)
   :updated_at (:updated_at session)
   :latest_event_at (:at latest-event)
   :recovery_requested stalled?})


(defn missing-session-status
  "Project the existing in-memory-running or absent-session status response."
  [session-id conversation-id runtime-active?]
  (if runtime-active?
    {:session_id session-id :conversation_id conversation-id :status "running"
     :has_active_stream true :can_send false
     :reason "Session is already processing. Use steer, follow-up, abort, or wait."}
    {:session_id session-id :conversation_id conversation-id :status "not_found"
     :has_active_stream false :can_send true :reason "No session state found. Ready for new turn."}))
