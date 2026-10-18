(ns knoxx.backend.infra.translation-dispatch-completion-test
  (:require [cljs.test :refer [deftest is testing]]
            [knoxx.backend.domain.translation-evidence :as evidence-domain]
            [knoxx.backend.extern.translation-dispatch-fixture :as fixture]
            [knoxx.backend.infra.translation-dispatch :as dispatch]
            [knoxx.backend.infra.translation-evidence-store :as store]))

(deftest ^:async a-completed-report-becomes-evidence-the-gate-recognizes
  (let [{base-deps :deps} (fixture/fixture)
        deps (assoc base-deps :translation-content-digest
                    "sha256-target-content")
        _ (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
        resolved (await (dispatch/resolve-batch-report!
                         deps
                         {:status "partial"
                          :batch_id "batch-1"
                          :completed_document "knoxx.docs/probe"}))
        receipt (:translation/receipt resolved)]
    (testing "a receipt is minted against the bound revision"
      (is (some? receipt))
      (is (= "sha256-aaa111bbb222" (:translation/source-revision receipt)))
      (is (= :es (:translation/locale receipt)))
      (is (= :en (:translation/source-locale receipt))))

    (testing "the gate now sees the translation"
      ;; The DoD line this proves: 'a validated, revision-specific receipt that
      ;; the publication gate recognizes'.
      (let [loaded (evidence-domain/evidence
                    {:receipts (await (store/completed-translations!
                                       (:evidence-store deps) fixture/evidence-scope))})
            gate-facts (evidence-domain/gate-facts loaded)]
        (is ((:translated-revision? gate-facts)
             :knoxx.docs/probe :knoxx.docs/promethean :es "sha256-aaa111bbb222"))
        (is (not ((:translated-revision? gate-facts)
                  :knoxx.docs/probe :knoxx.docs/promethean :fr "sha256-aaa111bbb222")))))

    (testing "the same report a second time cannot mint a second receipt"
      (let [again (await (dispatch/resolve-batch-report!
                          deps
                          {:status "complete"
                           :batch_id "batch-1"
                           :completed_document "knoxx.docs/probe"}))]
        (is (= :dispatch-already-resolved
               (:refusal/type (:translation/refusal again))))
        (is (= 1 (count (await (store/completed-translations!
                                (:evidence-store deps) fixture/evidence-scope)))))))))

(deftest ^:async completion-crashes-resume-without-exposing-provisional-evidence
  (doseq [fail-on [:before-receipt :before-finish]]
    (let [{base-deps :deps} (fixture/fixture)
          inner (store/memory-store)
          evidence-store (fixture/completion-fault-store inner fail-on)
          deps (assoc base-deps :evidence-store evidence-store)
          dispatched (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
          report {:status "complete"
                  :batch_id "batch-1"
                  :completed_document "knoxx.docs/probe"}
          first-error (try
                        (await (dispatch/resolve-batch-report! deps report))
                        nil
                        (catch :default error error))
          after-crash (await (store/dispatch-for-key!
                              evidence-store
                              (:dispatch/key (:dispatch/record dispatched))))]
      (testing (str fail-on " leaves a resumable accepted claim")
        (is (some? first-error))
        (is (= fail-on (:phase (ex-data first-error))))
        (is (= :dispatch/accepted (:dispatch/outcome after-crash))))

      (testing (str fail-on " exposes no receipt to a production evidence snapshot")
        ;; `:before-finish` has already inserted the immutable receipt. It must
        ;; remain provisional until this exact attempt is durably completed.
        (is (empty? (await (store/completed-translations!
                            evidence-store fixture/evidence-scope)))))

      (let [recovered (await (dispatch/resolve-batch-report! deps report))
            receipts (await (store/completed-translations!
                             evidence-store fixture/evidence-scope))]
        (testing (str fail-on " retries the same owner and completes exactly once")
          (is (= :dispatch/completed
                 (:dispatch/outcome (:dispatch/record recovered))))
          (is (= 1 (count receipts)))
          (is (= (:dispatch/attempt-id (:dispatch/record recovered))
                 (:translation/dispatch-attempt-id (first receipts)))))))))

(deftest ^:async a-stale-completion-cannot-settle-a-replacement-attempt
  (let [{base-deps :deps} (fixture/fixture)
        {:keys [entered observe! release!]} (fixture/paused-source-observer)
        deps (assoc base-deps :observe-source-revision observe!)
        dispatched (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
        completion (dispatch/resolve-batch-report!
                    deps {:status "complete"
                          :batch_id "batch-1"
                          :completed_document "knoxx.docs/probe"})
        _ (await entered)
        failed (await (dispatch/fail-batch-document!
                       deps "batch-1" "knoxx.docs/probe" "provider failed"))
        replacement (-> (:dispatch/record dispatched)
                        (assoc :dispatch/attempt-id "replacement-attempt-b"
                               :dispatch/outcome :dispatch/accepted)
                        (dissoc :dispatch/batch-id :dispatch/detail))
        replaced (await (store/reserve-dispatch!
                         (:evidence-store deps) replacement))
        _ (release! fixture/dispatched-revision)
        late-completion (await completion)]
    (testing "failure settles A and replacement B is admitted while A is paused"
      (is (= :dispatch/failed (:dispatch/outcome (:dispatch/record failed))))
      (is (= :reserved (:reservation/status replaced)))
      (is (= :dispatch-already-resolved
             (:refusal/type (:translation/refusal late-completion)))))

    (testing "the delayed A loses before receipt persistence or settlement of B"
      (is (empty? (await (store/completed-translations!
                          (:evidence-store deps) fixture/evidence-scope))))
      (is (= replacement
             (await (store/dispatch-for-key!
                     (:evidence-store deps)
                     (:dispatch/key replacement))))))))

(deftest ^:async stale-and-mismatched-answers-cannot-satisfy-the-gate
  (let [{:keys [deps]} (fixture/fixture)
        _ (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (testing "an unknown document is refused"
      (is (= :dispatch-record-missing
             (:refusal/type
              (:translation/refusal
               (await (dispatch/resolve-batch-report!
                       deps {:status "partial"
                             :batch_id "batch-1"
                             :completed_document "knoxx.docs/other"})))))))

    (testing "another batch's answer is refused"
      (is (= :dispatch-record-missing
             (:refusal/type
              (:translation/refusal
               (await (dispatch/resolve-batch-report!
                       deps {:status "partial"
                             :batch_id "batch-99"
                             :completed_document "knoxx.docs/probe"})))))))

    (testing "no refusal left a translation fact behind"
      (is (empty? (await (store/completed-translations! (:evidence-store deps) fixture/evidence-scope)))))))

(deftest ^:async a-malformed-report-is-refused-by-contract
  (let [{:keys [deps]} (fixture/fixture)]
    (try
      (await (dispatch/resolve-batch-report!
              deps {:status "invented"
                    :batch_id "batch-1"
                    :completed_document "knoxx.docs/probe"}))
      (is false "an unrecognized worker status must not be interpreted")
      (catch :default err
        (is (= :translation-dispatch/status-report (:contract (ex-data err))))))))

(deftest ^:async a-failed-document-is-distinct-from-missing-work
  (let [{:keys [deps]} (fixture/fixture)
        _ (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
        failed (await (dispatch/fail-batch-document!
                       deps "batch-1" "knoxx.docs/probe" "model unavailable"))]
    (testing "the attempt is recorded as failed, with its reason"
      (is (= :dispatch/failed (:dispatch/outcome failed)))
      (is (= "model unavailable" (:dispatch/detail (:dispatch/record failed)))))

    (testing "a failure for a document nobody dispatched is refused"
      (is (= :dispatch-record-missing
             (:refusal/type
              (:translation/refusal
               (await (dispatch/fail-batch-document!
                       deps "batch-1" "knoxx.docs/unknown" "boom")))))))

    (testing "a failed attempt is not a translation"
      (is (empty? (await (store/completed-translations! (:evidence-store deps) fixture/evidence-scope)))))))

(deftest ^:async recovery-mints-a-receipt-when-the-batch-completed-and-the-source-held
  ;; The happy path of `recover-settled-batch!`: the batch reports complete, the
  ;; source has not moved, so the receipt lost by failed bookkeeping is
  ;; reconstructed. Without this, a regression in `complete-if-source-agrees!`
  ;; inside the recovery context would only be caught on the drift branch.
  (let [{:keys [deps]} (fixture/fixture :batch-status "complete")
        first-result (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
        recovered (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
        receipt (:translation/receipt recovered)]
    (testing "the first pass leaves an in-flight claim bound to a batch"
      (is (= :dispatch/accepted (:dispatch/outcome first-result)))
      (is (= "batch-1" (:dispatch/batch-id (:dispatch/record first-result)))))

    (testing "the next pass reconstructs the receipt from the batch's own status"
      (is (= :dispatch/completed (:dispatch/outcome recovered)))
      (is (some? receipt))
      (is (= fixture/dispatched-revision (:translation/source-revision receipt)))
      (is (= :es (:translation/locale receipt))))

    (testing "the receipt is durable and the claim is settled"
      (is (= 1 (count (await (store/completed-translations!
                              (:evidence-store deps) fixture/evidence-scope)))))
      (is (= :dispatch/completed
             (:dispatch/outcome (await (store/dispatch-for-key!
                                        (:evidence-store deps)
                                        (:dispatch/key (:dispatch/record first-result))))))))

    (testing "the record it answers with agrees with the outcome it reports"
      ;; It used to answer with the *pre-recovery* record, whose own
      ;; `:dispatch/outcome` still read `:dispatch/accepted` while the map
      ;; around it said completed. A caller reading the outcome off the record
      ;; — the obvious thing to do with a returned record — saw an in-flight
      ;; claim that had in fact been settled, and would keep waiting on it.
      (is (= :dispatch/completed
             (:dispatch/outcome (:dispatch/record recovered))))
      (is (= (:dispatch/key (:dispatch/record first-result))
             (:dispatch/key (:dispatch/record recovered)))))

    (testing "a third pass has nothing left to do"
      (is (= :dispatch/duplicate
             (:dispatch/outcome (await (dispatch/dispatch-work!
                                        deps (fixture/work) (fixture/context)))))))

    (testing "no second receipt was minted along the way"
      ;; The failure this guards is the loop: an outcome that never reports
      ;; completed leaves the claim readable as in-flight, so every later pass
      ;; recovers the same batch and attempts another receipt.
      (is (= 1 (count (await (store/completed-translations!
                              (:evidence-store deps) fixture/evidence-scope))))))))

(deftest ^:async recovery-reads-the-batch-per-document-not-its-overall-status
  ;; The status route accumulates `completed_documents` and `failed_documents` on
  ;; the batch with `$push`, so the batch names which documents finished. Reading
  ;; the batch-wide status instead only worked because a Knoxx-created batch
  ;; happens to carry one document — an assumption this no longer needs.
  (testing "a partial batch that completed OUR document mints a receipt"
    ;; `partial` means *some* document failed. Under status-based recovery this
    ;; produced a duplicate and the earned receipt stayed lost.
    (let [{:keys [deps]} (fixture/fixture :batch-view {:status "partial"
                                               :completed_documents ["knoxx.docs/probe"]
                                               :failed_documents [{:document_id "other/doc"
                                                                   :error "boom"}]})
          _ (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
          recovered (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
      (is (= :dispatch/completed (:dispatch/outcome recovered)))
      (is (some? (:translation/receipt recovered)))))

  (testing "a complete batch that did NOT name our document is still running"
    ;; The inverse: a batch-wide `complete` must not be read as our document
    ;; having finished when the arrays do not say so.
    (let [{:keys [deps]} (fixture/fixture :batch-view {:status "complete"
                                               :completed_documents ["someone/else"]
                                               :failed_documents []})
          _ (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
          recovered (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
      (is (= :dispatch/duplicate (:dispatch/outcome recovered)))
      (is (empty? (await (store/completed-translations!
                          (:evidence-store deps) fixture/evidence-scope))))))

  (testing "a named failure makes the claim retriable"
    (let [{:keys [deps]} (fixture/fixture :batch-view
                                  {:status "partial"
                                   :completed_documents []
                                   :failed_documents [{:document_id "knoxx.docs/probe"
                                                       :error "model unavailable"}]})
          _ (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
          recovered (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
      (is (= :dispatch/failed (:dispatch/outcome recovered)))))

  (testing "a document named both completed and failed reads as failed"
    ;; The pessimistic reading is the one that cannot fabricate a receipt.
    (let [{:keys [deps]} (fixture/fixture :batch-view
                                  {:status "partial"
                                   :completed_documents ["knoxx.docs/probe"]
                                   :failed_documents [{:document_id "knoxx.docs/probe"
                                                       :error "retried"}]})
          _ (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
          recovered (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
      (is (= :dispatch/failed (:dispatch/outcome recovered)))
      (is (empty? (await (store/completed-translations!
                          (:evidence-store deps) fixture/evidence-scope)))))))
