(ns knoxx.backend.infra.db.policy.actor-projection
  "Policy actor projection boundary extracted from the Mongo-backed public facade."
  (:require [clojure.string :as str]
            [knoxx.backend.domain.contracts.loader :as contracts-loader]
            [knoxx.backend.domain.policy.decisions :as decisions]
            [knoxx.backend.domain.policy.protocol :as policy]
            [knoxx.backend.extern.policy-db :as native]
            [knoxx.backend.infra.db.actors :as policy-actors]
            [knoxx.backend.infra.db.policy.connection :as connection]
            [knoxx.backend.infra.db.policy.roles :as role-db]
            [knoxx.backend.infra.stores.mongo-policy-directory :as mongo-directory]))

(defn- upsert-actor-contract! [payload]
  (policy-actors/upsert-actor-contract! (native/contracts-dir) payload))

(defn ^:async upsert-actor-contract-best-effort!
  [payload]
  (try
    (await (upsert-actor-contract! payload))
    (catch :default err
      (native/handle-read-only-actor-contract-error! err))))

(defn- find-actor-contract-by-id [actor-id]
  (policy-actors/find-actor-contract-by-id (native/contracts-dir) actor-id))

(defn find-user-actor-contract-by-email [email]
  (policy-actors/find-user-actor-contract-by-email (native/contracts-dir) email))

(defn- list-actor-contracts []
  (policy-actors/list-actor-contracts (native/contracts-dir)))

(defn user-actor-id-from-email [email]
  (policy-actors/user-actor-id-from-email email))

(defn- ^:async project-actor-role-slugs!
  "Best-effort set the projected membership's roles, keeping the membership
   when role projection fails (mirrors sql-adapter's .catch behavior)."
  [membership actor-id role-slugs]
  (try
    (await (role-db/set-membership-roles! nil (:id membership)
                                  {:org-id (:org_id membership)
                                   :role-slugs role-slugs :role-ids []
                                   :replace true :contract-projection true}))
    (catch :default err
      (native/warn!
             "[policy-mongo] actor role projection failed; keeping actor membership"
             actor-id (native/error-message err)))))

(defn ^:async project-actor-via-store!
  "Upsert the user/membership/roles projection for one actor contract against
   the Mongo twins. Returns the membership row."
  [_pool primary-org actor]
  (let [validated (policy/validate-actor! actor)
        actor-id (:actor/id validated)
        db (await (connection/db!))
        email (or (decisions/normalize-email (:actor/email validated))
                  (policy-actors/actor-email-from-id actor-id))
        display-name (or (some-> (:actor/label validated) str str/trim not-empty)
                         actor-id email)
        role-slugs (decisions/actor-projection-role-slugs validated)
        user (await (mongo-directory/create-user!
                     db {:email email :display-name display-name
                         :auth-provider "actor-contract" :external-subject nil
                         :status "active"}))
        org (or (when-let [org-slug (some-> (:actor/org validated) str str/trim not-empty)]
                  (await (mongo-directory/find-org-by-slug db org-slug)))
                primary-org)]
    (when-not org
      (throw (native/error "primary org is required for actor projection sync")))
    (let [membership (await (mongo-directory/upsert-membership!
                             db {:user-id (:id user) :org-id (:id org)
                                 :status "active" :is-default true}))]
      (await (mongo-directory/set-membership-actor-id! db (:id membership) actor-id))
      (await (project-actor-role-slugs! membership actor-id role-slugs))
      membership)))

(defn ^:async sync-actor-projections!
  [pool primary-org actors]
  (await (connection/promise-each actors #(project-actor-via-store! pool primary-org %)))
  nil)

(defn- sync-user-from-actor-contract!* [pool primary-org payload]
  (let [actor-id (decisions/normalize-actor-id (or (:actor-id payload) (:actor_id payload)))
        email    (decisions/normalize-email (:email payload))]
    (if-not (or email actor-id)
      (native/resolved nil)
      (if-let [contract (or (find-actor-contract-by-id actor-id)
                            (find-user-actor-contract-by-email email))]
        (sync-actor-projections! pool primary-org [(:actor contract)])
        (native/resolved nil)))))

(defn ^:async sync-contract-role-record!
  [pool caps-by-id rec]
  (when-let [slug (decisions/role-record-slug rec)]
    (let [contract (:contract rec)
          perms (->> (or (:role/permissions contract) []) (map str) distinct sort vec)
          role (await (role-db/ensure-role! pool {:org-id nil
                                          :name (decisions/role-display-name slug contract)
                                          :slug slug :scope-kind "platform"
                                          :built-in false :system-managed true}))]
      (await (role-db/set-role-permissions! pool (:id role) perms))
      (await (role-db/set-role-tool-policies! pool (:id role) (role-db/role-tool-policies caps-by-id contract)))))
  nil)

(defn ^:async sync-contract-role-projections!
  [pool]
  (let [records (await (contracts-loader/load-all-contracts! (native/contracts-config)))
        caps-by-id (into {} (map (fn [r] [(:id r) (:contract r)]))
                         (decisions/contract-records-by-class records "capabilities"))]
    (await (connection/promise-each (decisions/contract-records-by-class records "roles")
                         #(sync-contract-role-record! pool caps-by-id %))))
  nil)

(defn ^:async upsert-user-actor-contract-for-membership!
  [pool org-id resolved-actor email display-name role-slugs]
  (let [org-row (await (connection/find-org-by-id pool org-id))]
    (await (upsert-actor-contract-best-effort!
            {:actor-id resolved-actor
             :email email
             :display-name display-name
             :org-slug (:slug org-row)
             :role-slugs role-slugs
             :kind :agent}))))

(defn ^:async sync-actor-contracts!
  [pool primary-org]
  (await (sync-actor-projections! pool primary-org (mapv :actor (list-actor-contracts)))))

(defn sync-user-from-actor-contract!
  [pool primary-org opts]
  (sync-user-from-actor-contract!* pool primary-org opts))
