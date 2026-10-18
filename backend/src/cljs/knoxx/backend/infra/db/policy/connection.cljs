(ns knoxx.backend.infra.db.policy.connection
  "Policy connection boundary extracted from the Mongo-backed public facade."
  (:require [knoxx.backend.extern.policy-db :as native]
            [knoxx.backend.infra.mongo-client :as mongo-client]
            [knoxx.backend.infra.stores.mongo-policy-audit-events :as mongo-audit]
            [knoxx.backend.infra.stores.mongo-policy-directory :as mongo-directory]
            [knoxx.backend.infra.stores.mongo-policy-store :as mongo-policy]))

(declare ensure-mongo-policy-db!)

(defn ^:async promise-each
  [items f]
  (doseq [item (or items [])]
    (await (f item)))
  nil)

(defn ^:async db!
  "Resolve the connected Mongo db, ensuring twin indexes once. Throws when
   Mongo is unavailable so write paths surface the failure rather than
   silently no-op."
  []
  (if-let [db (await (ensure-mongo-policy-db!))]
    db
    (throw (native/error "Mongo policy store unavailable"))))

(defn ^:async find-org-by-id [_pool org-id]
  (await (mongo-directory/find-org-by-id! (await (db!)) org-id)))

(defn ^:async find-org-by-slug [_pool slug]
  (await (mongo-directory/find-org-by-slug (await (db!)) slug)))

(defn ^:async append-audit! [_pool {:keys [before after] :as opts}]
  (when-let [db (await (mongo-client/init-mongo!))]
    (await (mongo-audit/insert-event!
            db (assoc opts
                      :before-json (when before (native/json-string before))
                      :after-json  (when after  (native/json-string after)))))))

(defn ^:async ensure-mongo-policy-db!
  "Connect to Mongo (idempotent) and ensure the policy-store indexes exist.
   Returns the db, or nil when Mongo is unavailable. Index setup is guarded by
   ensure-indexes! so a bad spec never crash-loops startup."
  []
  (let [db (await (mongo-client/init-mongo!))]
    (when db
      (await (mongo-policy/ensure-indexes! db)))
    db))
