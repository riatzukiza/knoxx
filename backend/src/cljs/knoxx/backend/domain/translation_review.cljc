(ns knoxx.backend.domain.translation-review
  "Portable review intent, immutable operation identities and split composition."
  (:require [knoxx.backend.law.translation-review-lineage :as lineage]
            [knoxx.backend.law.translation-split :as split-law]
            [knoxx.backend.shape.translation-review-wire :as wire]))

(defn effective-review-status
  "Derive the existing document status from effective per-member verdict counts."
  [counts total]
  (let [approved (get counts :approved 0)
        rejected (get counts :rejected 0)
        pending (get counts nil 0)]
    (cond
      (= total approved) :fully-approved
      (= total rejected) :fully-rejected
      (= total pending) :pending-review
      (pos? pending) :partial-review
      :else :mixed)))

(def ^:private default-evaluations
  "Compatibility defaults from the historical OpenPlanner review card."
  {:approved {:review/adequacy "good"
              :review/fluency "good"
              :review/terminology "correct"
              :review/risk "safe"}
   :in-review {:review/adequacy "adequate"
               :review/fluency "adequate"
               :review/terminology "minor_errors"
               :review/risk "safe"}
   :rejected {:review/adequacy "adequate"
              :review/fluency "adequate"
              :review/terminology "minor_errors"
              :review/risk "safe"}})

(def ^:private overall-by-status
  {:approved "approve"
   :in-review "needs_edit"
   :rejected "reject"})

(def ^:private semantic-review-keys
  [:review/adequacy :review/fluency :review/terminology :review/risk
   :review/overall :review/corrected-text :review/editor-notes])

(defn canonical-review-request
  "Normalize the existing verdict defaults while preserving optional intent."
  [request]
  (let [status (:split-review/status request)]
    (when-not (contains? overall-by-status status)
      (throw (ex-info "invalid translation split review status"
                      {:status 400
                       :code "translation_split_review_invalid"})))
    (cond-> (merge (get default-evaluations status)
                   (select-keys request
                                [:review/adequacy :review/fluency
                                 :review/terminology :review/risk
                                 :review/editor-notes])
                   {:review/overall (get overall-by-status status)})
      (some? (:split-review/corrected-text request))
      (assoc :review/corrected-text
             (:split-review/corrected-text request)))))

(defn same-semantic-review?
  "Whether principal and present semantic facts match one immutable review."
  [receipt principal canonical-request]
  (and (= principal (:review/principal receipt))
       (= canonical-request (select-keys receipt semantic-review-keys))))

(defn operation-id
  "Derive a retry-stable single-split operation while allowing A→B→A history."
  [digest-hex candidate-set-id split-id principal canonical-request effective]
  (if (and effective
           (same-semantic-review? effective principal canonical-request))
    (:review/operation-id effective)
    (str "publication-split-review-"
         (digest-hex
          (pr-str
           [candidate-set-id
            split-id
            [(:principal/user-id principal)
             (:principal/user-email principal)
             (:principal/membership-id principal)]
            [(:review/adequacy canonical-request)
             (:review/fluency canonical-request)
             (:review/terminology canonical-request)
             (:review/risk canonical-request)
             (:review/overall canonical-request)
             (:review/corrected-text canonical-request)
             (:review/editor-notes canonical-request)]
            (:review/id effective)])))))

(defn- bulk-plan-identity
  [{:keys [split-id canonical effective]}]
  [split-id
   [(:review/adequacy canonical)
    (:review/fluency canonical)
    (:review/terminology canonical)
    (:review/risk canonical)
    (:review/overall canonical)
    (:review/corrected-text canonical)
    (:review/editor-notes canonical)]
   (:review/id effective)])

(defn bulk-operation-group-id
  "One lexical rank binds the complete preappend intent and effective heads."
  [digest-hex candidate-set-id principal plans]
  (str "publication-split-review-bulk-"
       (digest-hex
        (pr-str
         [candidate-set-id
          [(:principal/user-id principal)
           (:principal/user-email principal)
           (:principal/membership-id principal)]
          (mapv bulk-plan-identity plans)]))))

(defn bulk-operation-id
  "Append the split selector after a group's complete shared lexical rank."
  [group-id split-id]
  (str group-id ":split:" split-id))

(defn- reviewed-source-split
  [digest-hex manifest candidate-set candidate-by-split review-histories source-split]
  (let [split-id (:split/id source-split)
        candidate (get candidate-by-split split-id)]
    (when-not (and candidate
                   (= (:split/index source-split)
                      (:candidate/split-index candidate)))
      (lineage/split-refusal!
       "candidate set does not cover its manifest in split order"
       "translation_split_candidate_mismatch"
       {:candidate-set/id (:candidate-set/id candidate-set)
        :split/id split-id}))
    (let [history (get review-histories split-id)
          effective (split-law/effective-review-receipt
                     digest-hex manifest candidate-set split-id history)]
      (wire/reviewed-split source-split candidate effective history))))

(defn split-review-projection
  "Compose checked split facts in manifest order and derive the existing status."
  [digest-hex manifest candidate-set review-histories]
  (let [candidate-by-split (into {}
                                 (map (juxt :candidate/split-id identity))
                                 (:candidate-set/members candidate-set))
        splits (mapv #(reviewed-source-split
                       digest-hex manifest candidate-set candidate-by-split
                       review-histories %)
                     (sort-by :split/index (:split-manifest/splits manifest)))
        counts (frequencies (map :review_status splits))]
    {:candidate_set_id (:candidate-set/id candidate-set)
     :manifest_id (:split-manifest/id manifest)
     :status (effective-review-status counts (count splits))
     :splits (vec splits)}))

(defn manifest-split-ids
  "Select every persisted manifest member in canonical split-index order."
  [authority]
  (->> (get-in authority [:turn :translation-turn/manifest :split-manifest/splits])
       (sort-by :split/index)
       (mapv :split/id)))

(defn complete-replay?
  "Whether every planned member repeats the current principal and semantic intent."
  [principal plans]
  (every? #(same-semantic-review? (:effective %) principal (:canonical %)) plans))

(defn replay-results
  "Retain existing immutable receipts without introducing another append."
  [plans]
  (mapv (fn [{:keys [effective]}] {:status :existing :receipt effective}) plans))
