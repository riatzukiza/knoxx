(ns knoxx.backend.extern.openplanner-translation-mongo.verification-resource
  "Preserved resource fixture operations; native handles remain in this extern."
  (:require [cljs.reader :as reader]
            [knoxx.backend.domain.node.crypto :as crypto]
            [knoxx.backend.domain.node.fs :as fs]
            [knoxx.backend.domain.translation-evidence :as evidence-domain]
            [knoxx.backend.extern.clock :as clock]
            [knoxx.backend.infra.publication-effects :as publication-effects]
            [knoxx.backend.infra.publication-source-revision :as source-revision]
            [knoxx.backend.infra.publication-target-static-site :as static-site]
            [knoxx.backend.infra.stores.mongo-translation-evidence :as evidence-mongo]
            [knoxx.backend.infra.stores.mongo-translation-split :as split-mongo]
            [knoxx.backend.infra.translation-agent-content :as agent-content]
            [knoxx.backend.infra.translation-content-integrity :as integrity]
            [knoxx.backend.infra.translation-evidence-store :as evidence-store]
            [knoxx.backend.infra.translation-split-store :as split-store]
            [knoxx.backend.law.translation-review-verification-fixture :as law]
            [knoxx.backend.law.translation-review-verification-resource :as resource]))

(defn- ^:async persist-candidate-context!
  [db {:keys [turn candidates candidate-set]}]
  (let [store (split-mongo/create-store db crypto/sha256-hex)
        turn-id (:translation-turn/id turn)]
    (await (split-mongo/setup-indexes! db))
    (await (split-store/admit-turn! store turn))
    (doseq [candidate candidates]
      (await (split-store/append-candidate-split! store turn-id candidate)))
    (await (split-store/complete-candidate-set! store turn-id candidate-set))))

(defn- ^:async reserve-bound-dispatch!
  [db opts dispatch]
  (let [evidence (evidence-mongo/create-store db)
        _ (await (evidence-mongo/setup-indexes! db))
        reservation (await (evidence-store/reserve-dispatch! evidence dispatch))
        reserved (:record reservation)]
    (when-not (and (= :reserved (:reservation/status reservation))
                   (= dispatch reserved))
      (throw (ex-info "verification dispatch was not freshly reserved"
                      {:reservation reservation})))
    (let [bound (await (evidence-store/bind-dispatch-batch!
                        evidence reserved (:run-id opts)))]
      (when-not bound
        (throw (ex-info "verification dispatch could not bind its batch" {})))
      {:dispatch bound :evidence evidence})))

(defn- ^:async persist-completion!
  [evidence opts {:keys [candidate-set output-revision dispatch turn]}]
  (when-not (await (evidence-store/claim-dispatch-completion! evidence dispatch))
    (throw (ex-info "verification dispatch could not claim completion" {})))
  (await (agent-content/write!
          (:content-root opts) dispatch output-revision
          (:candidate-set/text candidate-set)))
  (await (evidence-store/record-translation!
          evidence (resource/completion-receipt dispatch candidate-set turn (clock/instant-iso)
                                                   (integrity/content-digest (:candidate-set/text candidate-set)))))
  (when-not (await (evidence-store/finish-dispatch-completion!
                    evidence dispatch "verification candidate set completed"))
    (throw (ex-info "verification dispatch could not finish completion" {}))))

(defn- ^:async seed!
  [db opts]
  (let [source-text (await (fs/read-file! (:source-file opts)))
        source-revision (source-revision/content-revision source-text)
        proposed (resource/accepted-dispatch opts source-revision
                                    (clock/instant-iso))
        {:keys [dispatch evidence]}
        (await (reserve-bound-dispatch! db opts proposed))
        {:keys [turn candidate-set output-revision]
         :as context}
        (resource/candidate-context crypto/sha256-hex opts source-text dispatch source-revision)]
    (await (persist-candidate-context! db context))
    (await (persist-completion! evidence opts context))
    {:action "seeded"
     :run_id (:run-id opts)
     :document (str (:document opts))
     :candidate_set_id (:candidate-set/id candidate-set)
     :dispatch_attempt_id (:dispatch/attempt-id (:dispatch context))
     :manifest_id (get-in turn [:translation-turn/manifest :split-manifest/id])
     :split_ids (mapv :candidate/split-id (:candidate-set/members candidate-set))
     :source_revision source-revision
     :translation_revision output-revision
     :split_count (count (:candidate-set/members candidate-set))}))

(defn- collection
  [db name]
  (.collection db name))

(defn- ^:async rows!
  [db collection-name query]
  (let [native (await (.toArray (.find (collection db collection-name)
                                      (clj->js query))))]
    (js->clj native :keywordize-keys true)))

(defn- receipt-revisions
  [rows]
  (->> rows
       (keep :receipt_edn)
       (keep (fn [encoded]
               (try
                 (:translation/revision (reader/read-string encoded))
                 (catch :default _ nil))))
       distinct
       vec))

(defn- ^:async remove-content!
  [content-root revisions]
  (doseq [revision revisions]
    (await (fs/unlink! (agent-content/entry-path content-root revision)))))

(defn- ^:async delete-many!
  [db collection-name query]
  (await (.deleteMany (collection db collection-name) (clj->js query))))

(defn- ^:async delete-candidate-children!
  [db turn-ids candidate-set-ids]
  (when (seq candidate-set-ids)
    (await (delete-many! db split-mongo/REVIEW_RECEIPTS_COLLECTION
                         {:candidate_set_id {:$in candidate-set-ids}})))
  (when (seq turn-ids)
    (await (delete-many! db split-mongo/CANDIDATE_SETS_COLLECTION
                         {:turn_id {:$in turn-ids}}))
    (await (delete-many! db split-mongo/CANDIDATE_SPLITS_COLLECTION
                         {:turn_id {:$in turn-ids}}))))

(defn- publication-intent
  [opts]
  {:publication/id (:publication opts)
   :publication/garden (:garden opts)
   :publication/locale (:target-locale opts)
   :publication/path (:publication-path opts)
   :document/title (:document-title opts)})

(defn- publication-target
  [opts]
  (static-site/static-site-target
   {:publication-target/id :open-hax.publication/static-site
    :publication-target/config {:content-root (:content-root opts)}}))

(defn- ^:async materialization!
  [opts]
  (if-let [observed
           (await (publication-effects/observe!
                   (publication-target opts) nil (publication-intent opts)))]
    (let [artifact-path (:route/artifact observed)]
      {:action "materialization"
       :materialized true
       :path (:materialized/path observed)
       :revision (:materialized/revision observed)
       :artifact_path artifact-path
       :content (await (fs/read-file-or-nil!
                        (fs/join (:content-root opts) artifact-path)))})
    {:action "materialization" :materialized false}))

(defn- ^:async remove-materialization!
  [opts]
  (let [target (publication-target opts)
        intent (publication-intent opts)]
    (if-let [observed (await (publication-effects/observe! target nil intent))]
      (do (await (publication-effects/remove! target nil intent observed)) true)
      false)))

(defn- ^:async release-publication-idempotency!
  "Remove this run's deterministic publish reservation, if one was created."
  [opts]
  (when-let [source-text (await (fs/read-file-or-nil! (:source-file opts)))]
    (let [key (publication-effects/publish-idempotency-key
               :open-hax.publication/static-site
               (publication-intent opts)
               (source-revision/content-revision source-text))]
      (publication-effects/release!
       (static-site/static-site-store (:content-root opts)) key)
      true)))

(defn- ^:async cleanup-context!
  [db opts]
  (let [turns (await (rows! db split-mongo/TURNS_COLLECTION
                            {:run_id (:run-id opts)}))
        turn-ids (mapv :turn_id turns)
        candidate-sets (if (seq turn-ids)
                         (await (rows! db split-mongo/CANDIDATE_SETS_COLLECTION
                                       {:turn_id {:$in turn-ids}}))
                         [])
        receipts (await (rows! db evidence-mongo/RECEIPTS_COLLECTION
                              {:document (pr-str (:document opts))}))]
    {:turns turns
     :turn-ids turn-ids
     :candidate-sets candidate-sets
     :candidate-set-ids (mapv :candidate_set_id candidate-sets)
     :revisions (->> (concat (receipt-revisions receipts)
                             (keep :candidate_revision candidate-sets))
                     distinct
                     vec)}))

(defn- ^:async delete-fixture-evidence!
  [db opts {:keys [turn-ids candidate-set-ids]}]
  (await (delete-candidate-children! db turn-ids candidate-set-ids))
  (await (delete-many! db split-mongo/TURNS_COLLECTION
                       {:run_id (:run-id opts)}))
  (await (delete-many! db evidence-mongo/RECEIPTS_COLLECTION
                       {:document (pr-str (:document opts))}))
  (await (delete-many! db evidence-mongo/APPROVALS_COLLECTION
                       {:document (pr-str (:document opts))}))
  ;; Deleting the exact run-namespaced dispatch row also clears a crash-left
  ;; completion_owner without widening cleanup to another attempt or tenant.
  (await (delete-many! db evidence-mongo/DISPATCHES_COLLECTION
                       {:document_wire_id (resource/keyword-wire (:document opts))})))

(defn- ^:async cleanup!
  [db opts]
  (let [{:keys [turns candidate-sets revisions] :as context}
        (await (cleanup-context! db opts))
        materialization-removed? (await (remove-materialization! opts))
        idempotency-removed? (await (release-publication-idempotency! opts))]
    (await (remove-content! (:content-root opts) revisions))
    (await (delete-fixture-evidence! db opts context))
    {:action "cleaned"
     :run_id (:run-id opts)
     :turns (count turns)
     :candidate_sets (count candidate-sets)
     :content_entries (count revisions)
     :materialization_removed materialization-removed?
     :publication_idempotency_removed (boolean idempotency-removed?)}))

(defn- memory-example
  [example]
  {:source_text (:translation-memory/source-text example)
   :target_text (:translation-memory/target-text example)
   :split_id (:translation-memory/split-id example)
   :candidate_set_id (:translation-memory/candidate-set-id example)
   :review_receipt_id (:translation-memory/review-receipt-id example)})

(defn- ^:async fixture-candidate-set!
  [store run-id]
  (when-let [turn (await (split-store/turn-for-run! store run-id))]
    (await (split-store/candidate-set-for-turn!
            store (:translation-turn/id turn)))))

(defn- ^:async current-fixture-candidate-set-ids!
  [evidence opts candidate-set-id]
  (let [current
        (evidence-domain/current-receipts
         (await (evidence-store/completed-translations!
                 evidence {:org-id (:org-id opts) :project (:project opts)})))]
    ;; This is the same attempt-visible allowlist the agent runtime passes to
    ;; memory, narrowed to the verifier's exact run-owned candidate set.
    (loop [remaining (filter #(= candidate-set-id
                                 (:translation/candidate-set-id %)) current)
           allowed #{}]
      (if-let [receipt (first remaining)]
        (let [dispatch (await (evidence-store/dispatch-for-key!
                               evidence (:translation/dispatch-key receipt)))]
          (recur (next remaining)
                 (cond-> allowed
                   (and (:translation/dispatch-attempt-id receipt)
                        (evidence-store/receipt-visible-for-dispatch?
                         receipt dispatch))
                   (conj candidate-set-id))))
        allowed))))

(defn- memory-scope
  [opts current-candidate-set-ids]
  {:org-id (:org-id opts)
   :project (:project opts)
   :garden (:garden opts)
   :source-locale :en
   :target-locale (:target-locale opts)
   :current-candidate-set-ids current-candidate-set-ids
   :limit 50})

(defn- ^:async memory!
  [db opts]
  (let [store (split-mongo/create-store db crypto/sha256-hex)
        evidence (evidence-mongo/create-store db)
        candidate-set (await (fixture-candidate-set! store (:run-id opts)))
        candidate-set-id (:candidate-set/id candidate-set)
        current-candidate-set-ids
        (await (current-fixture-candidate-set-ids!
                evidence opts candidate-set-id))
        examples
        (await (split-store/applicable-memory!
                store (memory-scope opts current-candidate-set-ids)))]
    {:action "memory"
     :current_candidate_set_ids (vec (sort current-candidate-set-ids))
     :examples (mapv memory-example examples)}))

(defn ^:async execute!
  "Internal sibling boundary: validate data on both sides of an opaque DB handle.

   Only the owning lifecycle extern supplies this handle; the script never does."
  [db request]
  (law/assert-request! request)
  (let [opts (merge (:settings request) (:scope request))
        result (case (:action request)
                 "seed" (await (seed! db opts))
                 "cleanup" (await (cleanup! db opts))
                 "memory" (await (memory! db opts))
                 "materialization" (await (materialization! opts))
                 (throw (ex-info "Unsupported resource fixture action"
                                 {:code :translation-verification-resource-action-invalid})))]
    (law/assert-result! request result)))
