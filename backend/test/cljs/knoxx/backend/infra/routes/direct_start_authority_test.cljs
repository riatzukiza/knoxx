(ns knoxx.backend.infra.routes.direct-start-authority-test
  (:require [cljs.test :refer [deftest is async]]
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
