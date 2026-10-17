(ns knoxx.backend.infra.routes.resources.handlers
  "Resource and legacy-contract handlers with the existing admission boundary."
  (:require [clojure.string :as str]
            [knoxx.backend.domain.resources.editing :as editing]
            [knoxx.backend.domain.resources.loader :as resources]
            [knoxx.backend.extern.resource-files :as files]
            [knoxx.backend.extern.resources :as native]
            [knoxx.backend.infra.auth.authz :as authz]
            [knoxx.backend.infra.resources.validation :as validation]
            [knoxx.backend.infra.resources.writes :as writes]
            [knoxx.backend.shape.resource-wire :as wire]))

(def ^:private translation-admission-permission
  "org.translations.manage")

(def ^:private publication-admission-permission
  "org.publications.manage")

(defn- normalized-kind [record]
  (or (:resource/kind record)
      (some-> (wire/record-class record) resources/normalize-resource-kind)))

(defn- resource-list-summary [record]
  (wire/resource-list-summary record (normalized-kind record)))

(defn- contract-list-summary [record]
  (wire/contract-list-summary record (normalized-kind record)))

(defn ^:async handle-list-resources
  "List all resources, optionally filtered by resource kind/class.
   Public so tests can call it directly."
  [do-json config resource-kind]
  (try
    (let [all (await (resources/load-all-resources! config))
          resource-class (when resource-kind (validation/normalize-resource-class resource-kind))
          selected (cond->> all
                     resource-class (filter #(= (:resource/class %)
                                                resource-class))
                     :always        (sort-by (juxt :resource/class :resource/id))
                     :always        vec)]
      (do-json 200 {:resources (mapv resource-list-summary selected)}))
    (catch :default err
      (do-json 500 {:detail (str "Failed to list resources: " (native/error-message err))}))))

(defn ^:async handle-list-contracts
  "Compatibility alias for old /contracts clients."
  [do-json config contract-class]
  (try
    (let [all (await (resources/load-all-resources! config))
          resource-class (when contract-class (validation/normalize-resource-class contract-class))
          selected (cond->> all
                     resource-class (filter #(= (:resource/class %)
                                                resource-class))
                     :always        (sort-by (juxt :resource/class :resource/id))
                     :always        vec)]
      (do-json 200 {:contracts (mapv contract-list-summary selected)}))
    (catch :default err
      (do-json 500 {:detail (str "Failed to list contracts: " (native/error-message err))}))))

(defn ^:async handle-get-resource
  [do-json config resource-kind resource-id]
  (try
    (let [edn-text (await (files/read-text! (resources/resource-file-path config resource-kind resource-id)))
          resource-class (validation/normalize-resource-class resource-kind)
          validation (validation/validate-resource-edn resource-class (or edn-text ""))]
      (do-json 200 {:resourceClass resource-class
                    :resource/id resource-id
                    :ednText (or edn-text "")
                    :resource (wire/wire-value (:contract validation))
                    :validation (dissoc validation :contract)}))
    (catch :default err
      (if (= "ENOENT" (native/error-code err))
        (do-json 404 {:detail (str "Resource not found: " resource-id)})
        (do-json 500 {:detail (str "Failed to read resource: " (native/error-message err))})))))

(defn ^:async handle-get-contract
  [do-json config contract-class contract-id]
  (try
    (let [edn-text (await (files/read-text! (resources/resource-file-path config contract-class contract-id)))
          validation (validation/validate-contract-edn contract-class (or edn-text ""))]
      (do-json 200 {:contractClass (validation/normalize-contract-class contract-class)
                    :ednText (or edn-text "")
                    :contract (wire/wire-value (:contract validation))
                    :validation (dissoc validation :contract)}))
    (catch :default err
      (if (= "ENOENT" (native/error-code err))
        (do-json 404 {:detail (str "Contract not found: " contract-id)})
        (do-json 500 {:detail (str "Failed to read contract: " (native/error-message err))})))))

(defn admission-document-id
  "Return the document whose translation inventory changed after a resource write."
  [resource-class resource]
  (case (validation/normalize-resource-class resource-class)
    "documents" (:document/id resource)
    "publications" (:publication/document resource)
    nil))

(defn admission-scope
  "Project an authenticated write context into the trusted admission scope."
  [config ctx]
  (let [org-id (some-> (authz/ctx-org-id ctx) str not-empty)
        membership-id (some-> (authz/ctx-membership-id ctx) str not-empty)]
    (when-not (and org-id membership-id)
      (throw (ex-info "automatic document admission requires an organization and membership context"
                      {:status 403
                       :code "document_admission_context_required"})))
    (cond-> {:org-id org-id :membership-id membership-id}
      (some-> (:session-project-name config) str not-empty)
      (assoc :project (str (:session-project-name config))))))

(defn ^:async admit-saved-publication-resource!
  "Admit a saved document/publication through the app-owned internal hook."
  [config ctx resource-class resource admit!]
  (when-let [document-id (admission-document-id resource-class resource)]
    (when ctx
      (authz/ensure-permission! ctx translation-admission-permission)
      (authz/ensure-permission! ctx publication-admission-permission))
    (let [result (await (admit! (admission-scope config ctx)
                                {:document document-id}))]
      (when-not (and (true? (:ok result))
                     (zero? (or (:failed result) 0))
                     (pos? (or (:admitted result) 0)))
        (throw (ex-info "automatic document admission did not complete"
                        {:status 503
                         :code "document_admission_failed"
                         :document/id document-id
                         :admission result})))
      result)))

(defn- save-observation
  [klass route-id edn-text validate!]
  (let [validation (validate! klass edn-text)]
    {:class klass :validation (dissoc validation :contract)
     :parsed (:contract validation) :parsed-id (editing/parsed-resource-id klass (:contract validation))
     :route-id (str route-id) :valid? (:ok validation)}))

(defn- save-success
  [compat? {:keys [class validation parsed route-id]} edn-text admission]
  (let [base (if compat?
               {:ok true :contractClass class :ednText edn-text :contract (wire/wire-value parsed)
                :validation validation}
               {:ok true :resourceClass class :resource/id route-id :ednText edn-text
                :resource (wire/wire-value parsed) :validation validation})]
    (cond-> base admission (assoc :admission (wire/wire-value admission)))))

(defn- ^:async save-entered-resource!
  [do-json config observation edn-text ctx admit! compat?]
  (try
    (let [{:keys [class parsed route-id]} observation
          admission (await (writes/write-resource-and-admit!
                            config (resources/resource-file-path config class route-id) edn-text
                            (when admit! (fn [] (admit-saved-publication-resource! config ctx class parsed admit!)))))]
      (do-json 200 (save-success compat? observation edn-text admission)))
    (catch :default err
      (do-json (or (:status (ex-data err)) 500)
               {:ok false :detail (str "Failed to save and admit " (if compat? "contract" "resource") ": "
                                       (native/error-message err))
                :code (:code (ex-data err))}))))

(defn- ^:async save-validated-resource!
  [do-json config observation edn-text ctx admit! compat?]
  (let [{:keys [valid? parsed-id route-id validation]} observation
        noun (if compat? "Contract" "Resource")
        lower (str/lower-case noun)]
    (cond
      (not valid?) (do-json 400 {:ok false :detail (str noun " EDN failed validation") :validation validation})
      (and parsed-id (not= route-id parsed-id))
      (do-json 400 (merge {:ok false :detail (str "Refusing to save " lower ": record id does not match route "
                                                 (if compat? "contractId" "resourceId"))
                          :validation validation}
                         (if compat? {:routeContractId route-id :ednContractId parsed-id}
                             {:routeResourceId route-id :ednResourceId parsed-id})))
      :else (await (save-entered-resource! do-json config observation edn-text ctx admit! compat?)))))

(defn ^:async handle-save-resource
  "Preserve the five/seven-argument resource save boundary."
  ([do-json config resource-kind resource-id edn-text]
   (handle-save-resource do-json config resource-kind resource-id edn-text nil nil))
  ([do-json config resource-kind resource-id edn-text ctx admit!]
   (let [klass (validation/normalize-resource-class resource-kind)
         observation (save-observation klass resource-id edn-text validation/validate-resource-edn)]
     (await (save-validated-resource! do-json config observation edn-text ctx admit! false)))))

(defn ^:async handle-save-contract
  "Preserve the five/seven-argument compatibility save boundary."
  ([do-json config contract-class contract-id edn-text]
   (handle-save-contract do-json config contract-class contract-id edn-text nil nil))
  ([do-json config contract-class contract-id edn-text ctx admit!]
   (let [klass (validation/normalize-contract-class contract-class)
         observation (save-observation klass contract-id edn-text validation/validate-contract-edn)]
     (await (save-validated-resource! do-json config observation edn-text ctx admit! true)))))

(defn ^:async handle-copy-resource
  ([do-json config resource-kind source-id new-id]
   (handle-copy-resource do-json config resource-kind source-id new-id nil nil))
  ([do-json config resource-kind source-id new-id ctx admit!]
   (try
     (let [source-edn (await (files/read-text! (resources/resource-file-path config resource-kind source-id)))
           text (or source-edn "")
           cloned (validation/update-resource-id-in-edn-text resource-kind text new-id)]
       (await (handle-save-resource do-json config resource-kind new-id cloned ctx admit!)))
     (catch :default err
       (do-json 500 {:detail (str "Failed to copy resource: " (native/error-message err))})))))

(defn ^:async handle-copy-contract
  ([do-json config contract-class source-id new-id]
   (handle-copy-contract do-json config contract-class source-id new-id nil nil))
  ([do-json config contract-class source-id new-id ctx admit!]
   (try
     (let [source-edn (await (files/read-text! (resources/resource-file-path config contract-class source-id)))
           text (or source-edn "")
           cloned (validation/update-resource-id-in-edn-text contract-class text new-id)]
       (await (handle-save-contract do-json config contract-class new-id cloned ctx admit!)))
     (catch :default err
       (do-json 500 {:detail (str "Failed to copy contract: " (native/error-message err))})))))

(defn handle-validate-resource
  [do-json resource-kind edn-text]
  (let [resource-class (validation/normalize-resource-class resource-kind)]
    (do-json 200 (assoc (wire/wire-validation (validation/validate-resource-edn resource-class edn-text))
                        :resourceClass resource-class))))

(defn handle-validate-contract
  [do-json contract-class edn-text]
  (do-json 200 (assoc (wire/wire-validation (validation/validate-contract-edn contract-class edn-text))
                      :contractClass (validation/normalize-contract-class contract-class))))

(defn ^:async handle-agent-list-contracts
  [do-text config contract-class]
  (try
    (let [ids (await (resources/list-resource-ids! config contract-class))]
      (do-text 200 (pr-str ids)))
    (catch :default err
      (do-text 500 (str ";; Failed to list contracts: " (native/error-message err))))))

(defn ^:async handle-agent-get-contract-edn
  [do-text config contract-class contract-id]
  (try
    (let [edn-text (await (files/read-text! (resources/resource-file-path config contract-class contract-id)))]
      (do-text 200 (str edn-text)))
    (catch :default err
      (if (= "ENOENT" (native/error-code err))
        (do-text 404 (str ";; Contract not found: " contract-id))
        (do-text 500 (str ";; Failed to read contract: " (native/error-message err)))))))

(defn handle-agent-validate-contract-edn
  [do-json contract-class edn-text]
  (do-json 200 (assoc (wire/wire-validation (validation/validate-contract-edn contract-class edn-text))
                      :contractClass (validation/normalize-contract-class contract-class))))

(defn ^:async persist-agent-contract-edn!
  [do-text config route-id edn-text ctx admit! parsed warnings]
  (try
    (let [admission
          (await
           (writes/write-resource-and-admit!
            config (resources/resource-file-path config "agents" route-id)
            edn-text
            (when admit!
              (fn []
                (admit-saved-publication-resource!
                 config ctx "agents" parsed admit!)))))]
      (do-text 200 (pr-str (cond-> {:ok true
                                    :contractClass "agents"
                                    :contract/id route-id
                                    :contract parsed
                                    :warnings warnings}
                             admission (assoc :admission admission)))))
    (catch :default err
      (do-text (or (:status (ex-data err)) 500)
               (str ";; Failed to save and admit contract: "
                    (or (native/error-message err) (ex-message err)))))))

(defn ^:async handle-agent-put-contract-edn
  "Compatibility PUT handler retained for agent clients during resource migration."
  ([do-text config contract-class contract-id edn-text]
   (handle-agent-put-contract-edn do-text config contract-class contract-id edn-text nil nil))
  ([do-text config contract-class contract-id edn-text ctx admit!]
   (let [klass (validation/normalize-contract-class contract-class)
         route-id (str contract-id)]
     (if-not (= "agents" klass)
       (do-text 400 (pr-str {:ok false
                             :error "compatibility_contract_class_not_writable"
                             :contractClass klass}))
       (let [validation (validation/validate-contract-edn klass edn-text)
             parsed (:contract validation)
             parsed-id (editing/parsed-resource-id klass parsed)]
         (cond
           (not (:ok validation))
           (do-text 422 (pr-str {:ok false
                                 :errors (:errors validation)
                                 :warnings (:warnings validation)}))
           (and parsed-id (not= route-id parsed-id))
           (do-text 400 (pr-str {:ok false
                                 :error "contract_id_mismatch"
                                 :routeContractId route-id
                                 :ednContractId parsed-id}))

           :else
           (await (persist-agent-contract-edn!
                   do-text config route-id edn-text ctx admit! parsed
                   (:warnings validation)))))))))

(defn handle-ui-actions
  [do-json config actor-id surface]
  (let [resolved (native/ui-actions-for-actor config actor-id surface)]
    (do-json 200 {:actor_id (:actor-id resolved)
                  :surface (:surface resolved)
                  :default_agent_id (:default-agent-id resolved)
                  :actions (:actions resolved)})))
