(ns knoxx.backend.shape.translation-review-wire
  "Plain split-review wire projections; no lookup, clock or authority decisions.")

(def ^:private review-wire-fields
  "Current split-review facts retained by the resource UI projection."
  [[:review/id :review_id]
   [:review/recorded-at :reviewed_at]
   [:review/adequacy :adequacy]
   [:review/fluency :fluency]
   [:review/terminology :terminology]
   [:review/risk :risk]
   [:review/overall :overall]
   [:review/corrected-text :corrected_text]
   [:review/editor-notes :editor_notes]])

(defn review-wire-values
  "Project only facts actually present on the latest immutable review."
  [review]
  (into {}
        (keep (fn [[review-key wire-key]]
                (when (contains? review review-key)
                  [wire-key (get review review-key)])))
        review-wire-fields))

(defn review-label-wire-values
  "Project one authenticated receipt into the historical card's label shape.

  Resource review receipts retain stronger native names (`review_id` and
  `reviewed_at`) while the legacy card consumes `id` and `ts`; publishing both
  aliases keeps one durable history authoritative without inventing a second
  label store or translation workflow. Reviewer identity is always taken from
  the server-attributed principal on the receipt, never from client input."
  [review]
  (let [principal (:review/principal review)
        labeler-id (or (:principal/user-id principal)
                       (:principal/membership-id principal)
                       (:principal/user-email principal))
        labeler-email (or (:principal/user-email principal)
                          (:principal/user-id principal)
                          (:principal/membership-id principal))]
    (merge {:id (:review/id review)
            :review_id (:review/id review)
            :segment_id (:review/split-id review)
            :labeler_id labeler-id
            :labeler_email labeler-email
            :ts (:review/recorded-at review)
            :reviewed_at (:review/recorded-at review)
            :review_status (:review/status review)}
           (review-wire-values review))))

(defn reviewed-split
  "Project one checked member with present effective facts and newest-first labels."
  [source-split candidate effective history]
  (let [labels (mapv review-label-wire-values (reverse history))]
    (merge {:split_id (:split/id source-split)
            :split_index (:split/index source-split)
            :source_text (:split/source-text source-split)
            :candidate_text (:candidate/text candidate)
            :candidate_digest (:candidate/digest candidate)
            :review_status (:review/status effective)
            :label_count (count labels)
            :labels labels}
           (review-wire-values effective))))

(defn scoped-work
  "Attach the acting tenant coordinates to already-derived resource work."
  [scope work]
  (mapv #(assoc %
                :translation/org-id (:org-id scope)
                :translation/project (:project scope))
        work))

(defn mutation-result
  "Project the existing current-output and singular/plural mutation envelope."
  [status receipts projected]
  (cond-> {:split-review/status status
           :split-review/current-status (:translation/review-status projected)
           :split-review/current-translation-receipt
           (:translation/receipt projected)}
    (= 1 (count receipts)) (assoc :split-review/receipt (first receipts))
    (not= 1 (count receipts)) (assoc :split-review/receipts receipts)))
