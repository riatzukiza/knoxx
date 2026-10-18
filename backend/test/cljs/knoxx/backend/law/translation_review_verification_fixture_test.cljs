(ns knoxx.backend.law.translation-review-verification-fixture-test
  "Portable shape, identity and safe-refusal controls for the fixture adapter."
  (:require [cljs.reader :as reader]
            [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [knoxx.backend.law.publication-manifest :as manifest]
            [knoxx.backend.law.translation-review-verification-fixture :as law]))

(def source-commit (apply str (repeat 40 "a")))
(def uri-digest (apply str (repeat 64 "b")))
(def segment-id (apply str (repeat 24 "c")))

(def request
  {:action "legacy-read"
   :settings {:mongo-uri "mongodb://private-user:private-password@fixture.invalid"
              :mongo-db "owned-db" :source-commit source-commit
              :ownership-file "/owned-durable/fixture.json"}
   :scope {:run-id "owned-run" :org-id "owned-org" :project "owned-project"
           :document-id "owned-legacy-document" :garden-id nil :target-locale :es}})

(def ownership
  {:version 1 :run_id "owned-run" :source_commit source-commit
   :mongo_uri_sha256 uri-digest :mongo_db "owned-db"
   :document_id "owned-legacy-document" :segment_id segment-id
   :org_id "owned-org" :project "owned-project" :garden_id nil :target_lang "es"})

(def state-result
  {:action "legacy-read" :run_id "owned-run" :document_id "owned-legacy-document"
   :segment_id segment-id :org_id "owned-org" :project "owned-project" :garden_id nil
   :target_lang "es" :ownership_file "/owned-durable/fixture.json"
   :segment_count 1 :label_count 0 :label_version_counter 0 :status "pending"
   :source_text_matches true :translated_text_matches true})

(def resource-request
  {:action "materialization"
   :settings {:mongo-uri "mongodb://fixture.invalid" :mongo-db "owned-db"
              :source-commit source-commit :source-file "/owned/source.md"
              :content-root "/owned/content"}
   :scope {:run-id "owned-run" :org-id "owned-org" :project nil
           :document :owned/doc :garden :owned/garden :publication :owned/doc-es
           :publication-path "/owned/doc-es" :document-title "Owned fixture"
           :target-locale :es}})

(defn- refusal
  "Retain a classified refusal without relying on an opaque Error constructor."
  [operation]
  (try (operation) nil (catch :default error (ex-data error))))

(deftest schema-registry-is-readable-data-and-has-executable-predicates
  (is (= law/registry (reader/read-string (pr-str law/registry))))
  (is (= (set (keys law/registry)) (set (keys law/validators))))
  (is (every? fn? (vals law/validators)))
  (is (= request (law/assert-request! request)))
  (is (= state-result (law/assert-result! request state-result))))

(deftest library-composed-schemas-stay-closed-readable-maps
  (doseq [schema [law/ResourceSettings law/LegacySettings law/LegacySeedResult
                  law/LegacyReadResult law/LegacyCleanupResult law/DispatchReadResult]]
    (is (vector? schema))
    (is (= :map (first schema)))
    (is (= true (:closed (second schema))))
    (is (= schema (reader/read-string (pr-str schema))))
    (let [fields (mapv first (drop 2 schema))]
      (is (= (count fields) (count (set fields))))))
  (is (= resource-request (law/assert-request! resource-request)))
  (doseq [invalid [(update resource-request :settings dissoc :mongo-db)
                   (update resource-request :settings dissoc :source-file)
                   (assoc-in resource-request [:settings :unexpected] true)
                   (assoc-in request [:settings :unexpected] true)
                   (assoc-in resource-request [:scope :target-locale] :qualified/es)]]
    (is (= ::law/request (:contract (refusal #(law/assert-request! invalid)))))))

(deftest closed-request-shapes-refuse-unknown-action-and-scope
  (doseq [invalid [(assoc request :action "arbitrary-query")
                   (assoc request :query {})
                   (update request :scope dissoc :org-id)
                   (assoc-in request [:scope :project] nil)
                   (assoc-in request [:scope :garden-id] {})
                   (assoc-in request [:scope :target-locale] :fr)
                   (assoc-in request [:settings :source-commit] "main")
                   (assoc-in request [:settings :ownership-file] "relative.json")
                   (assoc-in request [:settings :mongo-uri] "   ")]]
    (is (= ::law/request (:contract (refusal #(law/assert-request! invalid))))))
  (testing "nil garden remains explicit, rather than absent or wildcard"
    (is (= request (law/assert-request! request)))
    (is (some? (refusal #(law/assert-request! (update request :scope dissoc :garden-id)))))))

(deftest refusal-data-never-includes-rejected-values-or-settings
  (let [invalid (assoc request :unexpected "raw-secret-and-content")
        result (refusal #(law/assert-request! invalid))
        encoded (pr-str result)]
    (is (= #{:code :contract :fields} (set (keys result))))
    (is (vector? (:fields result)))
    (doseq [secret ["private-user" "private-password" "fixture.invalid"
                    "raw-secret-and-content" "owned-db"]]
      (is (not (str/includes? encoded secret))))))

(deftest ownership-binds-every-recorded-coordinate-and-source
  (is (= segment-id (law/assert-valid! ::law/segment-id segment-id)))
  (doseq [invalid [nil "" "not-an-object-id" (apply str (repeat 24 "z"))]]
    (is (some? (refusal #(law/assert-valid! ::law/segment-id invalid)))))
  (is (= ownership (law/assert-valid! ::law/ownership ownership)))
  (is (law/ownership-matches? request ownership uri-digest))
  (doseq [[field value] [[:run_id "foreign-run"] [:source_commit (apply str (repeat 40 "d"))]
                         [:mongo_uri_sha256 (apply str (repeat 64 "e"))]
                         [:mongo_db "foreign-db"] [:document_id "foreign-doc"]
                         [:org_id "foreign-org"] [:project "foreign-project"]
                         [:garden_id "foreign-garden"] [:target_lang "fr"]]]
    (is (not (law/ownership-matches? request (assoc ownership field value) uri-digest))))
  (is (some? (refusal #(law/assert-valid! ::law/ownership (assoc ownership :client {})))))
  (is (some? (refusal #(law/assert-valid! ::law/ownership (dissoc ownership :garden_id))))))

(deftest result-action-and-exact-scope-cannot-be-rebound
  (doseq [invalid [(assoc state-result :action "legacy-seeded")
                   (assoc state-result :document_id "other-document")
                   (assoc state-result :org_id "other-org")
                   (assoc state-result :project "other-project")
                   (assoc state-result :garden_id "other-garden")
                   (assoc state-result :ownership_file "/other/record.json")
                   (assoc state-result :label_version_counter -1)
                   (dissoc state-result :label_version_counter)
                   (assoc state-result :source_text "forbidden stored content")
                   (assoc state-result :cursor {})]]
    (is (some? (refusal #(law/assert-result! request invalid))))))

(deftest hidden-counter-and-text-equality-are-observed-without-content
  (let [changed (assoc state-result :label_count 0 :label_version_counter 1
                       :translated_text_matches false)]
    (is (= changed (law/assert-result! request changed)))
    (is (not= state-result changed))))

(deftest seed-must-be-fresh-and-cleanup-must-prove-exact-absence
  (let [seed-request (assoc request :action "legacy-seed")
        seed (assoc state-result :action "legacy-seeded")
        cleanup-request (assoc request :action "legacy-cleanup")
        cleanup (-> state-result
                    (dissoc :segment_count :label_count :label_version_counter :status
                            :source_text_matches :translated_text_matches)
                    (assoc :action "legacy-cleaned" :removed_segment_count 1
                           :removed_label_count 2 :remaining_segment_count 0
                           :remaining_label_count 0))]
    (is (= seed (law/assert-result! seed-request seed)))
    (doseq [[field value] [[:label_count 1] [:label_version_counter 1] [:status "approved"]
                           [:source_text_matches false] [:translated_text_matches false]]]
      (is (some? (refusal #(law/assert-result! seed-request (assoc seed field value))))))
    (is (= cleanup (law/assert-result! cleanup-request cleanup)))
    (is (= (assoc cleanup :removed_segment_count 0 :removed_label_count 0)
           (law/assert-result! cleanup-request
                               (assoc cleanup :removed_segment_count 0 :removed_label_count 0))))
    (is (some? (refusal #(law/assert-result! cleanup-request
                                          (assoc cleanup :remaining_segment_count 1)))))))

(deftest original-resource-materialization-and-memory-retain-owned-text
  (let [artifact-path (manifest/artifact-relative-path
                       {:publication/document :owned/doc :publication/locale :es}
                       {:artifact/revision "source-revision" :artifact/media-type "text/markdown"})
        materialization {:action "materialization" :materialized true
                         :path "/owned/doc-es" :revision "source-revision"
                         :artifact_path artifact-path :content "Corrected owned text"}
        memory {:action "memory" :current_candidate_set_ids ["candidate-1"]
                :examples [{:source_text "Owned source" :target_text "Owned correction"
                            :split_id "split-1" :candidate_set_id "candidate-1"
                            :review_receipt_id "review-1"}]}]
    (is (= materialization (law/assert-result! resource-request materialization)))
    (is (= memory (law/assert-result! (assoc resource-request :action "memory") memory)))
    (doseq [invalid-path ["/absolute/artifact.md" "../outside.md" "artifacts/../outside.md"
                          "artifacts\\outside.md" "artifacts/file.md?query" "artifacts/file.md#fragment"]]
      (is (some? (refusal #(law/assert-result! resource-request
                                            (assoc materialization :artifact_path invalid-path))))))
    (is (= {:action "materialization" :materialized false}
           (law/assert-result! resource-request {:action "materialization" :materialized false})))))
