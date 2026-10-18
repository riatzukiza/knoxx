(ns knoxx.backend.infra.translation-dispatch.worker
  "Reserve before sending; recover exact worker batches without blind retries."
  (:require [knoxx.backend.domain.node.crypto :as crypto]
            [knoxx.backend.domain.translation-dispatch-outcome :as outcome]
            [knoxx.backend.extern.translation-dispatch-observation :as observation]
            [knoxx.backend.infra.clients.openplanner :as openplanner-client]
            [knoxx.backend.infra.translation-dispatch.completion :as completion]
            [knoxx.backend.infra.translation-evidence-store :as store]
            [knoxx.backend.law.translation-dispatch :as law]))

(defn- ^:async request-batch!
  "Validate the worker response before returning an attributable batch id."
  [client work context]
  (let [request (law/worker-request work context)
        response (await (openplanner-client/create-translation-batch! client request))]
    (:batch_id (law/assert-valid! :translation-dispatch/batch-created
                                  law/BatchCreated
                                  response))))

(defn- ^:async observe-batch!
  "Read the same tenant/key listing before deciding whether absence is proven."
  [client work context record listing-cap]
  (let [response (await (openplanner-client/translation-batches!
                         client
                         {:org_id (:dispatch/org-id context)
                          :garden_id (:dispatch/garden context)
                          :dispatch_key (:dispatch/key record)
                          :target_lang (name (:locale work))}))
        batches (vec (:batches response))]
    (outcome/batch-observation batches context work record listing-cap)))

(defn- ^:async try-bind!
  "Attempt one exact batch binding and retain nil on the original refusal."
  [evidence-store record batch-id]
  (try
    (await (store/bind-dispatch-batch! evidence-store record batch-id))
    (catch :default bind-error
      (observation/degraded! :bind-batch bind-error)
      nil)))

(defn- ^:async bind-created-batch!
  "Try the known batch binding at most twice, never creating another batch."
  [evidence-store record batch-id]
  (let [bound (or (await (try-bind! evidence-store record batch-id))
                  (await (try-bind! evidence-store record batch-id)))]
    (if bound
      {:dispatch/outcome :dispatch/accepted :dispatch/record bound}
      (outcome/unbound-batch record batch-id))))

(defn- ^:async recover-observation!
  "Preserve absence, sole-match and unresolved-listing branch order."
  [evidence-store record candidates conclusive? detail]
  (cond
    (and conclusive? (empty? candidates))
    (assoc (await (completion/settle-failure! evidence-store record detail))
           :dispatch/detail detail)
    (= 1 (count candidates))
    (let [batch-id (:batch_id (first candidates))
          rebound (await (bind-created-batch! evidence-store record batch-id))]
      (outcome/recovered-binding rebound detail))
    (seq candidates)
    (outcome/accepted-detail record detail
                             (str "; multiple batches carry one dispatch key;"
                                  " the queue uniqueness invariant is broken"))
    :else
    (outcome/accepted-detail record detail
                             (str "; batch listing was truncated, so absence is"
                                  " not proof the send failed"))))

(defn- ^:async recover-ambiguous-send!
  "Keep unknown sends accepted unless the original observation proves absence."
  [evidence-store client work context record detail listing-cap]
  (try
    (let [{:keys [candidates conclusive?]}
          (await (observe-batch! client work context record listing-cap))]
      (await (recover-observation! evidence-store record candidates conclusive? detail)))
    (catch :default observe-error
      (outcome/accepted-detail record detail
                               (str "; observation also failed: "
                                    (ex-message observe-error))))))

(defn- ^:async accept-dispatch!
  "Create once; distinguish an unknown create from a known batch binding."
  [evidence-store client work context record listing-cap]
  (let [created (try
                  {:batch-id (await (request-batch! client work context))}
                  (catch :default err
                    {:error (or (not-empty (str (ex-message err)))
                                "unknown worker failure")}))]
    (if (:error created)
      ;; The create itself failed, so whether it landed is genuinely unknown.
      (await (recover-ambiguous-send! evidence-store client work context record
                                      (:error created) listing-cap))
      (await (bind-created-batch! evidence-store record (:batch-id created))))))

(defn- ^:async reserve-and-send!
  "Reserve first and preserve the existing in-flight recovery and done branches."
  [{:keys [evidence-store client] :as deps} checked-work context record listing-cap]
  (let [reservation (await (store/reserve-dispatch! evidence-store record))]
    (case (:reservation/status reservation)
      :reserved (await (accept-dispatch! evidence-store client checked-work context
                                         (:record reservation) listing-cap))

      ;; In flight is not necessarily still running. Two recoverable cases hide
      ;; here, and reporting duplicate for both is what leaves a claim stuck
      ;; forever: with no batch id an earlier send was ambiguous, and with one
      ;; the batch may have finished while the bookkeeping that should have
      ;; recorded it failed.
      :in-flight
      (if (:dispatch/batch-id (:record reservation))
        (await (completion/recover-settled-batch! deps (:record reservation)))
        (await (recover-ambiguous-send!
                evidence-store client checked-work context (:record reservation)
                "recovering an earlier ambiguous send" listing-cap)))

      ;; Already settled. Nothing to re-enqueue.
      :done
      {:dispatch/outcome :dispatch/duplicate
       :dispatch/record (:record reservation)})))

(defn ^:async dispatch-work!
  "Validate and construct the same claim before reserving or calling the worker."
  [deps work context listing-cap clock]
  (let [checked-work (law/assert-valid! :translation-dispatch/work law/DerivedWork work)
        pin-refusal (law/pin-refusal checked-work context)
        record (law/dispatch-record checked-work
                                    context
                                    (if pin-refusal
                                      law/unreachable-outcome
                                      :dispatch/accepted)
                                    (clock)
                                    :attempt-id (crypto/random-uuid)
                                    :recovery-reason
                                    (:dispatch/recovery-reason context))]
    (if pin-refusal
      ;; Refused before any batch is created, and deliberately NOT persisted. A
      ;; pin refusal is a decision about *current* state, not an observed fact:
      ;; restore the checkout to the pinned bytes and it simply stops applying.
      ;; Recorded as a terminal claim it would outlive its own reason — the
      ;; reservation would keep finding it, answer duplicate, and block a pin
      ;; that had become perfectly valid. Nothing is written, so the next pass
      ;; re-decides from scratch.
      {:dispatch/outcome law/unreachable-outcome
       :dispatch/record record
       :translation/refusal pin-refusal}
      (await (reserve-and-send! deps checked-work context record listing-cap)))))
