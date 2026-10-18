(ns knoxx.backend.law.translation-evidence-store-rules
  "Leaf receipt/approval scope, visibility, identity and retry laws.

   Inputs are decoded Clojure data. The persistence owner retains its public
   compatibility Vars, all effects and higher-order conflict decisions.")

(defn receipt-in-scope?
  "Whether `receipt` belongs to `scope`.

   The in-memory store's equivalent of the durable store's query predicate, so
   both narrow identically. A nil project matches only receipts naming none."
  [receipt org-id project]
  (and (= org-id (:translation/org-id receipt))
       (= project (:translation/project receipt))))

(defn receipt-visible-for-dispatch?
  "Whether `receipt` is admissible as completed evidence beside `record`.

   Historical receipts have no attempt coordinate and retain their existing
   compatibility behavior. A newly written receipt is invisible until the
   current dispatch row is completed by that exact immutable attempt. This keeps
   the receipt-before-completed crash window recoverable without letting the
   gate, review inventory, or publication listing consume provisional evidence."
  [receipt record]
  (if-let [attempt-id (:translation/dispatch-attempt-id receipt)]
    (and record
         (= :dispatch/completed (:dispatch/outcome record))
         (= (:translation/dispatch-key receipt) (:dispatch/key record))
         (= attempt-id (:dispatch/attempt-id record)))
    true))

(defn receipt-identity
  "The durable identity at most one completed receipt may occupy.

   Every exact coordinate is included even where the dispatch key currently
   derives from some of them. That makes the persistence law explicit and keeps
   a future dispatch-key encoding change from collapsing different resource,
   source, or output facts. Content digest and split lineage are deliberately
   not identity: changing either at these same coordinates is a conflict, not a
   second completion. `:translation/at` is retry metadata and is also excluded
   so the first recorded timestamp wins."
  [receipt]
  [(:translation/org-id receipt)
   (:translation/project receipt)
   (:translation/garden receipt)
   (:translation/document receipt)
   (:translation/source-locale receipt)
   (:translation/locale receipt)
   (:translation/source-revision receipt)
   (:translation/revision receipt)
   (:translation/dispatch-key receipt)])

(defn receipt-replay?
  "Whether `attempted` repeats `existing` without changing an immutable fact.

   A caller retrying after an uncertain response naturally samples a later
   timestamp. Ignoring only that field makes the retry idempotent while still
   refusing changed target bytes, tenant scope, or split lineage."
  [existing attempted]
  (= (dissoc existing :translation/at)
     (dissoc attempted :translation/at)))

(defn legacy-compatible-receipt-replay?
  "Whether one pre-attempt receipt and one attempt-bound receipt state one fact.

   This is a bounded rolling-deploy exception, not the ordinary replay law.
   Exactly one side must lack the attempt coordinate and every historical fact
   besides retry time must agree. Two attempt-bound receipts with different
   attempt ids remain a conflict at the same stable identity."
  [left right]
  (let [left-bound? (contains? left :translation/dispatch-attempt-id)
        right-bound? (contains? right :translation/dispatch-attempt-id)]
    (and (not= left-bound? right-bound?)
         (= (dissoc left :translation/at :translation/dispatch-attempt-id)
            (dissoc right :translation/at :translation/dispatch-attempt-id)))))

(defn approval-in-scope?
  "Whether `approval` belongs to `scope`. See `receipt-in-scope?`."
  [approval org-id project]
  (and (= org-id (:review/org-id approval))
       (= project (:review/project approval))))

(defn approval-identity
  "The identity at most one approval may exist for.

   Includes the translation revision, not merely the gate's triple: approving a
   re-translation of the same source revision is a genuinely new act of review
   over different bytes, and collapsing the two would silently reuse the first
   reviewer's decision for content they never saw.

   Includes the tenant and project for the same reason the receipt does — an
   approval attests to a translation that exists in one scope."
  [approval]
  [(:review/org-id approval)
   (:review/project approval)
   (:review/document approval)
   (:review/garden approval)
   (:review/source-locale approval)
   (:review/locale approval)
   (:review/revision approval)
   (:review/content-digest approval)
   (:review/translation-revision approval)])
