(ns knoxx.backend.extern.app-routes-fixture
  "Inert named Fastify, agent, clock, and persistence ports for app routes."
  (:require [knoxx.backend.domain.action.run-state :as run-state]
            [knoxx.backend.domain.voice.turn-control :as turn-control]
            [knoxx.backend.infra.agent.hydration :as hydration]
            [knoxx.backend.infra.agent.policy :as policy]
            [knoxx.backend.infra.agent.service :as service]
            [knoxx.backend.infra.agent.turn :as turn]
            [knoxx.backend.infra.auth.authz :as authz]
            [knoxx.backend.infra.clients.openplanner :as openplanner]
            [knoxx.backend.infra.clients.proxx :as proxx]
            [knoxx.backend.infra.http :as http]
            [knoxx.backend.infra.routes.app :as app]
            [knoxx.backend.infra.stores.mongo-session-store :as sessions]
            [knoxx.backend.infra.tooling :as tooling]
            [knoxx.backend.shape.agent :as agent]))

(defn world
  "Create an isolated call history and configured named responses."
  ([] (world {}))
  ([responses]
   {:responses responses :calls (atom []) :replies (atom [])
    :routes (atom []) :runs (atom {}) :run-order (atom [])}))

(defn calls [world] @(:calls world))
(defn replies [world] @(:replies world))
(defn response [world] (last (replies world)))
(defn operations [world names]
  (filterv #(contains? names (first %)) (calls world)))
(defn native [value] (clj->js value))
(defn error [] (js/Error. "owned synthetic app-route failure"))

(defn- invoke-port [world name fallback args]
  (swap! (:calls world) conj (into [name] args))
  (let [value (get (:responses world) name fallback)]
    (if (fn? value) (apply value args) value)))

(defn- port
  ([world name fallback] (port world name fallback false))
  ([world name fallback asynchronous?]
  (let [call (fn [args]
               (let [value (invoke-port world name fallback args)]
                 (if asynchronous? (js/Promise.resolve value) value)))]
    (fn
      ([] (call []))
      ([a] (call [a]))
      ([a b] (call [a b]))
      ([a b c] (call [a b c]))
      ([a b c d] (call [a b c d]))
      ([a b c d e] (call [a b c d e]))
      ([a b c d e f] (call [a b c d e f]))))))

(defn- respond! [world _reply status body]
  (swap! (:replies world) conj {:status status :body body})
  body)

(defn dependencies
  "Capture a real defroute declaration and preserve its real handler body."
  [world ctx]
  (assoc app/deps
         :route! (fn [_app method path handler]
                   (swap! (:routes world) conj {:method method :path path :handler handler}))
         :json-response! (partial respond! world)
         :error-response! (fn [_reply _error status]
                            (respond! world nil status {:synthetic-error true}))
         :ensure-permission! (port world :permission nil)
         :with-request-context! (fn [_runtime _request _reply handler] (handler ctx))))

(defn ^:async invoke!
  "Invoke one actual factory using a native request, without starting Fastify."
  [world factory runtime config ctx request]
  (factory :app runtime config (dependencies world ctx))
  (let [handler (:handler (last @(:routes world)))]
    (await (handler (native (merge {:params {} :query {} :body {} :raw {:url "/"}}
                                   request)) :reply))))

(defn ^:async caught [task]
  (try {:failed? false :value (await (task))}
       (catch :default failure {:failed? true :error failure})))

(defn ^:async with-clock!
  "Restore the exact native Date.now descriptor even when an assertion throws."
  [now task]
  (let [descriptor (js/Object.getOwnPropertyDescriptor js/Date "now")]
    (try
      (js/Object.defineProperty js/Date "now" #js {:configurable true :value (fn [] now)})
      (await (task))
      (finally (js/Object.defineProperty js/Date "now" descriptor)))))

(defn- ^:async with-agent-ports! [world task]
  (with-redefs [hydration/settings-state* (atom {:llmModel "settings-model"})
                policy/validate-chat-policy! (port world :validate nil)
                service/send-agent-turn! (port world :send {:ok true :turn "turn"})
                service/queue-agent-control! (port world :control {:ok true})
                service/active-agent-session (port world :agent-session nil)
                service/resume-recovered-session! (port world :resume nil)
                agent/streaming? (port world :streaming false)
                agent/current-turn (port world :current-turn nil)
                turn/ensure-session-id (port world :session-id (fn [provided] (or provided "generated-session")))
                turn/ensure-conversation-access! (port world :conversation-access nil)
                turn-control/active-turn (port world :active-turn nil)
                turn-control/unregister-active-turn! (port world :unregister nil)
                turn-control/abort-active-turn! (port world :abort {:ok true})
                tooling/default-actor-id (port world :default-actor "default-actor")
                tooling/default-agent-contract-id (port world :default-contract "default-contract")
                tooling/effective-agent-contract (port world :contract {:id "resolved-contract" :model "contract-model"})
                tooling/agent-contract-catalog (port world :catalog [])
                tooling/actor-catalog (port world :actors [])
                tooling/email-enabled? (port world :email false)]
    (await (task))))

(defn- ^:async with-session-ports! [world task]
  (with-redefs [run-state/runs* (:runs world)
                run-state/run-order* (:run-order world)
                run-state/get-run-events-since (port world :events [])
                run-state/update-run! (port world :update-run nil)
                sessions/get-session (port world :session nil)
                sessions/active-session-snapshots (port world :snapshots [])
                sessions/list-active-sessions (port world :sessions [])
                sessions/complete-session! (port world :complete nil)
                sessions/update-session! (port world :update-session nil)
                sessions/undo-session-turns! (port world :undo nil)
                authz/policy-db (port world :policy-db nil)
                authz/run-visible? (port world :visible (fn [_ctx run] run))]
    (await (task))))

(defn- ^:async with-http-ports! [world task]
  (with-redefs [http/json-response! (partial respond! world)
                http/error-response! (port world :error-response
                                          (fn [_reply _failure status]
                                            (respond! world nil status {:synthetic-error true})))
                http/forward-knoxx-request! (port world :forward {:status 201 :body {:proxied true}} true)
                http/send-fetch-response! (fn [reply value]
                                            (respond! world reply (:status value) (:body value)))
                http/fetch-json (port world :fetch {:ok true :status 200 :body {:fetched true}} true)
                openplanner/client (port world :openplanner-client :openplanner)
                openplanner/enabled? (port world :openplanner-enabled false)
                openplanner/health! (port world :openplanner-health {:ok true :status 200 :body {:service "openplanner"}} true)
                openplanner/v1-json! (port world :openplanner-v1 {:upstream true} true)
                openplanner/mongo-query! (port world :mongo-query {:rows []} true)
                proxx/client (port world :proxx-client :proxx)
                proxx/health! (port world :proxx-health {:ok true :status 200 :body {:service "proxx"}} true)]
    (await (task))))

(defn ^:async with-ports!
  "Hold named port bindings through awaited code and restore them on every exit."
  [world task]
  (await (with-http-ports! world
           (^:async fn []
             (await (with-agent-ports! world
                      (^:async fn [] (await (with-session-ports! world task)))))))))
