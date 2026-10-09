(ns knoxx.backend.character.membership-policy-route-test
  (:require [cljs.test :refer [deftest is async]]
            [knoxx.backend.infra.auth.authz :as authz]
            [knoxx.backend.infra.db.policy :as policy]
            [knoxx.backend.infra.http :as http]
            [knoxx.backend.infra.routes.users.admin :as admin]))

(def ^:private policy-path "/api/admin/memberships/:membershipId/tool-policies")

(defn- captured-handler [context]
  (let [routes* (atom {})
        handlers {:route! (fn [_ method path handler]
                            (swap! routes* assoc [method path] handler))
                  :policy-db (fn [_] {:pool :isolated-policy-fixture})
                  :with-request-context! (fn [_ _ _ f] (f context))
                  :ensure-org-scope! authz/ensure-org-scope!
                  :policy-db-promise (fn [_ _ _ promise] promise)
                  :http-error http/http-error}]
    (#'admin/register-membership-policy-routes! nil {} handlers)
    (get @routes* ["PATCH" policy-path])))

(defn- request []
  #js {:params #js {:membershipId "creator-member"}
       :body #js {:toolPolicies #js [#js {"tool-id" "bluesky.timeline"
                                         :effect "allow"}]}})

(def ^:private scoped-operator
  {:org {:id "fixture-org"} :permissions ["org.user_policy.update"]
   :role-slugs ["policy-operator"]})

(defn- ^:async invoke-handler! [context membership writes*]
  (with-redefs [policy/get-membership!
                (fn [pool id]
                  (is (= :isolated-policy-fixture pool))
                  (is (= "creator-member" id))
                  (js/Promise.resolve {:membership membership}))
                policy/set-membership-tool-policies!
                (fn [pool id policies]
                  (swap! writes* conj {:pool pool :id id :policies policies})
                  (js/Promise.resolve {:updated true}))]
    (await ((captured-handler context) (request) #js {}))))

(deftest membership-policy-patch-preserves-scoped-operator-boundary
  (async done
    ((^:async fn []
       (try
         (let [membership {:id "creator-member" :orgId "fixture-org"}
               writes* (atom [])
               result (await (invoke-handler! scoped-operator membership writes*))]
           (is (= {:updated true} result))
           (is (= [{:pool :isolated-policy-fixture :id "creator-member"
                    :policies [{:tool-id "bluesky.timeline" :effect "allow"}]}]
                  @writes*) "The existing camelCase API body reaches only its selected member")
           (doseq [[context expected-code]
                   [[(assoc scoped-operator :org {:id "another-org"}) "org_scope_denied"]
                    [(assoc scoped-operator :permissions []) "permission_denied"]]]
             (reset! writes* [])
             (try
               (await (invoke-handler! context membership writes*))
               (is false "A denied policy operator reached the writer")
               (catch :default error
                 (is (= expected-code (:code (ex-data error))))))
             (is (empty? @writes*) "Denied scope or permission must have no write effect"))
           (reset! writes* [])
           (is (= {:updated true}
                  (await (invoke-handler! {:org {:id "another-org"}
                                           :role-slugs ["system_admin"]}
                                          membership writes*)))
               "Existing system-admin permission and organization handling is preserved")
           (is (= 1 (count @writes*)))
           (reset! writes* [])
           (try
             (await (invoke-handler! scoped-operator nil writes*))
             (is false "Missing membership must be refused")
             (catch :default error
               (is (= "membership_not_found" (:code (ex-data error))))))
           (is (empty? @writes*)))
         (catch :default error (is false (str error)))
         (finally (done)))))))

(defn- captured-roles-handler [context]
  (let [routes* (atom {})
        handlers {:route! (fn [_ method path handler]
                            (swap! routes* assoc [method path] handler))
                  :policy-db (fn [_] {:pool :isolated-policy-fixture})
                  :with-request-context! (fn [_ _ _ f] (f context))
                  :ensure-org-scope! authz/ensure-org-scope!
                  :policy-db-promise (fn [_ _ _ promise] promise)
                  :http-error http/http-error}]
    (#'admin/register-membership-routes! nil {} handlers)
    (get @routes* ["PATCH" "/api/admin/memberships/:membershipId/roles"])))

(defn- ^:async invoke-roles-handler! [context membership writes*]
  (with-redefs [policy/get-membership!
                (fn [pool id]
                  (is (= :isolated-policy-fixture pool))
                  (is (= "creator-member" id))
                  (js/Promise.resolve {:membership membership}))
                policy/set-membership-roles-for-context!
                (fn [db id roles]
                  (swap! writes* conj {:db db :id id :roles roles})
                  (js/Promise.resolve {:updated true}))]
    (await ((captured-roles-handler context)
            #js {:params #js {:membershipId "creator-member"}
                 :body #js {:orgId "fixture-org" :roleSlugs #js ["creator"]}}
            #js {}))))

(deftest membership-role-patch-preserves-camelcase-scope
  (async done
    ((^:async fn []
       (try
         (let [context {:org {:id "fixture-org"} :permissions ["org.members.update"]}
               membership {:id "creator-member" :orgId "fixture-org"}
               writes* (atom [])
               result (await (invoke-roles-handler! context membership writes*))]
           (is (= {:updated true} result))
           (is (= [{:db {:pool :isolated-policy-fixture} :id "creator-member"
                    :roles {:org-id "fixture-org" :role-ids [] :role-slugs ["creator"]
                            :actor-id nil :replace true}}]
                  @writes*) "The registered role route accepts the actual membership codec"))
         (catch :default error (is false (str error)))
         (finally (done)))))))

(deftest membership-role-patch-retains-denials-and-admin-handling
  (async done
    ((^:async fn []
       (try
         (let [context {:org {:id "fixture-org"} :permissions ["org.members.update"]}
               membership {:id "creator-member" :orgId "fixture-org"}
               writes* (atom [])]
           (doseq [[current row expected-code]
                   [[(assoc context :org {:id "another-org"}) membership "org_scope_denied"]
                    [(assoc context :permissions []) membership "permission_denied"]
                    [context nil "membership_not_found"]]]
             (reset! writes* [])
             (try
               (await (invoke-roles-handler! current row writes*))
               (is false "A refused role update reached the writer")
               (catch :default error
                 (is (= expected-code (:code (ex-data error))))))
             (is (empty? @writes*) "Refused role updates must have no write effect"))
           (is (= {:updated true}
                  (await (invoke-roles-handler! {:org {:id "another-org"}
                                                :role-slugs ["system_admin"]}
                                               membership writes*))))
           (is (= 1 (count @writes*))))
         (catch :default error (is false (str error)))
         (finally (done)))))))
