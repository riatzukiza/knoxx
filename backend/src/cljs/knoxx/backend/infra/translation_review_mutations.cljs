(ns knoxx.backend.infra.translation-review-mutations
  "Current-authority review planning, immutable appends and final projection."
  (:require [knoxx.backend.domain.translation-review :as review]
            [promesa.core :as p]
            [knoxx.backend.infra.translation-review-authority :as authority]
            [knoxx.backend.infra.translation-split-projection :as split-projection]
            [knoxx.backend.infra.translation-split-store :as split-store]
            [knoxx.backend.law.translation-split :as split-law]
            [knoxx.backend.shape.translation-review-wire :as wire]))

(defn- assert-review-dependencies!
  [{:keys [split-store evidence-store digest-hex clock]}]
  (when-not (and split-store evidence-store (fn? digest-hex) (fn? clock))
    (throw (ex-info "translation split review dependencies are unavailable"
                    {:status 503
                     :code "translation_split_review_unavailable"}))))

(defn- ^:async current-review-plan!
  "Read and canonicalize one split before any review append occurs."
  [{:keys [split-store digest-hex]}
   {:keys [candidate-set turn]} split-id request preserve-current-correction?]
  (let [candidate-set-id (:candidate-set/id candidate-set)
        manifest (:translation-turn/manifest turn)
        history (await (split-store/review-history-for-split!
                        split-store candidate-set-id split-id))
        effective (split-law/effective-review-receipt
                   digest-hex manifest candidate-set split-id history)
        request (cond-> request
                  (and preserve-current-correction?
                       (some? (:review/corrected-text effective)))
                  (assoc :split-review/corrected-text
                         (:review/corrected-text effective)))]
    {:split-id split-id
     :history history
     :effective effective
     :canonical (review/canonical-review-request request)}))

(defn- ^:async append-review-plan!
  "Append one precomputed canonical review at a caller-owned operation/time."
  [{:keys [split-store digest-hex]} scope
   {:keys [candidate-set turn]} {:keys [split-id history canonical]}
   recorded-at review-operation-id]
  (let [manifest (:translation-turn/manifest turn)
        review-request (assoc canonical :review/operation-id review-operation-id)
        attempted (split-law/review-receipt
                   digest-hex manifest candidate-set split-id
                   (:principal scope) recorded-at review-request)
        existing? (boolean (some #(= (:review/id attempted) (:review/id %))
                                 history))
        stored (await (split-store/append-review-receipt!
                       split-store attempted))]
    {:status (if existing? :existing :recorded)
     :receipt stored}))

(defn- ^:async append-current-review!
  "Append one canonical single-split receipt at its current review head.

  Separating the append from projection lets a document-level fast path persist
  every split and then compose once. A crash between appends remains safe: the
  publication gate derives readiness from durable review history, while a retry
  resumes through the same deterministic operation identities."
  [{:keys [digest-hex clock] :as deps} scope
   {:keys [candidate-set] :as authority} split-id request]
  (let [{:keys [canonical effective] :as plan}
        (await (current-review-plan! deps authority split-id request false))
        review-operation-id
        (review/operation-id digest-hex (:candidate-set/id candidate-set) split-id
                      (:principal scope) canonical effective)]
    (await (append-review-plan! deps scope authority plan (clock)
                                review-operation-id))))

(defn- ^:async project-current-review!
  [{:keys [split-store evidence-store content-root digest-hex clock
           project-reviewed-output!]} candidate-set-id]
  (let [projector (or project-reviewed-output!
                      split-projection/project-reviewed-output!)]
    (await (projector {:split-store split-store
                       :evidence-store evidence-store
                       :content-root content-root
                       :digest-hex digest-hex
                       :clock clock}
                      candidate-set-id))))

(defn- ^:async append-bulk-review-plans!
  "Append one operation group in manifest order.

   Sequential writes make interruption/retry behavior easy to observe, but
   correctness does not depend on their order: concurrent groups share one
   timestamp internally and the review law selects one common lexical rank."
  [deps scope authority plans recorded-at group-id]
  (loop [remaining plans
         results []]
    (if-let [plan (first remaining)]
      (recur (next remaining)
             (conj results
                   (await
                    (append-review-plan!
                     deps scope authority plan recorded-at
                     (review/bulk-operation-id group-id (:split-id plan))))))
      results)))

(defn ^:async record-split-review!
  "Append one server-attributed split review and refresh its current output.

  The request selects only a candidate set, split, verdict, optional correction,
  and optional evaluation fields. Tenant/project, principal, clock, immutable
  candidate coordinates, and retry identity are all resolved on this side of
  the trust boundary."
  [deps scope request]
  (assert-review-dependencies! deps)
  (let [candidate-set-id (:split-review/candidate-set-id request)
        split-id (:split-review/split-id request)
        authority (await (authority/review-authority! deps scope candidate-set-id))
        appended (await (append-current-review! deps scope authority split-id
                                                request))
        projected (await (project-current-review! deps candidate-set-id))]
    (wire/mutation-result (:status appended) [(:receipt appended)] projected)))

(defn- assert-bulk-request!
  [request]
  (when (contains? request :split-review/corrected-text)
    (throw (ex-info
            "document-level review cannot apply one correction to every split"
            {:status 400
             :code "translation_split_bulk_correction_unsupported"}))))

(defn- ^:async current-bulk-plans!
  [deps authority request]
  (await
   (p/all
    (mapv p/promise
          (mapv #(current-review-plan! deps authority % request true)
                (review/manifest-split-ids authority))))))

(defn- ^:async append-or-replay!
  [deps scope authority candidate-set-id plans recorded-at complete-replay?]
  (if complete-replay?
    (review/replay-results plans)
    (let [group-id (review/bulk-operation-group-id
                    (:digest-hex deps) candidate-set-id (:principal scope) plans)]
      (await (append-bulk-review-plans!
              deps scope authority plans recorded-at group-id)))))

(defn ^:async record-candidate-set-review!
  "Plan every member, append one coherent group, then project exactly once."
  [deps scope request]
  (assert-review-dependencies! deps)
  (assert-bulk-request! request)
  (let [candidate-set-id (:split-review/candidate-set-id request)
        authority (await (authority/review-authority! deps scope candidate-set-id))
        ;; One clock sample precedes all plan reads, including complete replay.
        recorded-at ((:clock deps))
        plans (await (current-bulk-plans! deps authority request))
        replay? (review/complete-replay? (:principal scope) plans)
        appended (await (append-or-replay!
                         deps scope authority candidate-set-id plans recorded-at replay?))
        projected (await (project-current-review! deps candidate-set-id))
        status (if replay? :existing :recorded)
        receipts (mapv :receipt appended)]
    (-> (wire/mutation-result status receipts projected)
        (dissoc :split-review/receipt)
        (assoc :split-review/receipts receipts))))
