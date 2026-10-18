(ns knoxx.backend.law.embedding
  "Portable contracts for Knoxx's uncached Qwen generation and recorded coverage."
  (:require [clojure.string :as str]
            [malli.core :as m]))

(def model "The admitted generation model." "qwen3-embedding:8b")
(def dimensions "The admitted generation width." 1024)

(defn finite-number?
  "A finite numeric component on either portable host."
  [value]
  (and (number? value)
       #?(:cljs (js/Number.isFinite value)
          :clj (Double/isFinite (double value)))))

(def NonBlank "A source or identity with visible text."
  [:and :string [:fn #(not (str/blank? %))]])
(def Vector "One complete vector; never pad, truncate or filter components."
  [:vector {:min 1024 :max 1024} [:fn finite-number?]])
(def Configuration "One selected direct Ollama route and model for both tiers."
  [:map {:closed true}
   [:model [:= model]] [:compact-model [:= model]] [:dimensions [:= dimensions]]
   [:base-url [:and :string [:fn #(boolean (re-matches #"http://[^/?#@\s]+:11434/v1/embeddings" %))]]]])
(def Request "Full SDK inputs, bound to the explicit dimensional request."
  [:map {:closed true} [:model [:= model]] [:dimensions [:= dimensions]]
   [:input [:vector {:min 1} NonBlank]]])
(def Response "Provider identity and indexed vectors before any SDK write."
  [:map [:model {:optional true} [:= model]]
   [:data [:vector {:min 1} [:map [:index nat-int?] [:embedding Vector]]]]])
(def RecordedRow "Recorded chunk identity, model and finite dimensional payload."
  [:map [:_id NonBlank] [:parent_id NonBlank] [:text :string]
   [:chunk_index nat-int?] [:chunk_count {:optional true} pos-int?]
   [:embedding_model [:= model]] [:embedding_dimensions [:= dimensions]]
   [:embedding Vector]])
(def Partition "Every query-visible partition selects the admitted model and width."
  [:map [:model [:= model]] [:dimensions [:= dimensions]]])
(def SDKCapabilities "Native capabilities are inspected only by the owning extern."
  [:map {:closed true} [:hot-model-resolver? [:= true]] [:compact-model-resolver? [:= true]]])
(def QueryRequest "Query data stays Clojure-shaped; SDK owns ranking and other filters."
  [:map [:q NonBlank] [:where {:optional true} :map]])

(def registry "Registry-keyed semantic boundaries, separate from transport values."
  {::configuration Configuration ::request Request ::response Response
   ::recorded-row RecordedRow ::partition Partition ::sdk-capabilities SDKCapabilities
   ::query-request QueryRequest})

(defn assert-valid!
  "Admit a named contract or return a classified refusal without raw payload data."
  [contract value]
  (when-not (m/validate (get registry contract) value)
    (throw (ex-info "Invalid checked embedding contract"
                    {:status 503 :code "knoxx_embedding_contract_invalid" :contract contract})))
  value)

(defn assert-model!
  "Refuse a selected SDK scope or legacy partition before provider dispatch."
  [selected]
  (when-not (= model selected)
    (throw (ex-info "Unadmitted embedding model selection"
                    {:status 503 :code "knoxx_embedding_model_invalid"
                     :expected-model model :selected-model selected})))
  selected)

(defn assert-query!
  "An explicit model filter cannot silently select a legacy provider identity."
  [payload]
  (assert-valid! ::query-request payload)
  (doseq [selected (vals (select-keys (:where payload) [:embedding_model "embedding_model"]))]
    (assert-model! selected))
  payload)

(defn assert-response!
  "Bind every result to exactly one requested input, including shuffled results."
  [request response]
  (assert-valid! ::request request)
  (assert-valid! ::response response)
  (let [indices (mapv :index (:data response))
        required (set (range (count (:input request))))]
    (when-not (and (= (count indices) (count required)) (= required (set indices)))
      (throw (ex-info "Embedding result indices do not bind the complete request"
                      {:status 503 :code "knoxx_embedding_result_identity_invalid"
                       :expected-count (count required) :actual-count (count indices)}))))
  response)

(defn valid-row?
  "Check recorded identity without interpreting SDK-owned chunking or hydration."
  [row]
  (and (m/validate RecordedRow row)
       (or (not (str/blank? (:text row)))
           (and (true? (:source_text_redacted row)) (map? (:source_ref row)) (seq (:source_ref row))))
       (or (nil? (:chunk_id row)) (= (:_id row) (:chunk_id row)))))

(defn assert-row!
  "Refuse a malformed stored vector before SDK scoring can omit it silently."
  [row]
  (when-not (valid-row? row)
    (throw (ex-info "Invalid recorded embedding identity or payload"
                    {:status 503 :code "knoxx_embedding_recorded_row_invalid"
                     :contract ::recorded-row :chunk-id (:_id row)})))
  row)

(defn complete-chunks?
  "Require unique identities and one contiguous, consistently declared chunk set."
  [rows]
  (let [n (count rows)
        counts (set (map #(or (:chunk_count %) (when (= n 1) 1)) rows))]
    (and (pos? n) (every? valid-row? rows)
         (= n (count (set (map :_id rows))))
         (= #{n} counts)
         (= (set (range n)) (set (map :chunk_index rows))))))
