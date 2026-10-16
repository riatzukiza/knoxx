(ns knoxx.backend.domain.memory-sessions-active
  "Pure active-session claim matching and synthetic row representation."
  (:require [clojure.string :as str]
            [knoxx.backend.domain.actor.scope :as actor-scope]))

(defn inactive-row [row]
  (assoc row
         :is_active false
         :active_status "inactive"
         :has_active_stream false))

(defn- agent-spec-value
  [agent-spec keys]
  (some (fn [k]
          (some-> (get agent-spec k)
                  str
                  str/trim
                  not-empty))
        keys))

(defn- active-session-actor-claims
  [session]
  (let [agent-spec (:agent_spec session)
        contract-actors (or (:contractActors agent-spec)
                            (:contract-actors agent-spec)
                            (:contract_actors agent-spec))]
    (actor-scope/normalize-actor-claims
     (concat [(or (:actor_id session)
                  (:actor-id session)
                  (:actorId session))
              (agent-spec-value agent-spec [:actor_id :actor-id :actorId])]
             (cond
               (set? contract-actors) contract-actors
               (sequential? contract-actors) contract-actors
               contract-actors [contract-actors]
               :else [])))))

(defn- active-session-matches-actor-filter?
  [session actor-id exclude-actor-ids]
  (let [include-actor-id (some-> actor-id str str/trim not-empty)
        exclude-actor-ids (->> (or exclude-actor-ids [])
                               (keep #(some-> % str str/trim not-empty))
                               distinct
                               vec)
        actors (active-session-actor-claims session)]
    (and (or (str/blank? (str (or include-actor-id "")))
             (actor-scope/actor-allowed? actors include-actor-id))
         (not-any? #(actor-scope/actor-allowed? actors %) exclude-actor-ids))))

(defn- actor-claim-includes?
  [actors actor-id]
  (let [wanted (actor-scope/normalize-actor-claim actor-id)]
    (and wanted
         (contains? (actor-scope/normalize-actor-claims actors) wanted))))

(defn- active-session-matches-contract?
  [session contract-id]
  (let [target (some-> contract-id str str/trim not-empty)
        agent-spec (:agent_spec session)]
    (or (nil? target)
        (= target (agent-spec-value agent-spec [:contract_id :contract-id :contractId]))
        (= target (agent-spec-value agent-spec [:sub_agent_id :sub-agent-id :subAgentId]))
        (= target (agent-spec-value agent-spec [:parent_agent_id :parent-agent-id :parentAgentId]))
        (= target (agent-spec-value agent-spec [:actor_id :actor-id :actorId]))
        (actor-claim-includes? (active-session-actor-claims session) target))))

(defn- active-session-actor-id
  [session agent-spec]
  (or (agent-spec-value agent-spec [:actor_id :actor-id :actorId])
      (some-> (or (:actor_id session) (:actor-id session) (:actorId session))
              str
              str/trim
              not-empty)))

(defn- active-session-event-types
  [agent-spec]
  (let [values (or (:event_types agent-spec)
                   (:event-types agent-spec)
                   (:eventTypes agent-spec))]
    (when (sequential? values)
      (->> values
           (map str)
           (remove str/blank?)
           distinct
           vec))))

(defn- active-session-synthetic-scope
  [session agent-spec]
  (let [actor-id (active-session-actor-id session agent-spec)
        event-types (active-session-event-types agent-spec)]
    (cond-> {}
      actor-id (assoc :actor_id actor-id)
      (agent-spec-value agent-spec [:contract_id :contract-id :contractId]) (assoc :contract_id (agent-spec-value agent-spec [:contract_id :contract-id :contractId]))
      (agent-spec-value agent-spec [:sub_agent_id :sub-agent-id :subAgentId]) (assoc :sub_agent_id (agent-spec-value agent-spec [:sub_agent_id :sub-agent-id :subAgentId]))
      (agent-spec-value agent-spec [:parent_agent_id :parent-agent-id :parentAgentId]) (assoc :parent_agent_id (agent-spec-value agent-spec [:parent_agent_id :parent-agent-id :parentAgentId]))
      (agent-spec-value agent-spec [:parent_run_id :parent-run-id :parentRunId]) (assoc :parent_run_id (agent-spec-value agent-spec [:parent_run_id :parent-run-id :parentRunId]))
      (agent-spec-value agent-spec [:spawn_kind :spawn-kind :spawnKind]) (assoc :spawn_kind (agent-spec-value agent-spec [:spawn_kind :spawn-kind :spawnKind]))
      (agent-spec-value agent-spec [:trigger_id :trigger-id :triggerId]) (assoc :trigger_id (agent-spec-value agent-spec [:trigger_id :trigger-id :triggerId]))
      (agent-spec-value agent-spec [:event_type :event-type :eventType]) (assoc :event_type (agent-spec-value agent-spec [:event_type :event-type :eventType]))
      (seq event-types) (assoc :event_types event-types)
      (agent-spec-value agent-spec [:event_id :event-id :eventId]) (assoc :event_id (agent-spec-value agent-spec [:event_id :event-id :eventId]))
      (agent-spec-value agent-spec [:event_scope_id :event-scope-id :eventScopeId]) (assoc :event_scope_id (agent-spec-value agent-spec [:event_scope_id :event-scope-id :eventScopeId]))
      (agent-spec-value agent-spec [:schedule_id :schedule-id :scheduleId]) (assoc :schedule_id (agent-spec-value agent-spec [:schedule_id :schedule-id :scheduleId])))))

(defn- active-session-synthetic-row
  [session]
  (let [agent-spec (:agent_spec session)]
    (merge {:session (:conversation_id session)
            :is_active true
            :active_status (:status session)
            :has_active_stream (boolean (:has_active_stream session))
            :title (str "Running · " (or (:run_id session) (:conversation_id session)))
            :event_count 0
            :last_ts (:updated_at session)}
           (active-session-synthetic-scope session agent-spec))))

(defn synthetic-active-session-rows
  [live page-rows actor-id exclude-actor-ids contract-id]
  (let [op-ids (set (map #(str (:session %)) page-rows))]
    (->> live
         (filter #(and (:conversation_id %)
                       (not (op-ids (str (:conversation_id %))))
                       (contains? #{"running" "waiting_input"} (:status %))
                       (active-session-matches-actor-filter? % actor-id exclude-actor-ids)
                       (active-session-matches-contract? % contract-id)))
         (map active-session-synthetic-row)
         vec)))

