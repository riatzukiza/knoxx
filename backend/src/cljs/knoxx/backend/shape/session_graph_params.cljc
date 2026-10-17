(ns knoxx.backend.shape.session-graph-params
  "Portable decoded-data descriptors for the legacy session graph projection.
   Event creation, clocks, I/O and conditional emission stay in the owner.")

(defn legacy-user-graph-params
  [run {:keys [run-id conversation-id session-id session-project request-text scope-extra]}]
  {:run-id run-id
   :conversation-id conversation-id
   :session-id session-id
   :ts (:created_at run)
   :node-id (str session-project ":run:" run-id ":user")
   :node-type "user_message"
   :text request-text
   :label "User message"
   :model (:model run)
   :scope-extra scope-extra})

(defn legacy-assistant-graph-params
  [run {:keys [run-id conversation-id session-id session-project answer scope-extra]}]
  {:run-id run-id
   :conversation-id conversation-id
   :session-id session-id
   :ts (:updated_at run)
   :node-id (str session-project ":run:" run-id ":assistant")
   :node-type "assistant_message"
   :text answer
   :label "Assistant message"
   :model (:model run)
   :scope-extra scope-extra})

(defn legacy-reasoning-graph-params
  [run {:keys [run-id conversation-id session-id session-project reasoning-text scope-extra]}]
  {:run-id run-id
   :conversation-id conversation-id
   :session-id session-id
   :ts (:updated_at run)
   :node-id (str session-project ":run:" run-id ":reasoning")
   :node-type "reasoning"
   :text reasoning-text
   :label "Reasoning"
   :model (:model run)
   :scope-extra scope-extra})
