;; SPDX-License-Identifier: GPL-3.0-or-later
(ns knoxx.backend.shape.sub-agent-spawn
  "Portable field projections for the existing sub-agent spawn wire format.")

(defn shared-context
  "Retain the configured shared-context fallback order."
  [sub-agent-config sub-agent-contract]
  (or (:shared-context sub-agent-config)
      (get-in sub-agent-config [:shared-context])
      (get-in sub-agent-contract [:context])
      {}))

(defn with-parent-lineage
  "Attach the existing parent lineage fields without resolving runtime policy."
  [sub-agent-config parent-job parent-event]
  (assoc sub-agent-config
         :parent-job parent-job
         :parent-run-id (or (get-in parent-job [:data :run-id])
                            (get-in parent-job [:data :run_id]))
         :parent-conversation-id (or (get-in parent-job [:data :conversation-id])
                                     (get-in parent-job [:data :conversation_id])
                                     (get-in parent-event [:payload :conversationId])
                                     (get-in parent-event [:payload :conversation_id]))
         :parent-session-id (or (get-in parent-job [:data :session-id])
                                (get-in parent-job [:data :session_id])
                                (get-in parent-event [:payload :sessionId])
                                (get-in parent-event [:payload :session_id]))))

(defn- lineage-fields
  [sub-agent-config sub-agent-contract]
  {:subAgentId (:contract/id sub-agent-contract)
   :sub-agent-id (:contract/id sub-agent-contract)
   :parentAgentId (:id (:parent-job sub-agent-config))
   :parent-agent-id (:id (:parent-job sub-agent-config))
   :parentRunId (:parent-run-id sub-agent-config)
   :parent-run-id (:parent-run-id sub-agent-config)
   :parentConversationId (:parent-conversation-id sub-agent-config)
   :parent-conversation-id (:parent-conversation-id sub-agent-config)
   :parentSessionId (:parent-session-id sub-agent-config)
   :parent-session-id (:parent-session-id sub-agent-config)
   :spawnKind "sub-agent"
   :spawn-kind "sub-agent"
   :parentCapabilitiesMode (:sub-agent/parent-capabilities sub-agent-contract)})

(defn agent-spec
  "Project resolved selections and both existing spellings of spawn fields."
  [sub-agent-config sub-agent-contract full-system-prompt task-prompt
   {:keys [model role thinking tool-policies context-policy]}]
  (merge
   {:model model
    :role role
    ;; Event-agent runner consumes camel-case keys; direct-start also accepts
    ;; snake/kebab variants after this becomes an HTTP/direct payload.
    :thinkingLevel thinking
    :thinking-level thinking
    :systemPrompt full-system-prompt
    :system-prompt full-system-prompt
    :taskPrompt task-prompt
    :task-prompt task-prompt
    :toolPolicies tool-policies
    :tool-policies tool-policies
    :contextPolicy context-policy
    :context-policy context-policy}
   (lineage-fields sub-agent-config sub-agent-contract)))
