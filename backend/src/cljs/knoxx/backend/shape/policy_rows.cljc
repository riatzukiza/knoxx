(ns knoxx.backend.shape.policy-rows
  "Policy policy rows boundary extracted from the Mongo-backed public facade.")

(defn grouped-role-permissions
  [rows]
  (reduce (fn [acc {:keys [role_id code]}]
            (update acc role_id (fnil conj []) code))
          {}
          rows))

(defn hydrate-role-row
  [perm-map tool-map {:keys [id org_id name slug scope_kind built_in system_managed created_at updated_at]}]
  {:id id :org-id org_id :name name :slug slug :scope-kind scope_kind
   :built-in built_in :system-managed system_managed
   :created-at created_at :updated-at updated_at
   :permissions (or (get perm-map id) [])
   :tool-policies (or (get tool-map id) [])})

(defn grouped-membership-roles
  [rows]
  (reduce (fn [acc {:keys [membership_id role_id slug name scope_kind org_id]}]
            (update acc membership_id (fnil conj [])
                    {:id role_id :slug slug :name name :scope-kind scope_kind :org-id org_id}))
          {}
          rows))

(defn request-user-map
  [membership-row]
  {:id (:user_id membership-row) :email (:email membership-row)
   :username (:email membership-row) :display-name (:display_name membership-row)
   :status (:user_status membership-row)})

(defn request-org-map
  [membership-row]
  {:id (:org_id membership-row) :slug (:org_slug membership-row)
   :name (:org_name membership-row) :status (:org_status membership-row)
   :is-primary (:is_primary membership-row) :kind (:org_kind membership-row)})

(defn request-membership-map
  [membership actor-id]
  {:id (:id membership) :actor-id actor-id :status (:status membership)
   :is-default (:is-default membership) :created-at (:created-at membership)
   :updated-at (:updated-at membership)})

(defn tool-row->map
  [{:keys [id label description risk_level]}]
  {:id id :label label :description description :risk-level risk_level})

(defn org-response
  [org]
  {:org {:id (:id org) :slug (:slug org) :name (:name org)
         :kind (:kind org) :is-primary (:is_primary org) :status (:status org)}})

(defn user-row->map
  [membership-map credential-map {:keys [id email display_name auth_provider external_subject status created_at updated_at]}]
  {:id id :email email :displayName display_name
   :authProvider auth_provider :externalSubject external_subject
   :status status :createdAt created_at :updatedAt updated_at
   :credentials (or (get credential-map id) [])
   :memberships (or (get membership-map id) [])})

(defn memberships-by-user
  [memberships]
  (reduce (fn [acc m]
            (update acc (:userId m) (fnil conj []) m))
          {}
          memberships))

(defn data-lake-response
  [lake]
  {:data-lake {:id (:id lake) :org-id (:org_id lake) :name (:name lake)
               :slug (:slug lake) :kind (:kind lake) :status (:status lake)}})

(defn invite-response
  [row code]
  {:invite {:id         (:id row)
            :org-id     (:org_id row)
            :code       code
            :email      (:email row)
            :status     (:status row)
            :expires-at (:expires_at row)
            :created-at (:created_at row)}})

(defn redeemed-invite-response [updated code]
  {:invite {:id          (:id updated)
            :org-id      (:org_id updated)
            :code        code
            :email       (:email updated)
            :status      (:status updated)
            :redeemed-at (:redeemed_at updated)
            :created-at  (:created_at updated)}})

(defn policy-context-map [primary-org bootstrap]
  {:pool nil
   :mongo? true
   :primary-org primary-org
   :bootstrap bootstrap
   :bootstrap-user-id (get-in bootstrap [:user :id])
   :bootstrap-membership-id (get-in bootstrap [:membership :id])})
