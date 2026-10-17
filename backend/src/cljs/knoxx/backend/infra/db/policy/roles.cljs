(ns knoxx.backend.infra.db.policy.roles
  "Policy roles boundary extracted from the Mongo-backed public facade."
  (:require [clojure.string :as str]
            [knoxx.backend.domain.contracts.loader :as contracts-loader]
            [knoxx.backend.domain.contracts.roles :as contracts-roles]
            [knoxx.backend.domain.policy.decisions :as decisions]
            [knoxx.backend.extern.policy-db :as native]
            [knoxx.backend.infra.db.policy.connection :as connection]
            [knoxx.backend.infra.registry.tools :as tool-registry]
            [knoxx.backend.infra.stores.mongo-policy-directory :as mongo-directory]
            [knoxx.backend.infra.stores.mongo-policy-roles :as mongo-roles]
            [knoxx.backend.infra.stores.mongo-policy-tools :as mongo-tools]
            [knoxx.backend.shape.policy-rows :as rows]))

(defn ^:async find-role [_pool {:keys [org-id slug]}]
  (await (mongo-roles/find-role (await (connection/db!)) {:org-id org-id :slug slug})))

(defn ^:async ensure-role!
  [_pool {:keys [org-id name slug scope-kind built-in system-managed]}]
  (await (mongo-roles/ensure-role! (await (connection/db!))
                                   {:org-id org-id :name name :slug slug
                                    :scope-kind scope-kind :built-in built-in
                                    :system-managed system-managed})))

(defn ^:async set-role-permissions!
  [_pool role-id permission-codes]
  (await (mongo-roles/set-role-permissions! (await (connection/db!)) role-id (decisions/unique permission-codes))))

(defn- normalize-tool-policy [p]
  (if (string? p)
    {:tool-id p :effect "allow" :constraints {}}
    (let [tool-id (or (:tool-id p) (:tool_id p) (:id p))]
      (when-not tool-id (throw (native/error "toolId is required for tool policy")))
      {:tool-id (tool-registry/normalize-tool-id tool-id)
       :effect  (if (= (:effect p) "deny") "deny" "allow")
       :constraints (or (:constraints p) {})})))

(defn role-tool-policies
  [caps-by-id contract]
  (let [cap-tool-policies
        (->> (or (:role/capabilities contract) [])
             (keep decisions/keywordish-id)
             (keep caps-by-id)
             (mapcat #(or (:cap/tools %) []))
             (keep tool-registry/normalize-tool-id)
             distinct sort
             (mapv (fn [tid] {:tool-id tid :effect "allow" :constraints {}})))
        declared-tool-policies
        (->> (or (:role/tool-policies contract)
                 (:role/tool_policies contract)
                 (:tool-policies contract)
                 (:toolPolicies contract)
                 [])
             (mapv normalize-tool-policy))]
    (->> (concat cap-tool-policies declared-tool-policies)
         (reduce (fn [acc policy]
                   (assoc acc (:tool-id policy) policy))
                 {})
         vals
         (sort-by :tool-id)
         vec)))

(defn ^:async set-role-tool-policies!
  [_pool role-id tool-policies]
  (let [db (await (connection/db!))
        normalized (mapv (comp native/policy-with-constraints-json normalize-tool-policy) tool-policies)]
    (await (mongo-tools/ensure-tool-definitions! db (mapv :tool-id normalized)))
    (await (mongo-tools/set-role-tool-policies! db role-id normalized))))

(defn- resolve-role-slugs
  [base-ids requested rows alias-map]
  (let [found (into {} (map (fn [r] [(:slug r) (str (:id r))]) rows))
        resolved (keep (fn [slug] (some #(get found %) (get alias-map slug))) requested)
        missing (filter (fn [slug] (not-any? #(contains? found %) (get alias-map slug))) requested)]
    (when (seq missing)
      (throw (native/error (str "Role not found for slug(s): " (str/join ", " missing)))))
    (into (vec base-ids) resolved)))

(defn ^:async resolve-role-ids
  [_pool {:keys [org-id role-ids role-slugs]}]
  (let [base-ids (set (map str (or role-ids [])))]
    (if (empty? role-slugs)
      (vec base-ids)
      (let [requested (decisions/requested-role-slugs role-slugs)
            alias-map (into {} (map (fn [slug] [slug (decisions/role-slug-aliases slug)])) requested)
            query-slugs (->> (vals alias-map) (mapcat identity) distinct vec)
            rows (await (mongo-roles/list-roles-by-slugs! (await (connection/db!)) query-slugs org-id))]
        (resolve-role-slugs base-ids requested rows alias-map)))))

(defn- canonical-contract-role-slug
  [known raw]
  (let [s (some-> raw str str/trim not-empty)]
    (cond
      (and s (contains? known s)) s
      (and s (contains? known (decisions/slugify s s))) (decisions/slugify s s)
      s (do (native/warn! "[policy-db] unknown role slug, skipping:" s) nil)
      :else nil)))

(defn ^:async canonicalize-contract-role-slugs!
  [role-slugs]
  (let [records (await (contracts-loader/load-all-contracts! (native/contracts-config)))
        known (decisions/known-contract-role-slugs records)]
    (->> (or role-slugs [])
         (keep #(canonical-contract-role-slug known %))
         distinct
         vec)))

(defn ^:async resolved-membership-role-slugs!
  [role-slugs contract-projection]
  (if contract-projection
    (await (canonicalize-contract-role-slugs! role-slugs))
    (or role-slugs [])))

(defn ^:async set-membership-roles!
  [pool membership-id {:keys [org-id role-ids role-slugs replace contract-projection]}]
  (let [resolved-slugs (await (resolved-membership-role-slugs! role-slugs contract-projection))
        resolved-ids (await (resolve-role-ids pool {:org-id org-id
                                                    :role-ids (or role-ids [])
                                                    :role-slugs resolved-slugs}))]
    (await (mongo-roles/set-membership-roles! (await (connection/db!)) membership-id (boolean replace) resolved-ids))))

(defn ^:async set-membership-tool-policies!
  [_pool membership-id tool-policies]
  (let [db (await (connection/db!))
        normalized (mapv (comp native/policy-with-constraints-json normalize-tool-policy) tool-policies)]
    (await (mongo-tools/ensure-tool-definitions! db (mapv :tool-id normalized)))
    (await (mongo-tools/set-membership-tool-policies! db membership-id normalized))))

(defn ^:async set-membership-actor-id!
  [_pool membership-id actor-id]
  (await (mongo-directory/set-membership-actor-id! (await (connection/db!)) membership-id actor-id)))

(defn- grouped-role-tool-policies
  [rows]
  (reduce (fn [acc {:keys [role_id tool_id effect constraints_json]}]
            (update acc role_id (fnil conj [])
                    {:tool-id tool_id :effect effect
                     :constraints (native/constraints-json->clj constraints_json)}))
          {}
          rows))

(defn ^:async hydrate-role-maps
  [_pool roles]
  (if (empty? roles)
    []
    (let [db (await (connection/db!))
          role-ids (mapv :id roles)
          perm-rows (await (mongo-roles/permissions-for-roles! db role-ids))
          tool-rows (await (mongo-tools/tool-policies-for-roles! db role-ids))
          perm-map (rows/grouped-role-permissions perm-rows)
          tool-map (grouped-role-tool-policies tool-rows)]
      (mapv #(rows/hydrate-role-row perm-map tool-map %) roles))))

(defn merge-tool-policies [role-policies membership-policies]
  (let [merged (atom {})]
    (doseq [p role-policies]
      (let [n (normalize-tool-policy p) tid (:tool-id n)]
        (when (or (nil? (get @merged tid))
                  (= (:effect n) "deny")
                  (not= (:effect (get @merged tid)) "deny"))
          (swap! merged assoc tid n))))
    (doseq [p membership-policies]
      (let [n (normalize-tool-policy p)]
        (swap! merged assoc (:tool-id n) n)))
    (->> (vals @merged) (sort-by :tool-id) vec)))

(defn list-permissions!
  [_pool]
  (let [codes (->> (contracts-roles/list-role-slugs (native/contracts-config))
                   (mapcat #(contracts-roles/role-permissions (native/contracts-config) %))
                   distinct sort vec)]
    (native/resolved
     {:permissions (mapv (fn [c]
                           {:id           c
                            :code         c
                            :resourceKind (first (str/split c #"\."))
                            :description  ""})
                         codes)})))

(defn ^:async list-tools!
  [_pool]
  (let [rows (await (mongo-tools/list-tools! (await (connection/db!))))]
    {:tools (mapv rows/tool-row->map rows)}))

(defn ^:async list-roles!
  [pool {:keys [org-id]}]
  (let [rows (await (mongo-roles/list-roles! (await (connection/db!)) {:org-id org-id}))]
    {:roles (await (hydrate-role-maps pool rows))}))

(defn ^:async get-role!
  [pool role-id]
  (if-let [row (await (mongo-roles/get-role-by-id! (await (connection/db!)) role-id))]
    {:role (first (await (hydrate-role-maps pool [row])))}
    {:role nil}))

(defn ^:async create-role!
  [pool uid mid {:keys [org-id name slug permission-codes tool-policies]}]
  (cond
    (str/blank? org-id) (throw (native/error "org-id is required"))
    (str/blank? name)   (throw (native/error "name is required"))
    :else
    (let [s (decisions/slugify (or slug name) "role")
          role (await (ensure-role! pool {:org-id org-id :name name :slug s
                                          :scope-kind "org" :built-in false
                                          :system-managed false}))]
      (await (set-role-permissions! pool (:id role) (or permission-codes [])))
      (await (set-role-tool-policies! pool (:id role) (or tool-policies [])))
      (await (connection/append-audit! pool {:actor-user-id uid :actor-membership-id mid
                                  :org-id org-id :action "role.create"
                                  :resource-kind "role" :resource-id (:id role)}))
      {:role (first (await (hydrate-role-maps pool [role])))})))
