(ns knoxx.backend.law.translation-review-verification-fixture
  "Closed data contracts for the run-owned translation verification adapter.

   Driver handles and diagnostic payloads never belong to these values. The
   existing resource actions deliberately retain their owned content outputs;
   the new legacy and dispatch observations expose only identities and state."
  (:require [knoxx.backend.law.publication-manifest :as manifest]
            [malli.core :as m]
            [malli.util :as mu]))

(def NonBlank
  "Visible text, represented as serializable schema data."
  [:and :string [:re "^[\\s\\S]*\\S[\\s\\S]*$"]])

(def Commit "One immutable checkout source identity." [:re "^[0-9a-f]{40}$"])
(def Digest "A nonsecret SHA-256 identity." [:re "^[0-9a-f]{64}$"])
(def SegmentId "A recorded Mongo ObjectId in portable form." [:re "^[0-9a-f]{24}$"])
(def AbsolutePath "An explicit absolute path, without a native handle." [:re "^/[^\\u0000]+$"])
(def RelativeArtifactPath
  "Plain shape; assert-result! also invokes the existing manifest path law."
  NonBlank)

(def FixtureAction
  "The complete entry vocabulary; unknown operations fail before construction."
  [:enum "seed" "cleanup" "memory" "materialization"
   "legacy-seed" "legacy-read" "legacy-cleanup" "dispatch-read"])

(def ConnectionSettings
  "Connection values are input-only and must never appear in refusal data."
  [:map {:closed true}
   [:mongo-uri NonBlank] [:mongo-db NonBlank] [:source-commit Commit]])

(def ResourceSettings
  "Existing resource actions retain their source and content roots."
  (m/form (mu/merge ConnectionSettings
                    [:map [:source-file AbsolutePath] [:content-root AbsolutePath]])))

(def LegacySettings
  "A durable ownership file is mandatory for every legacy operation."
  (m/form (mu/merge ConnectionSettings [:map [:ownership-file AbsolutePath]])))

(def FixtureSettings
  "Named settings union, specialized by each request action."
  [:or ResourceSettings LegacySettings])

(def ResourceScope
  "The original publication fixture coordinates, including an absent project."
  [:map {:closed true}
   [:run-id NonBlank] [:org-id NonBlank] [:project [:maybe NonBlank]]
   [:document :qualified-keyword] [:garden :qualified-keyword]
   [:publication :qualified-keyword] [:publication-path AbsolutePath]
   [:document-title NonBlank]
   [:target-locale [:and :keyword [:not :qualified-keyword]]]])

(def LegacyScope
  "One separately named legacy relation; nil garden is an exact coordinate."
  [:map {:closed true}
   [:run-id NonBlank] [:org-id NonBlank] [:project NonBlank]
   [:document-id NonBlank] [:garden-id [:maybe NonBlank]]
   [:target-locale [:= :es]]])

(def FixtureScope "Every scope is closed and tenant-bound." [:or ResourceScope LegacyScope])

(def FixtureOwnership
  "Portable intent persisted exclusively before a possibly partial insertion."
  [:map {:closed true}
   [:version [:= 1]] [:run_id NonBlank] [:source_commit Commit]
   [:mongo_uri_sha256 Digest] [:mongo_db NonBlank]
   [:document_id NonBlank] [:segment_id SegmentId]
   [:org_id NonBlank] [:project NonBlank] [:garden_id [:maybe NonBlank]]
   [:target_lang [:= "es"]]])

(defn- request-shape
  "Compose one named action with its input-only settings and exact scope."
  [action settings scope]
  [:map {:closed true}
   [:action [:= action]] [:settings settings] [:scope scope]])

(def FixtureRequest
  "Action dispatch prevents resource and legacy settings from being mixed."
  [:multi {:dispatch :action}
   ["seed" (request-shape "seed" ResourceSettings ResourceScope)]
   ["cleanup" (request-shape "cleanup" ResourceSettings ResourceScope)]
   ["memory" (request-shape "memory" ResourceSettings ResourceScope)]
   ["materialization" (request-shape "materialization" ResourceSettings ResourceScope)]
   ["dispatch-read" (request-shape "dispatch-read" ResourceSettings ResourceScope)]
   ["legacy-seed" (request-shape "legacy-seed" LegacySettings LegacyScope)]
   ["legacy-read" (request-shape "legacy-read" LegacySettings LegacyScope)]
   ["legacy-cleanup" (request-shape "legacy-cleanup" LegacySettings LegacyScope)]])

(def SeedResult
  "The existing production-law seed output, unchanged."
  [:map {:closed true}
   [:action [:= "seeded"]] [:run_id NonBlank] [:document NonBlank]
   [:candidate_set_id NonBlank] [:dispatch_attempt_id NonBlank] [:manifest_id NonBlank]
   [:split_ids [:vector NonBlank]] [:source_revision NonBlank]
   [:translation_revision NonBlank] [:split_count [:int {:min 0}]]])

(def CleanupResult
  "The existing resource cleanup outcome, unchanged."
  [:map {:closed true}
   [:action [:= "cleaned"]] [:run_id NonBlank] [:turns [:int {:min 0}]]
   [:candidate_sets [:int {:min 0}]] [:content_entries [:int {:min 0}]]
   [:materialization_removed :boolean] [:publication_idempotency_removed :boolean]])

(def MemoryExample
  "Owned memory content intentionally consumed by the existing verifier."
  [:map {:closed true}
   [:source_text :string] [:target_text :string] [:split_id NonBlank]
   [:candidate_set_id NonBlank] [:review_receipt_id NonBlank]])

(def MemoryResult
  "The current candidate set and production memory examples."
  [:map {:closed true}
   [:action [:= "memory"]] [:current_candidate_set_ids [:vector NonBlank]]
   [:examples [:vector MemoryExample]]])

(def MaterializationResult
  "Existing artifact content remains available only for the materialized case."
  [:or
   [:map {:closed true} [:action [:= "materialization"]] [:materialized [:= false]]]
   [:map {:closed true} [:action [:= "materialization"]] [:materialized [:= true]]
    [:path NonBlank] [:revision NonBlank] [:artifact_path RelativeArtifactPath] [:content :string]]])

(def LegacyIdentity
  "Safe result coordinates bind the ownership file and the actual inserted row."
  [:map {:closed true}
   [:run_id NonBlank] [:document_id NonBlank] [:segment_id SegmentId]
   [:org_id NonBlank] [:project NonBlank] [:garden_id [:maybe NonBlank]]
   [:target_lang [:= "es"]] [:ownership_file AbsolutePath]])

(def LegacyState
  "Stored state is explicit, including the otherwise hidden writer counter."
  [:map {:closed true}
   [:segment_count [:= 1]] [:label_count [:int {:min 0}]]
   [:label_version_counter [:int {:min 0}]]
   [:status [:enum "pending" "in_review" "approved" "rejected"]]
   [:source_text_matches :boolean] [:translated_text_matches :boolean]])

(def LegacySeedResult
  "A seed succeeds only with a fresh pending, unlabeled exact row."
  (m/form (reduce mu/merge
                  [LegacyIdentity LegacyState
                   [:map [:action [:= "legacy-seeded"]] [:label_count [:= 0]]
                    [:label_version_counter [:= 0]] [:status [:= "pending"]]
                    [:source_text_matches [:= true]] [:translated_text_matches [:= true]]]])))

(def LegacyReadResult
  "A bounded state observation contains no stored content or driver values."
  (m/form (reduce mu/merge
                  [LegacyIdentity LegacyState [:map [:action [:= "legacy-read"]]]])))

(def LegacyCleanupResult
  "Cleanup reports verified owned absence and exact operation counts."
  (m/form (mu/merge LegacyIdentity
                    [:map [:action [:= "legacy-cleaned"]]
                     [:removed_segment_count [:int {:min 0 :max 1}]]
                     [:removed_label_count [:int {:min 0}]]
                     [:remaining_segment_count [:= 0]] [:remaining_label_count [:= 0]]])))

(def DispatchState
  "One exact run-owned resource observation, without arbitrary document fields."
  [:map {:closed true}
   [:dispatch_count [:int {:min 0}]] [:claim_count [:int {:min 0}]]
   [:batch_count [:int {:min 0}]] [:receipt_count [:int {:min 0}]]
   [:dispatch_statuses [:vector NonBlank]] [:batch_statuses [:vector NonBlank]]
   [:dispatch_ids [:vector NonBlank]] [:batch_ids [:vector NonBlank]]
   [:receipt_ids [:vector NonBlank]] [:state_sha256 Digest]])

(def DispatchReadResult
  "A read-only snapshot is tied to tenant, source revision, and run."
  (m/form (mu/merge DispatchState
                    [:map [:action [:= "dispatch-read"]] [:run_id NonBlank]
                     [:org_id NonBlank] [:project [:maybe NonBlank]] [:document_id NonBlank]
                     [:source_revision NonBlank]])))

(def FixtureResult
  "Each operation has a distinct result; no generic raw result escape exists."
  [:multi {:dispatch :action}
   ["seeded" SeedResult] ["cleaned" CleanupResult] ["memory" MemoryResult]
   ["materialization" MaterializationResult] ["legacy-seeded" LegacySeedResult]
   ["legacy-read" LegacyReadResult] ["legacy-cleaned" LegacyCleanupResult]
   ["dispatch-read" DispatchReadResult]])

(def registry
  "Named schema data is the single contract source for fixture boundaries."
  {::arguments [:vector :string] ::segment-id SegmentId
   ::action FixtureAction ::settings FixtureSettings ::scope FixtureScope
   ::ownership FixtureOwnership ::request FixtureRequest ::result FixtureResult})

(def validators
  "Executable predicates correspond one-to-one with the named registry."
  (into {} (map (fn [[contract schema]] [contract (m/validator schema)])) registry))

(def ^:private known-fields
  (set (filter keyword? (tree-seq coll? seq (vals registry)))))

(defn safe-field-paths
  "Unknown field names are untrusted values too; replace them with a fixed token."
  [paths]
  (mapv (fn [path]
          (mapv #(cond (integer? %) %
                       (contains? known-fields %) %
                       :else :unrecognized-field)
                path))
        paths))

(defn- field-paths
  "Return safe schema field coordinates, never rejected values or schema data."
  [schema value]
  (->> (:errors (m/explain schema value))
       (map :in)
       safe-field-paths
       distinct vec))

(defn assert-valid!
  "Admit a named value or return only its contract and safe field paths."
  [contract value]
  (if-let [valid? (get validators contract)]
    (if (valid? value)
      value
      (throw (ex-info "Translation verification fixture contract refused"
                      {:code :translation-verification-contract-invalid
                       :contract contract
                       :fields (field-paths (get registry contract) value)})))
    (throw (ex-info "Unknown translation verification fixture contract"
                    {:code :translation-verification-contract-unknown}))))

(defn assert-request!
  "Validate the complete operation before any driver construction or effect."
  [request]
  (assert-valid! ::request request))

(defn- expected-result-fields
  "Bind safe output coordinates to the already admitted action and scope."
  [{:keys [action settings scope]}]
  (case action
    ("legacy-seed" "legacy-read" "legacy-cleanup")
    {:run_id (:run-id scope) :document_id (:document-id scope)
     :org_id (:org-id scope) :project (:project scope) :garden_id (:garden-id scope)
     :target_lang (name (:target-locale scope)) :ownership_file (:ownership-file settings)}
    "seed" {:run_id (:run-id scope) :document (str (:document scope))}
    "cleanup" {:run_id (:run-id scope)}
    "dispatch-read" {:run_id (:run-id scope) :org_id (:org-id scope) :project (:project scope)
                     :document_id (subs (str (:document scope)) 1)}
    {}))

(defn assert-result!
  "Validate converted output and bind its operation to the admitted request."
  [request result]
  (assert-request! request)
  (assert-valid! ::result result)
  (let [fields (expected-result-fields request)
        expected (get {"seed" "seeded" "cleanup" "cleaned"
                       "legacy-seed" "legacy-seeded" "legacy-cleanup" "legacy-cleaned"}
                      (:action request) (:action request))]
    (when-not (= expected (:action result))
      (throw (ex-info "Translation verification fixture result action mismatch"
                      {:code :translation-verification-result-action-mismatch
                       :contract ::result :fields [[:action]]})))
    (when-not (= fields (select-keys result (keys fields)))
      (throw (ex-info "Translation verification fixture result scope mismatch"
                      {:code :translation-verification-result-scope-mismatch
                       :contract ::result :fields (mapv vector (keys fields))})))
    (when (and (= "materialization" (:action request)) (:materialized result)
               (not (and (= (get-in request [:scope :publication-path]) (:path result))
                         (manifest/valid-artifact-path? (:artifact_path result)))))
      (throw (ex-info "Translation verification fixture artifact path refused"
                      {:code :translation-verification-artifact-path-invalid
                       :contract ::result :fields [[:artifact_path] [:path]]}))))
  result)

(defn ownership-matches?
  "Compare every recorded coordinate; no inferred identity or ambient adoption."
  [{:keys [settings scope]} ownership uri-digest]
  (and (= (:source-commit settings) (:source_commit ownership))
       (= (:mongo-db settings) (:mongo_db ownership))
       (= uri-digest (:mongo_uri_sha256 ownership))
       (= (select-keys scope [:run-id :org-id :project :document-id :garden-id])
          {:run-id (:run_id ownership) :org-id (:org_id ownership)
           :project (:project ownership) :document-id (:document_id ownership)
           :garden-id (:garden_id ownership)})
       (= (name (:target-locale scope)) (:target_lang ownership))))
