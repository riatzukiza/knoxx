(ns knoxx.backend.infra.translation-evidence-memory-dispatch
  "In-memory dispatch adapter for the translation evidence store.

   The root retains its protocol, public policy functions and store factory.
   Existing atom, Promise and validated-dispatch operations stay together here."
  (:require [knoxx.backend.domain.translation-evidence-state :as memory-state]
            [knoxx.backend.law.translation-dispatch :as dispatch-law]))

(defn- checked-reservation
  "Validate the record inside a reservation answer.

   The status is the store's word; the record is data it may have round-tripped
   through a database, so it is checked before a caller reads a revision off it
   and dispatches on that."
  [reservation]
  (cond-> reservation
    (:record reservation) (update :record dispatch-law/assert-record!)))

;; ── In-memory state transitions ────────────────────────────────────────────
;;
;; Each transition is a pure `state -> state` function that also stashes its
;; answer under a well-known key. The answer travels *inside* the new state
;; rather than being computed before the swap because a decision computed first
;; would have been made against a map another caller could already have
;; replaced. One `swap!` therefore both decides and records, and CLJS being
;; single-threaded makes that indivisible — the same property the Mongo
;; implementation gets from a unique index refusing the second insert.

(defn- update-in-flight-state
  "Apply `f` to the exact active attempt, or answer nil when it moved.

   Refusing to touch a terminal record is the compare-and-set the Mongo
   implementation expresses as a query predicate: whoever resolves the claim
   first wins, and the loser learns it lost instead of overwriting an outcome.
   The completion-owner predicate closes the success-vs-failure window: once
   receipt persistence has begun, only the completion finisher may move it."
  [current expected-record f]
  (let [dispatch-key (:dispatch/key expected-record)
        existing (get-in current [:dispatches dispatch-key])]
    (if (and (memory-state/exact-active-attempt? current expected-record)
             (nil? (get-in current [:completion-owners dispatch-key])))
      (let [updated (f existing)]
        (-> current
            (assoc-in [:dispatches dispatch-key] updated)
            (assoc :answer updated)))
      (assoc current :answer nil))))

(defn resolved-answer
  "The answer left by a transition, validated as a record."
  [state]
  (some-> (:answer state) dispatch-law/assert-record!))

(defn- batch-document-match
  [dispatches batch-id document-wire-id]
  (->> (vals dispatches)
       (filter #(and (= batch-id (:dispatch/batch-id %))
                     (= document-wire-id (:dispatch/document-wire-id %))))
       first))

(defn resolve-outcome-in-state
  "Set an in-flight claim's outcome, and its detail when one is given."
  [current expected-record outcome detail]
  (update-in-flight-state current expected-record
                          (fn [record]
                            (cond-> (assoc record :dispatch/outcome outcome)
                              (some? detail) (assoc :dispatch/detail detail)))))

(defn read-dispatch
  "Read and validate one dispatch from an already supplied memory snapshot."
  [state dispatch-key]
  (some-> (get-in state [:dispatches dispatch-key]) dispatch-law/assert-record!))

(defn read-batch-dispatch
  "Read and validate the first dispatch matching the supplied batch/document."
  [state batch-id document-wire-id]
  (some-> (batch-document-match (:dispatches state) batch-id document-wire-id)
          dispatch-law/assert-record!))

(defn read-batch-only-dispatch
  "Read and validate the first dispatch matching the supplied batch."
  [state batch-id]
  (some-> (->> (vals (:dispatches state))
               (filter #(= batch-id (:dispatch/batch-id %)))
               first)
          dispatch-law/assert-record!))

(defn reserve-memory!
  "Atomically reserve a validated dispatch and return its checked answer."
  [state record]
  (js/Promise.resolve
   (checked-reservation
    (:answer (swap! state memory-state/reserve-in-state
                    (dispatch-law/assert-record! record))))))

(defn bind-memory!
  "Atomically bind the exact active attempt without changing validation order."
  [state expected-record batch-id]
  (js/Promise.resolve
   (resolved-answer (swap! state update-in-flight-state
                           (dispatch-law/assert-record! expected-record)
                           #(assoc % :dispatch/batch-id batch-id)))))

(defn claim-memory-completion!
  "Atomically claim the exact attempt and return its validated answer."
  [state expected-record]
  (js/Promise.resolve
   (resolved-answer (swap! state memory-state/claim-completion-in-state
                           (dispatch-law/assert-record! expected-record)))))

(defn finish-memory-completion!
  "Atomically finish a receipt-backed attempt and validate its answer."
  [state expected-record detail]
  (js/Promise.resolve
   (resolved-answer (swap! state memory-state/finish-completion-in-state
                           (dispatch-law/assert-record! expected-record)
                           detail))))
