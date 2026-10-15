(ns knoxx.backend.embedding-law-test
  "Behavioral controls for complete, model-bound embedding batches and chunk sets."
  (:require [cljs.test :refer [deftest is]]
            [knoxx.backend.law.embedding :as law]
            [knoxx.backend.shape.embedding :as shape]))

(defn- row
  [index total]
  {:_id (str "owned-source::" index) :parent_id "owned-source"
   :text (str "complete owned chunk " index) :chunk_index index :chunk_count total
   :embedding_model law/model :embedding_dimensions 1024 :embedding (vec (repeat 1024 0.25))})

(deftest complete-recorded-chunk-identity-is-required
  (let [rows [(row 0 2) (row 1 2)]]
    (is (law/complete-chunks? rows))
    (is (not (law/complete-chunks? [(row 0 2)])) "missing tails cannot satisfy a declared two-chunk source")
    (is (not (law/complete-chunks? [(row 0 2) (row 0 2)])) "duplicate identities and indices refuse")
    (is (not (law/complete-chunks? [(row 0 2) (row 2 2)])) "unbound chunk indices refuse")
    (is (not (law/complete-chunks? [(row 0 2) (row 1 3)])) "contradictory counts refuse")
    (doseq [change [#(assoc % :embedding_model "gte-large-en-v1.5")
                    #(assoc % :embedding_dimensions 768)
                    #(assoc % :embedding [1 2 3])
                    #(assoc-in % [:embedding 5] js/Infinity)
                    #(assoc % :chunk_id "another-source::0")
                    #(assoc % :text "")]]
      (is (not (law/complete-chunks? [(change (row 0 1))]))))
    (is (law/valid-row? (assoc (row 0 1) :text "" :source_text_redacted true
                             :source_ref {:source_path "owned/source.txt"})))
    (is (not (law/valid-row? (assoc (row 0 1) :text "" :source_text_redacted true :source_ref {}))))))

(deftest provider-indices-bind-full-inputs-before-ordering
  (let [config {:model law/model :compact-model law/model :dimensions 1024
                :base-url "http://127.0.0.1:11434/v1/embeddings"}
        inputs [(str (apply str (repeat 200 "common prefix ")) "complete first tail")
                (str (apply str (repeat 200 "common prefix ")) "complete second tail")]
        request (shape/request config inputs)
        first-vector (vec (repeat 1024 0.25))
        second-vector (vec (repeat 1024 0.5))
        response {:model law/model :data [{:index 1 :embedding second-vector}
                                         {:index 0 :embedding first-vector}]}]
    (is (= inputs (:input (law/assert-valid! ::law/request request))))
    (is (= [first-vector second-vector]
           (shape/ordered-vectors (law/assert-response! request response))))
    (doseq [invalid [(update response :data pop)
                     (assoc-in response [:data 0 :index] 0)
                     (assoc-in response [:data 0 :index] 2)
                     (assoc-in response [:data 0 :index] nil)
                     (assoc response :model "other")]]
      (try (law/assert-response! request invalid)
           (is false "malformed complete-batch identity must refuse")
           (catch :default error
             (is (contains? #{"knoxx_embedding_contract_invalid" "knoxx_embedding_result_identity_invalid"}
                            (:code (ex-data error)))))))))
