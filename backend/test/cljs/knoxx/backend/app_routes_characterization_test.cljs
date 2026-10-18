(ns knoxx.backend.app-routes-characterization-test
  (:require [cljs.test :refer [deftest is]]
            [knoxx.backend.domain.error-observatory :as observatory]
            [knoxx.backend.extern.app-routes-fixture :as fixture]
            [knoxx.backend.extern.memory-sessions-fixture :as memory-fixture]
            [knoxx.backend.infra.routes.app :as app]))

(def member {:org-id "org" :roleSlugs ["worker"]
             :toolPolicies [{:toolId "read" :effect "allow"}
                            {:toolId "write" :effect "deny"}]})
(def config {:knoxx-base-url "http://knoxx.invalid" :project-name "project"
             :collection-name "collection" :ingestion-base-url "http://ingestion.invalid"})
(defn- body [world] (:body (fixture/response world)))
(defn- sent [world] (last (last (fixture/operations world #{:send}))))
(defn- ^:async route! [world factory ctx request]
  (await (fixture/invoke! world factory {} config ctx request)))

(deftest ^:async health-refuses-unconfigured-dependencies-without-contacting-them
  (let [w (fixture/world)]
    (await (fixture/with-ports! w
             (^:async fn []
               (await (route! w app/health! nil {}))
               (is (= 503 (:status (fixture/response w))))
               (is (= "unhealthy" (:status (body w))))
               (is (= false (get-in (body w) [:dependencies :proxx :configured])))
               (is (empty? (fixture/operations w #{:fetch :proxx-health :openplanner-health}))))))))

(deftest ^:async knoxx-health-allows-one-configured-healthy-dependency
  (let [w (fixture/world)]
    (await (fixture/with-ports! w
             (^:async fn []
               (await (app/send-knoxx-health! (assoc config :ollama-base-url "http://ollama.invalid///") :reply))
               (is (= 200 (:status (fixture/response w))))
               (is (true? (:reachable (body w))))
               (is (= [[:fetch "http://ollama.invalid/api/version" {:headers {} :method "GET"}]]
                      (fixture/operations w #{:fetch}))))))))

(deftest ^:async health-requires-proxx-and-openplanner-but-ollama-is-optional
  (let [w (fixture/world {:openplanner-enabled true})]
    (await (fixture/with-ports! w
             (^:async fn []
               (await (fixture/invoke! w app/health! {}
                         (assoc config :proxx-base-url "http://proxx.invalid" :proxx-auth-token "synthetic") nil {}))
               (is (= 200 (:status (fixture/response w))))
               (is (= #{:proxx-health :openplanner-health}
                      (set (map first (fixture/operations w #{:proxx-health :openplanner-health}))))))))))

(deftest ^:async configured-but-unreachable-dependency-is-a-real-503
  (let [w (fixture/world {:fetch {:ok false :status 503 :body {:down true}}})]
    (await (fixture/with-ports! w
             (^:async fn []
               (await (app/send-knoxx-health! (assoc config :ollama-base-url "http://ollama.invalid") :reply))
               (is (= 503 (:status (fixture/response w))))
               ;; The existing Ollama health adapter emits :detail; the outer
               ;; dependency projection reads :body. Preserve its nil wire value.
               (is (= {:configured true :reachable false :status_code 503 :detail nil}
                      (get-in (body w) [:details :dependencies :ollama]))))))))

(deftest ^:async config-keeps-feature-flags-and-runtime-defaults-on-the-wire
  (let [w (fixture/world)]
    (await (fixture/with-ports! w
             (^:async fn []
               (await (route! w app/config! nil {}))
               (is (= 200 (:status (fixture/response w))))
               (is (= {:knoxx_enabled true :stt_enabled false :tts_enabled false :ollama_enabled false
                       :proxx_enabled false :rbac_enabled false :default_actor_id "default-actor"
                       :default_agent_contract "default-contract" :proxx_default_model "settings-model"}
                      (select-keys (body w) [:knoxx_enabled :stt_enabled :tts_enabled :ollama_enabled
                                             :proxx_enabled :rbac_enabled :default_actor_id
                                             :default_agent_contract :proxx_default_model]))))))))

(deftest ^:async auth-context-refuses-an-absent-policy-database
  (let [w (fixture/world)]
    (await (fixture/with-ports! w (^:async fn []
             (await (route! w app/api-auth-context! member {}))
             (is (= {:status 503 :body {:detail "Knoxx policy database is not configured"}}
                    (fixture/response w))))))))

(deftest ^:async auth-context-retains-camel-wire-policy-fields
  (let [w (fixture/world {:policy-db :db}) ctx (assoc member :membershipToolPolicies [{:toolId "read" :effect "deny"}])]
    (await (fixture/with-ports! w (^:async fn []
             (await (fixture/invoke! w app/api-auth-context! {:policy-db :db} config ctx {}))
             (is (= (:toolPolicies ctx) (:toolPolicies (body w))))
             (is (= (:membershipToolPolicies ctx) (:membershipToolPolicies (body w))))
             (is (= ["worker"] (:roleSlugs (body w)))))))))

(deftest ^:async catalog-authorizes-before-lookup-and-adds-the-resolved-default-once
  (let [w (fixture/world {:catalog [{:id "z"}] :contract {:id "a"}})]
    (await (fixture/with-ports! w (^:async fn []
             (await (route! w app/api-knoxx-agents-catalog! member {:query {:actorId " requested "}}))
             (is (= :permission (ffirst (fixture/calls w))))
             (is (= "requested" (:actor_id (body w))))
             (is (= ["a" "z"] (mapv :id (:agents (body w))))))))))

(deftest ^:async proxy-factories-preserve-each-http-method-and-wildcard-path
  (doseq [[factory method] [[app/api-knoxx-proxy-get! "GET"] [app/api-knoxx-proxy-post! "POST"]
                             [app/api-knoxx-proxy-put! "PUT"] [app/api-knoxx-proxy-patch! "PATCH"]
                             [app/api-knoxx-proxy-delete! "DELETE"]]]
    (let [w (fixture/world)]
      (await (fixture/with-ports! w (^:async fn []
               (await (route! w factory nil {:params {:* "agents/a"}}))
               (is (= [method "agents/a" nil] (subvec (first (fixture/operations w #{:forward})) 3)))
               (is (= {:status 201 :body {:proxied true}} (fixture/response w)))))))))

(deftest ^:async ingestion-retains-query-string-and-post-body-serialization
  (let [w (fixture/world)]
    (await (fixture/with-ports! w (^:async fn []
             (await (route! w app/api-ingestion-browse! nil {:query {:path "a/b" :limit 2}}))
             (await (route! w app/api-ingestion-jobs-post! nil {:body {:path "a/b"}}))
             (is (= [[:fetch "http://ingestion.invalid/api/ingestion/browse?path=a%2Fb&limit=2" {:method "GET"}]
                     [:fetch "http://ingestion.invalid/api/ingestion/jobs"
                      {:method "POST" :headers {"Content-Type" "application/json"} :body "{\"path\":\"a/b\"}"}]]
                    (fixture/operations w #{:fetch}))))))))

(deftest ^:async unregistered-file-put-refuses-unsafe-path-without-writing
  (let [w (fixture/world)]
    (await (fixture/with-ports! w (^:async fn []
             (await (route! w app/api-ingestion-file-put! nil {:body {:path "../outside" :content "never written"}}))
             (is (= {:status 400 :body {:detail "Invalid or unsafe file path"}} (fixture/response w))))))))

(deftest ^:async data-get-preserves-raw-query-and-policy-disabled-post-compatibility
  (let [w (fixture/world)]
    (await (fixture/with-ports! w (^:async fn []
             (await (route! w app/api-data-op-get! nil {:params {:* "documents"} :raw {:url "/op/documents?q=a%2Fb"}}))
             (await (route! w app/api-data-op-post! nil {:params {:* "search/vector"} :body {:query "q"}}))
             (let [calls (fixture/operations w #{:openplanner-v1})]
               (is (= ["GET" "documents?q=a%2Fb" nil] (subvec (first calls) 2)))
               (is (= ["POST" "search/vector"] (subvec (second calls) 2 4)))))))))

(deftest ^:async authenticated-encoded-vector-search-refuses-before-upstream
  (let [w (fixture/world)]
    (await (fixture/with-ports! w (^:async fn []
             (let [result (await (fixture/caught #(route! w app/api-data-op-post! member
                                                    {:params {:* "v1/%73earch/%2576ector"} :body {:query "q"}})))]
               (is (:failed? result))
               (is (empty? (fixture/operations w #{:openplanner-v1})))))))))

(deftest ^:async mongo-events-replace-caller-tenant-before-dependency-query
  (let [w (fixture/world)]
    (await (fixture/with-ports! w (^:async fn []
             (await (route! w app/api-data-mongo-query! member
                      {:body {:collection "events" :filter {:extra.org_id "other" :type "resource"}}}))
             (is (= [:permission member "org.datalakes.read"] (first (fixture/calls w))))
             (is (= {:collection "events" :filter {:extra.org_id "org" :type "resource"}}
                    (last (first (fixture/operations w #{:mongo-query}))))))))))

(deftest ^:async removed-postgres-surfaces-keep-explicit-410-without-querying
  (doseq [factory [app/api-data-pg-tables! app/api-data-pg-query!]]
    (let [w (fixture/world)]
      (await (fixture/with-ports! w (^:async fn []
               (await (route! w factory nil {}))
               (is (= 410 (:status (fixture/response w))))
               (is (= "pg_removed" (:error (body w))))
               (is (empty? (fixture/calls w)))))))))

(deftest ^:async chat-and-direct-retain-mode-and-contract-default-precedence
  (doseq [[factory mode] [[app/api-knoxx-chat! "rag"] [app/api-knoxx-direct! "direct"]]]
    (let [w (fixture/world)]
      (await (fixture/with-ports! w (^:async fn []
               (await (route! w factory member {:body {:message "hello" :agentSpec {:model "requested-model"}}}))
               (is (= 200 (:status (fixture/response w))))
               (is (= mode (:mode (sent w))))
               (is (= "requested-model" (get-in (sent w) [:agent-spec :model])))
               (is (= "resolved-contract" (get-in (sent w) [:agent-spec :contract-id])))
               (is (= "default-actor" (get-in (sent w) [:agent-spec :actor-id])))))))))

(deftest ^:async member-cannot-request-unowned-tool-or-role-but-retains-resource-pin
  (let [w (fixture/world)]
    (await (fixture/with-ports! w (^:async fn []
             (await (route! w app/api-knoxx-direct! member
                      {:body {:agentSpec {:role "system-admin" :toolPolicies [{:toolId "read"} {:toolId "write"}]
                                          :resourcePolicies [{:document "pinned"}]}}}))
             (is (= [{:toolId "read" :effect "allow"}] (get-in (sent w) [:auth-context :toolPolicies])))
             (is (= ["worker"] (get-in (sent w) [:auth-context :roleSlugs])))
             (is (= [{:document "pinned"}] (get-in (sent w) [:auth-context :resourcePolicies]))))))))

(deftest ^:async system-admin-and-trusted-local-requests-retain-existing-policy-override
  (doseq [ctx [nil (assoc member :roleSlugs ["system-admin"])]]
    (let [w (fixture/world)]
      (await (fixture/with-ports! w (^:async fn []
               (await (route! w app/api-knoxx-direct! ctx
                        {:body {:agentSpec {:role "operator" :toolPolicies [{:toolId "write"}]}}}))
               (is (= [{:toolId "write" :effect "allow"}] (get-in (sent w) [:auth-context :toolPolicies])))
               (is (= ["operator"] (get-in (sent w) [:auth-context :roleSlugs])))))))))

(deftest ^:async queue-policy-failure-returns-429-and-never-sends
  (let [w (fixture/world {:validate (fn [_ctx _model] (throw (fixture/error)))})]
    (await (fixture/with-ports! w (^:async fn []
             (await (app/queue-chat-start! :runtime config :reply member "model" {:message "q"} {:queued true}))
             (is (= 429 (:status (fixture/response w))))
             (is (= [[:validate member "model"]] (fixture/operations w #{:validate :send}))))))))

(deftest ^:async queued-chat-retains-both-wire-aliases-and-generated-identities
  (let [w (fixture/world)]
    (await (fixture/with-ports! w (^:async fn []
             (await (route! w app/api-knoxx-chat-start! member {:body {:message "hello"}}))
             (is (= 202 (:status (fixture/response w))))
             (is (= "generated-session" (:session_id (body w)) (:sessionId (body w))))
             (is (= (:run_id (body w)) (:runId (body w)) (:run-id (sent w))))
             (is (uuid? (uuid (:run_id (body w)))))
             (is (= (:conversation_id (body w)) (:conversationId (body w)) (:conversation-id (sent w))))
             (is (= ["contract-model"] (mapv last (fixture/operations w #{:validate})))))))))

(deftest ^:async queued-direct-retains-provided-identities-and-snake-only-wire
  (let [w (fixture/world)]
    (await (fixture/with-ports! w (^:async fn []
             (await (route! w app/api-knoxx-direct-start! member
                      {:body {:sessionId "s" :conversationId "c" :runId "r" :model "request-model"}}))
             (is (= {:ok true :queued true :run_id "r" :conversation_id "c" :session_id "s" :model "request-model"}
                    (body w)))
             (is (= ["request-model"] (mapv last (fixture/operations w #{:validate})))))))))

(deftest ^:async active-stream-conflict-prevents-policy-and-send
  (let [w (fixture/world {:session {:status "completed"} :agent-session :native-agent :streaming true})]
    (await (fixture/with-ports! w (^:async fn []
             (await (route! w app/api-knoxx-chat-start! member {:body {:sessionId "s" :conversationId "c"}}))
             (is (= 409 (:status (fixture/response w))))
             (is (= "agent-already-processing" (:code (body w))))
             (is (true? (:has-active-stream (body w))))
             (is (empty? (fixture/operations w #{:validate :send}))))))))

(deftest ^:async zombie-recovery-is-strictly-after-sixty-seconds-and-completes-before-queue
  (doseq [[now recovered?] [[60000 false] [60001 true]]]
    (let [w (fixture/world) queued (atom [])]
      (await (fixture/with-ports! w (^:async fn []
               (await (fixture/with-clock! now (^:async fn []
                        (await (app/detect-zombies "c" {:status "running" :updated_at "1970-01-01T00:00:00.000Z" :messages []}
                                 "s" (fn [label] (swap! queued conj label)) {:can-send false} :reply nil)))))
               (is (= recovered? (boolean (seq @queued))))
               (is (= (if recovered? [:unregister :complete] [:unregister])
                      (mapv first (fixture/operations w #{:unregister :complete}))))
               (when-not recovered? (is (= 409 (:status (fixture/response w)))))))))))

(deftest ^:async runtime-turn-prevents-zombie-recovery-even-with-unknown-time
  (let [w (fixture/world {:active-turn :registered}) queued (atom 0)]
    (await (fixture/with-ports! w (^:async fn []
             (await (app/detect-zombies "c" {:status "running"} "s" (fn [_] (swap! queued inc)) {} :reply nil))
             (is (zero? @queued))
             (is (empty? (fixture/operations w #{:complete})))
             (is (= 409 (:status (fixture/response w)))))))))

(deftest ^:async stale-session-status-requests-nonblocking-recovery-without-completing
  (let [w (fixture/world {:session {:session_id "s" :conversation_id "c" :status "running"}})]
    (await (fixture/with-ports! w (^:async fn []
             (await (route! w app/api-knoxx-session-status! nil {:query {:sessionId "s"}}))
             (is (= 200 (:status (fixture/response w))))
             (is (true? (:recovery_requested (body w))))
             (is (false? (:can_send (body w))))
             (is (= 1 (count (fixture/operations w #{:resume}))))
             (is (empty? (fixture/operations w #{:complete :send}))))))))

(deftest ^:async session-status-distinguishes-missing-id-idle-missing-and-live-runtime
  (doseq [[request responses status expected] [[{} {} 400 nil]
                                              [{:query {:session_id "s"}} {} 200 "not_found"]
                                              [{:query {:session_id "s" :conversation_id "c"}}
                                               {:active-turn :registered} 200 "running"]]]
    (let [w (fixture/world responses)]
      (await (fixture/with-ports! w (^:async fn []
               (await (route! w app/api-knoxx-session-status! nil request))
               (is (= status (:status (fixture/response w))))
               (is (= expected (:status (body w))))))))))

(deftest ^:async controls-authorize-conversation-before-queue-and-retain-actor-lineage
  (doseq [[factory kind permission] [[app/api-knoxx-steer! "steer" "agent.controls.steer"]
                                     [app/api-knoxx-follow-up! "follow_up" "agent.controls.follow_up"]]]
    (let [w (fixture/world)]
      (await (fixture/with-ports! w (^:async fn []
               (await (route! w factory member {:body {:conversationId "c" :actorId " actor " :message "next" :lineage {:parent "p"}}}))
               (is (= [:permission member permission] (first (fixture/calls w))))
               (is (= [:conversation-access (assoc member :actorId "actor") "c"] (second (fixture/calls w))))
               (is (= {:kind kind :conversation-id "c" :actor-id "actor" :message "next" :metadata {:parent "p"}}
                      (select-keys (last (first (fixture/operations w #{:control})))
                                   [:kind :conversation-id :actor-id :message :metadata])))))))))

(deftest ^:async legacy-abort-and-undo-current-map-decoding-refuses-before-mutation
  (doseq [[factory request] [[app/api-knoxx-abort! {:body {:conversation_id "c"}}]
                              [app/api-knoxx-session-undo! {:body {:session_id "s"}}]
                              [app/api-admin-agents-abort! {:body {:run_id "r"}}]]]
    (let [w (fixture/world)]
      (await (fixture/with-ports! w (^:async fn []
               (await (route! w factory member request))
               (is (= 400 (:status (fixture/response w))))
               (is (empty? (fixture/operations w #{:abort :undo :update-run :update-session})))))))))

(deftest ^:async active-runs-filter-visibility-and-preserve-safe-content-projection
  (let [w (fixture/world {:visible (fn [_ctx run] (when (= "allowed" (:run_id run)) run))})]
    (reset! (:runs w) {"allowed" {:run_id "allowed" :session_id "s" :status "running"
                                 :request_messages [{:role "user" :content "hello" :content-parts [{:type "image" :data "private" :url "safe"}]}]}
                       "denied" {:run_id "denied" :status "running"}
                       "done" {:run_id "done" :status "completed"}})
    (reset! (:run-order w) ["denied" "allowed" "done"])
    (await (fixture/with-ports! w (^:async fn []
             (await (route! w app/api-knoxx-agents-active! member {:query {:limit "2"}}))
             (is (= ["allowed"] (mapv :run_id (:runs (body w)))))
             (is (= [{:type "image" :url "safe"}] (get-in (body w) [:runs 0 :content_parts])))
             (is (= "hello" (get-in (body w) [:runs 0 :latest_user_message]))))))))

(deftest ^:async live-summaries-deduplicate-session-overlay-and-sort-before-limit
  (let [w (fixture/world {:sessions [{:session_id "duplicate" :status "running" :updated_at 99}
                                     {:session_id "new" :status "waiting_input" :updated_at 20}
                                     {:session_id "done" :status "completed" :updated_at 100}]})]
    (reset! (:runs w) {"r" {:run_id "r" :session_id "duplicate" :status "running" :updated_at 10}})
    (reset! (:run-order w) ["r"])
    (await (fixture/with-ports! w (^:async fn []
             (is (= ["new" "duplicate"] (mapv :session_id (await (app/live-active-agent-summaries! 5 false)))))
             (is (= ["done"] (mapv :session_id (await (app/live-active-agent-summaries! 1 true))))))))))

(deftest ^:async run-read-distinguishes-not-found-denied-and-filtered-memory
  (doseq [[visibility status] [[nil 403] [{:run_id "r" :safe true} 200]]]
    (let [w (fixture/world {:visible visibility})]
      (reset! (:runs w) {"r" {:run_id "r" :private true}})
      (await (fixture/with-ports! w (^:async fn []
               (await (route! w app/api-knoxx-run-get! member {:params {:runId "missing"}}))
               (is (= 404 (:status (fixture/response w))))
               (await (route! w app/api-knoxx-run-get! member {:params {:runId "r"}}))
               (is (= status (:status (fixture/response w))))
               (when (= status 200) (is (= visibility (:run (body w)))))))))))

(deftest ^:async event-catchup-retains-since-and-blank-run-refusal
  (let [w (fixture/world {:events [{:type "delta"}]})]
    (await (fixture/with-ports! w (^:async fn []
             (await (route! w app/api-knoxx-run-events! member {:params {:runId ""}}))
             (is (= 400 (:status (fixture/response w))))
             (is (empty? (fixture/operations w #{:events})))
             (await (route! w app/api-knoxx-run-events! member {:params {:runId "r"} :query {:since "cursor"}}))
             (is (= [[:events "r" "cursor"]] (fixture/operations w #{:events})))
             (is (= {:run_id "r" :events [{:type "delta"}] :count 1} (body w))))))))

(deftest ^:async shibboleth-absent-and-empty-import-refuse-without-success-wire
  (let [w (fixture/world {:fetch {:ok true :status 200 :body {:session {}}}})]
    (await (fixture/with-ports! w (^:async fn []
             (await (route! w app/api-shibboleth-handoff! nil {}))
             (is (= 503 (:status (fixture/response w))))
             (is (empty? (fixture/operations w #{:fetch})))
             (await (fixture/invoke! w app/api-shibboleth-handoff! {}
                      (assoc config :shibboleth-base-url "http://shib.invalid") nil {:body {:items []}}))
             (is (= 502 (:status (fixture/response w)))))))))

(deftest ^:async shibboleth-handoff-keeps-source-payload-session-id-and-item-count
  (let [w (fixture/world {:fetch {:ok true :status 200 :body {:session {:id "imported"}}}})]
    (await (fixture/with-ports! w (^:async fn []
             (await (fixture/invoke! w app/api-shibboleth-handoff! {}
                      (assoc config :shibboleth-base-url "http://shib.invalid") nil
                      {:body {:model "m" :conversation_id "c" :items [{:role "user" :content "q"}]}}))
             (is (= {:ok true :session_id "imported" :ui_url "" :imported_item_count 1} (body w)))
             (is (= {:source_app "knoxx" :model "m" :system_prompt nil :provider nil :conversation_id "c"
                     :fake_tools_enabled false :items [{:role "user" :content "q"}]}
                    (get-in (first (fixture/operations w #{:fetch})) [2 :json]))))))))

(deftest ^:async current-turn-errors-are-observed-with-the-existing-zombie-fallback
  (let [failure (fixture/error) observer-failure (fixture/error)]
    (doseq [[value recover? observer-mode] [[nil true :ok] [:active false :ok]
                                           [failure true :ok] [failure true :throw-error] [failure true :throw-null]]]
      (let [observed (atom []) queued (atom [])
            w (fixture/world {:agent-session :synthetic-agent
                              :current-turn (fn [_] (if (identical? value failure) (throw failure) value))})]
        (with-redefs [observatory/log-error! (fn [boundary context err]
                                             (swap! observed conj [boundary context err])
                                             (case observer-mode
                                               :throw-error (throw observer-failure)
                                               :throw-null (memory-fixture/throw-nil!)
                                               nil))]
          (await (fixture/with-ports! w (^:async fn []
                   (await (fixture/with-clock! 60001 (^:async fn []
                            (await (app/detect-zombies "c"
                                     {:status "running" :updated_at "1970-01-01T00:00:00.000Z" :messages []}
                                     "s" (fn [label] (swap! queued conj label)) {} :reply nil)))))))))
        (is (= (if (identical? value failure)
                 [["app.session-current-turn" {:phase :clear-ghost-turn} failure]
                  ["app.session-current-turn" {:phase :runtime-processing-session} failure]] []) @observed))
        (is (= recover? (boolean (seq @queued))))
        (is (= (if recover? [:unregister :complete] [])
               (mapv first (fixture/operations w #{:unregister :complete}))))
        (is (= 2 (count (fixture/operations w #{:current-turn}))))
        (when-not recover? (is (= 409 (:status (fixture/response w)))))))))

(deftest ^:async non-error-current-turn-throws-retain-identity-and-do-not-recover
  (let [failure (fixture/native {:synthetic "non-Error"}) observed (atom [])
        w (fixture/world {:agent-session :synthetic-agent :current-turn (fn [_] (throw failure))})
        result (with-redefs [observatory/log-error! (fn [& args] (swap! observed conj args))]
                 (await (fixture/caught (^:async fn []
                          (await (fixture/with-ports! w (^:async fn []
                                   (await (app/detect-zombies "c" {:status "running"} "s" (fn [_] :unexpected) {} :reply nil)))))))))]
    (is (true? (:failed? result)))
    (is (identical? failure (:error result)))
    (is (empty? @observed))
    (is (empty? (fixture/operations w #{:unregister :complete})))
    (is (empty? (fixture/replies w)))))
