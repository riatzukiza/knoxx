(ns knoxx.backend.infra.db.policy.credentials
  "Policy credentials boundary extracted from the Mongo-backed public facade."
  (:require [clojure.string :as str]
            [knoxx.backend.domain.policy.decisions :as decisions]
            [knoxx.backend.extern.policy-db :as native]
            [knoxx.backend.infra.db.policy.connection :as connection]
            [knoxx.backend.infra.stores.mongo-policy-actor-credentials :as mongo-actor-creds]
            [knoxx.backend.infra.stores.mongo-policy-directory :as mongo-directory]
            [knoxx.backend.law.bootstrap-credentials :as bootstrap-law]))

(defn- password-record [row credential]
  {:user-id (:user_id row) :email (:email row) :display-name (:display_name row)
   :membership-id (:id row) :org-id (:org_id row) :org-slug (:org_slug row)
   :actor-id (:actor_id row) :secret-json (native/secret-json->clj (:secret_json credential))})

(defn- ^:async active-password-membership!
  [db normalized user {:keys [find-membership! find-bootstrap-credential! get-membership-credential!]}]
  (let [bootstrap-credential (await (find-bootstrap-credential! db (:id user) normalized))
        query (bootstrap-law/local-password-membership-query normalized bootstrap-credential)]
    (when-let [row (await (find-membership! db query))]
      (when (and (= "active" (:user_status row)) (= "active" (:status row)))
        (let [credential (or bootstrap-credential
                             (await (get-membership-credential! db (:user_id row) (:org_id row)
                                                               "local" "password")))]
          (password-record row credential))))))

(defn ^:async list-actor-credentials!
  "Return active actor credential rows for provider as {:credentials [...]}."
  [_pool provider]
  (when (str/blank? provider)
    (throw (native/error "provider is required")))
  (if-let [db (await (connection/ensure-mongo-policy-db!))]
    {:credentials (mapv mongo-actor-creds/credential-row->response
                        (await (mongo-actor-creds/list-actor-credentials-by-provider! db provider)))}
    {:credentials []}))

(defn ^:async local-password-auth-record!
  "Resolve active local credentials; bootstrap ownership precedes the default membership."
  ([_pool email]
   (local-password-auth-record!
    _pool email
    {:get-db! connection/db!
     :find-user! mongo-directory/find-user-by-email!
     :find-membership! mongo-directory/find-membership-row-by-email-and-org!
     :find-bootstrap-credential! mongo-actor-creds/find-active-bootstrap-local-password!
     :get-membership-credential! mongo-actor-creds/get-credential-by-user-org-provider-kind!}))
  ([_pool email {:keys [get-db! find-user!] :as dependencies}]
   (when-let [normalized (decisions/normalize-email email)]
     (let [db (await (get-db!))
           user (await (find-user! db normalized))]
       (when (and user (= "active" (:status user)))
         (await (active-password-membership! db normalized user dependencies)))))))

(defn ^:async upsert-actor-credential!
  "Upsert an actor credential by user-id + org-id + provider."
  [encode-response _pool _uid _mid user-id {:keys [org-id provider kind account-identifier secret-json status]}]
  (if-let [db (await (connection/ensure-mongo-policy-db!))]
    (let [row (await (mongo-actor-creds/upsert-actor-credential!
                      db user-id org-id provider
                      {:kind kind
                       :account-identifier account-identifier
                       :secret-json (native/keyword-map (or secret-json {}))
                       :status status}))]
      (encode-response row))
    (throw (native/error "Mongo policy store unavailable"))))

(defn ^:async get-actor-credential!
  "An actor's active credential for a provider.

   scope narrows the membership lookup: {:org-id, :membership-id}. actor_id is
   unique nowhere — not even within an org — so an unnarrowed lookup refuses an
   ambiguous actor rather than returning some member's secret. A caller holding a
   request context should pass its membership id, which is exact.

   Dispatches through the context's :get-actor-credential! when it carries one,
   the same seam :resolve-context! and :query! already use. This is the query
   seam mongo-policy-actor-credentials names as the correct dispatch point, and
   it is what lets a harness supply credentials without a database: until it
   existed, every credential-backed tool was unreachable from a test, which is
   most of the Discord and Bluesky surface."
  ([policy-context actor-id provider] (get-actor-credential! policy-context actor-id provider nil))
  ([policy-context actor-id provider scope]
   (if-let [f (:get-actor-credential! policy-context)]
     (await (f actor-id provider scope))
     (when-let [db (await (connection/ensure-mongo-policy-db!))]
       {:credential (mongo-actor-creds/credential-row->response
                     (await (mongo-actor-creds/get-actor-credential-by-actor-and-provider!
                             db actor-id provider scope)))}))))
