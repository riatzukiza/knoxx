(ns knoxx.backend.infra.character.turn-context
  "Current principal, encounter and graph disclosure for an automatic turn."
  (:require [knoxx.backend.domain.action.run-state :refer [append-run-event! update-run!]]
            [knoxx.backend.domain.agent.agent-templates :as templates]
            [knoxx.backend.domain.character.decision-input :as decision-input]
            [knoxx.backend.domain.character.tool-modes :as character-modes]
            [knoxx.backend.domain.realtime :refer [broadcast-ws-session!]]
            [knoxx.backend.infra.agent.hydration :as hydration]
            [knoxx.backend.infra.character.authority :as character-authority]
            [knoxx.backend.infra.character.encounter-runtime :as encounters]
            [knoxx.backend.infra.run-event-payload :as run-payload]
            [knoxx.backend.infra.run-events :as run-events]))

(defn- character-spec
  [agent-spec]
  (if-not (:tool-modes agent-spec)
    agent-spec
    (let [projection (or (:character-context agent-spec)
                         {:identity (or (:actor-id agent-spec) (:contract-id agent-spec))
                          :persona (or (:system-prompt agent-spec) "")
                          ;; Explicit absence of a loaded physical projection.
                          :snapshot {} :evidence-ids []})]
      (-> agent-spec
          (assoc :character-context projection)
          (update :system-prompt #(character-modes/system-context-text % {:character projection}))))))

(defn ^:async prepare!
  "Resolve the current principal and admitted outside context before recall."
  [runtime config auth-context agent-spec template-context]
  (let [auth-context (if (or (:tool-modes agent-spec) (:character-encounters agent-spec))
                       (await (character-authority/resolve-current! config auth-context agent-spec))
                       auth-context)
        agent-spec (dissoc (templates/render-agent-prompts agent-spec auth-context template-context)
                           :decision-encounters)
        context (when (:character-encounters agent-spec)
                  (await (encounters/decision-context! runtime config agent-spec auth-context)))]
    {:auth-context auth-context
     :encounter-context context
     :agent-spec (character-spec (cond-> agent-spec context (assoc :decision-encounters context)))}))

(defn- disclosed-agent-spec [agent-spec snapshot]
  (cond-> (dissoc agent-spec :decision-encounters)
    (:context snapshot) (assoc :decision-encounters (:context snapshot))))

(defn ^:async refresh-query!
  "Refresh the source snapshot after policy awaits and before query disclosure."
  [runtime config ctx]
  (if-not (:character-encounters (:agent-spec ctx))
    ctx
    (let [snapshot (await (encounters/disclosure-snapshot! runtime config (:agent-spec ctx) (:auth-context ctx)))]
      (assoc ctx :agent-spec (disclosed-agent-spec (:agent-spec ctx) snapshot)
                 :auth-context (or (:auth-context snapshot) (:auth-context ctx))
                 :graph-authority (:graph-authority snapshot)
                 :memory-query (decision-input/memory-query (:message ctx) (:context snapshot))))))

(defn query-authority-resolver
  "Bind every recall authority refresh to the query source snapshot."
  [runtime config ctx]
  (^:async fn []
    (let [current (await (encounters/graph-authority! runtime config (:agent-spec ctx) (:auth-context ctx)))]
      ;; The query can contain encounter text. A changed source snapshot must
      ;; refuse the query before provider generation, even if another source
      ;; still grants an otherwise valid graph scope.
      (when (or (not (contains? ctx :graph-authority))
                (and (:graph-authority ctx)
                     (= (hydration/graph-authority-binding (:graph-authority ctx))
                        (hydration/graph-authority-binding current))))
        current))))

(defn- ^:async refresh-prompt-disclosure! [runtime config agent-spec auth-context memory]
  (if-not (:character-encounters agent-spec)
    {:agent-spec agent-spec :memory memory}
    (let [snapshot (await (encounters/disclosure-snapshot! runtime config agent-spec auth-context))]
      (when-not (:auth-context snapshot)
        (throw (ex-info "Character disclosure authority unavailable" {:reason :character-disclosure-authority-unavailable})))
      {:agent-spec (disclosed-agent-spec agent-spec snapshot)
       :memory (hydration/retain-current-graph-hydration memory (:graph-authority snapshot))})))

(defn ^:async capture-prompt!
  "Capture fresh whole-context disclosure or a bounded safe refusal."
  [runtime config agent-spec auth-context memory]
  (try
    (await (refresh-prompt-disclosure! runtime config agent-spec auth-context memory))
    ;; knoxx-lint/allow-silent-catch — do not retain an authority exception/cause.
    ;; Finish startup ownership before ordinary bounded failure settlement.
    (catch :default _error
      {:refused? true :agent-spec (dissoc agent-spec :decision-encounters)
       :memory (hydration/retain-current-graph-hydration memory nil)})))

(defn resource-patch
  "Project hydration resources without private authority or candidate snapshots."
  [passive memory]
  (cond-> {}
    passive (assoc :passiveHydration (select-keys passive [:query :tokens :database :elapsedMs :results]))
    memory (assoc :memoryHydration (hydration/memory-hydration-projection memory))))

(defn retain-resources!
  "Retain only disclosed graph and context outcomes in the run resources."
  [run-id agent-spec passive memory]
  (let [evidence (decision-input/inclusion-evidence (:decision-encounters agent-spec))]
    (update-run! run-id
                 #(update % :resources
                          (fn [resources]
                            (cond-> (merge (dissoc resources :characterEncounters)
                                           (resource-patch passive memory))
                              evidence (assoc :characterEncounters evidence)))))))

(defn ^:async emit-inclusion!
  "Persist and publish bounded prepared encounter inclusion evidence."
  [run-id conversation-id session-id agent-spec]
  (when-let [evidence (decision-input/inclusion-evidence (:decision-encounters agent-spec))]
    (let [payload (assoc evidence :status "prepared" :stage "request-context"
                         :hits (count (:event-ids evidence)))
          event (run-payload/tool-event-payload run-id conversation-id session-id "character_encounter_context" payload)]
      (update-run! run-id #(assoc-in % [:resources :characterEncounters] evidence))
      (append-run-event! run-id event)
      (await (run-events/flush! run-id))
      (broadcast-ws-session! session-id "events" event))))
