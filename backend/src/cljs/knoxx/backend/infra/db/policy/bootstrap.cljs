(ns knoxx.backend.infra.db.policy.bootstrap
  "Policy bootstrap boundary extracted from the Mongo-backed public facade."
  (:require [clojure.string :as str]
            [knoxx.backend.domain.policy.decisions :as decisions]
            [knoxx.backend.extern.policy-db :as native]
            [knoxx.backend.infra.auth.password :as password]
            [knoxx.backend.infra.db.policy.connection :as connection]
            [knoxx.backend.infra.db.policy.memberships :as memberships]
            [knoxx.backend.infra.db.policy.roles :as role-db]
            [knoxx.backend.infra.stores.mongo-policy-actor-credentials :as mongo-actor-creds]
            [knoxx.backend.infra.stores.mongo-policy-directory :as mongo-directory]
            [knoxx.backend.infra.stores.mongo-policy-roles :as mongo-roles]))

(defn ^:async ensure-primary-org!
  [_pool opts]
  (let [primary-org-slug (or (:primaryOrgSlug opts) (:primary-org-slug opts) "open-hax")
        primary-org-name (or (:primaryOrgName opts) (:primary-org-name opts) "Open Hax")
        primary-org-kind (or (:primaryOrgKind opts) (:primary-org-kind opts) "platform_owner")
        slug (decisions/slugify primary-org-slug "open-hax")]
    (await (mongo-directory/ensure-primary-org! (await (connection/db!))
                                                {:slug slug
                                                 :name (str primary-org-name)
                                                 :kind (str primary-org-kind)}))))

(defn ^:async ensure-bootstrap-user!
  [pool primary-org opts]
  (let [db (await (connection/db!))
        email (str/lower-case (str (or (:bootstrapSystemAdminEmail opts)
                                       (:bootstrap-system-admin-email opts)
                                       "system-admin@open-hax.local")))
        dn (str (or (:bootstrapSystemAdminName opts)
                    (:bootstrap-system-admin-name opts)
                    "Knoxx System Admin"))
        user (await (mongo-directory/create-user! db {:email email :display-name dn
                                                      :auth-provider "bootstrap"
                                                      :external-subject nil :status "active"}))
        membership (await (mongo-directory/upsert-membership! db {:user-id (:id user)
                                                                  :org-id (:id primary-org)
                                                                  :status "active"
                                                                  :is-default true}))]
    (await (role-db/set-membership-roles! pool (:id membership) {:org-id (:id primary-org)
                                                         :role-slugs ["system-admin"]
                                                         :replace true}))
    (await (role-db/set-membership-actor-id! pool (:id membership) "system_admin"))
    {:user user :membership membership}))

(defn ^:async ensure-bootstrap-local-password!
  "Idempotently project the environment-owned bootstrap password into the
   local credential store. Blank passwords revoke any previously provisioned
   local bootstrap credential."
  ([db primary-org bootstrap opts]
   (ensure-bootstrap-local-password!
    db primary-org bootstrap opts
    {:encode-password password/hash-password
     :reconcile-bootstrap-credential!
     mongo-actor-creds/reconcile-bootstrap-local-password!}))
  ([db primary-org bootstrap opts
    {:keys [encode-password reconcile-bootstrap-credential!]}]
   (let [configured-password (some-> (or (:bootstrapSystemAdminPassword opts)
                                         (:bootstrap-system-admin-password opts))
                                     str not-empty)
         user-id (get-in bootstrap [:user :id])
         user-email (get-in bootstrap [:user :email])
         org-id (:id primary-org)
         previous-emails (decisions/previous-bootstrap-system-admin-emails opts user-email)]
     (await (reconcile-bootstrap-credential!
             db {:user-id user-id
                 :org-id org-id
                 :account-identifier user-email
                 :previous-account-identifiers previous-emails
                 :secret-json (when configured-password
                                (assoc (encode-password configured-password)
                                       :bootstrap-system-admin true))}))
     nil)))

(defn get-bootstrap-context!
  [_pool primary-org bootstrap]
  (native/resolved
   {"primaryOrg"    {"id"        (:id primary-org)
                     "slug"      (:slug primary-org)
                     "name"      (:name primary-org)
                     "kind"      (:kind primary-org)
                     "isPrimary" (:is_primary primary-org)
                     "status"    (:status primary-org)}
    "bootstrapUser" {"id"           (get-in bootstrap [:user :id])
                     "email"        (get-in bootstrap [:user :email])
                     "displayName"  (get-in bootstrap [:user :display_name])
                     "membershipId" (get-in bootstrap [:membership :id])}}))

(defn ^:async ensure-bootstrap-allowlist-role! [pool org-id membership-id slug]
  (let [db (await (connection/db!))
        role (or (await (role-db/find-role pool {:slug slug :org-id org-id}))
                 (await (role-db/find-role pool {:slug slug :org-id nil})))]
    (when role
      ;; replace? false: additive link, mirroring the PG insert-membership-role
      ;; (ON CONFLICT DO NOTHING) used during allowlist bootstrap.
      (await (mongo-roles/set-membership-roles! db membership-id false [(:id role)])))))

(defn ^:async ensure-bootstrap-allowlist-user! [pool org-id role-slugs email]
  (let [db (await (connection/db!))
        user (await (mongo-directory/create-user! db {:email email :display-name email
                                                      :auth-provider "bootstrap"
                                                      :external-subject nil :status "active"}))
        ms (await (mongo-directory/upsert-membership! db {:user-id (:id user) :org-id org-id
                                                          :status "active" :is-default false}))]
    (await (connection/promise-each role-slugs
                         (partial ensure-bootstrap-allowlist-role! pool org-id (:id ms))))
    (await (role-db/set-membership-actor-id!
            pool (:id ms) (memberships/default-membership-actor-id role-slugs)))))

(defn ^:async ensure-bootstrap-allowlist-users! [pool primary-org opts]
  (let [emails (decisions/bootstrap-allowlist-emails opts)
        role-slugs (decisions/bootstrap-allowlist-role-slugs opts)
        org-id (:id primary-org)]
    (when (seq emails)
      (await (native/all!
              (vec
               (mapv (partial ensure-bootstrap-allowlist-user! pool org-id role-slugs)
                     emails)))))))
