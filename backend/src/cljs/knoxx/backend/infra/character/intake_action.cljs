(ns knoxx.backend.infra.character.intake-action
  "Native scheduled intake effects over a canonical actor contract. GPL-3.0-or-later."
  (:require [knoxx.backend.infra.character.encounter-runtime :as encounters]
            [knoxx.backend.infra.agent.event-policy-authority :as event-authority]
            [knoxx.backend.infra.tooling :as tooling]
            [knoxx.backend.law.character.encounter :as law]
            [knoxx.backend.runtime.state :as runtime-state]
            [knoxx.backend.shape.character.encounter :as shape]))

(defn ^:async intake-handler!
  "The action's agent id is authored trigger data. Ignore event/provider source,
   account, grant and encounter options; observation uses current canonical data."
  [ctx action]
  (let [actor-id (law/assert-shape! shape/NonBlankString (:actor/id ctx) :intake-actor)
        agent-id (law/assert-shape! shape/NonBlankString
                                    (or (get-in action [:action/with :agent-id]) (:agent/id ctx)) :intake-agent)
        selected (tooling/resolve-agent-contract (:config ctx) agent-id actor-id)]
    (when-not (and selected (not (false? (:enabled selected)))
                   (= actor-id (:actor-id selected)) (= agent-id (:id selected)))
      (throw (ex-info "Scheduled intake contract is not selected for this actor" {:reason :invalid-intake-contract})))
    {:ok true :action/kind :actions/character-intake
     :observation (await (encounters/observe! (or (:runtime ctx) @runtime-state/runtime*) (:config ctx)
                                             (assoc selected :contract-id agent-id)
                                             (event-authority/authorized-context nil actor-id nil nil)))}))
