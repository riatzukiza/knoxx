(ns knoxx.backend.extern.trigger-spawn-http-test
  "Real HTTP/trigger/runner admission with private Clio stores and a local provider session."
  (:require [cljs.test :refer [deftest is]]
            [knoxx.backend.domain.condition.builtin :as conditions]
            [knoxx.backend.domain.event.dispatch :as dispatch]
            [knoxx.backend.extern.agent-turn-fixture :as fixture]
            [knoxx.backend.extern.event-queue-fixture :as queue]
            [knoxx.backend.infra.agent.session :as sessions]
            [knoxx.backend.infra.agent.tool-catalog :as catalog]
            [knoxx.backend.infra.agent.turn :as turns]
            [knoxx.backend.infra.auth.authz :as authz]
            [knoxx.backend.infra.http :as http]
            [knoxx.backend.infra.http-server :as server]
            [knoxx.backend.infra.routes.tools :as routes]
            [knoxx.backend.infra.stores.mongo-session-store :as threads]
            [knoxx.backend.infra.stores.session-titles :as titles]
            [knoxx.backend.infra.stores.session-store-registry :as registry]
            [knoxx.backend.runtime.state :as runtime-state]
            [knoxx.backend.shape.agent :as agent]
            [knoxx.backend.shape.run-directory :as runs]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(defn- provider-session
  []
  (reify agent/IAgentSession
    (set-thinking-level! [_ _level] nil)
    (messages [_] [])
    (streaming? [_] false)
    (current-turn [_] nil)
    (subscribe! [_ _handler] (fn []))
    (send-user-message! [_ _content] (throw (ex-info "Prompt seam must remain local" {})))
    (follow-up! [_ _message] (throw (ex-info "No follow-up in smoke" {})))
    (steer! [_ _message] (throw (ex-info "No steering in smoke" {})))
    (abort! [_] nil)))

(defn- native-route!
  [app method url options]
  (.route app (js/Object.assign #js {:method method :url url} options)))

(defn- guard!
  [request reply done]
  (if (= "fixture-local-admission" (aget request "headers" "authorization"))
    (do (aset request "ctx" {:permissions ["org.events.control"]}) (done))
    (http/json-response! reply 401 {:detail "Fixture authentication required"})))

(deftest ^:async http-trigger-creates-an-admitted-agent-session
  (let [directory (fs/mkdtempSync (path/join (os/tmpdir) "knoxx-trigger-spawn-"))
        contracts (path/join directory "contracts")
        app (server/create-app!)
        previous-runtime @runtime-state/runtime*
        previous-sessions @sessions/sessions*
        prompted* (atom 0)]
    (try
      (fs/cpSync (path/resolve "test/fixtures/trigger-contracts") contracts #js {:recursive true})
      ;; Private seeded trigger: manual fire carries a keyword that its real condition admits.
      (fs/writeFileSync (path/join contracts "triggers/ussyverse_social_replies_event.edn")
                        (pr-str {:contract/kind :trigger :contract/id "ussyverse_social_replies_event"
                                 :enabled true :trigger/kind :event :trigger/listener "discord_automation"
                                 :trigger/events [:discord.message] :trigger/action :actions/start-agent-session
                                 :trigger/agent "ussyverse_social_replies" :trigger/task "Local spawn proof"
                                 :data {:context {:content "frankie"}}}))
      (conditions/register-builtins!)
      (dispatch/reset-dedup!)
      (reset! runtime-state/runtime* #js {})
      (routes/register-trigger-fire-route!
       app @runtime-state/runtime* {:contracts-dir contracts :workspace-root directory}
       {:route! native-route! :session-guard guard! :ensure-permission! authz/ensure-permission!
        :json-response! http/json-response! :error-response! http/error-response!})
      (await (server/listen! app "127.0.0.1" 0))
      (let [port (aget (.address (.-server app)) "port")
            url (str "http://127.0.0.1:" port "/api/admin/triggers/ussyverse_social_replies_event/fire")]
        (await
         (queue/with-queue!
          (^:async fn []
            (let [before (await (runs/list-runs @registry/session-store* {:all? true}))]
              (is (= 401 (.-status (await (js/fetch url #js {:method "POST"})))))
              (is (= before (await (runs/list-runs @registry/session-store* {:all? true}))))
              ;; Production HTTP route, trigger dispatch, action, runner and persistence stay real.
              ;; Only provider construction/hydration/prompt seams are local and non-billable.
              (with-redefs [sessions/create-session-manager!
                            (fn ([_ _ _ _ _ _ _] (provider-session))
                                ([_ _ _ _ _ _ _ _] (provider-session)))
                            catalog/visible-session-signature (fn [& _args] "local-smoke")
                            titles/maybe-prime-session-title! (fn [& _args] nil)
                            turns/hydrate-and-materialize!
                            (^:async fn [runtime config context _parts]
                              [nil nil []
                               (await (sessions/ensure-agent-session!
                                       runtime config (:conversation-id context) (:model-id context)
                                       (:auth-context context) (:thinking-level context) (:session-id context)
                                       (:agent-spec context) (:startup-owner context)))])
                            turns/prompt-and-await! (fixture/prompt-stub #(swap! prompted* inc))]
                (let [response (await (js/fetch url #js {:method "POST"
                                                         :headers #js {:authorization "fixture-local-admission"}}))
                      body (js->clj (await (.json response)) :keywordize-keys true)]
                  (is (= 202 (.-status response)) (pr-str body))
                  (is (= ["ussyverse_social_replies_event"] (:matchedTriggers body))))
                (await (queue/wait-idle!)))
              (let [created (filterv #(not= "queue-fixture-seed" (:run_id %))
                                     (await (runs/list-runs @registry/session-store* {:all? true})))
                    run (first created)]
                (is (= 1 (count created)))
                (is (= 1 @prompted*))
                (is (some? (sessions/active-agent-session (:conversation_id run))))
                (is (some? (await (threads/get-session (:session_id run))))))
              (println "INFO checks cover HTTP401/202, trigger dispatch, real runner admission, durable Clio run/thread and active agent session; provider prompting local."))))))
      (finally
        (await (server/close! app))
        (reset! runtime-state/runtime* previous-runtime)
        (reset! sessions/sessions* previous-sessions)
        (dispatch/reset-dedup!)
        (fs/rmSync directory #js {:recursive true :force true})))))
