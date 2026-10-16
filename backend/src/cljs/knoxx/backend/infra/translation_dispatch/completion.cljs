(ns knoxx.backend.infra.translation-dispatch.completion
  "Ordered completion ownership, source observation and receipt persistence."
  (:require [knoxx.backend.domain.translation-dispatch-outcome :as outcome]
            [knoxx.backend.extern.translation-dispatch-observation :as observation]
            [knoxx.backend.infra.clients.openplanner :as openplanner-client]
            [knoxx.backend.infra.translation-evidence-store :as store]
            [knoxx.backend.law.translation-dispatch :as law]))

(defn ^:async settle-failure!
  "Fail only the exact active attempt; otherwise retain its current refusal."
  [evidence-store record detail]
  (if-let [settled (await (store/resolve-dispatch!
                           evidence-store record :dispatch/failed detail))]
    {:dispatch/outcome :dispatch/failed
     :dispatch/record settled}
    {:dispatch/outcome :dispatch/duplicate
     :dispatch/record (or (await (store/dispatch-for-key!
                                  evidence-store (:dispatch/key record)))
                          record)
     :translation/refusal (outcome/attempt-lost-refusal record)}))

(defn- ^:async record-completion!
  "Claim completion, persist its receipt, then finish only that exact attempt."
  [evidence-store clock record detail content-digest split-evidence]
  (if-let [claimed (await (store/claim-dispatch-completion! evidence-store record))]
    (let [receipt (law/translation-receipt claimed (law/output-revision claimed)
                                           (clock) content-digest split-evidence)
          stored-receipt (await (store/record-translation! evidence-store receipt))
          settled (await (store/finish-dispatch-completion!
                          evidence-store claimed detail))]
      (when-not settled
        (throw (ex-info "translation completion ownership was lost after receipt persistence"
                        {:dispatch/key (:dispatch/key claimed)
                         :dispatch/attempt-id (:dispatch/attempt-id claimed)})))
      {:translation/receipt stored-receipt
       :dispatch/record settled})
    {:translation/refusal (outcome/attempt-lost-refusal record)}))

(defn- ^:async refuse-drifted-completion!
  "Settle a moved source as the same terminal unreachable dispatch."
  [evidence-store record drift]
  (let [settled (await (store/resolve-dispatch!
                        evidence-store record
                        law/unreachable-outcome
                        "source moved between dispatch and completion"))]
    (cond-> {:translation/refusal drift}
      (some? settled) (assoc :dispatch/record settled))))

(defn- ^:async complete-if-source-agrees!
  "Observe source bytes before either receipt persistence or drift settlement."
  [evidence-store clock observe-source-revision record content-digest split-evidence]
  (if-let [drift (law/source-drift-refusal
                  record
                  (await (observe-source-revision record)))]
    (await (refuse-drifted-completion! evidence-store record drift))
    (await (record-completion! evidence-store clock record nil content-digest
                               split-evidence))))

(defn- ^:async recovered-completion-result!
  "Read a replacement only after the same moved-attempt refusal."
  [evidence-store record result]
  (let [attempt-moved? (= :dispatch-already-resolved
                          (get-in result [:translation/refusal :refusal/type]))
        current (when attempt-moved?
                  (await (store/dispatch-for-key!
                          evidence-store (:dispatch/key record))))]
    (outcome/recovered-completion record result current)))

(defn- ^:async batch-document-outcome!
  "Read a batch conservatively; classify its document outside the fetch catch."
  [client record]
  (let [batch (try
                (await (openplanner-client/translation-batch!
                        client (:dispatch/batch-id record)
                        {:org_id (:dispatch/org-id record)}))
                (catch :default batch-error
                  (observation/degraded! :read-batch batch-error)
                  nil))]
    (when batch
      (law/batch-document-outcome batch (:dispatch/document-wire-id record)))))

(defn ^:async recover-settled-batch!
  "Recover this document's outcome through the same source and receipt guards."
  [{:keys [evidence-store client clock observe-source-revision]} record]
  (when-not (fn? observe-source-revision)
    (throw (ex-info "recovering a settled batch requires a source-revision observer"
                    {:dispatch/key (:dispatch/key record)})))
  (let [result (await (batch-document-outcome! client record))]
    (case result
      :completed
      (await (recovered-completion-result!
              evidence-store record
              (await (complete-if-source-agrees!
                      evidence-store clock observe-source-revision record nil nil))))
      :failed
      (await (settle-failure! evidence-store record
                              "the batch reports this document failed"))
      {:dispatch/outcome :dispatch/duplicate :dispatch/record record})))

(defn- ^:async existing-split-completion!
  "Read the first exact full-lineage receipt for a terminal split retry."
  [evidence-store record content-digest split-evidence]
  (when (and record split-evidence)
    (some #(when (outcome/exact-split-completion? record content-digest split-evidence %) %)
          (await (store/completed-translations!
                  evidence-store
                  {:org-id (:dispatch/org-id record)
                   :project (:dispatch/project record)})))))

(defn- ^:async resolve-checked-report!
  "Keep terminal split retry and source-checked completion branches in order."
  [{:keys [evidence-store clock observe-source-revision
           translation-content-digest translation-split-evidence]} record checked]
  (if-let [refusal (law/completion-refusal record checked)]
    (if-let [receipt (await (existing-split-completion!
                             evidence-store record translation-content-digest
                             translation-split-evidence))]
      {:translation/receipt receipt}
      {:translation/refusal refusal})
    (await (complete-if-source-agrees! evidence-store clock
                                       observe-source-revision record
                                       translation-content-digest
                                       translation-split-evidence))))

(defn ^:async resolve-batch-report!
  "Validate and join a report before the unchanged refusal/completion stages."
  [{:keys [evidence-store observe-source-revision] :as deps} report]
  (when-not (fn? observe-source-revision)
    (throw (ex-info "resolving a completion requires a source-revision observer"
                    {:batch_id (:batch_id report)})))
  (let [checked (law/assert-valid! :translation-dispatch/status-report
                                   law/BatchStatusReport
                                   report)
        record (await (store/dispatch-for-batch-document!
                       evidence-store (:batch_id checked)
                       (:completed_document checked)))]
    (await (resolve-checked-report! deps record checked))))
