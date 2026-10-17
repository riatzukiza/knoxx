(ns knoxx.backend.infra.db.policy
  "Public Mongo policy facade; implementation follows the existing named Mongo twins."
  (:require [clojure.string :as str]
            [knoxx.backend.domain.policy.decisions :as decisions]
            [knoxx.backend.extern.policy-db :as native]
            [knoxx.backend.infra.db.policy.actor-projection :as projections]
            [knoxx.backend.infra.db.policy.bootstrap :as bootstrap]
            [knoxx.backend.infra.db.policy.connection :as connection]
            [knoxx.backend.infra.db.policy.credentials :as credential-db]
            [knoxx.backend.infra.db.policy.directory :as directory-db]
            [knoxx.backend.infra.db.policy.invites :as invite-db]
            [knoxx.backend.infra.db.policy.memberships :as memberships]
            [knoxx.backend.infra.db.policy.roles :as role-db]
            [knoxx.backend.infra.mongo-client :as mongo-client]
            [knoxx.backend.infra.stores.mongo-policy-store :as mongo-policy]
            [knoxx.backend.shape.policy-rows :as rows]))

(defn- parse-role-slugs-json
  [value]
  (try
    (native/parse-role-slugs value)
    (catch :default _ [])))

(defn- actor-credential-response [row]
  (native/actor-credential-response row))

(def promise-each connection/promise-each)
(def upsert-actor-contract-best-effort! projections/upsert-actor-contract-best-effort!)
(def db! connection/db!)
(def find-org-by-id connection/find-org-by-id)
(def find-org-by-slug connection/find-org-by-slug)
(def find-role role-db/find-role)
(def ensure-role! role-db/ensure-role!)
(def set-role-tool-policies! role-db/set-role-tool-policies!)
(def resolve-role-ids role-db/resolve-role-ids)
(def canonicalize-contract-role-slugs! role-db/canonicalize-contract-role-slugs!)
(def resolved-membership-role-slugs! role-db/resolved-membership-role-slugs!)
(def set-membership-roles! role-db/set-membership-roles!)
(def set-membership-tool-policies! role-db/set-membership-tool-policies!)
(def set-membership-actor-id! role-db/set-membership-actor-id!)
(def hydrate-role-maps role-db/hydrate-role-maps)
(def hydrate-memberships memberships/hydrate-memberships)
(def detailed-membership-roles memberships/detailed-membership-roles)
(def build-request-context memberships/build-request-context)
(def project-actor-via-store! projections/project-actor-via-store!)
(def sync-actor-projections! projections/sync-actor-projections!)
(def sync-contract-role-record! projections/sync-contract-role-record!)
(def sync-contract-role-projections! projections/sync-contract-role-projections!)
(def ensure-primary-org! bootstrap/ensure-primary-org!)
(def ensure-bootstrap-user! bootstrap/ensure-bootstrap-user!)
(def ensure-bootstrap-local-password! bootstrap/ensure-bootstrap-local-password!)
(def resolve-request-context! memberships/resolve-request-context!)
(def evaluate-tool-access! memberships/evaluate-tool-access!)
(def list-actor-credentials! credential-db/list-actor-credentials!)
(def list-permissions! role-db/list-permissions!)
(def list-tools! role-db/list-tools!)
(def get-bootstrap-context! bootstrap/get-bootstrap-context!)
(def org-data-lake-counts directory-db/org-data-lake-counts)
(def list-orgs! directory-db/list-orgs!)
(def create-org! directory-db/create-org!)
(def self-org-slug decisions/self-org-slug)
(def ensure-self-org! directory-db/ensure-self-org!)
(def list-roles! role-db/list-roles!)
(def get-role! role-db/get-role!)
(def create-role! role-db/create-role!)
(def list-users! directory-db/list-users!)
(def upsert-user-actor-contract-for-membership! projections/upsert-user-actor-contract-for-membership!)
(def create-user! directory-db/create-user!)
(def local-password-auth-record! credential-db/local-password-auth-record!)
(def list-memberships! memberships/list-memberships!)
(def get-membership! memberships/get-membership!)
(def set-membership-roles-public! memberships/set-membership-roles-public!)
(def list-data-lakes! directory-db/list-data-lakes!)
(def create-data-lake! directory-db/create-data-lake!)
(def create-invite! invite-db/create-invite!)
(defn redeem-invite! [pool code email]
  (invite-db/redeem-invite! parse-role-slugs-json pool code email))
(defn list-invites! [pool options]
  (invite-db/list-invites! parse-role-slugs-json pool options))
(def sync-actor-contracts! projections/sync-actor-contracts!)
(def sync-user-from-actor-contract! projections/sync-user-from-actor-contract!)
(def update-user-actor! memberships/update-user-actor!)
(defn upsert-actor-credential! [pool uid mid user-id options]
  (credential-db/upsert-actor-credential!
   actor-credential-response pool uid mid user-id options))
(def get-actor-credential! credential-db/get-actor-credential!)
(def ensure-bootstrap-allowlist-role! bootstrap/ensure-bootstrap-allowlist-role!)
(def ensure-bootstrap-allowlist-user! bootstrap/ensure-bootstrap-allowlist-user!)
(def ensure-bootstrap-allowlist-users! bootstrap/ensure-bootstrap-allowlist-users!)

(defn ^:async touch-session-best-effort!
  [_pool session-id]
  (await (mongo-policy/touch-session-best-effort! session-id)))

(defn ^:async create-session!
  [_pool {:keys [token] :as opts}]
  (if (str/blank? token)
    (throw (native/error "token is required"))
    (await (mongo-policy/create-session! (await (db!)) opts))))

(defn ^:async get-session-by-token!
  [_pool token]
  (when-let [db (await (connection/ensure-mongo-policy-db!))]
    (await (mongo-policy/get-session-by-token! db token))))

(defn ^:async delete-session-by-token!
  [_pool token]
  (when-let [db (await (connection/ensure-mongo-policy-db!))]
    (await (mongo-policy/delete-session-by-token! db token))))

(defn ^:async cleanup-expired-sessions!
  [_pool]
  (if-let [db (await (connection/ensure-mongo-policy-db!))]
    (await (mongo-policy/cleanup-expired-sessions! db))
    0))

(defn ^:async recover-session-secret!
  "Load the session secret from the Mongo config collection, generating and
   persisting one if absent. Returns Promise<string>."
  [_pool]
  (if-let [db (await (connection/ensure-mongo-policy-db!))]
    (await (mongo-policy/recover-session-secret! db nil))
    (throw (native/error "Mongo policy store unavailable"))))

(defn context-pool [policy-context] (:pool policy-context))

(defn configured? [policy-context] (boolean (or (:mongo? policy-context) (:query! policy-context))))

(defn context-primary-org [policy-context] (:primary-org policy-context))

(defn context-bootstrap [policy-context] (:bootstrap policy-context))

(defn context-actor-user-id [policy-context] (:bootstrap-user-id policy-context))

(defn context-actor-membership-id [policy-context] (:bootstrap-membership-id policy-context))

(defn close!
  "No-op for the Mongo policy store: the shared Mongo client is owned by
   infra.mongo-client and closed by the global shutdown path, not per
   policy-context. Retained so graceful-shutdown's call site is unchanged."
  [_policy-context]
  (native/resolved nil))

(defn query!
  "Deprecated raw-SQL entrypoint. The Mongo policy store no longer executes
   SQL, so a Mongo policy-context resolves nil here and legacy callers degrade
   to their empty-result fallbacks. A context that injects its own :query! fn
   (e.g. mailbox routes wiring a custom executor) still has it honored. Use the
   named policy DB functions instead."
  [policy-context sql-str params]
  (if-let [f (:query! policy-context)]
    (f sql-str params)
    (native/resolved nil)))

(defn bootstrap-context!
  [policy-context]
  (if-let [f (:bootstrap-context! policy-context)]
    (f)
    (get-bootstrap-context! (context-pool policy-context)
                            (context-primary-org policy-context)
                            (context-bootstrap policy-context))))

(defn resolve-context!
  [policy-context headers-like]
  (if-let [f (:resolve-context! policy-context)]
    (f headers-like)
    (resolve-request-context! (context-pool policy-context) headers-like)))

(defn sync-actor-contracts-for-context!
  [policy-context]
  (if-let [f (:sync-actor-contracts! policy-context)]
    (f)
    (sync-actor-contracts! (context-pool policy-context)
                           (context-primary-org policy-context))))

(defn sync-user-from-actor-contract-for-context!
  [policy-context opts]
  (if-let [f (:sync-user-from-actor-contract! policy-context)]
    (f opts)
    (sync-user-from-actor-contract! (context-pool policy-context)
                                    (context-primary-org policy-context)
                                    opts)))

(defn create-user-for-context!
  [policy-context payload]
  (create-user! (context-pool policy-context)
                (context-actor-user-id policy-context)
                (context-actor-membership-id policy-context)
                payload))

(defn local-password-auth-record-for-context!
  [policy-context email]
  (local-password-auth-record! (context-pool policy-context) email))

(defn create-invite-for-context!
  [policy-context payload]
  (create-invite! (context-pool policy-context)
                  (context-actor-user-id policy-context)
                  (context-actor-membership-id policy-context)
                  payload))

(defn create-org-for-context!
  [policy-context payload]
  (create-org! (context-pool policy-context)
               (context-actor-user-id policy-context)
               (context-actor-membership-id policy-context)
               payload))

(defn create-role-for-context!
  [policy-context payload]
  (create-role! (context-pool policy-context)
                (context-actor-user-id policy-context)
                (context-actor-membership-id policy-context)
                payload))

(defn create-data-lake-for-context!
  [policy-context payload]
  (create-data-lake! (context-pool policy-context)
                     (context-actor-user-id policy-context)
                     (context-actor-membership-id policy-context)
                     payload))

(defn set-membership-roles-for-context!
  [policy-context membership-id payload]
  (set-membership-roles-public! (context-pool policy-context)
                                (context-actor-user-id policy-context)
                                (context-actor-membership-id policy-context)
                                membership-id
                                payload))

(defn update-user-actor-for-context!
  [policy-context user-id payload]
  (update-user-actor! (context-pool policy-context)
                      (context-actor-user-id policy-context)
                      (context-actor-membership-id policy-context)
                      user-id
                      payload))

(defn upsert-actor-credential-for-context!
  [policy-context user-id payload]
  (upsert-actor-credential! (context-pool policy-context)
                            (context-actor-user-id policy-context)
                            (context-actor-membership-id policy-context)
                            user-id
                            payload))

(defn ^:async allowlist-best-effort! [pool primary-org opts]
  (when (seq (or (:bootstrapAllowlistEmails opts) (:bootstrap-allowlist-emails opts)))
    (try
      (await (ensure-bootstrap-allowlist-users! pool primary-org opts))
      (catch :default err
        (native/warn! "[policy-db] allowlist failed:" (native/error-message err))))))

(defn ^:async sync-actor-contracts-best-effort! [pool primary-org]
  (try
    (await (sync-actor-contracts! pool primary-org))
    (catch :default err
      (native/warn! "[policy-db] actor sync failed:" (native/error-message err)))))

(defn ^:async cleanup-expired-sessions-best-effort! [pool]
  (try
    (await (cleanup-expired-sessions! pool))
    (catch :default _ nil)))

(defn ^:async initialise-policy-db!
  "Mongo-backed initialisation: connect + ensure twin indexes (guarded so a
   bad spec never crash-loops startup), seed the primary org + contract role
   projections + bootstrap user, run best-effort allowlist/actor-sync/cleanup,
   then return the policy context map."
  [opts]
  (let [db (await (connection/ensure-mongo-policy-db!))]
    (when-not db
      (throw (native/error "Mongo policy store unavailable")))
    (await (mongo-client/require-transaction-capable-topology! db))
    (let [primary-org (await (ensure-primary-org! nil opts))]
      (await (sync-contract-role-projections! nil))
      (let [bootstrap (await (ensure-bootstrap-user! nil primary-org opts))]
        (await (ensure-bootstrap-local-password! db primary-org bootstrap opts))
        (await (allowlist-best-effort! nil primary-org opts))
        (await (sync-actor-contracts-best-effort! nil primary-org))
        (await (cleanup-expired-sessions-best-effort! nil))
        (rows/policy-context-map primary-org bootstrap)))))

(defn ^:async create-policy-db
  "Initialise the Mongo-backed policy DB and return a CLJS policy context.

   Security-critical initialization failures propagate. Bootstrap must never
   compose protected routes around a nil policy context."
  [options]
  (let [opts (native/options options)]
    (await (initialise-policy-db! opts))))
