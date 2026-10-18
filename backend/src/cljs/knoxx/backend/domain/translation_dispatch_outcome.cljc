(ns knoxx.backend.domain.translation-dispatch-outcome
  "Pure result shaping for the existing translation dispatch contracts."
  (:require [knoxx.backend.law.translation-dispatch :as law]))

(defn attempt-lost-refusal
  "Describe the same lost dispatch attempt without reading or changing it."
  [record]
  {:refusal/type :dispatch-already-resolved
   :refusal/expected {:dispatch/key (:dispatch/key record)
                      :dispatch/attempt-id (:dispatch/attempt-id record)
                      :dispatch/outcome :dispatch/accepted}
   :refusal/actual :dispatch-attempt-moved})

(defn batch-observation
  "Filter a supplied worker listing and preserve its existing absence rule."
  [batches context work record listing-cap]
  (let [candidates (filterv #(and (= (:dispatch/key record) (:dispatch_key %))
                                (law/batch-matches-dispatch? % context work
                                                            (:dispatch/at record)))
                            batches)]
    {:candidates candidates
     :conclusive? (and (< (count batches) listing-cap)
                       (every? #(= (:dispatch/key record) (:dispatch_key %)) batches))}))

(defn accepted-detail
  "Keep an unresolved dispatch accepted with the original detail bytes."
  [record detail suffix]
  {:dispatch/outcome :dispatch/accepted
   :dispatch/record record
   :dispatch/detail (str detail suffix)})

(defn recovered-binding
  "Attach the original exact-batch recovery explanation to its binding result."
  [rebound detail]
  (assoc rebound
         :dispatch/detail (str detail
                               "; recovered the exact worker batch from"
                               " its durable dispatch key")))

(defn unbound-batch
  "Retain an accepted batch whose two binding writes could not be recorded."
  [record batch-id]
  {:dispatch/outcome :dispatch/accepted
   :dispatch/record record
   :dispatch/detail (str "the worker accepted batch " batch-id
                         " but the binding could not be recorded;"
                         " it remains recoverable by dispatch key")})

(defn recovered-completion
  "Shape the same receipt/refusal and current-attempt result after recovery."
  [record result current]
  (let [attempt-moved? (= :dispatch-already-resolved
                          (get-in result [:translation/refusal :refusal/type]))]
    (assoc result
           :dispatch/outcome (cond
                               (:translation/receipt result) :dispatch/completed
                               attempt-moved? :dispatch/duplicate
                               :else law/unreachable-outcome)
           :dispatch/record (or (:dispatch/record result) current record))))

(defn exact-split-completion?
  "Compare the same full split receipt coordinates for an idempotent retry."
  [record content-digest split-evidence receipt]
  (and (= (:dispatch/key record) (:translation/dispatch-key receipt))
       (= (law/output-revision record) (:translation/revision receipt))
       (= content-digest (:translation/content-digest receipt))
       (every? (fn [[key value]] (= value (get receipt key))) split-evidence)))

(defn degraded-context
  "Build only fixed dispatch fallback fields from a caught-value presence bit."
  [operation error-present?]
  {:operation (case operation :bind-batch :bind-batch :read-batch :read-batch)
   :fallback :nil
   :primary-error-present? (boolean error-present?)})
