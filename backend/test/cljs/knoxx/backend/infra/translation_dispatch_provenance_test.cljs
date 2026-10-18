(ns knoxx.backend.infra.translation-dispatch-provenance-test
  (:require [cljs.test :refer [deftest is testing]]
            [knoxx.backend.extern.translation-dispatch-fixture :as fixture]
            [knoxx.backend.infra.translation-dispatch :as dispatch]
            [knoxx.backend.infra.translation-evidence-store :as store]
            [knoxx.backend.law.translation-dispatch :as law]))

(deftest ^:async a-legacy-matching-batch-is-never-adopted-even-when-it-is-the-only-one
  ;; The create threw and one old-shape matching batch exists. Without the
  ;; dispatch key this does not identify the request that created it: one
  ;; unrelated actor creating the same document/locale batch produces exactly
  ;; this situation. A legacy server may also ignore the new dispatch_key query
  ;; field, so its response is not evidence that the keyed batch is absent.
  (let [{:keys [batches deps]}
        (fixture/fixture :answer (fn [_ _] (throw (ex-info "connection reset" {})))
                 :observed (fn [_] {:batches [{:batch_id "batch-existing"
                                               :created_at "2026-08-22T09:00:00.000Z"
                                               :document_ids ["knoxx.docs/probe"]}]}))
        result (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (testing "nothing is bound"
      (is (nil? (:dispatch/batch-id (:dispatch/record result)))))

    (testing "the claim is left in flight, not marked retriable"
      ;; Retriable would risk translating the same revision twice; bound would
      ;; risk fabricating evidence. In flight is the only honest answer.
      (is (= :dispatch/accepted (:dispatch/outcome result)))
      (is (re-find #"not proof the send failed" (:dispatch/detail result))))

    (testing "no second batch is ever sent"
      (let [retry (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
        (is (= 1 (count @batches)))
        (is (not= :dispatch/failed (:dispatch/outcome retry))
            "a claim that may be running must not become retriable")))))

(deftest ^:async a-source-that-moved-since-dispatch-cannot-be-completed
  ;; The worker is handed a document id, not bytes, and fetches the content when
  ;; it runs. So a receipt naming the revision Knoxx hashed at dispatch time is
  ;; only substantiated if the source has not moved since — otherwise a pinned
  ;; old revision gets reported translated on the strength of a translation of
  ;; different bytes.
  (let [{:keys [deps]} (fixture/fixture :source-revision "sha256-something-else")
        _ (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
        result (await (dispatch/resolve-batch-report!
                       deps {:status "processing"
                             :batch_id "batch-1"
                             :completed_document "knoxx.docs/probe"}))]
    (testing "the completion is refused, naming both revisions"
      (is (= :source-moved-since-dispatch
             (:refusal/type (:translation/refusal result))))
      (is (= fixture/dispatched-revision (:refusal/expected (:translation/refusal result))))
      (is (= "sha256-something-else" (:refusal/actual (:translation/refusal result)))))

    (testing "no receipt was minted for bytes nobody can vouch for"
      (is (empty? (await (store/completed-translations! (:evidence-store deps) fixture/evidence-scope)))))

    (testing "the claim is settled for good, not retried forever"
      ;; Retrying THIS claim can never work: the worker fetches current bytes and
      ;; the dispatch key names the revision that is no longer current, so every
      ;; attempt would be refused on completion and re-enqueued. An intent
      ;; tracking :source/current gets a different key next pass; one that pinned
      ;; the old revision genuinely cannot be satisfied.
      (let [stored (await (store/dispatch-for-key!
                           (:evidence-store deps)
                           (:dispatch/key (law/dispatch-record
                                           (fixture/work) (fixture/context) :dispatch/accepted (fixture/clock)
                                           :attempt-id "dispatch-attempt-key-only"))))]
        (is (= :dispatch/unreachable (:dispatch/outcome stored)))
        (is (not (law/retriable? (:dispatch/outcome stored))))))))

(deftest ^:async an-unreachable-pinned-revision-is-not-endlessly-re-dispatched
  ;; The loop this prevents: pinned revisions are deliberately never reported
  ;; superseded, so without a terminal outcome every pass would enqueue another
  ;; batch for a revision the worker can never fetch.
  (let [{:keys [batches deps]} (fixture/fixture :source-revision "sha256-something-else")
        _ (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
        _ (await (dispatch/resolve-batch-report!
                  deps {:status "processing"
                        :batch_id "batch-1"
                        :completed_document "knoxx.docs/probe"}))
        retry (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (testing "a later pass does not enqueue the unreachable revision again"
      (is (= :dispatch/duplicate (:dispatch/outcome retry)))
      (is (= 1 (count @batches))))))

(deftest ^:async recovery-from-batch-state-also-checks-the-source
  ;; The recovery path exists because evidence was lost, which is no reason to
  ;; trust it more than the worker's own report. It used to mint directly.
  (let [{:keys [deps]} (fixture/fixture :source-revision "sha256-something-else"
                                :batch-status "complete")
        first-result (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
        recovered (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (testing "the batch says complete, but the source moved"
      (is (= :dispatch/accepted (:dispatch/outcome first-result)))
      (is (= :dispatch/unreachable (:dispatch/outcome recovered))))

    (testing "no receipt was minted from a complete batch over changed bytes"
      (is (empty? (await (store/completed-translations! (:evidence-store deps) fixture/evidence-scope)))))))

(deftest ^:async an-unreadable-source-cannot-substantiate-a-completion
  (let [{:keys [deps]} (fixture/fixture)
        deps (assoc deps :observe-source-revision
                    (fixture/source-observer nil))
        _ (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
        result (await (dispatch/resolve-batch-report!
                       deps {:status "processing"
                             :batch_id "batch-1"
                             :completed_document "knoxx.docs/probe"}))]
    (testing "an unreadable source is not proof either"
      (is (= :source-moved-since-dispatch
             (:refusal/type (:translation/refusal result))))
      (is (nil? (:refusal/actual (:translation/refusal result)))))))

(deftest ^:async completing-without-a-source-observer-is-refused
  (let [{:keys [deps]} (fixture/fixture)
        without (dissoc deps :observe-source-revision)]
    (testing "a caller that forgot the check fails rather than skipping it"
      ;; Defaulting to 'assume unchanged' would delete the only evidence that
      ;; the worker translated the revision the receipt names.
      (try
        (await (dispatch/resolve-batch-report!
                without {:status "processing"
                         :batch_id "batch-1"
                         :completed_document "knoxx.docs/probe"}))
        (is false "a missing observer must not be silently tolerated")
        (catch :default err
          (is (re-find #"source-revision observer" (ex-message err))))))))

(deftest ^:async observation-will-not-bind-a-batch-older-than-the-claim
  ;; Matching on garden, locale and document alone is not a match on THIS
  ;; dispatch: a tenant that translated the same document into the same locale
  ;; before has an older batch matching all three. Binding to it would let
  ;; `recover-settled-batch!` mint a receipt for a revision that batch never saw.
  (let [{:keys [deps]}
        (fixture/fixture :answer (fn [_ _] (throw (ex-info "connection reset" {})))
                 :observed (fn [_] {:batches [{:batch_id "batch-from-last-year"
                                               :created_at "2025-01-01T00:00:00.000Z"
                                               :document_ids ["knoxx.docs/probe"]}]}))
        result (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (testing "the historical batch is not adopted"
      (is (not= "batch-from-last-year" (:dispatch/batch-id (:dispatch/record result)))))

    (testing "a legacy response cannot prove the keyed batch absent"
      (is (= :dispatch/accepted (:dispatch/outcome result))))))

(deftest ^:async observation-will-not-bind-a-batch-of-unknown-age
  (let [{:keys [deps]}
        (fixture/fixture :answer (fn [_ _] (throw (ex-info "connection reset" {})))
                 :observed (fn [_] {:batches [{:batch_id "batch-undated"
                                               :document_ids ["knoxx.docs/probe"]}]}))
        result (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (testing "an uncorrelated legacy row is not evidence of provenance or absence"
      (is (not= "batch-undated" (:dispatch/batch-id (:dispatch/record result))))
      (is (= :dispatch/accepted (:dispatch/outcome result))))))

(deftest batch-provenance-is-decided-by-creation-time
  (let [claim-at "2026-08-22T09:00:00.000Z"]
    (testing "a batch created at or after the claim can be the claim's"
      (is (law/batch-created-after? {:created_at claim-at} claim-at))
      (is (law/batch-created-after? {:created_at "2026-08-22T09:00:00.001Z"} claim-at))
      (is (law/batch-created-after? {:createdAt claim-at} claim-at) "camelCase too"))

    (testing "a batch created before the claim cannot be"
      (is (not (law/batch-created-after? {:created_at "2026-08-22T08:59:59.999Z"}
                                         claim-at))))

    (testing "an absent or unparseable creation time is refused, not guessed"
      (is (not (law/batch-created-after? {} claim-at)))
      (is (not (law/batch-created-after? {:created_at ""} claim-at)))
      (is (not (law/batch-created-after? {:created_at "yesterday"} claim-at)))
      (is (not (law/batch-created-after? {:created_at "2026-08-22T09:00:00Z"} claim-at))
          "an instant in another format cannot be compared as a string"))))

(deftest ^:async a-truncated-batch-listing-does-not-license-a-retry
  ;; The batch listing sorts newest-first and stops at `batch-listing-cap`, so a
  ;; busy garden can push our batch off the end. Reading that absence as "the
  ;; send did not land" is how a duplicate translation happens.
  (let [full-page (vec (repeat dispatch/batch-listing-cap
                               {:batch_id "someone-elses"
                                :created_at "2026-08-22T09:00:00.000Z"
                                :document_ids ["knoxx.docs/other"]}))
        {:keys [deps]} (fixture/fixture :answer (fn [_ _] (throw (ex-info "timeout" {})))
                                :observed (fn [_] {:batches full-page}))
        result (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (testing "the claim stays in flight rather than becoming retriable"
      (is (= :dispatch/accepted (:dispatch/outcome result)))
      (is (re-find #"truncated" (:dispatch/detail result))))))

(deftest ^:async a-short-batch-listing-does-license-a-retry
  (let [{:keys [deps]} (fixture/fixture :answer (fn [_ _] (throw (ex-info "timeout" {})))
                                :observed (fn [_] {:batches []}))
        result (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (testing "an absence that is conclusive is treated as one"
      (is (= :dispatch/failed (:dispatch/outcome result))))))

(deftest the-mirrored-listing-cap-matches-the-store
  (testing "the cap this namespace reasons about is the one the store applies"
    ;; Mirrored rather than imported because the store boundary does not expose
    ;; it. If that changes, this fails rather than silently drifting.
    (is (= 50 dispatch/batch-listing-cap))))

(deftest ^:async an-ambiguous-legacy-set-is-not-adopted
  ;; Two old-shape matching batches remain uncorrelated. A server returning them
  ;; may have ignored the dispatch_key filter, so Knoxx neither adopts one nor
  ;; treats their response as evidence that its exact request is absent.
  (let [candidate (fn [id] {:batch_id id
                            :created_at "2026-08-22T09:00:00.000Z"
                            :document_ids ["knoxx.docs/probe"]})
        {:keys [deps]}
        (fixture/fixture :answer (fn [_ _] (throw (ex-info "connection reset" {})))
                 :observed (fn [_] {:batches [(candidate "batch-a") (candidate "batch-b")]}))
        result (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (testing "neither candidate is bound"
      (is (nil? (:dispatch/batch-id (:dispatch/record result)))))

    (testing "the claim stays in flight, and says why"
      (is (= :dispatch/accepted (:dispatch/outcome result)))
      (is (re-find #"not proof the send failed" (:dispatch/detail result))))))

(deftest batch-matching-compares-every-field-the-batch-carries
  (let [context* {:dispatch/garden "knoxx.docs/promethean"
                  :dispatch/document-wire-id "knoxx.docs/probe"
                  :dispatch/source-locale :en
                  :dispatch/org-id "org-1"
                  :dispatch/membership-id "member-1"
                  :dispatch/project "knoxx-session"}
        work* {:document :knoxx.docs/probe :locale :es
               :revision fixture/dispatched-revision :replace-stale? false}
        at "2026-08-22T09:00:00.000Z"
        batch {:batch_id "b1"
               :created_at at
               :document_ids ["knoxx.docs/probe"]
               :project "knoxx-session"
               :source_lang "en"
               :target_lang "es"}]
    (testing "a fully agreeing batch matches"
      (is (law/batch-matches-dispatch? batch context* work* at)))

    (testing "a disagreement on any carried field is not a match"
      (doseq [[field value] [[:project "other"] [:source_lang "de"] [:target_lang "fr"]
                             [:document_ids ["knoxx.docs/other"]]]]
        (is (not (law/batch-matches-dispatch? (assoc batch field value)
                                              context* work* at))
            (str field " was ignored"))))

    (testing "a field the batch does not carry is not compared"
      ;; The batch record is another repository's shape. Requiring a field it may
      ;; not carry would reject every candidate and turn every ambiguous send
      ;; into a duplicate translation.
      (doseq [field [:project :source_lang :target_lang]]
        (is (law/batch-matches-dispatch? (dissoc batch field) context* work* at)
            (str "absent " field " was treated as a mismatch"))))))

(defn- ^:async observed-outcome!
  "The dispatch outcome when the create fails and observation returns `observed`."
  [observed]
  (let [{:keys [deps]} (fixture/fixture :answer (fn [_ _]
                                          (throw (ex-info "connection reset" {})))
                                :observed observed)]
    (:dispatch/outcome (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context))))))

(def ^:private observed-candidate
  {:batch_id "b"
   :created_at "2026-08-22T09:00:00.000Z"
   :document_ids ["knoxx.docs/probe"]})

(deftest ^:async only-a-conclusive-absence-makes-a-claim-retriable
  ;; The invariant is about the *set* of outcomes: exactly one observation result
  ;; licenses a retry, and it is the one where absence is actually evidence.
  ;; Every other shape leaves the claim in flight.
  (testing "a conclusive absence is the only retriable case"
    (is (= :dispatch/failed (await (observed-outcome! (fn [_] {:batches []}))))))

  (testing "one matching batch is not — a match does not identify the request"
    (is (= :dispatch/accepted
           (await (observed-outcome! (fn [_] {:batches [observed-candidate]}))))))

  (testing "several matching batches are not"
    (is (= :dispatch/accepted
           (await (observed-outcome!
                   (fn [_] {:batches [observed-candidate
                                      (assoc observed-candidate :batch_id "b2")]}))))))

  (testing "a truncated listing is not, even with nothing matching"
    (let [full-page (vec (repeat dispatch/batch-listing-cap
                                 (assoc observed-candidate
                                        :document_ids ["knoxx.docs/other"])))]
      (is (= :dispatch/accepted
             (await (observed-outcome! (fn [_] {:batches full-page}))))))))

(deftest ^:async a-pin-that-was-never-the-bytes-on-disk-is-refused-before-dispatch
  ;; The mismatch this catches *predates* dispatch, so no drift guard can see it:
  ;; the intent pins a historical or opaque revision, the observed digest is the
  ;; current content, the worker translates that current content, and completion
  ;; compares the current digest against the current digest. They agree, and a
  ;; receipt claims the pinned revision was translated. False, with nothing ever
  ;; having changed.
  (let [{:keys [batches deps]} (fixture/fixture)
        pinned-context (dispatch/dispatch-context fixture/intent fixture/scope "sha256-what-is-there-now")
        result (await (dispatch/dispatch-work! deps (fixture/work) pinned-context))]
    (testing "no batch is created for a revision that cannot be substantiated"
      (is (empty? @batches)))

    (testing "the refusal names both the pin and the observable digest"
      (is (= :pin-not-tied-to-observable-bytes
             (:refusal/type (:translation/refusal result))))
      (is (= fixture/dispatched-revision (:refusal/expected (:translation/refusal result))))
      (is (= "sha256-what-is-there-now"
             (:refusal/actual (:translation/refusal result)))))

    (testing "the outcome is terminal, because no retry makes a pin resolvable"
      (is (= :dispatch/unreachable (:dispatch/outcome result)))
      (is (not (law/retriable? (:dispatch/outcome result)))))

    (testing "nothing is persisted, so the refusal cannot outlive its reason"
      ;; A pin refusal is a decision about *current* state, not an observed
      ;; fact. Recorded as a terminal claim it would keep blocking a pin that had
      ;; since become valid.
      (is (nil? (await (store/dispatch-for-key!
                        (:evidence-store deps)
                        (:dispatch/key (:dispatch/record result)))))))))

(deftest ^:async a-pin-becomes-dispatchable-once-its-bytes-are-current
  ;; The consequence of not persisting the refusal: restore the checkout to the
  ;; pinned bytes and the same intent dispatches normally, instead of being
  ;; blocked forever by a terminal claim that outlived its own reason.
  (let [{:keys [batches deps]} (fixture/fixture)
        stale-context (dispatch/dispatch-context fixture/intent fixture/scope "sha256-not-yet-restored")
        refused (await (dispatch/dispatch-work! deps (fixture/work) stale-context))
        after-refusal (count @batches)
        restored (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (testing "the first attempt is refused and enqueues nothing"
      (is (= :dispatch/unreachable (:dispatch/outcome refused)))
      (is (zero? after-refusal)))

    (testing "once the bytes match the pin, the same work dispatches"
      (is (= :dispatch/accepted (:dispatch/outcome restored)))
      (is (= 1 (count @batches))))))

(deftest ^:async a-revision-equal-to-the-observed-digest-dispatches-normally
  ;; A `:source/current` intent resolves to exactly this digest, so the two are
  ;; equal by construction; a pin that happens to name the current content is
  ;; equally fine. The refusal must not catch either.
  (let [{:keys [batches deps]} (fixture/fixture)
        result (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (is (= :dispatch/accepted (:dispatch/outcome result)))
    (is (= 1 (count @batches)))
    (is (nil? (:translation/refusal result)))))

(deftest ^:async a-refused-drifted-completion-answers-with-the-settled-claim
  ;; The same self-consistency the completed branch needs, on the branch that
  ;; settles a claim it cannot substantiate. `refuse-drifted-completion!` marks
  ;; the claim unreachable; answering with the pre-refusal record would report a
  ;; claim as still accepted at the same moment it was closed for good.
  (let [{:keys [deps]} (fixture/fixture :batch-status "complete"
                                :source-revision "sha256-moved00000000")
        _ (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))
        drifted (await (dispatch/dispatch-work! deps (fixture/work) (fixture/context)))]
    (testing "the completion is refused for drift"
      (is (= :source-moved-since-dispatch
             (:refusal/type (:translation/refusal drifted))))
      (is (nil? (:translation/receipt drifted))))

    (testing "the claim is terminal, and the answer says so"
      (is (= law/unreachable-outcome (:dispatch/outcome drifted)))
      (is (= law/unreachable-outcome
             (:dispatch/outcome (:dispatch/record drifted)))))

    (testing "nothing was recorded as a translation"
      (is (empty? (await (store/completed-translations!
                          (:evidence-store deps) fixture/evidence-scope)))))))
