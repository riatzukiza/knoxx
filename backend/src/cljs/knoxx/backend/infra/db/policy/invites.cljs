(ns knoxx.backend.infra.db.policy.invites
  "Policy invites boundary extracted from the Mongo-backed public facade."
  (:require [clojure.string :as str]
            [knoxx.backend.extern.policy-db :as native]
            [knoxx.backend.infra.db.policy.connection :as connection]
            [knoxx.backend.infra.db.policy.directory :as directory-db]
            [knoxx.backend.infra.stores.mongo-policy-invites :as mongo-invites]
            [knoxx.backend.shape.policy-rows :as rows]))

(defn ^:async create-invite!
  [pool uid mid {:keys [org-id email role-slugs inviter-membership-id]}]
  (cond
    (str/blank? org-id) (throw (native/error "org-id is required"))
    (str/blank? email)  (throw (native/error "email is required"))
    :else
    (when-let [db (await (connection/ensure-mongo-policy-db!))]
      (let [slugs      (or role-slugs ["basic-user"])
            identity   (native/invite-identity)
            code       (:code identity)
            expires-at (:expires-at identity)
            row (await (mongo-invites/insert-invite!
                        db {:org-id               org-id
                            :code                 code
                            :email                email
                            :inviter-membership-id (or inviter-membership-id mid)
                            :role-slugs-json      (native/json-string slugs)
                            :expires-at           expires-at}))]
        (await (connection/append-audit! pool {:actor-user-id uid :actor-membership-id mid
                                   :org-id org-id :action "invite.create"
                                   :resource-kind "invite" :resource-id (:id row)}))
      (rows/invite-response row code)))))

(defn ^:async redeem-invite!
  [decode-role-slugs pool code email]
  (if (or (str/blank? code) (str/blank? email))
    (throw (native/error "code and email are required"))
    (let [db (await (connection/db!))
          invite (await (mongo-invites/pending-by-code! db code))]
      (if-not invite
        (throw (native/invite-error "Invalid or expired invite code" 400))
        (let [invite-email (str/lower-case (str (:email invite)))
              req-email    (str/lower-case (str email))
              role-slugs   (or (seq (decode-role-slugs (:role_slugs invite)))
                               ["basic-user"])]
          (when-not (= invite-email req-email)
            (throw (native/invite-error "Invite email does not match" 403)))
          (let [updated (await (mongo-invites/redeem-invite! db (:id invite)))]
            (await (directory-db/create-user! pool nil nil
                                 {:email (:email updated)
                                  :display-name (:email updated)
                                  :auth-provider "invite"
                                  :status "active"
                                  :membership-status "active"
                                  :org-id (:org_id updated)
                                  :role-slugs (vec role-slugs)
                                  :is-default true}))
            (rows/redeemed-invite-response updated code)))))))

(defn- invite-row->map
  [decode-role-slugs {:keys [id org_id code email status role_slugs expires_at redeemed_at created_at]}]
  {:id id
   :org-id org_id
   :code code
   :email email
   :status status
   :role-slugs (decode-role-slugs role_slugs)
   :expires-at expires_at
   :redeemed-at redeemed_at
   :created-at created_at})

(defn ^:async list-invites!
  [decode-role-slugs _pool {:keys [org-id status]}]
  (if (str/blank? org-id)
    (throw (native/error "org-id is required"))
    (when-let [db (await (connection/ensure-mongo-policy-db!))]
      {:invites (mapv (partial invite-row->map decode-role-slugs)
                      (await (mongo-invites/list-invites-by-org! db org-id status)))})))
