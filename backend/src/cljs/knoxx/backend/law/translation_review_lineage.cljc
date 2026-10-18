(ns knoxx.backend.law.translation-review-lineage
  "Portable existing split-review lineage and tenant-coordinate guards.")

(def ^:private split-lineage-keys
  "The all-or-none completed-receipt link to canonical split authority."
  [:translation/split-manifest-id
   :translation/candidate-claim-id
   :translation/candidate-set-id
   :translation/candidate-set-digest
   :translation/split-count
   :translation/split-turn-admitted-at])

(defn complete-split-lineage?
  "Whether the completed receipt contains every canonical lineage coordinate."
  [receipt]
  (and receipt (every? #(contains? receipt %) split-lineage-keys)))

(defn split-refusal!
  "Refuse split review with the existing typed conflict and exact supplied details."
  [message code details]
  (throw (ex-info message (merge {:status 409 :code code} details))))

(defn- manifest-receipt-binding
  "Every immutable source and tenant coordinate shared by a manifest/receipt."
  [manifest]
  [(:split-manifest/org-id manifest)
   (:split-manifest/project manifest)
   (:split-manifest/garden manifest)
   (:split-manifest/document manifest)
   (:split-manifest/source-locale manifest)
   (:split-manifest/target-locale manifest)
   (:split-manifest/source-revision manifest)])

(defn- receipt-manifest-binding
  [receipt]
  [(:translation/org-id receipt)
   (:translation/project receipt)
   (:translation/garden receipt)
   (:translation/document receipt)
   (:translation/source-locale receipt)
   (:translation/locale receipt)
   (:translation/source-revision receipt)])

(defn- receipt-lineage-binding
  [receipt]
  [(:translation/split-manifest-id receipt)
   (:translation/candidate-claim-id receipt)
   (:translation/candidate-set-id receipt)
   (:translation/candidate-set-digest receipt)
   (:translation/split-count receipt)
   (:translation/split-turn-admitted-at receipt)
   (:translation/dispatch-key receipt)
   (receipt-manifest-binding receipt)])

(defn- authority-lineage-binding
  [turn candidate-set]
  (let [manifest (:translation-turn/manifest turn)
        claim (:translation-turn/candidate-claim turn)]
    [(:split-manifest/id manifest)
     (:candidate-claim/id claim)
     (:candidate-set/id candidate-set)
     (:candidate-set/digest candidate-set)
     (count (:candidate-set/members candidate-set))
     (:translation-turn/admitted-at turn)
     (:translation-turn/dispatch-key turn)
     (manifest-receipt-binding manifest)]))

(defn assert-lineage-binding!
  "Require one receipt, authenticated turn, and authenticated set to be exact."
  [receipt turn candidate-set]
  (when-not (and (= (receipt-lineage-binding receipt)
                   (authority-lineage-binding turn candidate-set))
                 ;; Review order marks an effective or revoking revision.
                 (or (contains? receipt :translation/split-review-order)
                     (= (:translation/revision receipt)
                        (:candidate-set/revision candidate-set))))
    (split-refusal!
     "completed translation split lineage disagrees with canonical authority"
     "translation_split_lineage_mismatch"
     {:candidate-set/id (:translation/candidate-set-id receipt)}))
  {:manifest (:translation-turn/manifest turn) :candidate-set candidate-set})

(defn scoped-receipts
  "Retain only receipts belonging to the acting organization and project."
  [scope receipts]
  (filterv #(and (= (:org-id scope) (:translation/org-id %))
                 (= (:project scope) (:translation/project %)))
           receipts))

(defn scoped-approvals
  "Retain only approvals belonging to the acting organization and project."
  [scope approvals]
  (filterv #(and (= (:org-id scope) (:review/org-id %))
                 (= (:project scope) (:review/project %)))
           approvals))

(defn dispatch-matches-work?
  "Whether a point-read record's body names the work encoded by its lookup key."
  [scope work record]
  (= [(:org-id scope)
      (:project scope)
      (:translation/garden work)
      (:translation/document work)
      (:translation/source-locale work)
      (:translation/locale work)
      (:translation/source-revision work)]
     [(:dispatch/org-id record)
      (:dispatch/project record)
      (:dispatch/garden record)
      (:dispatch/document record)
      (:dispatch/source-locale record)
      (:dispatch/locale record)
      (:dispatch/revision record)]))

(defn assert-review-scope!
  "Require the authenticated manifest to name the acting organization and project."
  [scope manifest]
  (when-not (= [(:org-id scope) (:project scope)]
               [(:split-manifest/org-id manifest)
                (:split-manifest/project manifest)])
    (throw (ex-info "translation candidate set belongs to another review scope"
                    {:status 403
                     :code "translation_split_review_scope_mismatch"}))))

(defn manifest-review-request
  "Project one authenticated manifest onto the current-receipt selector."
  [manifest]
  {:review/document (:split-manifest/document manifest)
   :review/garden (:split-manifest/garden manifest)
   :review/locale (:split-manifest/target-locale manifest)
   :review/revision (:split-manifest/source-revision manifest)})
