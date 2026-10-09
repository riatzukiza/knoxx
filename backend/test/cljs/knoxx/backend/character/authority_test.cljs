(ns knoxx.backend.character.authority-test
  (:require [cljs.test :refer [deftest is async]]
            [knoxx.backend.infra.character.authority :as authority]
            [knoxx.backend.infra.agent.tool-catalog :as catalog]
            [knoxx.backend.infra.agent.event-policy-authority :as event-authority]
            [knoxx.backend.infra.agent.turn :as turn]
            [knoxx.backend.infra.db.policy :as policy]
            [knoxx.backend.infra.stores.mongo-policy-roles :as mongo-roles]
            [knoxx.backend.infra.stores.mongo-policy-tools :as mongo-tools]))

(def stored-context
  {:actor {:binding "creative-actor"} :org {:id "fixture-org"} :membership {:id "fixture-member"}
   :tool-policies [{:tool-id "discord.read" :effect "allow"}]
   :resourcePolicies [{:effect "allow" :scope "stored-private-scope"}]})

(deftest current-stored-authority-keeps-caller-scope-and-refuses-mismatch
  (async done
    ((^:async fn []
       (try
         (let [seen* (atom nil)
               config {:resolve-agent-authority! (fn [scope] (reset! seen* scope) stored-context)}
               inbound {:actor {:binding "creative-actor"}
                        :org {:id "fixture-org"} :membership {:id "fixture-member"}
                        :resourcePolicies [{:effect "allow" :scope "caller-ceiling"}]}
               result (await (authority/resolve-current! config inbound {:actor-id "creative-actor"}))]
           (is (= {:actor-id "creative-actor" :org-id "fixture-org" :membership-id "fixture-member"} @seen*))
           (is (= (:tool-policies stored-context) (:tool-policies result)))
           (is (= (:resourcePolicies inbound) (:resourcePolicies result)))
           (await (authority/resolve-current! config {:actorId "creative-actor" :orgId "fixture-org" :membershipId "fixture-member"}
                                             {:actor-id "creative-actor"}))
           (is (= {:actor-id "creative-actor" :org-id "fixture-org" :membership-id "fixture-member"} @seen*)
               "Flat request scope is preserved by the canonical auth codec")
           (doseq [context [nil (assoc-in stored-context [:org :id] "other-org")
                            (assoc-in stored-context [:membership :id] "other-member")
                            (assoc-in stored-context [:actor :binding] "another-actor")]]
             (try
               (await (authority/resolve-current! {:resolve-agent-authority! (fn [_] context)} inbound {:actor-id "creative-actor"}))
               (is false "Mismatched authority was accepted")
               (catch :default error (is (= :invalid-actor-context (:reason (ex-data error))))))))
         (catch :default error (is false (str error)))
         (finally (done)))))))

(deftest current-authority-never-selects-an-actor-from-the-requested-spec
  (async done
    ((^:async fn []
       (try
         (doseq [context [nil {:org {:id "fixture-org"}}
                          {:actor {:id "creative-actor" :binding nil}}
                          (assoc stored-context :actor {:binding "authenticated-other"})]]
           (let [reads* (atom [])
                 config {:resolve-agent-authority! (fn [scope] (swap! reads* conj scope) stored-context)}]
             (try
               (await (authority/resolve-current! config context {:actor-id "creative-actor"}))
               (is false "Missing or mismatched authenticated actor was accepted")
               (catch :default error
                 (is (= :invalid-actor-context (:reason (ex-data error))))))
             (is (empty? @reads*) "Invalid actor selection must be refused before stored-authority IO")))
         (catch :default error (is false (str error)))
         (finally (done)))))))

(deftest ^:async turn-normalization-cannot-rebind-a-server-event-principal
  (let [reads* (atom [])
        authenticated (event-authority/authorized-context nil "authenticated-other" nil nil)
        requested {:actor-id "creative-actor"}
        normalized (#'turn/auth-context-for-agent-turn authenticated requested)]
    (is (= "authenticated-other" (:actorId normalized)))
    (is (event-authority/authorized? normalized))
    (try
      (await (authority/resolve-current!
              {:resolve-agent-authority! (fn [scope] (swap! reads* conj scope) stored-context)}
              normalized requested))
      (is false "The spec relabeled a genuine server token as another actor")
      (catch :default error
        (is (= :invalid-actor-context (:reason (ex-data error))))))
    (is (empty? @reads*))))

(deftest focused-contract-ceiling-does-not-invent-stored-grants
  (with-redefs [catalog/allowed-tool-ids (fn [& _] #{"discord.read" "discord.send" "bash"})]
    (is (= #{"discord.read"} (catalog/focused-authorized-tool-ids {} stored-context {})))
    (is (= #{} (catalog/focused-authorized-tool-ids {} nil {})))))

(def ^:private fixture-policy-db :isolated-policy-read-fixture)

(def ^:private active-member-row
  {:id "fixture-member" :user_id "fixture-user" :user_status "active"
   :email "creator@example.test" :display_name "Fixture creator"
   :org_id "fixture-org" :org_status "active" :org_name "Fixture org" :org_slug "fixture-org"
   :actor_id "creative-actor" :status "active" :is_default false})

(def ^:private other-member-row
  (assoc active-member-row :id "other-member" :user_id "other-user" :email "other@example.test"
         :org_id "other-org" :org_slug "other-org" :actor_id "other-actor"))

(def ^:private blue-constraints
  {:account-id "did:plc:fixture-creator" :scope-id "home" :visibility "public" :max-items 4})

(def ^:private other-blue-constraints
  (assoc blue-constraints :account-id "did:plc:other-account" :scope-id "other-home"))

(defn- tool-policy-row [owner-key owner-id tool-id effect constraints]
  {owner-key owner-id :tool_id tool-id :effect effect :constraints_json constraints})

(defn- policy-read-fixture []
  {:calls* (atom [])
   :roles* (atom [{:id "role-deny" :org_id "fixture-org" :name "A guard" :slug "creator-guard" :scope_kind "org"}
                  {:id "role-allow" :org_id "fixture-org" :name "B creator" :slug "creator" :scope_kind "org"}])
   :member-policies* (atom [(tool-policy-row :membership_id "fixture-member" "bluesky.timeline" "allow" blue-constraints)
                            (tool-policy-row :membership_id "fixture-member" "discord.send" "deny" {:scope-id "private-channel"})
                            (tool-policy-row :membership_id "other-member" "bluesky.timeline" "allow" other-blue-constraints)])
   :role-policies [(tool-policy-row :role_id "role-deny" "bluesky.search" "deny" {:scope-id "blocked-search"})
                   (tool-policy-row :role_id "role-allow" "bluesky.search" "allow" {})
                   (tool-policy-row :role_id "role-allow" "discord.channel.messages" "allow" {:scope-id "private-channel"})
                   (tool-policy-row :role_id "role-allow" "discord.send" "allow" {})
                   (tool-policy-row :role_id "role-allow" "discord.guild.channels" "allow" {})]})

(def ^:private membership-role-rows
  [{:membership_id "fixture-member" :role_id "role-deny" :org_id "fixture-org" :slug "creator-guard" :name "A guard" :scope_kind "org"}
   {:membership_id "fixture-member" :role_id "role-allow" :org_id "fixture-org" :slug "creator" :name "B creator" :scope_kind "org"}])

(defn- fixture-rows! [state port db ids rows id-key]
  (is (= fixture-policy-db db) "All policy reads remain on the injected fixture DB")
  (swap! (:calls* state) conj [port (vec ids)])
  (filterv #(contains? (set ids) (get % id-key)) rows))

(defn- unscoped-read-refused! []
  (throw (ex-info "Fixture refuses a default database read" {:reason :unscoped-fixture-read})))

(defn- ^:async with-policy-read-fixture! [state task!]
  (with-redefs [policy/db! (fn [] fixture-policy-db)
                mongo-roles/roles-for-memberships!
                (fn ([_ids] (unscoped-read-refused!))
                    ([db ids] (fixture-rows! state :membership-roles db ids membership-role-rows :membership_id)))
                mongo-tools/tool-policies-for-memberships!
                (fn ([_ids] (unscoped-read-refused!))
                    ([db ids] (fixture-rows! state :membership-policies db ids @(:member-policies* state) :membership_id)))
                mongo-roles/list-roles-by-ids!
                (fn ([_ids] (unscoped-read-refused!))
                    ([db ids] (fixture-rows! state :roles db ids @(:roles* state) :id)))
                mongo-roles/permissions-for-roles!
                (fn ([_ids] (unscoped-read-refused!))
                    ([db ids] (fixture-rows! state :permissions db ids [] :role_id)))
                mongo-tools/tool-policies-for-roles!
                (fn ([_ids] (unscoped-read-refused!))
                    ([db ids] (fixture-rows! state :role-policies db ids (:role-policies state) :role_id)))
                ;; This port supplies only a trusted contract ceiling. Actual
                ;; stored authority and the focused intersection remain real.
                catalog/allowed-tool-ids
                (fn [_config _context _spec]
                  #{"bluesky.timeline" "bluesky.search" "discord.channel.messages" "discord.send"})]
    (await (task!))))

(defn- policy-by-tool [policies tool-id]
  (first (filter #(= tool-id (:tool-id %)) policies)))

(defn- assert-hydrated-membership-policies! [members context]
  (let [[member other-member] members
        policies (:tool-policies member)]
    (is (identical? policies (:toolPolicies member)) "Canonical and public API fields share the same trusted vector")
    (is (= policies (:membership-tool-policies context)))
    (is (= blue-constraints (:constraints (policy-by-tool policies "bluesky.timeline"))))
    (is (= other-blue-constraints
           (:constraints (policy-by-tool (:tool-policies other-member) "bluesky.timeline"))))
    (is (= {:id "creative-actor" :binding "creative-actor"} (:actor context)))
    (is (= ["fixture-user" "fixture-org" "fixture-member"]
           [(get-in context [:user :id]) (get-in context [:org :id]) (get-in context [:membership :id])]))
    (is (false? (:is-system-admin context)))
    (is (= [] (:permissions context)))))

(defn- assert-focused-policy-results! [context]
  (let [policies (:tool-policies context)]
    (is (= blue-constraints (:constraints (policy-by-tool policies "bluesky.timeline"))))
    (is (= "deny" (:effect (policy-by-tool policies "discord.send"))) "Membership deny suppresses the role allow")
    (is (= {:scope-id "private-channel"} (:constraints (policy-by-tool policies "discord.send"))))
    (is (= "deny" (:effect (policy-by-tool policies "bluesky.search"))) "Role deny survives a later role allow")
    (is (= #{"bluesky.timeline" "discord.channel.messages"}
           (catalog/focused-authorized-tool-ids {} context {:actor-id "creative-actor"})))
    (is (= "allow" (:effect (policy-by-tool policies "discord.guild.channels")))
        "A stored role grant outside the contract ceiling cannot expand focused tools")))

(defn- ^:async assert-removed-override-stays-owner-scoped! [state]
  (swap! (:member-policies* state)
         #(filterv (fn [row] (not (and (= "fixture-member" (:membership_id row))
                                      (= "bluesky.timeline" (:tool_id row))))) %))
  (let [members (await (policy/hydrate-memberships nil [active-member-row other-member-row]))
        context (await (policy/build-request-context nil active-member-row))
        other-context (await (policy/build-request-context nil other-member-row))]
    (is (nil? (policy-by-tool (:tool-policies (first members)) "bluesky.timeline")))
    (is (identical? (:tool-policies (first members)) (:toolPolicies (first members))))
    (is (nil? (policy-by-tool (:tool-policies context) "bluesky.timeline")))
    (is (= #{"discord.channel.messages"} (catalog/focused-authorized-tool-ids {} context {}))
        "Fresh hydration observes the removed override without borrowing the other member's grant")
    (is (= other-blue-constraints (:constraints (policy-by-tool (:tool-policies other-context) "bluesky.timeline"))))
    (is (= ["other-user" "other-org" "other-member"]
           [(get-in other-context [:user :id]) (get-in other-context [:org :id]) (get-in other-context [:membership :id])]))
    (try
      (await (authority/resolve-current! {:resolve-agent-authority! (fn [_scope] other-context)}
                                        context {:actor-id "creative-actor"}))
      (is false "A different current member, actor/account, and org must not supply this owner authority")
      (catch :default error (is (= :invalid-actor-context (:reason (ex-data error))))))))

(deftest canonical-membership-policies-survive-real-hydration-and-current-focused-clamp
  (async done
    ((^:async fn []
       (try
         (let [state (policy-read-fixture)]
           (await (with-policy-read-fixture!
                    state
                    (^:async fn []
                      (let [members (await (policy/hydrate-memberships nil [active-member-row other-member-row]))
                            context (await (policy/build-request-context nil active-member-row))]
                        (assert-hydrated-membership-policies! members context)
                        (assert-focused-policy-results! context)
                        (swap! (:roles* state) #(vec (reverse %)))
                        (assert-focused-policy-results! (await (policy/build-request-context nil active-member-row)))
                        (await (assert-removed-override-stays-owner-scoped! state))
                        (is (some #{[:membership-policies ["fixture-member"]]} @(:calls* state)))
                        (is (some #{[:membership-policies ["other-member"]]} @(:calls* state))))))))
         (catch :default error (is false (str error)))
         (finally (done)))))))
