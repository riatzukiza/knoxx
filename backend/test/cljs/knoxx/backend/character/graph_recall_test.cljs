(ns knoxx.backend.character.graph-recall-test
  "Automatic encounter graph recall must reach the actual session prompt."
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [knoxx.backend.character.encounter-test :as fixture]
            [knoxx.backend.domain.character.encounter-context :as encounter-context]
            [knoxx.backend.extern.agent-turn-prompt :as prompt]
            [knoxx.backend.infra.agent.hydration :as hydration]
            [knoxx.backend.infra.agent.stream :as stream]
            [knoxx.backend.infra.agent.turn :as turn]
            [knoxx.backend.infra.clients.openplanner :as planner]
            [knoxx.backend.law.character.encounter :as encounter-law]
            [knoxx.backend.shape.agent :as agent]))

(defn- capturing-session
  [prompts*]
  (reify agent/IAgentSession
    (streaming? [_] false)
    (current-turn [_] nil)
    (messages [_] [])
    (subscribe! [_ _listener] (fn [] nil))
    (send-user-message! [_ content]
      (swap! prompts* conj content)
      (js/Promise.resolve nil))
    (follow-up! [_ _content] (js/Promise.resolve nil))
    (steer! [_ _content] (js/Promise.resolve nil))
    (set-thinking-level! [_ _level] nil)
    (abort! [_] (js/Promise.resolve nil))))

(defn- vector-result
  [row]
  {:result {:ids [[(:id row)]]
            :documents [[(:text row)]]
            :metadatas [[(merge (:extra row) {:kind (:kind row) :role "user"})]]
            :distances [[0.1]]}})

(deftest ^:async sessionless-encounter-and-graph-only-neighbor-reach-the-maker-prompt
  ;; Both records use the existing pure encounter identity/admission laws.
  ;; The non-seed is absent from vector results and recent decision context.
  ;; The graph response is transport fixture data, not graph traversal proof.
  (let [prepared (fixture/prepared [(fixture/item "seed" "An outside encounter about a harbor")
                                    (fixture/item "neighbor" "GRAPH ONLY: bells beneath the harbor")])
        [seed neighbor] (:records prepared)
        seed-row (first (:events prepared))
        decisions {(encounter-law/stream-id fixture/digest fixture/owner fixture/source)
                   (fixture/authority fixture/owner fixture/source)}
        encounter-state (encounter-context/assemble-context
                         fixture/digest fixture/owner [seed] decisions {})
        auth-context {:orgId "org-local" :membershipId "creator-member"
                      :userId "creator-user" :actorId "creator"
                      :permissions ["agent.memory.read"]
                      :toolPolicies [{:toolId "discord.channel.messages" :effect "allow"
                                      :constraints {:account-id "local-bot"
                                                    :scope-id "allowed-channel"
                                                    :visibility "public"}}]}
        spec {:actor-id "creator"
              :decision-encounters (assoc encounter-state
                                         :owner fixture/owner
                                         :inclusion-evidence [{:encounter-id (:id seed)
                                                               :authorization (fixture/authority fixture/owner fixture/source)}])
              :memory-hydration {:enabled? true :mode :always :k 6}}
        config {:session-project-name "creator-local"}
        graph-calls* (atom []) session-reads* (atom []) prompts* (atom [])
        session (capturing-session prompts*)]
    (is (nil? (:session seed-row)) "Encounter identity is not a fabricated session")
    (is (= [(:id seed)] (:event-ids encounter-state)))
    (with-redefs [planner/client (fn ([_config] :fixture-client)
                                   ([_config _options] :fixture-client))
                  planner/enabled? (fn [_client] true)
                  planner/vector-search! (fn [_client _payload] (vector-result seed-row))
                  planner/session! (fn [_client session-id _options]
                                     (swap! session-reads* conj session-id)
                                     (throw (ex-info "Sessionless encounters cannot borrow session authority"
                                                     {:fixture/session-read true})))
                  planner/graph-memory! (fn [_client payload]
                                          (swap! graph-calls* conj payload)
                                          {:query "harbor"
                                           :nodes [{:id (:id seed) :text (:text seed) :isSeed true}
                                                   {:id (:id neighbor) :text (:text neighbor) :isSeed false}]
                                           :edges [{:source (:id seed) :target (:id neighbor)
                                                    :edgeKind "fixture-admitted-relation"}]
                                           :daimoi [{:originNodeId (:id seed) :currentNodeId (:id neighbor)
                                                      :trail [(:id seed) (:id neighbor)]}]})
                  stream/register-active-turn! (fn ([_state _abort] nil)
                                                  ([_state _abort _spec] nil))
                  prompt/log-prompt! (fn [_observation] nil)
                  turn/finalize-turn-success! (fn [& _arguments] :fixture-completed)]
      (let [memory (await (hydration/passive-memory-hydration!
                          config "fixture-conversation" "remember the harbor" auth-context spec))]
        (is (= 1 (count @graph-calls*)) "Automatic recall must consult the graph")
        (is (contains? (set (map :id (:hits memory))) (:id seed))
            "An authorized outside encounter must survive sessionless recall")
        (is (contains? (set (map :id (:hits memory))) (:id neighbor))
            "Recall must include an authorized graph neighbor beyond semantic seeds")
        (await (turn/prompt-and-await!
                config "fixture-session" "fixture-run" "fixture-conversation" 0
                "fixture-model" "direct" session "Choose a creative opportunity"
                [] nil memory [] spec))
        (is (= [] @session-reads*))
        (is (= 1 (count @prompts*)))
        (is (str/includes? (first @prompts*) "GRAPH ONLY: bells beneath the harbor")
            "The graph result must reach the actual provider-session boundary")))))
