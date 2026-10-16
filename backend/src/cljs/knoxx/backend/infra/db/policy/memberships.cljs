(ns knoxx.backend.infra.db.policy.memberships
  "Policy memberships boundary extracted from the Mongo-backed public facade."
  (:require [clojure.string :as str]
            [knoxx.backend.domain.actor.scope :as actor-scope]
            [knoxx.backend.domain.policy.decisions :as decisions]
            [knoxx.backend.extern.policy-db :as native]
            [knoxx.backend.infra.db.policy.actor-projection :as projections]
            [knoxx.backend.infra.db.policy.connection :as connection]
            [knoxx.backend.infra.db.policy.roles :as role-db]
            [knoxx.backend.infra.stores.mongo-policy-directory :as mongo-directory]
            [knoxx.backend.infra.stores.mongo-policy-roles :as mongo-roles]
            [knoxx.backend.infra.stores.mongo-policy-tools :as mongo-tools]
            [knoxx.backend.shape.policy-rows :as rows]))

(defn default-membership-actor-id [role-slugs]
  (actor-scope/default-membership-actor-id role-slugs))

(defn- grouped-membership-tool-policies
  [rows]
  (reduce (fn [acc {:keys [membership_id tool_id effect constraints_json]}]
            (update acc membership_id (fnil conj [])
                    {:tool-id tool_id :effect effect
                     :constraints (native/constraints-json->clj constraints_json)}))
          {}
          rows))

(defn- hydrate-membership-row
  [roles-by-m tools-by-m {:keys [id user_id org_id actor_id org_name org_slug status is_default created_at updated_at]}]
  (let [roles (or (get roles-by-m id) [])]
    {:id id :userId user_id :orgId org_id
     :actorId (or (decisions/normalize-actor-id actor_id)
                  (default-membership-actor-id (map :slug roles)))
     :orgName org_name :orgSlug org_slug :status status :isDefault is_default
     :createdAt created_at :updatedAt updated_at
     :roles roles :toolPolicies (or (get tools-by-m id) [])}))

(defn ^:async hydrate-memberships
  [_pool memberships]
  (if (empty? memberships)
    []
    (let [db (await (connection/db!))
          membership-ids (mapv :id memberships)
          role-rows (await (mongo-roles/roles-for-memberships! db membership-ids))
          tool-rows (await (mongo-tools/tool-policies-for-memberships! db membership-ids))
          roles-by-m (rows/grouped-membership-roles role-rows)
          tools-by-m (grouped-membership-tool-policies tool-rows)]
      (mapv #(hydrate-membership-row roles-by-m tools-by-m %) memberships))))

(defn- ^:async find-request-membership-row [_pool headers-like]
  (let [membership-id (native/header-value headers-like "x-knoxx-membership-id")
        user-email    (some-> (native/header-value headers-like "x-knoxx-user-email") str/lower-case)
        org-id        (native/header-value headers-like "x-knoxx-org-id")
        org-slug      (some-> (native/header-value headers-like "x-knoxx-org-slug") str/lower-case)]
    (cond
      (and (str/blank? membership-id) (str/blank? user-email))
      (throw (native/http-error 401 "Missing x-knoxx-user-email or x-knoxx-membership-id"
                         "request_context_missing"))

      (not (str/blank? membership-id))
      (await (mongo-directory/find-membership-row-with-user-org! (await (connection/db!)) membership-id))

      :else
      (await (mongo-directory/find-membership-row-by-email-and-org!
              (await (connection/db!))
              {:user-email user-email :org-id org-id :org-slug org-slug})))))

(defn- validate-membership-row!
  [membership-row]
  (cond
    (not membership-row)
    (throw (native/http-error 401 "Request context did not resolve to a membership"
                       "request_context_unresolved"))
    (not= (:user_status membership-row) "active")
    (throw (native/http-error 403 "User is not active" "user_inactive"))
    (not= (:status membership-row) "active")
    (throw (native/http-error 403 "Membership is not active" "membership_inactive"))
    (not= (:org_status membership-row) "active")
    (throw (native/http-error 403 "Org is not active" "org_inactive"))))

(defn ^:async detailed-membership-roles
  [pool membership]
  (let [role-ids (mapv :id (:roles membership))]
    (if (empty? role-ids)
      []
      (let [rows (await (mongo-roles/list-roles-by-ids! (await (connection/db!)) role-ids))]
        (await (role-db/hydrate-role-maps pool rows))))))

(defn- request-policy-summary
  [membership detailed-roles]
  {:permissions (sort (decisions/unique (mapcat :permissions detailed-roles)))
   :tool-policies (role-db/merge-tool-policies (mapcat :tool-policies detailed-roles)
                                       (:toolPolicies membership))
   :role-slugs (sort-by #(- (decisions/rolePriority %)) (map :slug detailed-roles))})

(defn- request-context-map
  [membership-row membership detailed-roles]
  (let [{:keys [permissions tool-policies role-slugs]} (request-policy-summary membership detailed-roles)
        ;; The membership's *stored* actor binding, or nil. Kept separate from
        ;; actor-id below, which falls back to a role-derived default and is
        ;; therefore never nil — so it cannot answer "was an actor assigned?".
        ;; Anything deciding authority (which credentials a token may read) must
        ;; use the binding; the default is a display and role convenience.
        actor-binding (decisions/normalize-actor-id (:actor_id membership-row))
        actor-id (or actor-binding
                     (default-membership-actor-id role-slugs))]
    {:user (rows/request-user-map membership-row)
     :org (rows/request-org-map membership-row)
     :membership (rows/request-membership-map membership actor-id)
     :actor {:id actor-id :binding actor-binding}
     :roles detailed-roles
     :role-slugs role-slugs
     :permissions permissions
     :tool-policies tool-policies
     :membership-tool-policies (:toolPolicies membership)
     :is-system-admin (boolean (some #{"system_admin" "system-admin"} role-slugs))}))

(defn ^:async build-request-context
  [pool membership-row]
  (validate-membership-row! membership-row)
  (let [membership (first (await (hydrate-memberships pool [membership-row])))
        detailed-roles (await (detailed-membership-roles pool membership))]
    (request-context-map membership-row membership detailed-roles)))

(defn ^:async resolve-request-context!
  "Resolve a Knoxx auth context from headers-like (Fastify headers or CLJS map).
   Returns Promise<CLJS ctx map>."
  [pool headers-like]
  (await (build-request-context pool (await (find-request-membership-row pool headers-like)))))

(defn ^:async evaluate-tool-access!
  [pool headers-like tool-id]
  (let [ctx (await (resolve-request-context! pool headers-like))
        match (some #(when (= (:tool-id %) tool-id) %) (:tool-policies ctx))]
    {:context ctx
     :tool-id tool-id
     :allowed (boolean (and match (= (:effect match) "allow")))}))

(defn ^:async list-memberships!
  [pool {:keys [org-id]}]
  (if (str/blank? org-id)
    (throw (native/error "org-id is required"))
    (let [rows (await (mongo-directory/list-memberships-with-org! (await (connection/db!)) {:org-id org-id}))]
      {:memberships (await (hydrate-memberships pool rows))})))

(defn ^:async get-membership!
  [pool membership-id]
  (if-let [row (await (mongo-directory/find-membership-row-with-user-org! (await (connection/db!)) membership-id))]
    {:membership (first (await (hydrate-memberships pool [row])))}
    {:membership nil}))

(defn ^:async set-membership-roles-public!
  [pool uid mid membership-id {:keys [org-id role-ids role-slugs actor-id replace]
                                 :or {replace true}}]
  (let [ms (await (mongo-directory/get-membership! (await (connection/db!)) membership-id))]
    (when-not ms (throw (native/error "membership not found")))
    (let [resolved-actor (or (decisions/normalize-actor-id actor-id)
                             (decisions/normalize-actor-id (:actor_id ms))
                             (default-membership-actor-id (or role-slugs [])))]
      (await (role-db/set-membership-roles! pool membership-id {:org-id (or org-id (:org_id ms))
                                                        :role-ids (or role-ids [])
                                                        :role-slugs (or role-slugs [])
                                                        :replace replace}))
      (await (role-db/set-membership-actor-id! pool membership-id resolved-actor))
      (let [row (await (mongo-directory/find-membership-row-with-user-org! (await (connection/db!)) membership-id))]
        (await (projections/upsert-actor-contract-best-effort!
                {:actor-id resolved-actor :email (:email row)
                 :display-name (:display_name row) :org-slug (:org_slug row)
                 :role-slugs (or role-slugs [])})))
      (await (connection/append-audit! pool {:actor-user-id uid :actor-membership-id mid
                                  :org-id (:org_id ms) :action "membership.roles.update"
                                  :resource-kind "membership" :resource-id membership-id}))
      {:membership nil})))

(defn ^:async update-user-actor!
  "Update a membership's actor-id and optionally its roles."
  [pool uid mid user-id {:keys [org-id actor-id role-slugs]}]
  (let [ms (await (mongo-directory/find-membership-by-user-and-org! (await (connection/db!)) user-id org-id))]
    (if-not ms
      (throw (native/error "membership not found"))
      (let [membership-id (:id ms)
            resolved-actor (or (decisions/normalize-actor-id actor-id)
                               (decisions/normalize-actor-id (:actor_id ms))
                               (default-membership-actor-id (or role-slugs [])))]
        (when (seq role-slugs)
          (await (role-db/set-membership-roles! pool membership-id
                                        {:org-id org-id :role-slugs role-slugs :replace true})))
        (await (role-db/set-membership-actor-id! pool membership-id resolved-actor))
        (await (connection/append-audit! pool {:actor-user-id uid :actor-membership-id mid
                                    :org-id org-id :action "user.update_actor"
                                    :resource-kind "user" :resource-id user-id}))
        {:ok true}))))
