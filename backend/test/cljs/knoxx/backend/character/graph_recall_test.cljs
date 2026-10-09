(ns knoxx.backend.character.graph-recall-test
  "Automatic encounter graph recall must reach the actual session prompt."
  (:require ["@open-hax/openplanner-sdk" :as sdk-fixture]
            [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [knoxx.backend.character.encounter-test :as fixture]
            [knoxx.backend.character.turn-context-test :as turn-fixture]
            [knoxx.backend.domain.character.encounter-context :as encounter-context]
            [knoxx.backend.extern.agent-turn-prompt :as prompt]
            [knoxx.backend.infra.agent.hydration :as hydration]
            [knoxx.backend.infra.agent.session :as sessions]
            [knoxx.backend.infra.agent.stream :as stream]
            [knoxx.backend.infra.agent.turn :as turn]
            [knoxx.backend.infra.character.encounter-runtime :as encounters]
            [knoxx.backend.infra.clients.openplanner :as planner]
            [knoxx.backend.infra.clients.openplanner-mongo :as mongo]
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

(defn- graph-authority [records]
  {:scope {:actor-id "creator" :org-id "org-local" :membership-id "creator-member"
           :user-id "creator-user" :policy-revision "held-current-source-policy"}
   :project "creator-local" :records (mapv #(select-keys % [:id :text]) records)})

(deftest ^:async sessionless-encounter-and-graph-only-neighbor-reach-the-maker-prompt
  ;; Both records use the existing pure encounter identity/admission laws.
  ;; The non-seed is absent from vector results and recent decision context.
  ;; The released owning adapter ranks held SDK index rows and traverses the
  ;; held scoped edge. These are not live storage or grant fixtures.
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
              :character-encounters {:sources []}
              :decision-encounters (assoc encounter-state
                                         :owner fixture/owner
                                         :inclusion-evidence [{:encounter-id (:id seed)
                                                               :authorization (fixture/authority fixture/owner fixture/source)}])
              :memory-hydration {:enabled? true :mode :always :k 6}}
        config {:session-project-name "creator-local"}
        graph-calls* (atom []) session-reads* (atom []) prompts* (atom [])
        authority-calls* (atom [])
        client (mongo/->MongoOpenPlannerClient config nil)
        recall! planner/scoped-graph-recall!
        authority (graph-authority [seed neighbor])
        session (capturing-session prompts*)]
    (is (nil? (:session seed-row)) "Encounter identity is not a fabricated session")
    (is (= [(:id seed)] (:event-ids encounter-state)))
    (sdk-fixture/__setScopedGraphFixture (clj->js authority))
    (try
    (with-redefs [planner/client (fn ([_config] client)
                                   ([_config _options] client))
                  planner/enabled? (fn [_client] true)
                  planner/vector-search! (fn [_client _payload] (vector-result seed-row))
                  planner/session! (fn [_client session-id _options]
                                     (swap! session-reads* conj session-id)
                                     (throw (ex-info "Sessionless encounters cannot borrow session authority"
                                                     {:fixture/session-read true})))
                  planner/scoped-graph-recall! (fn [client payload resolve-current!]
                                          (swap! graph-calls* conj payload)
                                          (recall! client payload resolve-current!))
                  planner/graph-memory! (fn [& _] (throw (js/Error. "Legacy REST graph is not scoped recall")))
                  encounters/graph-authority! (fn [runtime actual-config actual-spec actual-context]
                                               (swap! authority-calls* conj [runtime actual-config actual-spec actual-context])
                                               authority)
                  sessions/ensure-agent-session! (fn ([_ _ _ _] session) ([_ _ _ _ _ _ _ _] session))
                  stream/register-active-turn! (fn ([_state _abort] nil)
                                                  ([_state _abort _spec] nil))
                  prompt/log-prompt! (fn [_observation] nil)
                  turn/finalize-turn-success! (fn [& _arguments] :fixture-completed)]
      (let [[_ memory _ actual-session]
            (await (turn/hydrate-and-materialize!
                    :fixture-runtime config {:conversation-id "fixture-conversation" :session-id "fixture-session"
                                              :message "remember the harbor" :mode "direct" :model-id "fixture-model"
                                              :agent-spec spec :auth-context auth-context} []))]
        (is (identical? session actual-session))
        (is (= 5 (count @authority-calls*)) "Actual turn supplies fresh authority before SDK opening and each owning read stage")
        (is (every? #(= [:fixture-runtime config spec auth-context] %) @authority-calls*))
        (is (= 1 (count @graph-calls*)) "Automatic recall must consult the graph")
        (is (contains? (set (map :id (:hits memory))) (:id seed))
            "An authorized outside encounter must survive sessionless recall")
        (is (contains? (set (map :id (:hits memory))) (:id neighbor))
            "Recall must include an authorized graph neighbor beyond semantic seeds")
        (is (= [(:id seed) (:id neighbor)] (get-in memory [:hits 1 :path])))
        (is (= ["held-edge:seed:neighbor"] (get-in memory [:hits 1 :path-edge-ids])))
        (is (= "graph-neighbor" (get-in memory [:hits 1 :reason])))
        (is (= "not-loaded" (:field-status memory)))
        (is (not (contains? (first @graph-calls*) :scope)) "Request payload supplies no principal or source grants")
        (await (turn/prompt-and-await!
                config "fixture-session" "fixture-run" "fixture-conversation" 0
                "fixture-model" "direct" actual-session "Choose a creative opportunity"
                [] nil memory [] spec))
        (is (= [] @session-reads*))
        (is (= 1 (count @prompts*)))
        (is (str/includes? (first @prompts*) "GRAPH ONLY: bells beneath the harbor")
            "The graph result must reach the actual provider-session boundary")))
    (finally (sdk-fixture/__clearScopedGraphFixture())))))

(defn- assert-graph-outcome-projection! [status memory resource events]
  (let [event (first events)
        failure (when (= :failed status) {:stage :graph :code :transport-error})]
    (is (= status (:status memory)) "The actual hydration boundary preserves the configured owning outcome")
    (is (= failure (:failure memory))) (is (= [] (:hits memory)))
    (is (= status (:status resource)) "Turn settlement must preserve failed/denied/pending/empty distinctions")
    (is (= failure (:failure resource)) "Only bounded graph failure metadata may survive settlement")
    (is (= "not-loaded" (:field-status resource)))
    (is (= true (:graph? resource))) (is (= [] (:hits resource)))
    (is (= 1 (count events)) "Every attempted empty graph outcome must produce one observable hydration event")
    (is (= (name status) (:status event)))
    (is (= failure (:failure event)))
    (is (= "not-loaded" (:field-status event))) (is (= 0 (:hits event)))
    (is (not-any? #(contains? resource %) [:scope :auth-context :records :authority-binding])
        "A settled resource must not publish the trusted authority binding or private candidate snapshot")))

(defn- ^:async settled-graph-outcome! [status]
  (doseq [provider-fails? [false true]]
    (await (turn-fixture/with-actual-character-turn!
            (^:async fn [{:keys [config request runs* provider-failure* memories* prompts* logs*]}]
              (let [recall! planner/scoped-graph-recall!]
                (reset! provider-failure* provider-fails?)
                (with-redefs [planner/scoped-graph-recall!
                              (^:async fn [client payload resolve!]
                                (if (= :failed status)
                                  (throw (ex-info "PRIVATE_GRAPH_TRANSPORT" {:credential "PRIVATE_GRAPH_CREDENTIAL"}))
                                  (-> (await (recall! client payload resolve!))
                                      (assoc-in [:selection :status] (name status))
                                      (assoc-in [:selection :hits] []))))]
                  (let [response (try (await (turn/send-agent-turn! :fixture-runtime config request))
                                      (catch :default _error :held-provider-failed))
                        run (get @runs* (:run-id request))
                        memory (last @memories*)
                        resource (get-in run [:resources :memoryHydration])
                        events (filterv #(= "memory_hydration" (:type %)) (:events run))]
                    (is (= (if provider-fails? :held-provider-failed "Hermetic answer")
                           (if provider-fails? response (:answer response))))
                    (is (= (if provider-fails? "failed" "completed") (:status run)))
                    (is (= 1 (count @prompts*))) (is (= 1 (count @logs*)))
                    (is (not (re-find #"PRIVATE_GRAPH_TRANSPORT|PRIVATE_GRAPH_CREDENTIAL" (pr-str [run @logs* @prompts*]))))
                    (assert-graph-outcome-projection! status memory resource events)))))))))

(deftest ^:async failed-graph-outcomes-retain-safe-metadata-and-events-through-success-and-failure-settlement
  (await (settled-graph-outcome! :failed)))

(deftest ^:async denied-graph-outcomes-remain-observable-through-success-and-failure-settlement
  (await (settled-graph-outcome! :denied)))

(deftest ^:async indexing-pending-graph-outcomes-remain-observable-through-success-and-failure-settlement
  (await (settled-graph-outcome! :indexing-pending)))

(deftest ^:async empty-graph-outcomes-remain-observable-through-success-and-failure-settlement
  (await (settled-graph-outcome! :empty)))

(deftest ^:async malformed-scoped-port-results-do-not-become-empty-success
  (let [config {:session-project-name "creator-local"}
        spec {:character-encounters {:sources []} :memory-hydration {:enabled? true :mode :always}}
        client (mongo/->MongoOpenPlannerClient config nil)]
    (doseq [result [nil {} {:version 1 :field-status "not-loaded" :selection {:status "completed" :hits []}}]]
      (with-redefs [planner/client (fn ([_] client) ([_ _] client))
                    planner/scoped-graph-recall! (fn [& _] result)]
        (let [memory (await (hydration/passive-memory-hydration! config "fixture" "remember" nil spec (fn [] nil)))]
          (is (= :failed (:status memory)))
          (is (= :invalid-projection (get-in memory [:failure :code])))
          (is (= [] (:hits memory)))
          (is (nil? (hydration/passive-memory-hydration-text memory))))))))

(deftest ^:async graph-transport-failure-is-not-successful-vector-only-recall
  ;; The session scope is authorized through the real visibility adapter.
  ;; First preserve the original session visibility precondition through the
  ;; legacy conversational branch. Then the character branch requires scoped
  ;; graph recall; that precondition cannot stand in for its source authority.
  (let [row {:id "allowed-session:memory" :kind "knoxx.user"
             :text "An authorized harbor observation"
             :extra {:session "allowed-session"}}
        auth-context {:orgId "org-local" :membershipId "creator-member"
                      :userId "creator-user" :actorId "creator"
                      :permissions ["agent.memory.read"]}
        spec {:actor-id "creator"
              :memory-hydration {:enabled? true :mode :always :k 6}}
        config {:session-project-name "creator-local"}
        graph-calls* (atom [])
        client (mongo/->MongoOpenPlannerClient config nil)
        recall! planner/scoped-graph-recall!
        authority (graph-authority [{:id "held-encounter" :text "An authorized harbor observation"}])
        session-reads* (atom [])]
    (sdk-fixture/__setScopedGraphFixture (clj->js (assoc authority :fail true)))
    (try
    (with-redefs [planner/client (fn ([_config] client)
                                   ([_config _options] client))
                  planner/enabled? (fn [_client] true)
                  planner/vector-search! (fn [_client _payload] (vector-result row))
                  planner/session! (fn [_client session-id _options]
                                     (swap! session-reads* conj session-id)
                                     {:rows [{:id "scope-evidence"
                                              :extra {:org_id "org-local"
                                                      :membership_id "creator-member"
                                                      :user_id "creator-user"}}]})
                  planner/scoped-graph-recall! (fn [client payload resolve!]
                                          (swap! graph-calls* conj payload)
                                          (recall! client payload resolve!))]
      (let [legacy (await (hydration/passive-memory-hydration! config "fixture-conversation"
                                                              "remember the harbor" auth-context spec))
            _ (is (= 1 (count (:hits legacy))) "The original conversational memory path still admits the session")
            memory (await (hydration/passive-memory-hydration!
                          config "fixture-conversation" "remember the harbor"
                          auth-context (assoc spec :character-encounters {:sources []}) (fn [] authority)))]
        (is (= ["allowed-session"] @session-reads*)
            "The existing session visibility boundary admits the held principal")
        (is (= 1 (count @graph-calls*))
            "Automatic recall must attempt its distinct graph boundary")
        (is (= :failed (:status memory))
            "A transport failure has a truthful failed outcome")
        (is (= :graph (get-in memory [:failure :stage]))
            "The failed graph stage remains distinct from semantic search")
        (is (= :transport-error (get-in memory [:failure :code]))
            "Operators can distinguish transport failure from empty/denied recall")
        (is (= [] (:hits memory))
            "A successful vector seed is not silently substituted for graph recall")
        (is (not (str/includes? (pr-str memory) "PRIVATE")))))
    (finally (sdk-fixture/__clearScopedGraphFixture())))))
