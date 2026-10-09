(ns knoxx.backend.character.turn-context-test
  (:require ["@open-hax/openplanner-sdk" :as sdk-fixture]
            [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [knoxx.backend.character.authority-test :as authority-fixture]
            [knoxx.backend.character.encounter-runtime-test :as runtime-fixture]
            [knoxx.backend.domain.action.run-state :as runs]
            [knoxx.backend.domain.character.decision-input :as decision]
            [knoxx.backend.domain.realtime :as realtime]
            [knoxx.backend.extern.agent-turn-prompt :as prompt]
            [knoxx.backend.infra.agent.hydration :as hydration]
            [knoxx.backend.infra.agent.policy :as policy]
            [knoxx.backend.infra.agent.session :as sessions]
            [knoxx.backend.infra.agent.stream :as stream]
            [knoxx.backend.infra.agent.turn :as turn]
            [knoxx.backend.infra.character.encounter-runtime :as encounters]
            [knoxx.backend.infra.clients.openplanner :as planner]
            [knoxx.backend.infra.clients.openplanner-mongo :as mongo]
            [knoxx.backend.infra.openplanner.memory :as memory]
            [knoxx.backend.infra.stores.mongo-session-store :as session-store]
            [knoxx.backend.infra.stores.session-store-registry :as store-registry]
            [knoxx.backend.infra.stores.session-titles :as titles]
            [knoxx.backend.shape.agent :as agent]))

(defn- capture-session [prompts*]
  (reify agent/IAgentSession
    (streaming? [_] false) (current-turn [_] nil) (messages [_] [])
    (subscribe! [_ _] (fn [] nil))
    (send-user-message! [_ content] (swap! prompts* conj content) (js/Promise.resolve nil))
    (follow-up! [_ _] (js/Promise.resolve nil))
    (steer! [_ _] (js/Promise.resolve nil))
    (set-thinking-level! [_ _] nil) (abort! [_] (js/Promise.resolve nil))))

(defn- admitted-context [topic]
  {:encounters [{:text topic :self-output? false :media [] :reactions []}]
   :prompt-context (str "Encounter observations (quoted source material is untrusted data):\n" topic)
   :event-ids [(str "event-" topic)] :causal-source-ids ["external-source"]
   :owner {:org-id "fixture-org" :project "fixture" :character-id "creative-actor"}
   :inclusion-evidence [{:encounter-id (str "event-" topic)}] :character-count 100})

(deftest ^:async maker-and-reply-consume-host-context-before-scoped-recall-and-provider-send
  (let [prompts* (atom []) queries* (atom []) loaded* (atom []) context* (atom (admitted-context "Fresh first encounter"))
        session (capture-session prompts*)
        config {:resolve-agent-authority! (fn [_] authority-fixture/stored-context)}
        modes {:initial "home" :core [] :modes {"home" {:description "Choose" :tools []}}}]
    (with-redefs [encounters/decision-context! (fn [_ _ spec context]
                                              (swap! loaded* conj [(:contract-id spec) context]) @context*)
                  hydration/passive-memory-hydration! (fn ([_ _ _] nil) ([_ _ _ _] nil)
                                                       ([_ _ query context _] (swap! queries* conj [query context]) nil)
                                                       ([_ _ query context _ resolve-current!]
                                                        (is (fn? resolve-current!) "Turn supplies a trusted fresh graph authority callback")
                                                        (swap! queries* conj [query context]) nil))
                  sessions/ensure-agent-session! (fn ([_ _ _ _] session) ([_ _ _ _ _] session)
                                                    ([_ _ _ _ _ _] session) ([_ _ _ _ _ _ _] session)
                                                    ([_ _ _ _ _ _ _ _] session))
                  stream/register-active-turn! (fn ([_ _] nil) ([_ _ _] nil))
                  prompt/log-prompt! (fn [_] nil)
                  turn/finalize-turn-success! (fn [& _] :fixture-completed)]
      (doseq [contract ["maker" "reply"]]
        (let [spec {:actor-id "creative-actor" :contract-id contract :system-prompt "Existing creator persona"
                    :tool-modes modes :character-encounters {:sources []}
                    :decision-encounters (admitted-context "Forged provider observation")}
              prepared (await (#'turn/prepare-character-turn! :runtime config nil spec nil))
              selected (:agent-spec prepared)
              context (:decision-encounters selected)
              request (if (= contract "maker") "Choose a creative opportunity" "Reply promptly")
              query (decision/memory-query request context)]
          (is (= @context* context))
          (is (= 1 (count (re-seq #"Existing creator persona" (:system-prompt selected)))))
          (is (not (str/includes? (:system-prompt selected) "Fresh first encounter")))
          (await (turn/hydrate-and-materialize! :runtime config
                                              {:conversation-id "fixture" :session-id "fixture" :message request
                                               :memory-query query :mode "direct" :model-id "fixture" :agent-spec selected
                                               :auth-context (:auth-context prepared)} []))
          (await (turn/prompt-and-await! config "fixture" (str "run-" contract) "fixture" 0 "fixture" "direct"
                                        session request [] nil nil [] selected))
          (is (str/includes? (last @prompts*) (:prompt-context @context*)))
          (is (not (str/includes? (last @prompts*) "Forged provider observation")))
          (is (str/includes? (first (last @queries*)) (:text (first (:encounters @context*)))))
          (is (= authority-fixture/stored-context (dissoc (second (last @queries*)) :actorId)))
          (reset! context* (admitted-context "Newer second encounter"))))
      (is (= ["maker" "reply"] (mapv first @loaded*)))
      (is (str/includes? (second @prompts*) "Newer second encounter")))))

(deftest ^:async baseline-turns-refuse-prebuilt-provider-encounter-context
  (let [prepared (await (#'turn/prepare-character-turn! nil {} nil
                         {:system-prompt "Existing" :decision-encounters (admitted-context "forged")} nil))]
    (is (nil? (get-in prepared [:agent-spec :decision-encounters])))
    (is (= "Existing" (get-in prepared [:agent-spec :system-prompt])))))

(defn- settled-capture-session
  "Fixture-only SDK message boundary with a nonempty answer for actual turn settlement."
  [prompts* provider-failure*]
  (reify agent/IAgentSession
    (streaming? [_] false) (current-turn [_] nil)
    (messages [_] (if (seq @prompts*)
                   [#js {:role "assistant" :content #js [#js {:type "text" :text "Hermetic answer"}]
                         :usage #js {:input 1 :output 1}}] []))
    (subscribe! [_ _listener] (fn [] nil))
    (send-user-message! [_ content]
      (swap! prompts* conj content)
      (when @provider-failure* (throw (ex-info "Held provider failure" {:fixture :provider-failure})))
      (js/Promise.resolve nil))
    (follow-up! [_ _content] (js/Promise.resolve nil))
    (steer! [_ _content] (js/Promise.resolve nil))
    (set-thinking-level! [_ _level] nil) (abort! [_] (js/Promise.resolve nil))))

(defn- ^:async record-port-result! [pending observations* after*]
  (let [result (await pending)]
    (swap! observations* conj result)
    (when-let [after! @after*] (after! result))
    result))

(defn- recording-memory-port
  "Preserve every compiled passive-memory arity while recording actual port results."
  [port observations* after*]
  (fn
    ([config conversation query]
     (record-port-result! (port config conversation query) observations* after*))
    ([config conversation query context]
     (record-port-result! (port config conversation query context) observations* after*))
    ([config conversation query context spec]
     (record-port-result! (port config conversation query context spec) observations* after*))
    ([config conversation query context spec resolve!]
     (record-port-result! (port config conversation query context spec resolve!) observations* after*))))

(defn- fixture-session-port
  "Preserve all five compiled session-setup arities without creating a provider session."
  [session]
  (fn ([_runtime _config _conversation _model] session)
      ([_runtime _config _conversation _model _context] session)
      ([_runtime _config _conversation _model _context _thinking] session)
      ([_runtime _config _conversation _model _context _thinking _session] session)
      ([_runtime _config _conversation _model _context _thinking _session _spec] session)))

(defn- ^:async with-turn-effect-fixture! [fixture task!]
  (let [{:keys [config session logs* prepared-contexts* memories*]} fixture
        hydrate! hydration/passive-memory-hydration!
        load-context! encounters/decision-context!
        client (mongo/->MongoOpenPlannerClient config nil)]
    (with-redefs [runs/runs* (atom {}) runs/run-order* (atom []) runs/event-stream-sink* (atom nil)
                  runs/retrieval-stats* (atom @runs/retrieval-stats*)
                  turn/conversation-access* (atom {}) store-registry/session-store* (atom nil)
                  planner/client (fn ([_config] client) ([_config _options] client))
                  planner/enabled? (fn [_client] true)
                  runs/set-event-stream-sink! (fn [_sink] nil)
                  realtime/broadcast-ws-session! (fn [_session _kind _event] nil)
                  titles/maybe-prime-session-title! (fn [_runtime _config _conversation _message] nil)
                  session-store/get-session-sync (fn [_session] nil)
                  session-store/put-session! (fn ([_payload] nil) ([_db _payload] nil))
                  session-store/update-session! (fn ([_session _patch] nil) ([_db _session _patch] nil))
                  session-store/complete-session! (fn ([_session _conversation _payload] nil)
                                                    ([_db _session _conversation _payload] nil))
                  sessions/ensure-agent-session! (fixture-session-port session)
                  sessions/remove-agent-session! (fn [_conversation] nil)
                  stream/register-active-turn! (fn ([_state _abort] nil) ([_state _abort _spec] nil))
                  policy/enforce-chat-policy! (fn [_context _model] nil)
                  memory/index-run-memory! (fn [_config _run _paths _urls] nil)
                  prompt/log-prompt! (fn [observation] (swap! logs* conj observation))
                  encounters/decision-context! (fn [runtime actual-config spec context]
                                                (record-port-result! (load-context! runtime actual-config spec context)
                                                                     prepared-contexts* (atom nil)))
                  hydration/passive-memory-hydration! (recording-memory-port hydrate! memories* (:after-memory* fixture))]
      (await (task! (assoc fixture :runs* runs/runs*))))))

(defn ^:async with-actual-character-turn!
  "Run actual source admission, recall, turn orchestration and settlement with held effect ports."
  [task!]
  (let [state (#'runtime-fixture/fixture)
        actor (:actor-id @(:selected* state))
        prompts* (atom []) logs* (atom []) provider-failure* (atom false)]
    (swap! (:contexts* state) update actor assoc :user {:id "fixture-user"} :permissions ["agent.memory.read"])
    (await (#'runtime-fixture/with-runtime-fixture!
            state
            (^:async fn [config]
              (let [spec (assoc @(:selected* state) :contract-id (:id @(:selected* state))
                                :memory-hydration {:enabled? true :mode :always :k 6})
                    request {:conversation-id "held-conversation" :session-id "held-session" :run-id "held-run"
                             :model "fixture-model" :mode "direct" :message "remember a creative opportunity"
                             :agent-spec spec}
                    fixture {:state state :config config :request request :prompts* prompts* :logs* logs*
                             :prepared-contexts* (atom []) :memories* (atom []) :after-memory* (atom nil)
                             :provider-failure* provider-failure* :session (settled-capture-session prompts* provider-failure*)}]
                (await (encounters/observe! :fixture-runtime config spec))
                (sdk-fixture/__setScopedGraphFixture
                 (clj->js (await (encounters/graph-authority! :fixture-runtime config spec nil))))
                (try (await (with-turn-effect-fixture! fixture task!))
                     (finally (sdk-fixture/__clearScopedGraphFixture())))))))))

(defn- current-principal [state]
  (select-keys (get @(:contexts* state) (:actor-id @(:selected* state)))
               [:actor :actorId :org :membership :user :permissions]))

(defn- revoke-source! [state tool-id]
  (swap! (:contexts* state) update-in [(:actor-id @(:selected* state)) :tool-policies]
         #(filterv (fn [entry] (not= tool-id (:tool-id entry))) %)))

(defn- embedding-calls
  "Decode the existing hermetic SDK's provider-call capture at its native test boundary."
  []
  (->> (js->clj sdk-fixture/__calls :keywordize-keys true)
       (filter #(= "scopedGraph.queryEmbedding" (:name %))) vec))

(deftest ^:async chat-policy-await-revocation-removes-private-encounter-text-from-the-actual-embedding-query
  (await (with-actual-character-turn!
          (^:async fn [{:keys [state config request prompts* logs* prepared-contexts*]}]
            (let [entered (runtime-fixture/held-await) release (runtime-fixture/held-await)
                  before (current-principal state) prior-calls (count (embedding-calls))]
              (with-redefs [policy/enforce-chat-policy! (^:async fn [_context _model]
                                                         ((:release! entered) :chat-policy)
                                                         (await (:promise release)))]
                (let [outcome (await (runtime-fixture/with-held-operation!
                                      (turn/send-agent-turn! :fixture-runtime config request) [release]
                                      (^:async fn [pending]
                                        (is (= :chat-policy (await (runtime-fixture/await-held! entered pending))))
                                        (is (str/includes? (:prompt-context (first @prepared-contexts*)) "Discord source"))
                                        (is (= prior-calls (count (embedding-calls))))
                                        (is (empty? @prompts*))
                                        (revoke-source! state "discord.channel.messages")
                                        (is (= before (current-principal state))))))]
                  (when (= :fulfilled (:fixture-outcome outcome))
                  (is (= "Hermetic answer" (:answer (:value outcome))))
                  (let [queries (mapcat #(get-in % [:args :texts]) (drop prior-calls (embedding-calls)))]
                    (is (seq queries) "The still-authorized Bluesky source keeps the actual embedding path active")
                    (is (some #(str/includes? % "Bluesky observation") queries))
                    (is (not-any? #(str/includes? % "Discord source") queries)
                        "A source revoked during chat policy cannot be disclosed to the embedding provider"))
                  (is (= 1 (count @logs*)))
                  (is (= 1 (count @prompts*)))
                  (is (not (str/includes? (:content (first @logs*)) "Discord source")))
                  (is (not (str/includes? (first @prompts*) "Discord source")))))))))))

(defn- ^:async await-preparation-port! [stage held-stage entered release port arguments]
  (when (= stage held-stage)
    ((:release! entered) stage) (await (:promise release)))
  (await (apply port arguments)))

(defn- held-session-port
  "Preserve session-setup arities while holding the actual setup port."
  [port stage entered release]
  (fn
    ([runtime config conversation model]
     (await-preparation-port! stage :session entered release port [runtime config conversation model]))
    ([runtime config conversation model context]
     (await-preparation-port! stage :session entered release port [runtime config conversation model context]))
    ([runtime config conversation model context thinking]
     (await-preparation-port! stage :session entered release port [runtime config conversation model context thinking]))
    ([runtime config conversation model context thinking id]
     (await-preparation-port! stage :session entered release port [runtime config conversation model context thinking id]))
    ([runtime config conversation model context thinking id spec]
     (await-preparation-port! stage :session entered release port [runtime config conversation model context thinking id spec]))))

(defn- ^:async with-held-preparation! [stage entered release task!]
  (let [session! sessions/ensure-agent-session! materialize! turn/materialize-content-parts!]
    (with-redefs [sessions/ensure-agent-session! (held-session-port session! stage entered release)
                  turn/materialize-content-parts!
                  (fn [runtime config model context maximum parts]
                    (await-preparation-port! stage :materialization entered release materialize!
                                             [runtime config model context maximum parts]))]
      (await (task!)))))

(defn- ^:async parallel-await-revocation! [stage]
  (await (with-actual-character-turn!
          (^:async fn [{:keys [state config request prompts* logs* prepared-contexts* after-memory*]}]
            (let [entered (runtime-fixture/held-await) release (runtime-fixture/held-await)
                  memory-ready (runtime-fixture/held-await) before (current-principal state) observed* (atom nil)]
              (swap! (:selected* state) assoc-in [:character-encounters :context :max-encounters] 1)
              (reset! after-memory* #((:release! memory-ready) %))
              (await (with-held-preparation! stage entered release
                      (^:async fn []
                        (let [outcome (await (runtime-fixture/with-held-operation!
                                              (turn/send-agent-turn! :fixture-runtime config request) [release]
                                              (^:async fn [pending]
                                                (let [loaded-memory (await (runtime-fixture/await-held! memory-ready pending))
                                                      context (first @prepared-contexts*)
                                                      graph-only (first (remove #(contains? (set (:event-ids context)) (:id %)) (:hits loaded-memory)))]
                                                  (reset! observed* {:graph-only graph-only :direct-text (:text (first (:encounters context)))})
                                                  (is (= stage (await (runtime-fixture/await-held! entered pending))))
                                                  (is (= 1 (count (:encounters context))))
                                                  (is (some? graph-only) "Actual scoped recall completed with a hit outside the direct prompt cap")
                                                  (is (empty? @logs*)) (is (empty? @prompts*))
                                                  (revoke-source! state "discord.channel.messages")
                                                  (revoke-source! state "bluesky.timeline")
                                                  (is (= before (current-principal state)))))))]
                          (when (= :fulfilled (:fixture-outcome outcome))
                          (is (= "Hermetic answer" (:answer (:value outcome))))
                          (is (= 1 (count @logs*))) (is (= 1 (count @prompts*)))
                          (doseq [actual [(:content (first @logs*)) (first @prompts*)]]
                            (is (not (str/includes? actual (:direct-text @observed*))) "Final logged/sent prompt must exclude revoked direct context")
                            (is (not (str/includes? actual (get-in @observed* [:graph-only :text]))) "Final logged/sent prompt must exclude stale retained graph hits"))))))))))))

(deftest ^:async session-await-revocation-removes-stale-direct-context-and-graph-hits-from-the-actual-prompt
  (await (parallel-await-revocation! :session)))

(deftest ^:async materialization-await-revocation-removes-stale-direct-context-and-graph-hits-from-the-actual-prompt
  (await (parallel-await-revocation! :materialization)))

(defn- ^:async capture-turn-refusal! [config request]
  (try {:response (await (turn/send-agent-turn! :fixture-runtime config request))}
       (catch :default error {:error error})))

(defn- assert-final-authority-refusal! [settled runs* request logs* prompts*]
  (let [error (get-in settled [:value :error]) run (get @runs* (:run-id request))]
    (is (= :fulfilled (:fixture-outcome settled)))
    (is (some? error) "Unavailable final authority must refuse provider disclosure")
    (is (= :character-disclosure-authority-unavailable (:reason (ex-data error))))
    (is (= "failed" (:status run)) "The new final authority await must not leave a running run")
    (is (empty? @logs*)) (is (empty? @prompts*))
    (is (= 1 (count (filter #(= "run_failed" (:type %)) (:events run)))))
    (is (not (re-find #"PRIVATE_FINAL_AUTHORITY|PRIVATE_FINAL_CREDENTIAL|Discord source|Bluesky observation"
                      (pr-str [run (ex-data error) (str error)]))))))

(defn- final-authority-config [config unavailable*]
  (let [resolve! (:resolve-agent-authority! config)]
    (assoc config :resolve-agent-authority!
           (fn [scope]
             (when @unavailable*
               (throw (ex-info "PRIVATE_FINAL_AUTHORITY" {:credential "PRIVATE_FINAL_CREDENTIAL"})))
             (resolve! scope)))))

(defn- ^:async held-final-authority-refusal! [failure]
  (await (with-actual-character-turn!
          (^:async fn [{:keys [state config request runs* logs* prompts* after-memory*]}]
            (let [entered (runtime-fixture/held-await) release (runtime-fixture/held-await)
                  memory-ready (runtime-fixture/held-await) unavailable* (atom false)
                  config (final-authority-config config unavailable*)]
              (reset! after-memory* #((:release! memory-ready) %))
              (await (with-held-preparation! :session entered release
                       (^:async fn []
                         (let [settled (await (runtime-fixture/with-held-operation!
                                               (capture-turn-refusal! config request) [release]
                                               (^:async fn [pending]
                                                 (is (seq (:hits (await (runtime-fixture/await-held! memory-ready pending)))))
                                                 (is (= :session (await (runtime-fixture/await-held! entered pending))))
                                                 (is (empty? @logs*)) (is (empty? @prompts*))
                                                 (case failure
                                                   :missing-principal (reset! (:contexts* state) {})
                                                   :disabled-contract (swap! (:selected* state) assoc :enabled false)
                                                   :private-exception (reset! unavailable* true)))))]
                           (assert-final-authority-refusal! settled runs* request logs* prompts*))))))))))

(deftest ^:async final-authority-refusal-after-parallel-preparation-settles-failed-without-private-disclosure
  (doseq [failure [:missing-principal :disabled-contract :private-exception]]
    (await (held-final-authority-refusal! failure))))

(deftest ^:async source-only-direct-context-does-not-require-a-memory-permission-at-final-disclosure
  (await (with-actual-character-turn!
          (^:async fn [{:keys [state config request prompts* logs*]}]
            (swap! (:contexts* state) assoc-in [(:actor-id @(:selected* state)) :permissions] [])
            (let [request (assoc-in request [:agent-spec :memory-hydration] {:enabled? false})]
              (is (= "Hermetic answer" (:answer (await (turn/send-agent-turn! :fixture-runtime config request)))))
              (is (= 1 (count @prompts*))) (is (= 1 (count @logs*)))
              (is (str/includes? (first @prompts*) "Discord source"))
              (is (str/includes? (:content (first @logs*)) "Discord source")))))))
