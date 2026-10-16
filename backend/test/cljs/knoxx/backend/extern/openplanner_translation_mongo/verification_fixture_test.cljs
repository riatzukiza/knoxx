(ns knoxx.backend.extern.openplanner-translation-mongo.verification-fixture-test
  "Actual adapter operations with inert driver effects and real owned records."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [cljs.test :refer [deftest is testing]]
            [knoxx.backend.extern.openplanner-translation-mongo.verification-fixture :as native]
            [knoxx.backend.extern.translation-review-verification-fixture :as fixture]
            [knoxx.backend.law.translation-review-verification-fixture :as law]))

(defn- ^:async outcome [operation]
  (try {:value (await (operation))}
       (catch :default error {:failed? true :error error :data (ex-data error)})))

(defn- operations [world kind]
  (filterv #(= kind (first %)) @(:operations* world)))

(deftest ^:async complete-request-refusal-precedes-every-native-effect
  (await
   (fixture/with-world!
    (^:async fn [world]
      (doseq [invalid [(assoc (:request world) :action "arbitrary-query")
                       (assoc-in (:request world) [:scope :org-id] "")
                       (assoc-in (:request world) [:scope :project] nil)
                       (assoc-in (:request world) [:scope :garden-id] (fixture/native-object))
                       (assoc (:request world) :db (fixture/native-object))]]
        (is (:failed? (await (outcome #(native/execute! invalid))))))
      (is (empty? @(:operations* world)))
      (is (not (fixture/ownership-exists? world)))))))

(deftest ^:async canonical-segment-contracts-refuse-invalid-normalized-source-and-tenant-before-insert
  (doseq [changes [{:source-text ""} {:target-lang ""} {:document-id ""} {:org-id nil}]]
    (await
     (fixture/with-world!
      (^:async fn [world]
        (await
         (fixture/with-normalization-fault!
          changes
          (^:async fn []
            (let [result (await (outcome #(native/execute! (:request world))))]
              (is (:failed? result))
              (is (= :verification-legacy-segment-invalid (:code (:data result))))
              (is (empty? (operations world :insert)))
              (is (fixture/ownership-exists? world)))))))))))

(deftest ^:async fresh-seed-is-insert-only-owned-and-explicitly-unlabeled
  (await
   (fixture/with-world!
    (^:async fn [world]
      (let [result (await (native/execute! (:request world)))
            ownership (fixture/read-ownership world)
            rows (get @(:rows* world) "translation_segments")]
        (is (= "legacy-seeded" (:action result)))
        (is (= 1 (count rows)))
        (is (= 0 (:label_version_counter (first rows))))
        (is (= "pending" (:status (first rows))))
        (is (= (:segment_id result) (:segment_id ownership)))
        (is (= ownership (law/assert-valid! ::law/ownership ownership)))
        (is (= fixture/source-text (:source_text (first rows))))
        (is (= fixture/translated-text (:translated_text (first rows))))
        (is (= 1 (count (operations world :construct))))
        (is (= 1 (count (operations world :close))))
        (is (empty? (get @(:rows* world) "translation_labels"))))))))

(deftest ^:async preexisting-relation-is-never-adopted-or-overwritten
  (await
   (fixture/with-world!
    (^:async fn [world]
      (let [foreign {:_id (fixture/object-id) :document_id "owned-legacy-document"
                     :org_id "owned-org" :project "owned-project" :garden_id nil
                     :target_lang "es" :source_text "Do not overwrite"}]
        (fixture/add-row! world "translation_segments" foreign)
        (is (:failed? (await (outcome #(native/execute! (:request world))))))
        (is (= [foreign] (get @(:rows* world) "translation_segments")))
        (is (empty? (operations world :insert))))))))

(deftest ^:async repeated-seed-cannot-adopt-an-existing-owned-intent
  (await
   (fixture/with-world!
    (^:async fn [world]
      (await (native/execute! (:request world)))
      (let [before @(:rows* world) ownership (fixture/read-ownership world)
            count-before (count @(:operations* world))]
        (is (:failed? (await (outcome #(native/execute! (:request world))))))
        (is (= ownership (fixture/read-ownership world)))
        (is (= before @(:rows* world)))
        (is (= count-before (count @(:operations* world)))))))))

(deftest ^:async post-insertion-relation-collision-is-uncertain-and-retains-owned-intent
  (let [world* (atom nil)]
    (await
     (fixture/with-world!
      {:after-insert #(fixture/add-row!
                      @world* "translation_segments"
                      {:_id (fixture/object-id) :document_id "owned-legacy-document"
                       :org_id "owned-org" :project "owned-project" :garden_id nil :target_lang "es"})}
      (^:async fn [world]
        (reset! world* world)
        (is (:failed? (await (outcome #(native/execute! (:request world))))))
        (is (= 2 (count (get @(:rows* world) "translation_segments"))))
        (is (fixture/ownership-exists? world))
        (is (empty? (operations world :delete))))))))

(deftest ^:async intent-precedes-partial-insert-and-action-first-cause-survives-close-failure
  (let [primary (ex-info "inert partial insertion failure" {:test :primary})
        secondary (ex-info "inert close failure" {:test :secondary})]
    (await
     (fixture/with-world!
      {:after-insert #(throw primary) :close #(throw secondary)}
      (^:async fn [world]
        (let [result (await (outcome #(native/execute! (:request world))))]
          (is (:failed? result))
          (is (identical? primary (ex-cause (:error result))))
          (is (true? (:cleanup-failed? (:data result))))
          (is (fixture/ownership-exists? world))
          (is (= 1 (count (get @(:rows* world) "translation_segments"))))
          (is (= 1 (count (operations world :close))))))))))

(deftest ^:async constructed-client-closes-on-connect-and-result-refusal
  (testing "a connection rejection still closes the constructed client"
    (let [primary (ex-info "inert connect failure" {})]
      (await
       (fixture/with-world!
        {:connect #(throw primary)}
        (^:async fn [world]
          (let [result (await (outcome #(native/execute! (:request world))))]
            (is (identical? primary (:error result)))
            (is (= 1 (count (operations world :close))))))))))
  (testing "a malformed converted result is refused before return and still closes"
    (await
     (fixture/with-world!
      (^:async fn [world]
        (await (native/execute! (:request world)))
        (fixture/update-segment! world {:label_version_counter "unknown"})
        (let [result (await (outcome #(native/execute! (assoc (:request world) :action "legacy-read"))))]
          (is (:failed? result))
          (is (= 2 (count (operations world :close))))))))))

(deftest ^:async nil-primary-is-not-replaced-by-a-cleanup-error
  (await
   (fixture/with-world!
    {:after-insert fixture/throw-nil! :close #(throw (ex-info "inert cleanup failure" {}))}
    (^:async fn [world]
      (let [result (await (outcome #(native/execute! (:request world))))]
        (is (:failed? result))
        (is (nil? (ex-cause (:error result))))
        (is (true? (:primary-thrown? (:data result))))
        (is (false? (:primary-error-present? (:data result))))
        (is (fixture/ownership-exists? world))
        (is (= 1 (count (operations world :close)))))))))

(deftest ^:async stored-counter-and-content-equality-drift-are-not-hidden-by-api-projection
  (await
   (fixture/with-world!
    (^:async fn [world]
      (await (native/execute! (:request world)))
      (let [read-request (assoc (:request world) :action "legacy-read")
            before (await (native/execute! read-request))]
        (fixture/update-segment! world {:label_version_counter 1 :translated_text "Changed"})
        (let [after (await (native/execute! read-request))]
          (is (= 0 (:label_version_counter before)))
          (is (= 1 (:label_version_counter after)))
          (is (= 0 (:label_count after)))
          (is (false? (:translated_text_matches after)))
          (is (not (contains? after :translated_text)))))))))

(deftest ^:async cleanup-reentry-after-label-delete-is-exact-and-preserves-foreign-rows
  (let [interrupt? (atom true)]
    (await
     (fixture/with-world!
      {:after-delete-labels #(when (compare-and-set! interrupt? true false)
                              (throw (ex-info "inert interruption after labels" {})))}
      (^:async fn [world]
        (let [seed (await (native/execute! (:request world)))
              owned-label {:_id (fixture/object-id) :segment_id (:segment_id seed)
                           :org_id "owned-org" :project "owned-project"}
              foreign-label (assoc owned-label :_id (fixture/object-id) :org_id "other-org")
              foreign-segment {:_id (fixture/object-id) :document_id "foreign-doc"
                               :org_id "owned-org" :project "owned-project" :garden_id nil
                               :target_lang "es"}
              cleanup (assoc (:request world) :action "legacy-cleanup")]
          (fixture/add-row! world "translation_labels" owned-label)
          (fixture/add-row! world "translation_labels" foreign-label)
          (fixture/add-row! world "translation_segments" foreign-segment)
          (is (:failed? (await (outcome #(native/execute! cleanup)))))
          (is (= 2 (count (get @(:rows* world) "translation_segments"))))
          (is (= [foreign-label] (get @(:rows* world) "translation_labels")))
          (is (fixture/ownership-exists? world))
          (let [result (await (native/execute! cleanup))]
            (is (= 0 (:remaining_segment_count result)))
            (is (= 0 (:remaining_label_count result)))
            (is (= [foreign-segment] (get @(:rows* world) "translation_segments")))
            (is (= [foreign-label] (get @(:rows* world) "translation_labels"))))
          (is (= 0 (:removed_segment_count (await (native/execute! cleanup)))))))))))

(deftest ^:async changed-owned-source-refuses-before-client-or-deletion
  (await
   (fixture/with-world!
    (^:async fn [world]
      (await (native/execute! (:request world)))
      (let [before @(:rows* world) operation-count (count @(:operations* world))]
        (fixture/rewrite-ownership! world (assoc (fixture/read-ownership world) :mongo_db "foreign-db"))
        (is (:failed? (await (outcome #(native/execute! (assoc (:request world) :action "legacy-cleanup"))))))
        (is (= before @(:rows* world)))
        (is (= operation-count (count @(:operations* world)))))))))

(deftest ^:async cleanup-cannot-mistake-a-moved-owned-id-for-verified-absence
  (let [world* (atom nil) move? (atom false)]
    (await
     (fixture/with-world!
      {:after-delete-labels #(when @move?
                              (fixture/update-segment! @world* {:garden_id "changed-after-label-delete"}))}
      (^:async fn [world]
        (reset! world* world)
        (let [seed (await (native/execute! (:request world)))
              foreign {:_id (fixture/object-id) :document_id "foreign-document"
                       :org_id "foreign-org" :project "foreign-project"
                       :garden_id nil :target_lang "es"}]
          (fixture/add-row! world "translation_segments" foreign)
          (fixture/add-row! world "translation_labels"
                            {:_id (fixture/object-id) :segment_id (:segment_id seed)
                             :org_id "owned-org" :project "owned-project"})
          (reset! move? true)
          (let [result (await (outcome #(native/execute! (assoc (:request world) :action "legacy-cleanup"))))
                deletes (operations world :delete)]
            (is (:failed? result))
            (is (nil? (:value result)))
            (is (= :verification-owned-row-scope-mismatch (:code (:data result))))
            (is (= 2 (count (get @(:rows* world) "translation_segments"))))
            (is (= foreign (some #(when (= "foreign-document" (:document_id %)) %)
                                (get @(:rows* world) "translation_segments"))))
            (is (= "changed-after-label-delete"
                   (:garden_id (first (get @(:rows* world) "translation_segments")))))
            (is (= ["translation_labels"] (mapv second deletes)))
            (is (fixture/ownership-exists? world)))))))))

(deftest ^:async malformed-resource-receipts-refuse-before-any-destructive-cleanup
  (doseq [encoded ["{:translation/revision \"do-not-print-receipt-bytes\""
                   nil "{:translation/revision \"owned-output\"}"]]
    (await
     (fixture/with-world!
      (^:async fn [world]
        (let [{:keys [request artifact]} (fixture/resource-cleanup-world! world encoded)
              before @(:rows* world)
              result (await (outcome #(native/execute! request)))]
          (is (:failed? result))
          (is (= :verification-resource-receipt-invalid (:code (:data result))))
          (is (nil? (:value result)))
          (is (= before @(:rows* world)))
          (is (empty? (operations world :delete)))
          (is (= "Owned content must survive refused cleanup." (fixture/owned-content artifact)))
          (is (= 1 (count (operations world :close))))))))))

(deftest ^:async receipt-primary-and-close-secondary-stay-distinct-and-secret-free
  (doseq [null? [false true]]
    (await
     (fixture/with-world!
      {:close #(throw (ex-info "do-not-print-secondary-close" {}))}
      (^:async fn [world]
        (let [{:keys [request artifact]} (fixture/resource-cleanup-world! world "inert encoded receipt")
              primary (ex-info "do-not-print-parser-primary" {})
              parse! (if null? fixture/throw-nil! (fn [_] (throw primary)))
              ;; The one reader fault is the inert malformed-receipt boundary.
              result (with-redefs [reader/read-string (if null? (fn [_] (parse!)) parse!)]
                       (await (outcome #(native/execute! request))))
              failure (:error result)
              receipt-failure (ex-cause failure)]
          (is (:failed? result))
          (is (= :verification-primary-and-cleanup-failed (:code (:data result))))
          (is (true? (:cleanup-failed? (:data result))))
          (is (= :verification-resource-receipt-invalid (:code (ex-data receipt-failure))))
          (is (if null? (nil? (ex-cause receipt-failure))
                  (identical? primary (ex-cause receipt-failure))))
          (is (empty? (operations world :delete)))
          (is (= "Owned content must survive refused cleanup." (fixture/owned-content artifact)))
          (is (= 1 (count (operations world :close)))))
        (let [{:keys [request]} (fixture/resource-cleanup-world! world
                                      "{:translation/revision \"do-not-print-receipt-bytes\"")
              capture (await (fixture/capture-cli! #(native/execute! request)))
              text (first (:stderr capture))]
          (is (= 1 (:exit-code capture)))
          (is (empty? (:stdout capture)))
          (is (= 1 (count (:stderr capture))))
          (is (str/includes? text "verification-resource-receipt-invalid"))
          (is (str/includes? text "\"cleanup_failed\":true"))
          (is (not (str/includes? text "do-not-print")))))))))
