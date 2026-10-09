(ns knoxx.backend.infra.routes.direct-start-authority-test
  (:require [cljs.test :refer [deftest is async]]
            [knoxx.backend.infra.agent.service :as service]
            [knoxx.backend.infra.auth.authz :as authz]
            [knoxx.backend.infra.routes.app :as app]
            [knoxx.backend.infra.tooling :as tooling]))

(defn- request [actor]
  #js {:body #js {:message "fixture only" :model "fixture-model"
                 :agent_spec #js {:actor_id actor}
                 :auth_context #js {:actorId actor :orgId "payload-org"
                                    :membershipId "payload-member"}}})

(deftest direct-start-requires-server-actor-and-does-not-borrow-payload-authority
  (async done
    ((^:async fn []
       (try
         (let [queued* (atom [])
               server {:actor {:binding "authenticated-actor"}
                       :org {:id "server-org"} :membership {:id "server-member"}}]
           (with-redefs [tooling/effective-agent-contract (fn ([_ _] {}) ([_ _ _] {}))
                         tooling/default-agent-contract-id (fn ([_] nil) ([_ _] nil))
                         tooling/default-actor-id (fn [_] nil)
                         app/queue-direct-start!
                         (fn [_ _ _ context _ body _ _]
                           (swap! queued* conj {:context context :body body}))]
             (doseq [context [nil (assoc server :actor {:binding "another-actor"})
                              (assoc server :actor {:id "authenticated-actor" :binding nil})]]
               (try
                 (await (app/handle-direct-start nil {} #js {} context (request "authenticated-actor")))
                 (is false "Forged or mismatched actor-scoped direct start reached the queue")
                 (catch :default error
                   (is (= 403 (:status (ex-data error))))
                   (is (= :invalid-actor-context (:reason (ex-data error))))))
               (is (empty? @queued*)))
             (await (app/handle-direct-start nil {} #js {} server (request "authenticated-actor")))
             (is (= 1 (count @queued*)))
             (is (= "authenticated-actor" (:actorId (:context (first @queued*)))))
             (is (= (:org server) (:org (:context (first @queued*)))))
             (is (= (:membership server) (:membership (:context (first @queued*)))))
             (is (nil? (:orgId (:context (first @queued*)))))
             (is (nil? (:membershipId (:context (first @queued*)))))))
         (catch :default error (is false (str error)))
         (finally (done)))))))

(defn- ordinary-request [actor]
  #js {:body #js {:message "fixture only" :model "fixture-model"
                 :agent_spec (if (= :omitted actor) #js {} #js {:actor_id actor})
                 :auth_context #js {:actorId "payload-actor" :orgId "payload-org"}
                 :requested_actor_id "payload-actor"}})

(defn- registered-handler [register context]
  (let [handler* (atom nil)
        dependencies {:route! (fn [_ _ _ handler] (reset! handler* handler))
                      :with-request-context! (fn [_ _ _ f] (f context))
                      :ensure-permission! authz/ensure-permission!
                      :json-response! (fn [_ _ result] result)
                      :error-response! (fn [_ error _] (throw error))}]
    (register nil nil {} dependencies)
    @handler*))

(def ^:private routes
  [app/api-knoxx-chat! app/api-knoxx-direct!
   app/api-knoxx-chat-start! app/api-knoxx-direct-start!])

(def ^:private authenticated-context
  {:actor {:binding "authenticated-actor"} :permissions ["agent.chat.use"]
   :org {:id "server-org"} :membership {:id "server-member"}})

(defn- record-turn! [effects* body]
  (swap! effects* conj body)
  (js/Promise.resolve {:ok true}))

(defn- reply []
  (let [reply #js {}]
    (aset reply "code" (fn [_] reply))
    (aset reply "type" (fn [_] reply))
    (aset reply "send" (fn [body] body))
    reply))

(defn- ^:async invoke-ordinary! [register context actor effects*]
  (with-redefs [tooling/effective-agent-contract (fn ([_ _] {}) ([_ _ _] {}))
                tooling/default-agent-contract-id (fn ([_] nil) ([_ _] nil))
                tooling/default-actor-id (fn [_] "server-default")
                service/send-agent-turn! (fn [_ _ body] (record-turn! effects* body))
                app/queue-chat-start! (fn [_ _ _ _ _ body _] (record-turn! effects* body))
                app/queue-direct-start! (fn [_ _ _ _ _ body _ _] (record-turn! effects* body))]
    (await ((registered-handler register context) (ordinary-request actor) (reply)))))

(deftest omitted-actor-keeps-ordinary-route-defaults-and-authenticated-principal
  (async done
    ((^:async fn []
       (try
         (doseq [register routes
                 context [authenticated-context nil]
                 actor [:omitted nil " "]]
           (let [effects* (atom [])]
             (try
               (await (invoke-ordinary! register context actor effects*))
               (is (= 1 (count @effects*)))
               (is (= "server-default" (get-in (first @effects*) [:agent-spec :actor-id])))
               (is (= (:actor context) (get-in (first @effects*) [:auth-context :actor])))
               (is (= (:org context) (get-in (first @effects*) [:auth-context :org])))
               (is (nil? (get-in (first @effects*) [:auth-context :actorId])))
               (is (nil? (get-in (first @effects*) [:auth-context :orgId])))
               (catch :default error
                 (is false (str "Ordinary request was denied before its owned effect: " error))))))
         (finally (done)))))))

(deftest explicit-actor-retains-all-four-route-binding-guards
  (async done
    ((^:async fn []
       (try
         (doseq [register routes]
           (let [effects* (atom [])]
             (await (invoke-ordinary! register authenticated-context "authenticated-actor" effects*))
             (is (= 1 (count @effects*)))
             (is (= "authenticated-actor" (get-in (first @effects*) [:auth-context :actorId])))
             (doseq [context [nil authenticated-context
                              (assoc authenticated-context :actor {:id "another-actor" :binding nil})]]
               (reset! effects* [])
               (try
                 (await (invoke-ordinary! register context "another-actor" effects*))
                 (is false "An explicit unbound actor reached an ordinary route effect")
                 (catch :default error
                   (is (= 403 (:status (ex-data error))))
                   (is (= :invalid-actor-context (:reason (ex-data error))))))
               (is (empty? @effects*)))))
         (catch :default error (is false (str error)))
         (finally (done)))))))
