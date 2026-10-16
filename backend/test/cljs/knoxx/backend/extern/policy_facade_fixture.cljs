(ns knoxx.backend.extern.policy-facade-fixture
  "Inert named Mongo/contract ports for the existing public policy facade."
  (:require [knoxx.backend.domain.contracts.loader :as loader]
            [knoxx.backend.infra.db.actors :as actors]
            [knoxx.backend.infra.mongo-client :as mongo]
            [knoxx.backend.infra.stores.mongo-policy-actor-credentials :as credentials]
            [knoxx.backend.infra.stores.mongo-policy-audit-events :as audit]
            [knoxx.backend.infra.stores.mongo-policy-data-lakes :as lakes]
            [knoxx.backend.infra.stores.mongo-policy-directory :as directory]
            [knoxx.backend.infra.stores.mongo-policy-invites :as invites]
            [knoxx.backend.infra.stores.mongo-policy-roles :as roles]
            [knoxx.backend.infra.stores.mongo-policy-store :as store]
            [knoxx.backend.infra.stores.mongo-policy-tools :as tools]))

(defn world
  ([] (world {}))
  ([responses] {:responses responses :calls (atom [])}))

(defn calls [world] @(:calls world))

(defn operations [world names]
  (filterv #(contains? names (first %)) (calls world)))

(defn- invoke-port [world name fallback args]
  (swap! (:calls world) conj (into [name] args))
  (let [value (get (:responses world) name fallback)]
    (if (fn? value) (apply value args) value)))

(defn- port
  "Match compiled multi-arity store entry points, retaining their exact arguments."
  [world name fallback]
  (let [call (partial invoke-port world name fallback)]
    (fn
      ([] (call []))
      ([a] (call [a]))
      ([a b] (call [a b]))
      ([a b c] (call [a b c]))
      ([a b c d] (call [a b c d]))
      ([a b c d e] (call [a b c d e]))
      ([a b c d e f] (call [a b c d e f])))))

(defn native-value [value] (clj->js value))
(defn native-error [] (js/Error. "owned synthetic policy failure"))

(defn error-summary [error]
  {:message (.-message error) :code (.-code error)
   :status (or (.-statusCode error) (.-status error))})

(defn ^:async caught [task]
  (try {:failed? false :value (await (task))}
       (catch :default error {:failed? true :error error})))

(defn- ^:async with-directory! [world task]
  (with-redefs [actors/find-actor-contract-by-id (port world :actor-by-id nil)
                actors/find-user-actor-contract-by-email (port world :actor-by-email nil)
                actors/upsert-actor-contract! (port world :actor-contract nil)
                directory/find-org-by-id! (port world :org {:id "org" :slug "org-slug"})
                directory/find-org-by-slug (port world :org-by-slug nil)
                directory/find-membership-row-with-user-org! (port world :membership nil)
                directory/find-membership-row-by-email-and-org! (port world :email-membership nil)
                directory/find-user-by-email! (port world :user-by-email nil)
                directory/create-user! (port world :create-user {:id "user"})
                directory/upsert-membership! (port world :upsert-membership {:id "member" :org_id "org"})
                directory/set-membership-actor-id! (port world :actor-binding nil)
                directory/list-orgs! (port world :orgs [])]
    (await (task))))

(defn- ^:async with-roles! [world task]
  (with-redefs [roles/list-roles! (port world :all-roles [])
                roles/list-roles-by-slugs! (port world :roles-by-slugs [])
                roles/list-roles-by-ids! (port world :roles-by-ids [])
                roles/permissions-for-roles! (port world :permissions [])
                roles/roles-for-memberships! (port world :member-roles [])
                roles/set-membership-roles! (port world :assign-roles nil)
                tools/tool-policies-for-roles! (port world :role-tools [])
                tools/tool-policies-for-memberships! (port world :member-tools [])
                tools/ensure-tool-definitions! (port world :ensure-tools nil)
                tools/set-role-tool-policies! (port world :set-role-tools nil)]
    (await (task))))

(defn- ^:async with-stores-and-credentials! [world task]
  (with-redefs [mongo/init-mongo! (port world :connect :db)
                mongo/require-transaction-capable-topology! (port world :topology nil)
                store/ensure-indexes! (port world :indexes nil)
                loader/load-all-contracts! (port world :contracts [])
                credentials/find-active-bootstrap-local-password! (port world :bootstrap-password nil)
                credentials/get-credential-by-user-org-provider-kind! (port world :password nil)
                credentials/get-actor-credential-by-actor-and-provider! (port world :actor-credential nil)
                credentials/list-actor-credentials-by-provider! (port world :provider-credentials [])
                lakes/list-data-lakes-by-org! (port world :lakes [])
                audit/insert-event! (port world :audit nil)
                store/get-session-by-token! (port world :session nil)
                store/delete-session-by-token! (port world :delete-session nil)
                store/cleanup-expired-sessions! (port world :expired-sessions 0)
                invites/pending-by-code! (port world :pending-invite nil)
                invites/redeem-invite! (port world :redeem-invite nil)]
    (await (task))))

(defn ^:async with-stores!
  "Bind only named ports through compiled awaits; restore every temporary binding."
  [world task]
  (await (with-stores-and-credentials! world
           (^:async fn []
             (await (with-roles! world
                      (^:async fn [] (await (with-directory! world task)))))))))
