(ns knoxx.backend.infra.translation-review-authority
  "Existing receipt admission and current split authority reads."
  (:require [knoxx.backend.domain.translation-evidence :as evidence-domain]
            [knoxx.backend.infra.translation-evidence-store :as store]
            [knoxx.backend.infra.translation-split-store :as split-store]
            [knoxx.backend.law.translation-evidence :as law]
            [knoxx.backend.law.translation-review-lineage :as lineage]
            [knoxx.backend.law.translation-split-turn :as split-turn-law]))

(defn ^:async load-receipts!
  "A present snapshot, including [], suppresses the replaceable store read."
  [evidence-store scope receipts-transform receipts-snapshot]
  (let [receipts (if (some? receipts-snapshot)
                   receipts-snapshot
                   (await (store/completed-translations!
                           evidence-store (select-keys scope [:org-id :project]))))]
    ((or receipts-transform identity) receipts)))

(defn- admissible-receipts
  [scope receipt-admissible? receipts]
  (filterv #(and (= (:org-id scope) (:translation/org-id %))
                 (= (:project scope) (:translation/project %))
                 (or (nil? receipt-admissible?) (receipt-admissible? %)))
           receipts))

(defn- ^:async current-receipt!
  "Index tenant-scoped receipts through the existing context-free evidence law.

  A targeted newest-row query would duplicate its admission/generation/order
  authority. Transform first; scope and optional admissibility are independent
  guarantees even when a replaceable store ignores its query scope."
  [evidence-store scope request receipt-admissible? receipts-transform
   receipts-snapshot]
  (let [receipts (await (load-receipts! evidence-store scope receipts-transform
                                      receipts-snapshot))
        evidence (evidence-domain/evidence
                  {:receipts (admissible-receipts scope receipt-admissible? receipts)})]
    (evidence-domain/receipt-for evidence
                                 (:review/document request)
                                 (:review/garden request)
                                 (:review/locale request)
                                 (:review/revision request))))

(defn- assert-approval-authorizer!
  [authorize-approval!]
  (when-not (fn? authorize-approval!)
    (throw (ex-info "translation approval requires content admission"
                    {:status 503
                     :code "translation_approval_admission_unavailable"}))))

(defn- ^:async record-current-approval!
  [{:keys [evidence-store clock authorize-approval!]} scope checked receipt]
  (await (authorize-approval! checked receipt))
  (await (store/record-approval!
          evidence-store
          (law/approve checked receipt (:principal scope) (clock)))))

(defn ^:async approve!
  "Validate exact receipt admission before authorizing bytes and recording approval."
  [{:keys [evidence-store authorize-approval! receipt-admissible?
           receipts-transform receipts-snapshot] :as deps} scope request]
  (assert-approval-authorizer! authorize-approval!)
  (let [checked (law/assert-approval-request! request)
        receipt (await (current-receipt! evidence-store scope checked
                                         receipt-admissible? receipts-transform
                                         receipts-snapshot))]
    (if-let [refusal (law/approval-refusal checked receipt)]
      {:approval/refusal refusal}
      (await (record-current-approval! deps scope checked receipt)))))

(defn- ^:async receipt-for-current-dispatch!
  [evidence-store scope turn record record-current?]
  (await
   (current-receipt!
    evidence-store scope
    (lineage/manifest-review-request (:translation-turn/manifest turn))
    (fn [candidate]
      (and record-current?
           (= (:translation/dispatch-key candidate) (:dispatch/key record))
           (store/receipt-visible-for-dispatch? candidate record)))
    nil nil)))

(defn- current-authority?
  [record-current? candidate-set receipt record]
  (and record-current?
       (lineage/complete-split-lineage? receipt)
       (= (:candidate-set/id candidate-set) (:translation/candidate-set-id receipt))
       (some? (:translation/dispatch-attempt-id receipt))
       (store/receipt-visible-for-dispatch? receipt record)))

(defn- ^:async assert-current-review-authority!
  [evidence-store scope turn candidate-set]
  (let [record (await (store/dispatch-for-key!
                       evidence-store (:translation-turn/dispatch-key turn)))
        record-current? (split-turn-law/review-binding-matches? record turn)
        receipt (await (receipt-for-current-dispatch!
                        evidence-store scope turn record record-current?))]
    (when-not (current-authority? record-current? candidate-set receipt record)
      (lineage/split-refusal!
       "translation candidate set is no longer the current completed generation"
       "translation_split_candidate_set_stale"
       {:candidate-set/id (:candidate-set/id candidate-set)
        :current-candidate-set/id (:translation/candidate-set-id receipt)
        :dispatch/outcome (:dispatch/outcome record)}))
    (lineage/assert-lineage-binding! receipt turn candidate-set)
    receipt))

(defn ^:async review-authority!
  "Resolve one candidate set and require its exact current completion authority."
  [{:keys [split-store evidence-store]} scope candidate-set-id]
  (let [candidate-set (await (split-store/candidate-set-by-id!
                              split-store candidate-set-id))
        turn (await (split-store/turn-for-candidate-set!
                     split-store candidate-set-id))]
    (when-not (and candidate-set turn)
      (lineage/split-refusal! "translation candidate set is not persisted"
                      "translation_split_candidate_set_missing"
                      {:candidate-set/id candidate-set-id}))
    (lineage/assert-review-scope! scope (:translation-turn/manifest turn))
    (await (assert-current-review-authority!
            evidence-store scope turn candidate-set))
    {:candidate-set candidate-set :turn turn}))
