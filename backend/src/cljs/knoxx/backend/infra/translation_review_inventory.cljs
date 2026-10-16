(ns knoxx.backend.infra.translation-review-inventory
  "Resource-first inventory with ordered concurrent dispatch and split reads."
  (:require [knoxx.backend.domain.translation-evidence :as evidence-domain]
            [knoxx.backend.domain.translation-review :as review]
            [knoxx.backend.domain.translation-review-inventory :as inventory]
            [promesa.core :as p]
            [knoxx.backend.infra.translation-evidence-store :as store]
            [knoxx.backend.infra.translation-review-authority :as authority]
            [knoxx.backend.infra.translation-split-projection :as split-projection]
            [knoxx.backend.law.translation-review-lineage :as lineage]
            [knoxx.backend.shape.translation-review-wire :as wire]))

(defn- ^:async split-review-projection!
  [translation-split-store digest-hex receipt]
  (let [{:keys [turn candidate-set review-histories]}
        (await (split-projection/current-reviewed-output!
                {:split-store translation-split-store :digest-hex digest-hex}
                (:translation/candidate-set-id receipt)))
        manifest (:translation-turn/manifest turn)]
    (lineage/assert-lineage-binding! receipt turn candidate-set)
    (review/split-review-projection digest-hex manifest candidate-set review-histories)))

(defn- ^:async attach-split-review!
  [translation-split-store digest-hex current-by-work item review]
  (let [receipt (get current-by-work (inventory/work-key item))]
    (if (lineage/complete-split-lineage? receipt)
      (let [projection (await (split-review-projection!
                               translation-split-store digest-hex receipt))]
        (assoc review :split_review projection))
      review)))

(defn- ^:async attach-split-reviews!
  [translation-split-store digest-hex work receipts reviews]
  (let [current-by-work (evidence-domain/current-receipts-by-work receipts)]
    (await
     (p/all
      (mapv p/promise
            (mapv #(attach-split-review! translation-split-store digest-hex
                                        current-by-work %1 %2)
                  work reviews))))))

(defn- assert-dispatch-work!
  [scope item dispatch-key record]
  (when (and record
             (or (not= dispatch-key (:dispatch/key record))
                 (not (lineage/dispatch-matches-work? scope item record))))
    (throw (ex-info "translation dispatch lookup returned the wrong claim"
                    {:expected-dispatch-key dispatch-key
                     :actual-dispatch-key (:dispatch/key record)
                     :expected-work (inventory/work-key item)
                     :actual-work (inventory/work-key record)}))))

(defn- ^:async dispatch-for-work!
  [evidence-store scope item]
  (if-let [dispatch-key (inventory/dispatch-lookup-key scope item)]
    (let [record (await (store/dispatch-for-key! evidence-store dispatch-key))]
      (assert-dispatch-work! scope item dispatch-key record)
      record)
    nil))

(defn- ^:async dispatches-for-work!
  [evidence-store scope work]
  (await
   (p/all
    (mapv p/promise
          (mapv #(dispatch-for-work! evidence-store scope %) work)))))

(defn- ^:async current-approvals!
  [evidence-store translation-split-store digest-hex scope receipts]
  (let [stored (lineage/scoped-approvals
                scope
                (await (store/approvals! evidence-store
                                         (select-keys scope [:org-id :project]))))]
    (await (split-projection/current-review-approvals!
            {:split-store translation-split-store :digest-hex digest-hex}
            receipts stored))))

(defn ^:async reviewable!
  "Left-join resource work with scoped evidence and current split-review facts."
  [{:keys [evidence-store split-store digest-hex publication-index
           source-revisions receipts-transform receipts-snapshot]} scope]
  (let [work (wire/scoped-work
              scope (inventory/desired-work publication-index source-revisions))
        receipts (lineage/scoped-receipts
                  scope (await (authority/load-receipts!
                                evidence-store scope receipts-transform
                                receipts-snapshot)))
        approvals (await (current-approvals! evidence-store split-store digest-hex
                                             scope receipts))
        dispatches (await (dispatches-for-work! evidence-store scope work))
        reviews (inventory/project-inventory work receipts approvals dispatches)
        reviews (await (attach-split-reviews! split-store digest-hex
                                              work receipts reviews))]
    {:project (:project scope) :reviews reviews}))
