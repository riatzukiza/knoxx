(ns knoxx.backend.infra.translation-dispatch-test
  (:require [cljs.test :refer [deftest is testing]]
            [knoxx.backend.extern.promise :as promise]
            [knoxx.backend.extern.translation-dispatch-fixture :as fixture]
            [knoxx.backend.infra.translation-dispatch :as dispatch]
            [knoxx.backend.infra.translation-evidence-store :as store]
            [knoxx.backend.law.translation-dispatch :as law]))

(deftest ^:async gated-work-reaches-the-worker-with-a-concrete-revision
  (let [{:keys [batches deps]} (fixture/fixture)
        result (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (testing "the worker was asked once, in its own contract's shape"
      (is (= 1 (count @batches)))
      (is (= {:garden_id "knoxx.docs/promethean"
              :target_lang "es"
              :document_ids ["knoxx.docs/probe"]
              :source_lang "en"
              :org_id "org-1"
              :membership_id "member-1"
              :dispatch_key (:dispatch/key (:dispatch/record result))}
             (first @batches))))

    (testing "the revision the worker cannot carry is bound Knoxx-side"
      (is (= :dispatch/accepted (:dispatch/outcome result)))
      (is (= "sha256-aaa111bbb222" (:dispatch/revision (:dispatch/record result))))
      (is (= "batch-1" (:dispatch/batch-id (:dispatch/record result)))))

    (testing "no translation fact exists yet — the worker has not answered"
      (is (empty? (await (store/completed-translations! (:evidence-store deps) fixture/evidence-scope)))))))

(deftest ^:async duplicate-dispatch-does-not-enqueue-twice
  (let [{:keys [batches deps]} (fixture/fixture)
        first-result (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
        second-result (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (testing "the second ask reuses the identity instead of translating again"
      (is (= :dispatch/accepted (:dispatch/outcome first-result)))
      (is (= :dispatch/duplicate (:dispatch/outcome second-result)))
      (is (= 1 (count @batches))
          "a second batch would translate the same revision twice"))

    (testing "the duplicate carries the running batch, so a caller can see it"
      (is (= "batch-1" (:dispatch/batch-id (:dispatch/record second-result)))))))

(deftest ^:async a-definite-worker-refusal-becomes-retriable
  ;; The create threw AND observation found no batch, so the send definitely did
  ;; not land. Only then is the claim safe to mark retriable.
  (let [{:keys [batches deps]} (fixture/fixture :answer (fn [_ _]
                                                  (throw (ex-info "worker refused" {}))))
        result (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (testing "the outcome is failure, with the reason kept"
      (is (= :dispatch/failed (:dispatch/outcome result)))
      (is (= :dispatch/failed (:dispatch/outcome (:dispatch/record result))))
      (is (= "worker refused" (:dispatch/detail (:dispatch/record result)))))

    (testing "the claim reached a terminal outcome rather than sticking in flight"
      ;; Left in flight, the work would never be retried and never reported: it
      ;; would silently never happen.
      (let [stored (await (store/dispatch-for-key!
                           (:evidence-store deps)
                           (:dispatch/key (:dispatch/record result))))]
        (is (= :dispatch/failed (:dispatch/outcome stored)))))

    (testing "no translation fact was fabricated"
      (is (empty? (await (store/completed-translations! (:evidence-store deps) fixture/evidence-scope)))))
    (is (= 1 (count @batches)))))

(deftest ^:async a-nil-batch-id-cannot-produce-an-unattributable-dispatch
  (let [{:keys [deps]} (fixture/fixture :answer (fn [_ _] {:ok false}))
        result (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (testing "a client answering without a batch id is a failure, not success"
      (is (= :dispatch/failed (:dispatch/outcome result))))))

(deftest ^:async an-unobservable-send-stays-in-flight-rather-than-duplicating
  ;; Both the create and the observation failed, so nothing is known. A stuck
  ;; claim is visible and fixable; a duplicate translation is neither.
  (let [{:keys [deps]}
        (fixture/fixture :answer (fn [_ _] (throw (ex-info "connection reset" {})))
                 :observed (fn [_] (throw (ex-info "worker unreachable" {}))))
        result (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (testing "the conservative end is chosen, and it says why"
      (is (= :dispatch/accepted (:dispatch/outcome result)))
      (is (re-find #"observation also failed" (:dispatch/detail result))))))

(deftest ^:async a-batch-level-failure-resolves-by-batch-id-alone
  ;; The worker's terminal failure report names no document at all — it sends
  ;; status "failed" plus an error. Without resolving by batch id the claim would
  ;; sit in flight forever and never be retried.
  (let [{:keys [batches deps]} (fixture/fixture)
        _ (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
        failed (await (dispatch/fail-batch! deps "batch-1" "All documents failed"))]
    (testing "the binding is found and marked failed"
      (is (= :dispatch/failed (:dispatch/outcome failed)))
      (is (= "All documents failed" (:dispatch/detail (:dispatch/record failed)))))

    (testing "the claim is retriable, so the work can happen"
      (let [retry (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
        (is (= :dispatch/accepted (:dispatch/outcome retry)))
        (is (= 2 (count @batches)))))

    (testing "an unknown batch is refused rather than guessed at"
      (is (= :dispatch-record-missing
             (:refusal/type (:translation/refusal
                             (await (dispatch/fail-batch! deps "batch-99" "boom")))))))))

(deftest ^:async intents-with-no-derived-work-are-not-dispatched
  (let [{:keys [batches deps]} (fixture/fixture)
        translated (assoc fixture/facts :translated-revision? (constantly true))
        results (await (dispatch/dispatch-intents! deps [fixture/intent] translated fixture/scope))]
    (testing "an already-translated intent derives nothing and is absent"
      (is (empty? results))
      (is (empty? @batches)))))

(deftest ^:async dispatch-intents-reports-one-entry-per-dispatched-intent
  (let [{:keys [batches deps]} (fixture/fixture)
        results (await (dispatch/dispatch-intents! deps [fixture/intent] fixture/facts fixture/scope))]
    (testing "the report names the publication it belongs to"
      (is (= 1 (count results)))
      (is (= :knoxx.docs/probe-es (:publication/id (first results))))
      (is (= :dispatch/accepted (:dispatch/outcome (first results))))
      (is (= 1 (count @batches))))))

(deftest a-selector-revision-can-never-be-dispatched
  (testing "work whose revision is a selector is refused before the worker is called"
    ;; Defence in depth: the gate resolves the selector, so this shape should be
    ;; impossible — which is exactly why it is asserted rather than assumed.
    (is (thrown? js/Error
                 (law/assert-valid! :translation-dispatch/work
                                    law/DerivedWork
                                    {:document :knoxx.docs/probe
                                     :locale :es
                                     :revision :source/current
                                     :replace-stale? false})))))

(deftest ^:async a-failed-dispatch-can-be-retried
  ;; The regression this guards: classifying every non-accepted claim as `:done`
  ;; made a failed dispatch permanently terminal. The gate still reported the
  ;; translation missing, but every later pass answered `:dispatch/duplicate`
  ;; and no batch was ever enqueued again — that source revision could only be
  ;; translated by deleting rows by hand.
  (let [{:keys [batches deps]}
        (fixture/fixture :answer (fn [_ n]
                           (if (= 1 n)
                             (throw (ex-info "worker unavailable" {}))
                             {:batch_id (str "batch-" n)}))
                 ;; Observation finds nothing, so the first send definitely did
                 ;; not land and the claim is safe to retry.
                 :observed (fn [_] {:batches []}))
        first-result (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
        retry (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (testing "the first attempt failed"
      (is (= :dispatch/failed (:dispatch/outcome first-result))))

    (testing "a later pass replaces it with a fresh attempt"
      (is (= :dispatch/accepted (:dispatch/outcome retry)))
      (is (= 2 (count @batches)) "the work was actually enqueued again"))

    (testing "the retry is bound to the NEW batch, not the failed attempt's"
      ;; A stale batch id would let the old batch's completion resolve this
      ;; attempt, minting a receipt for a translation it never produced.
      (is (= "batch-2" (:dispatch/batch-id (:dispatch/record retry)))))

    (testing "a completed claim stays terminal"
      (await (dispatch/resolve-batch-report!
              deps {:status "complete"
                    :batch_id "batch-2"
                    :completed_document "knoxx.docs/probe"}))
      (let [after-completion (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
        (is (= :dispatch/duplicate (:dispatch/outcome after-completion)))
        (is (= 2 (count @batches))
            "a completed translation must never be re-dispatched")))))

(deftest ^:async a-failed-completion-report-can-also-be-retried
  (let [{:keys [batches deps]} (fixture/fixture)
        _ (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
        _ (await (dispatch/fail-batch-document!
                  deps "batch-1" "knoxx.docs/probe" "model unavailable"))
        retry (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (testing "a worker-reported failure is retriable, like a dispatch failure"
      (is (= :dispatch/accepted (:dispatch/outcome retry)))
      (is (= 2 (count @batches))))

    (testing "the previous batch's completion can no longer resolve this claim"
      (let [stale (await (dispatch/resolve-batch-report!
                          deps {:status "complete"
                                :batch_id "batch-1"
                                :completed_document "knoxx.docs/probe"}))]
        ;; `:dispatch-record-missing` rather than `:worker-batch-mismatch`, and
        ;; that is the stronger outcome: the retry rebound the claim to batch-2,
        ;; so the (batch-1, document) join finds no binding at all. Had the retry
        ;; left the stale batch id in place, this lookup WOULD have found the
        ;; claim and minted a receipt for a translation batch-2 never finished.
        (is (= :dispatch-record-missing (:refusal/type (:translation/refusal stale))))
        (is (empty? (await (store/completed-translations! (:evidence-store deps) fixture/evidence-scope))))))))

(deftest retriable-and-terminal-outcomes-are-disjoint-and-complete
  (testing "every outcome is exactly one of accepted, retriable, or terminal"
    (doseq [outcome law/outcomes]
      (let [accepted? (= :dispatch/accepted outcome)
            retriable? (law/retriable? outcome)
            terminal? (law/terminal? outcome)]
        (is (= 1 (count (filter true? [accepted? retriable? terminal?])))
            (str outcome " is not in exactly one class")))))

  (testing "a completed translation is never retriable"
    (is (not (law/retriable? :dispatch/completed)))
    (is (law/terminal? :dispatch/completed)))

  (testing "a failed or rejected attempt is retriable"
    (is (law/retriable? :dispatch/failed))
    (is (law/retriable? :dispatch/rejected))))

(deftest ^:async a-known-batch-id-is-recovered-when-the-binding-write-fails
  ;; A failure *after* the batch id is known is not an ambiguous send: the batch
  ;; exists and can be named. Routing it through observation would be strictly
  ;; worse without durable correlation. The worker now stores the dispatch key,
  ;; so the next reconciliation pass can recover the exact batch and repair the
  ;; binding without creating a duplicate.
  (let [attempts (atom 0)
        failing-store (let [inner (store/memory-store)]
                        (reify store/ITranslationEvidenceStore
                          (reserve-dispatch! [_ r] (store/reserve-dispatch! inner r))
                          (resolve-dispatch! [_ k o d] (store/resolve-dispatch! inner k o d))
                          (bind-dispatch-batch! [_ k b]
                            (if (<= (swap! attempts inc) 2)
                              (throw (ex-info "write failed" {}))
                              (store/bind-dispatch-batch! inner k b)))
                          (claim-dispatch-completion! [_ r]
                            (store/claim-dispatch-completion! inner r))
                          (finish-dispatch-completion! [_ r d]
                            (store/finish-dispatch-completion! inner r d))
                          (dispatch-for-key! [_ k] (store/dispatch-for-key! inner k))
                          (dispatch-for-batch-document! [_ b d]
                            (store/dispatch-for-batch-document! inner b d))
                          (dispatch-for-batch! [_ b] (store/dispatch-for-batch! inner b))
                          (record-translation! [_ r] (store/record-translation! inner r))
                          (completed-translations! [_ s] (store/completed-translations! inner s))
                          (record-approval! [_ a] (store/record-approval! inner a))
                          (approvals! [_ s] (store/approvals! inner s))))
        batches (atom [])
        deps {:evidence-store failing-store
              :client (fixture/fake-client
                       {:batches batches
                        :observed (fn [opts]
                                    {:batches [{:batch_id "batch-1"
                                                :dispatch_key (:dispatch_key opts)
                                                :created_at "2026-08-22T09:00:00.000Z"
                                                :document_ids ["knoxx.docs/probe"]}]})})
              :clock fixture/clock
              :observe-source-revision (fixture/source-observer fixture/dispatched-revision)}
        result (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
        attempts-after-first @attempts
        recovered (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (testing "the binding is retried, bounded"
      (is (= 2 attempts-after-first) "one attempt plus one retry, not a loop"))

    (testing "the first pass stays in flight and preserves the recovery route"
      (is (= :dispatch/accepted (:dispatch/outcome result)))
      (is (re-find #"batch-1" (:dispatch/detail result)))
      (is (re-find #"recoverable by dispatch key" (:dispatch/detail result))))

    (testing "the next pass binds the exact queued batch"
      (is (= 3 @attempts))
      (is (= "batch-1" (:dispatch/batch-id (:dispatch/record recovered))))
      (is (re-find #"durable dispatch key" (:dispatch/detail recovered))))

    (testing "the worker was asked exactly once across both passes"
      (is (= 1 (count @batches))))))

(deftest ^:async dispatch-intents-keeps-worker-requests-sequential
  (let [pause (fixture/paused-batch-responder)
        answer (fn [request n]
                 (if (= 1 n) ((:respond! pause) request n) {:batch_id (str "batch-" n)}))
        {:keys [batches deps]} (fixture/fixture :answer answer)
        second-intent (assoc fixture/intent :publication/id :knoxx.docs/second-es
                             :publication/document :knoxx.docs/second-document)
        task (dispatch/dispatch-intents! deps [fixture/intent second-intent]
                                         fixture/facts fixture/scope)]
    (try
      (await (promise/race [(:entered pause) task]))
      (is (= 1 (count @batches)) "second worker request waits for the first response")
      ((:release! pause) {:batch_id "batch-1"})
      (let [results (await task)]
        (is (= [:knoxx.docs/probe-es :knoxx.docs/second-es]
               (mapv :publication/id results)))
        (is (= ["batch-1" "batch-2"] (mapv #(get-in % [:dispatch/record :dispatch/batch-id]) results)))
        (is (= [:dispatch/accepted :dispatch/accepted] (mapv :dispatch/outcome results)))
        (is (= 2 (count @batches))))
      (finally
        ((:release! pause) {:batch_id "batch-1"})
        (await task)))))
