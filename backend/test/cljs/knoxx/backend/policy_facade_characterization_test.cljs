(ns knoxx.backend.policy-facade-characterization-test
  (:require [cljs.test :refer [deftest is]]
            [knoxx.backend.extern.policy-facade-fixture :as fixture]
            [knoxx.backend.infra.auth.authz :as authz]
            [knoxx.backend.infra.db.policy :as policy]))

(def active-member
  {:id "member" :user_id "user" :org_id "org" :email "user@example.invalid"
   :display_name "User" :user_status "active" :status "active" :org_status "active"
   :org_slug "org-slug" :org_name "Org" :actor_id nil})

(defn- outcome [task] (fixture/caught task))

(deftest ^:async missing-request-identity-refuses-before-any-store-call
  (let [world (fixture/world)]
    (await (fixture/with-stores! world
             (^:async fn []
               (let [result (await (outcome #(policy/resolve-request-context! nil (fixture/native-value {}))))]
                 (is (:failed? result))
                 (is (= {:message "Missing x-knoxx-user-email or x-knoxx-membership-id"
                         :code "request_context_missing" :status 401}
                        (fixture/error-summary (:error result))))
                 (is (empty? (fixture/calls world)))))))))

(deftest ^:async explicit-membership-header-takes-precedence-over-email-and-org
  (let [world (fixture/world {:membership active-member})]
    (await (fixture/with-stores! world
             (^:async fn []
               (let [context (await (policy/resolve-request-context!
                                    nil (fixture/native-value {"x-knoxx-membership-id" " member "
                                                               "x-knoxx-user-email" "OTHER@EXAMPLE.INVALID"
                                                               "x-knoxx-org-id" "other-org"})))]
                 (is (= [[:membership :db "member"]]
                        (fixture/operations world #{:membership :email-membership})))
                 (is (= {:id "workspace_user" :binding nil} (:actor context)))
                 (is (= "org" (get-in context [:org :id])))))))))

(deftest ^:async email-lookup-normalizes-case-and-retains-both-org-selectors
  (let [world (fixture/world {:email-membership active-member})]
    (await (fixture/with-stores! world
             (^:async fn []
               (await (policy/resolve-request-context!
                       nil (fixture/native-value {"x-knoxx-user-email" " USER@EXAMPLE.INVALID "
                                                  "x-knoxx-org-id" " org "
                                                  "x-knoxx-org-slug" " ORG-SLUG "})))
               (is (= [[:email-membership :db {:user-email "user@example.invalid"
                                               :org-id "org" :org-slug "org-slug"}]]
                      (fixture/operations world #{:membership :email-membership}))))))))

(deftest ^:async inactive-and-unresolved-identities-stop-before-role-hydration
  (doseq [[row status code] [[nil 401 "request_context_unresolved"]
                            [(assoc active-member :user_status "disabled") 403 "user_inactive"]
                            [(assoc active-member :status "disabled") 403 "membership_inactive"]
                            [(assoc active-member :org_status "disabled") 403 "org_inactive"]]]
    (let [world (fixture/world {:membership row})]
      (await (fixture/with-stores! world
               (^:async fn []
                 (let [result (await (outcome #(policy/resolve-request-context!
                                               nil (fixture/native-value {"x-knoxx-membership-id" "member"}))))]
                   (is (:failed? result))
                   (is (= [status code] ((juxt :status :code) (fixture/error-summary (:error result)))))
                   (is (empty? (fixture/operations world #{:member-roles :member-tools :roles-by-ids}))))))))))

(deftest ^:async request-context-keeps-explicit-binding-role-priority-and-membership-policy-override
  (let [world (fixture/world
               {:member-roles [{:membership_id "member" :role_id "worker" :slug "knowledge-worker"}
                               {:membership_id "member" :role_id "admin" :slug "system-admin"}]
                :roles-by-ids [{:id "worker" :slug "knowledge-worker"} {:id "admin" :slug "system-admin"}]
                :permissions [{:role_id "worker" :code "read"} {:role_id "admin" :code "read"}
                              {:role_id "admin" :code "write"}]
                :role-tools [{:role_id "worker" :tool_id "read" :effect "allow"}
                             {:role_id "admin" :tool_id "read" :effect "deny"}]
                :member-tools [{:membership_id "member" :tool_id "read" :effect "allow"}]})]
    (await (fixture/with-stores! world
             (^:async fn []
               (let [context (await (policy/build-request-context nil (assoc active-member :actor_id " bound-actor ")))]
                 (is (= {:id "bound-actor" :binding "bound-actor"} (:actor context)))
                 (is (= ["system-admin" "knowledge-worker"] (:role-slugs context)))
                 (is (= ["read" "write"] (:permissions context)))
                 (is (= [{:tool-id "read" :effect "allow" :constraints {}}] (:tool-policies context)))
                 (is (true? (:is-system-admin context)))))))))

(deftest ^:async role-deny-wins-independent-of-store-order-without-membership-override
  (doseq [effects [["deny" "allow"] ["allow" "deny"]]]
    (let [world (fixture/world
                 {:member-roles [{:membership_id "member" :role_id "role" :slug "developer"}]
                  :roles-by-ids [{:id "role" :slug "developer"}]
                  :role-tools (mapv #(hash-map :role_id "role" :tool_id "read" :effect %) effects)})]
      (await (fixture/with-stores! world
               (^:async fn []
                 (let [context (await (policy/build-request-context nil active-member))]
                   (is (= ["deny"] (mapv :effect (:tool-policies context)))))))))))

(deftest ^:async role-alias-resolution-preserves-org-query-and-additive-assignment
  (let [world (fixture/world {:roles-by-slugs [{:id "role" :slug "knowledge_worker"}]})]
    (await (fixture/with-stores! world
             (^:async fn []
               (await (policy/set-membership-roles! nil "member"
                         {:org-id "org" :role-ids ["existing"] :role-slugs [" knowledge-worker "] :replace false}))
               (is (= [[:roles-by-slugs :db ["knowledge-worker" "knowledge_worker"] "org"]]
                      (fixture/operations world #{:roles-by-slugs})))
               (is (= [[:assign-roles :db "member" false ["existing" "role"]]]
                      (fixture/operations world #{:assign-roles}))))))))

(deftest ^:async unknown-role-refuses-before-assignment
  (let [world (fixture/world)]
    (await (fixture/with-stores! world
             (^:async fn []
               (let [result (await (outcome #(policy/set-membership-roles!
                                             nil "member" {:org-id "org" :role-slugs ["missing"]})))]
                 (is (:failed? result))
                 (is (= "Role not found for slug(s): missing" (:message (fixture/error-summary (:error result)))))
                 (is (empty? (fixture/operations world #{:assign-roles})))))))))

(deftest ^:async tool-definitions-precede-policy-storage-and-constraints-stay-json
  (let [world (fixture/world)]
    (await (fixture/with-stores! world
             (^:async fn []
               (await (policy/set-role-tool-policies! nil "role"
                         ["read" {:tool_id "discord_send" :effect "deny" :constraints {:channel "one"}}]))
               (is (= [[:ensure-tools :db ["read" "discord.send"]]
                       [:set-role-tools :db "role"
                        [{:tool-id "read" :effect "allow" :constraints {} :constraints-json "{}"}
                         {:tool-id "discord.send" :effect "deny" :constraints {:channel "one"}
                          :constraints-json "{\"channel\":\"one\"}"}]]]
                      (fixture/operations world #{:ensure-tools :set-role-tools}))))))))

(deftest ^:async directory-counts-join-native-twins-before-response-shaping
  (let [world (fixture/world {:orgs [{:id "a" :member_count "2"} {:id "b" :member_count 0}]
                              :all-roles [{:org_id "a"} {:org_id "a"} {:org_id nil}]
                              :lakes (fn [_db org] (if (= org "a") [{:id "lake"}] []))})]
    (await (fixture/with-stores! world
             (^:async fn []
               (let [result (await (policy/list-orgs! nil))]
                 (is (= [["a" 2 2 1] ["b" 0 0 0]]
                        (mapv (juxt :id :member-count :role-count :data-lake-count) (:orgs result))))
                 (is (= [[:lakes :db "a"] [:lakes :db "b"]] (fixture/operations world #{:lakes})))))))))

(deftest ^:async create-user-keeps-membership-binding-contract-and-audit-order
  (let [world (fixture/world {:roles-by-slugs [{:id "role" :slug "knowledge-worker"}]})]
    (await (fixture/with-stores! world
             (^:async fn []
               (is (= {:user {:id "user"} :membership {:id "member" :org_id "org"}}
                      (await (policy/create-user! nil "operator" "operator-member"
                               {:email "user@example.invalid" :org-id "org" :actor-id "explicit"}))))
               (let [effects (fixture/operations world #{:create-user :upsert-membership :assign-roles
                                                         :actor-binding :actor-contract :audit})]
                 (is (= [:create-user :upsert-membership :assign-roles :actor-binding :actor-contract :audit]
                        (mapv first effects)))
                 (is (= [:actor-binding :db "member" "explicit"] (nth effects 3)))
                 (is (= {:actor-user-id "operator" :actor-membership-id "operator-member"
                         :org-id "org" :action "user.create_or_update" :resource-kind "user"
                         :resource-id "user" :before-json nil :after-json nil}
                        (last (last effects))))))))))

(deftest ^:async local-login-short-circuits-blank-and-inactive-users
  (doseq [[email responses expected] [[" " {} []]
                                     ["user@example.invalid" {:user-by-email {:id "user" :status "disabled"}}
                                      [:user-by-email]]]]
    (let [world (fixture/world responses)]
      (await (fixture/with-stores! world
               (^:async fn []
                 (is (nil? (await (policy/local-password-auth-record! nil email))))
                 (is (= expected (mapv first (fixture/operations world #{:user-by-email :bootstrap-password
                                                                         :email-membership :password}))))))))))

(deftest ^:async malformed-password-json-propagates-instead-of-becoming-an-empty-secret
  (let [world (fixture/world {:user-by-email {:id "user" :status "active"}
                              :email-membership active-member :password {:secret_json "{"}})]
    (await (fixture/with-stores! world
             (^:async fn []
               (is (:failed? (await (outcome #(policy/local-password-auth-record! nil "user@example.invalid")))))
               (is (= [[:password :db "user" "org" "local" "password"]]
                      (fixture/operations world #{:password}))))))))

(deftest ^:async actor-credential-custom-port-receives-exact-scope-without-store-access
  (let [calls (atom []) context {:get-actor-credential! (fn [& args] (swap! calls conj args) {:credential :owned})}
        world (fixture/world)]
    (await (fixture/with-stores! world
             (^:async fn []
               (is (= {:credential :owned} (await (policy/get-actor-credential! context "actor" "provider"))))
               (is (= {:credential :owned} (await (policy/get-actor-credential!
                                                   context "actor" "provider" {:org-id "org" :membership-id "member"}))))
               (is (= [["actor" "provider" nil]
                       ["actor" "provider" {:org-id "org" :membership-id "member"}]] (mapv vec @calls)))
               (is (empty? (fixture/calls world))))))))

(deftest ^:async unavailable-session-store-keeps-empty-read-and-cleanup-fallbacks
  (let [world (fixture/world {:connect nil})]
    (await (fixture/with-stores! world
             (^:async fn []
               (is (nil? (await (policy/get-session-by-token! nil "token"))))
               (is (nil? (await (policy/delete-session-by-token! nil "token"))))
               (is (= 0 (await (policy/cleanup-expired-sessions! nil))))
               (is (empty? (fixture/operations world #{:session :delete-session :expired-sessions :indexes}))))))))

(deftest ^:async unavailable-required-store-keeps-original-error-object
  (let [failure (fixture/native-error)
        world (fixture/world {:connect (fn [] (throw failure))})]
    (await (fixture/with-stores! world
             (^:async fn []
               (let [result (await (outcome #(policy/find-org-by-id nil "org")))]
                 (is (:failed? result))
                 (is (identical? failure (:error result)))
                 (is (empty? (fixture/operations world #{:org :indexes})))))))))

(deftest ^:async invalid-or-wrong-email-invite-cannot-redeem-or-create-user
  (doseq [[invite email status] [[nil "user@example.invalid" 400]
                                [{:id "invite" :email "other@example.invalid"} "user@example.invalid" 403]]]
    (let [world (fixture/world {:pending-invite invite})]
      (await (fixture/with-stores! world
               (^:async fn []
                 (let [result (await (outcome #(policy/redeem-invite! nil "code" email)))]
                   (is (:failed? result))
                   (is (= status (:status (fixture/error-summary (:error result)))))
                   (is (empty? (fixture/operations world #{:redeem-invite :create-user :audit}))))))))))

(deftest ^:async stored-membership-deny-survives-hydration-into-request-authorization
  (let [world (fixture/world
               {:member-roles [{:membership_id "member" :role_id "developer" :slug "developer"}]
                :roles-by-ids [{:id "developer" :slug "developer"}]
                :permissions [{:role_id "developer" :code "docs.read"}]
                :role-tools [{:role_id "developer" :tool_id "read" :effect "allow"}]
                :member-tools [{:membership_id "member" :tool_id "read" :effect "deny"}]})
        expected [{:tool-id "read" :effect "deny" :constraints {}}]]
    (await (fixture/with-stores! world
             (^:async fn []
               (let [wire (first (await (policy/hydrate-memberships nil [active-member])))
                     context (await (policy/build-request-context nil active-member))]
                 (is (= expected (:toolPolicies wire)))
                 (is (not (contains? wire :tool-policies)) "The established hydrated wire shape stays camelCase")
                 (is (= expected (:membership-tool-policies context)))
                 (is (= expected (:tool-policies context)))
                 (is (false? (authz/system-admin? context)))
                 (is (false? (authz/ctx-tool-allowed? context "read")))
                 (is (false? (authz/ctx-tool-allowed? context "unknown")))
                 (is (= ["docs.read"] (:permissions context)))
                 (is (= ["org" "user" "member"]
                        [(get-in context [:org :id]) (get-in context [:user :id])
                         (get-in context [:membership :id])]))))))))

(deftest ^:async context-credential-upsert-retains-target-scope-and-native-response-codec
  (let [row {:id "credential" :user_id "target-user" :org_id "target-org"
             :provider "discord_bot" :kind "bot-token" :account_identifier "account"
             :secret_json (fixture/native-value {:botToken "synthetic"}) :status "active"}
        world (fixture/world {:upsert-credential row})
        context {:pool :ignored :bootstrap-user-id "operator"
                 :bootstrap-membership-id "operator-member"}
        payload {:org-id "target-org" :provider "discord_bot" :kind "bot-token"
                 :account-identifier "account" :secret-json {:botToken "synthetic"}
                 :status "active"}]
    (await (fixture/with-stores! world
             (^:async fn []
               (let [result (await (policy/upsert-actor-credential-for-context!
                                    context "target-user" payload))]
                 (is (= [[:upsert-credential :db "target-user" "target-org" "discord_bot"
                          {:kind "bot-token" :account-identifier "account"
                           :secret-json {:botToken "synthetic"} :status "active"}]]
                        (fixture/operations world #{:upsert-credential})))
                 (is (= {:credential {:id "credential" :userId "target-user" :orgId "target-org"
                                      :provider "discord_bot" :kind "bot-token"
                                      :accountIdentifier "account" :status "active"
                                      :secretJson {:botToken "synthetic"}
                                      :configuredFields ["botToken"]}}
                        result))))))))
