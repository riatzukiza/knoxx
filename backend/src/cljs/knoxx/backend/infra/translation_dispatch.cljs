(ns knoxx.backend.infra.translation-dispatch
  "Dispatching the publication gate's derived translation work, and turning the
  worker's answer back into evidence.

  Two directions, both of them narrow:

    dispatch-work!        derived work -> a claim, then the worker's batch
    resolve-batch-report! the worker's status report -> a translation receipt

  Neither reimplements translation. The worker already exists — it polls
  `/api/translations/batches/next`, translates in a shared agent session, and
  reports progress back through `/api/translations/batches/:id/status`. This
  namespace only asks it for work and interprets what it says, which is what the
  card means by dispatching 'through the established worker boundary'.

  The worker stores the dispatch key for exact recovery; revision evidence stays
  here, in the Knoxx dispatch record joined through that key.

  ## Observe before deciding

  Three of the paths below recover rather than guess, and they share one reason:
  older worker batches carried no idempotency key, so a request whose outcome is
  unknown cannot be safely repeated against a legacy server. New batches carry
  a Knoxx dispatch key and are tenant-uniqued by it. The same rule
  `infra.publication-effects/reconcile-in-flight!` follows for the same reason.

  So an ambiguous send is observed (`recover-ambiguous-send!`), an in-flight
  claim that may have finished is re-read (`recover-settled-batch!`), and only a
  send that provably did not land becomes retriable. Where nothing can be
  observed, the claim is left in flight: a stuck claim is visible and fixable, a
  duplicate translation is neither."
  (:require [knoxx.backend.domain.publication-gate :as gate]
            [knoxx.backend.infra.translation-dispatch.completion :as completion]
            [knoxx.backend.infra.translation-dispatch.worker :as worker]
            [knoxx.backend.infra.translation-evidence-store :as store]
            [knoxx.backend.law.translation-dispatch :as law]
            [knoxx.backend.shape.resource-identity :as resource-identity]))

(defn dispatch-context
  "The dispatch context for one hydrated publication intent.

   The garden, the document's wire id and the source locale are facts about the
   intent; the organization, membership and project are facts about the acting
   principal and its configuration. None is defaulted — a dispatch that invented
   an organization would file a batch in a tenant nobody named.

   `source-digest` is the content digest observed at dispatch time, and it is
   deliberately separate from the intent's `:publication/revision`. An intent may
   pin an opaque revision like `\"abc123\"`, which no observer can ever reproduce;
   completion compares digest to digest, so the digest has to be recorded
   independently of whatever the intent chose to call the revision."
  [intent {:keys [org-id membership-id project]} source-digest]
  (law/assert-valid!
   :translation-dispatch/context
   law/DispatchContext
   (cond-> {:dispatch/garden (resource-identity/encode-keyword
                              (:publication/garden intent))
            :dispatch/document-wire-id (resource-identity/encode-keyword
                                        (:publication/document intent))
            :dispatch/source-locale (:document/source-locale intent)
            :dispatch/org-id org-id
            :dispatch/membership-id membership-id}
     (some? project) (assoc :dispatch/project project)
     (some? source-digest) (assoc :dispatch/source-digest source-digest))))

(def batch-listing-cap
  "The row limit the batch listing applies, mirrored from
   `extern.openplanner-translation-mongo.batches`.

   Named here because this namespace has to reason about *hitting* it: a full
   page means the listing may have truncated, so a batch missing from it is not
   proof the batch does not exist. Mirrored rather than imported because the
   store boundary does not expose it, and a test pins the two together."
  50)

(defn ^:async dispatch-work!
  "Dispatch one derived translation work item, exactly once.

   The claim is taken *before* the worker is called. Calling first and recording
   after would leave a window in which a second pass sees no claim, enqueues a
   second batch, and translates the same revision twice — and the second
   translation cannot be withdrawn, because nothing recorded that the first was
   already asked for.

   Returns the outcome and the record it applies to. A duplicate is not an
   error: it is the correct answer to asking twice."
  [{:keys [clock] :as deps} work context]
  (await (worker/dispatch-work! deps work context batch-listing-cap clock)))

(defn ^:async candidate-recovery-context!
  "Mark a fresh attempt when the evidence snapshot found no usable candidate.

  The caller invokes this only for work the gate derived from a content-
  authenticated evidence snapshot. If the same key already completed, the
  missing work means its candidate bytes are unavailable (including historical
  receipts that never bound bytes), so the explicit store recovery CAS is
  lawful. Other outcomes keep their ordinary reservation semantics."
  [evidence-store work context]
  (let [dispatch-key (law/dispatch-key
                      {:org-id (:dispatch/org-id context)
                       :project (:dispatch/project context)
                       :garden (:dispatch/garden context)
                       :document (:document work)
                       :source-locale (:dispatch/source-locale context)
                       :locale (:locale work)
                       :revision (:revision work)})
        existing (await (store/dispatch-for-key! evidence-store dispatch-key))]
    (cond-> context
      (contains? #{:dispatch/completed :dispatch/duplicate}
                 (:dispatch/outcome existing))
      (assoc :dispatch/recovery-reason :candidate-unavailable))))

(defn derived-work
  "The translation work one hydrated intent derives, or nil.

   Delegates wholly to `domain.publication-gate/gate`, which computes evidence
   once and keys the derived work to that single concrete revision. Nothing here
   re-decides admissibility: this namespace's job is to dispatch what the gate
   already decided, and a second opinion about it would be the drift the gate's
   compute-once rule exists to prevent."
  [intent facts]
  (:translation-work (gate/gate intent facts)))

(defn ^:async dispatch-intents!
  "Dispatch the derived translation work for every intent that has any.

   Sequential rather than concurrent, deliberately. The claims are atomic, so
   concurrency would be safe for correctness — but every dispatch creates a
   batch on a shared worker queue, and fanning out an entire garden's backlog in
   one pass is how a reconciliation run becomes an incident. One at a time also
   keeps the returned report in a readable order.

   Returns one entry per intent that derived work. An intent with no derived
   work is absent rather than present-and-empty: the gate's silence means
   'nothing to do', which is not an event."
  [deps intents facts scope]
  (let [results (atom [])]
    (doseq [intent intents]
      (when-let [work (derived-work intent facts)]
        (let [digest ((:current-source-revision facts) (:publication/document intent))
              checked-work (:action/with work)
              context (await (candidate-recovery-context!
                              (:evidence-store deps)
                              checked-work
                              (dispatch-context intent scope digest)))
              outcome (await (dispatch-work! deps checked-work context))]
          (swap! results conj (assoc outcome
                                     :publication/id (:publication/id intent))))))
    @results))

(defn ^:async resolve-batch-report!
  "Turn one worker status report into translation evidence, or refuse it.

   `report` names a batch and a document; only the store knows which concrete
   revision that pair was asked about, so the join happens first and every
   refusal is decided against the binding rather than against the report.

   A refusal is returned, not thrown. An untrusted boundary receiving a stale or
   mismatched answer is an ordinary event, and the caller — an HTTP route the
   worker calls — has to answer either way. The claim is left in flight on a
   refusal: the report did not resolve it, and marking it failed would discard a
   translation that may still be running.

   Returns `{:translation/receipt r}` on success, or `{:translation/refusal f}`."
  [{:keys [evidence-store clock observe-source-revision
           translation-content-digest translation-split-evidence]} report]
  (await (completion/resolve-batch-report!
          {:evidence-store evidence-store :clock clock
           :observe-source-revision observe-source-revision
           :translation-content-digest translation-content-digest
           :translation-split-evidence translation-split-evidence}
          report)))

(defn ^:async fail-batch-document!
  "Record that the worker could not translate one document of a batch.

   Distinct from a refusal: the worker answered, and the answer was failure. The
   claim moves to `:dispatch/failed` so a later pass can re-dispatch it, which is
   the distinction the card asks for between missing work and an
   attempted-but-unsuccessful run."
  [{:keys [evidence-store]} batch-id document-wire-id detail]
  (if-let [record (await (store/dispatch-for-batch-document! evidence-store
                                                             batch-id
                                                             document-wire-id))]
    (await (completion/settle-failure! evidence-store record detail))
    {:translation/refusal {:refusal/type :dispatch-record-missing
                           :refusal/actual {:batch_id batch-id
                                            :document document-wire-id}}}))

(defn ^:async fail-batch!
  "Record that the worker could not translate a batch, by batch id alone.

   The worker's terminal failure report names no document — it sends
   `status = \"failed\"` plus an error and nothing else. A Knoxx-created batch
   carries exactly one document (`law.translation-dispatch/WorkerRequest`
   enforces it), so the batch id identifies the binding unambiguously.

   Without this the claim would stay `:dispatch/accepted` forever: later passes
   would read it as still running, never retry it, and the gate would go on
   asking for a translation that already failed."
  [{:keys [evidence-store]} batch-id detail]
  (if-let [record (await (store/dispatch-for-batch! evidence-store batch-id))]
    (await (completion/settle-failure! evidence-store record detail))
    {:translation/refusal {:refusal/type :dispatch-record-missing
                           :refusal/actual {:batch_id batch-id}}}))
