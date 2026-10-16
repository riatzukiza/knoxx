(ns knoxx.backend.infra.db.policy.directory
  "Policy directory boundary extracted from the Mongo-backed public facade."
  (:require [clojure.string :as str]
            [knoxx.backend.domain.policy.decisions :as decisions]
            [knoxx.backend.extern.policy-db :as native]
            [knoxx.backend.infra.db.policy.actor-projection :as projections]
            [knoxx.backend.infra.db.policy.connection :as connection]
            [knoxx.backend.infra.db.policy.memberships :as memberships]
            [knoxx.backend.infra.db.policy.roles :as role-db]
            [knoxx.backend.infra.stores.mongo-policy-actor-credentials :as mongo-actor-creds]
            [knoxx.backend.infra.stores.mongo-policy-data-lakes :as mongo-data-lakes]
            [knoxx.backend.infra.stores.mongo-policy-directory :as mongo-directory]
            [knoxx.backend.infra.stores.mongo-policy-roles :as mongo-roles]
            [knoxx.backend.shape.policy-rows :as rows]))

(defn- ^:async accumulate-org-lake-count!
  [db acc o]
  (let [lakes (await (mongo-data-lakes/list-data-lakes-by-org! db (:id o)))]
    (swap! acc assoc (:id o) (count lakes))
    nil))

(defn ^:async org-data-lake-counts
  "Resolve {org-id -> data-lake-count} for the given org rows (sequential
   awaits; the directory twin's list-orgs! zeroes data_lake_count)."
  [db rows]
  (let [acc (atom {})]
    (await (connection/promise-each rows (partial accumulate-org-lake-count! db acc)))
    @acc))

(defn ^:async list-orgs!
  [_pool]
  (let [db (await (connection/db!))
        rows (await (mongo-directory/list-orgs! db))
        ;; The directory twin owns member_count but zeroes role_count /
        ;; data_lake_count (later slices). Recompute them here, preserving the
        ;; PG list-with-counts semantics, before org-row->map coerces them.
        all-roles (await (mongo-roles/list-roles! db {:org-id nil}))
        role-counts (frequencies (keep :org_id all-roles))
        lake-counts (await (org-data-lake-counts db rows))]
    {:orgs (mapv (fn [o]
                   (native/org-row->map (assoc o
                                        :role_count (get role-counts (:id o) 0)
                                        :data_lake_count (get lake-counts (:id o) 0))))
                 rows)}))

(defn ^:async create-org!
  [pool uid mid {:keys [name slug kind status]
                  :or {kind "customer" status "active"}}]
  (if (str/blank? name)
    (throw (native/error "name is required"))
    (let [s (decisions/slugify (or slug name) "org")
          org (await (mongo-directory/create-org! (await (connection/db!)) {:slug s :name name
                                                                 :kind kind :status status}))]
      (await (projections/sync-contract-role-projections! pool))
      (await (connection/append-audit! pool {:actor-user-id uid :actor-membership-id mid
                                  :org-id (:id org) :action "org.create"
                                  :resource-kind "org" :resource-id (:id org)}))
      (rows/org-response org))))

(defn ^:async ensure-self-org!
  [_pool email display-name]
  (let [db (await (connection/db!))
        slug (decisions/self-org-slug email)
        label (or (some-> display-name str str/trim not-empty)
                  (decisions/normalize-email email)
                  "User")
        name (str label " Self")
        existing (await (mongo-directory/find-org-by-slug db slug))]
    (if existing
      existing
      (await (mongo-directory/create-org! db {:slug slug :name name
                                              :kind "self" :status "active"})))))

(defn credentials-by-user
  [credentials]
  (reduce (fn [acc c]
            (update acc (:user_id c) (fnil conj []) (native/credential-row->map c)))
          {}
          credentials))

(defn ^:async list-users!
  [pool {:keys [org-id]}]
  (let [db (await (connection/db!))
        users (await (mongo-directory/list-users! db {:org-id org-id}))
        user-ids (mapv :id users)
        mem-rows (await (mongo-directory/memberships-for-users-with-org! db user-ids org-id))
        by-user (rows/memberships-by-user (await (memberships/hydrate-memberships pool mem-rows)))
        cred-rows (if org-id
                    (await (mongo-actor-creds/list-credentials-for-users-org! db user-ids org-id))
                    [])
        by-cred-user (credentials-by-user cred-rows)]
    {:users (mapv #(rows/user-row->map by-user by-cred-user %) users)}))

(defn- require-not-blank!
  [value message]
  (when (str/blank? value)
    (throw (native/error message))))

(defn ^:async create-user!
  [pool uid mid {:keys [email display-name auth-provider external-subject status
                         membership-status org-id role-slugs role-ids is-default actor-id]
                  :or {auth-provider "local" status "active"
                       membership-status "active" is-default true}}]
  (require-not-blank! email "email is required")
  (require-not-blank! org-id "org-id is required")
  (let [dn (or display-name email)
        resolved-slugs (or role-slugs ["knowledge-worker"])
        actor-contract (projections/find-user-actor-contract-by-email email)
        resolved-actor (or (decisions/normalize-actor-id actor-id) (:id actor-contract)
                           (projections/user-actor-id-from-email email)
                           (memberships/default-membership-actor-id resolved-slugs))
        db (await (connection/db!))
        user (await (mongo-directory/create-user! db {:email email :display-name dn
                                                      :auth-provider auth-provider
                                                      :external-subject external-subject
                                                      :status status}))
        ms (await (mongo-directory/upsert-membership! db {:user-id (:id user)
                                                          :org-id org-id
                                                          :status membership-status
                                                          :is-default is-default}))]
    (await (role-db/set-membership-roles! pool (:id ms) {:org-id org-id :role-ids (or role-ids [])
                                                 :role-slugs resolved-slugs :replace true}))
    (await (role-db/set-membership-actor-id! pool (:id ms) resolved-actor))
    (await (projections/upsert-user-actor-contract-for-membership! pool org-id resolved-actor email dn resolved-slugs))
    (await (connection/append-audit! pool {:actor-user-id uid :actor-membership-id mid
                                :org-id org-id :action "user.create_or_update"
                                :resource-kind "user" :resource-id (:id user)}))
    {:user user :membership ms}))

(defn- data-lake-row->map
  [{:keys [id org_id name slug kind config_json status created_at updated_at]}]
  {:id id :org-id org_id :name name :slug slug :kind kind
   :config (native/constraints-json->clj config_json)
   :status status :created-at created_at :updated-at updated_at})

(defn ^:async list-data-lakes!
  [_pool {:keys [org-id]}]
  (if (str/blank? org-id)
    (throw (native/error "org-id is required"))
    (when-let [db (await (connection/ensure-mongo-policy-db!))]
      {:data-lakes (mapv data-lake-row->map
                         (await (mongo-data-lakes/list-data-lakes-by-org! db org-id)))})))

(defn ^:async create-data-lake!
  [pool uid mid {:keys [org-id name slug kind config status]
                  :or {kind "workspace_docs" status "active"}}]
  (cond
    (str/blank? org-id) (throw (native/error "org-id is required"))
    (str/blank? name)   (throw (native/error "name is required"))
    :else
    (when-let [db (await (connection/ensure-mongo-policy-db!))]
      (let [s    (decisions/slugify (or slug name) "lake")
            lake (await (mongo-data-lakes/create-data-lake!
                         db org-id {:name name :slug s :kind kind
                                    :config-json (native/value (or config {}))
                                    :status status}))]
        (await (connection/append-audit! pool {:actor-user-id uid :actor-membership-id mid
                                    :org-id org-id :action "data_lake.create"
                                    :resource-kind "data_lake" :resource-id (:id lake)}))
        (rows/data-lake-response lake)))))
