(ns knoxx.backend.domain.translation-evidence-state
  "Decoded claim and completion transitions for the in-memory evidence store.

   These functions return a new state with its answer. The infrastructure owner
   keeps validation, the atom, the single swap!, and Promise construction."
  (:require [knoxx.backend.law.translation-dispatch :as dispatch-law]))

(defn- in-flight?
  [record]
  (= :dispatch/accepted (:dispatch/outcome record)))

(defn- reserved-state
  "Assemble the next state only after a reservation branch admits the record."
  [current dispatch-key record]
  (-> current
      (assoc-in [:dispatches dispatch-key] record)
      (update :completion-owners dissoc dispatch-key)
      (assoc :answer {:reservation/status :reserved :record record})))

(defn reserve-in-state
  "Claim `record`'s key, or report the claim already there.

   A replaceable existing claim is replaced by `record` outright rather than
   reported as settled. That includes ordinary failed/rejected retries and the
   explicit candidate-unavailable recovery of a completed claim. Replacing
   wholesale is what clears the previous attempt's batch id: left behind, the
   old batch's completion report would resolve the new attempt, minting a receipt
   for a translation the new attempt never produced. See
   `law.translation-dispatch/replaceable-claim?` for the complete admission law."
  [current record]
  (let [dispatch-key (:dispatch/key record)
        existing (get-in current [:dispatches dispatch-key])]
    (cond
      (nil? existing)
      (reserved-state current dispatch-key record)

      (in-flight? existing)
      (assoc current :answer {:reservation/status :in-flight :record existing})

      (dispatch-law/replaceable-claim? record existing)
      (reserved-state current dispatch-key record)

      :else
      (assoc current :answer {:reservation/status :done :record existing}))))

(defn- completion-owner
  "Stable store-private owner for one immutable dispatch attempt."
  [record]
  (pr-str (dispatch-law/attempt-binding record)))

(defn exact-active-attempt?
  "True when the expected immutable attempt remains accepted in this state."
  [current expected-record]
  (let [existing (get-in current [:dispatches (:dispatch/key expected-record)])]
    (and existing
         (in-flight? existing)
         (dispatch-law/same-attempt? existing expected-record))))

(defn claim-completion-in-state
  "Acquire, or idempotently resume, completion for one exact attempt."
  [current expected-record]
  (let [dispatch-key (:dispatch/key expected-record)
        existing (get-in current [:dispatches dispatch-key])
        owner (completion-owner expected-record)
        held-by (get-in current [:completion-owners dispatch-key])]
    (cond
      ;; An equal callback can read accepted just before another equal callback
      ;; finishes. Treat that post-read/pre-claim schedule as an idempotent claim,
      ;; not a nondeterministic already-resolved refusal.
      (and existing
           (= :dispatch/completed (:dispatch/outcome existing))
           (dispatch-law/same-attempt? existing expected-record))
      (assoc current :answer existing)

      (not (exact-active-attempt? current expected-record))
      (assoc current :answer nil)

      (or (nil? held-by) (= owner held-by))
      (-> current
          (assoc-in [:completion-owners dispatch-key] owner)
          (assoc :answer (get-in current [:dispatches dispatch-key])))

      :else
      (assoc current :answer nil))))

(defn finish-completion-in-state
  "Complete the exact attempt after its receipt is durable.

   An already-completed equal attempt is an idempotent finish: two equal
   callbacks may both persist the same receipt, but only one changes the claim."
  [current expected-record detail]
  (let [dispatch-key (:dispatch/key expected-record)
        existing (get-in current [:dispatches dispatch-key])
        owner (completion-owner expected-record)
        held-by (get-in current [:completion-owners dispatch-key])]
    (cond
      (and existing
           (= :dispatch/completed (:dispatch/outcome existing))
           (dispatch-law/same-attempt? existing expected-record))
      (assoc current :answer existing)

      (and (exact-active-attempt? current expected-record)
           (= owner held-by))
      (let [updated (cond-> (assoc existing :dispatch/outcome :dispatch/completed)
                      (some? detail) (assoc :dispatch/detail detail))]
        (-> current
            (assoc-in [:dispatches dispatch-key] updated)
            (update :completion-owners dissoc dispatch-key)
            (assoc :answer updated)))

      :else
      (assoc current :answer nil))))

(defn record-approval-in-state
  "Store `approval` under `identity` unless one is already there, answering
   which happened."
  [current identity approval]
  (if-let [existing (get-in current [:approvals identity])]
    (assoc current :answer {:approval/status :existing :approval existing})
    (-> current
        (assoc-in [:approvals identity] approval)
        (assoc :answer {:approval/status :recorded :approval approval}))))
