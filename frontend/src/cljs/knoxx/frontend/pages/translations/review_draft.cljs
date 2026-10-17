(ns knoxx.frontend.pages.translations.review-draft
  "Local editor ownership; resource and legacy admission remain existing laws."
  (:require [knoxx.frontend.pages.translations.logic :as logic]
            [knoxx.frontend.pages.translations.split-review :as split-review]))

(defn authority
  "Bind displayed detail to work, locale and the server-admitted candidate."
  [row]
  (when row
    [(logic/work-row-id row) (:project row)
     (or (:target_lang row) (:locale row))
     (select-keys row [:revision :translation_revision :legacy_candidate
                       :contract_candidate :hydration_state])
     (some-> (split-review/review row)
             (select-keys [:candidate_set_id :manifest_id]))]))

(defn initial-state
  "Start without a selected authority, segment or user draft."
  []
  {:authority nil :segment nil :revision 0 :dirty? false
   :canonical logic/default-label})

(defn segment-key
  "Identify one segment without treating a new review as new authority."
  [segment]
  (when segment
    (select-keys segment [:id :candidate_set_id :manifest_id])))

(defn clear!
  "Discard only the local editor when its actual context is reset."
  [^js state]
  (set! (.-current state)
        (-> (.-current state)
            (assoc :segment nil :dirty? false :canonical logic/default-label)
            (update :revision inc))))

(defn select-work!
  "Advance candidate authority even when publication work identity is stable."
  [^js state selected]
  (let [next-authority (authority selected)]
    (when (not= next-authority (:authority (.-current state)))
      (clear! state)
      (set! (.-current state) (assoc (.-current state) :authority next-authority))
      true)))

(defn bind-detail
  "Carry the actual selected-row authority beside locally installed detail."
  [selected detail]
  (when detail (with-meta detail {:knoxx.frontend.pages.translations.review-draft/authority (authority selected)})))

(defn current-detail
  "Exclude old candidate bytes before passive detail and form effects run."
  [selected detail]
  (when (= (authority selected) (:knoxx.frontend.pages.translations.review-draft/authority (meta detail))) detail))

(defn capture
  "Capture local editor ownership separately from mutation saving ownership."
  [^js state]
  (select-keys (.-current state) [:authority :segment :revision]))

(defn unchanged?
  "Whether no later segment navigation, edit or authority change occurred."
  [^js state captured]
  (= captured (capture state)))

(defn reviewed!
  "Acknowledge only the unchanged draft that a successful review submitted."
  [^js state captured]
  (when (unchanged? state captured)
    (set! (.-current state) (assoc (.-current state) :dirty? false))))

(defn edit!
  "Record the normal form edit synchronously before React effects can complete."
  [^js state _form]
  (set! (.-current state)
        (-> (.-current state)
            (assoc :dirty? true)
            (update :revision inc))))

(defn hydrate!
  "Hydrate pristine forms while retaining a newer same-segment dirty draft."
  [^js state segment set-form!]
  (let [segment-identity (segment-key segment)
        form (logic/segment-review-form segment)
        current (.-current state)
        preserve? (and (= segment-identity (:segment current)) (:dirty? current))]
    (set! (.-current state)
          (assoc current :segment segment-identity :canonical form
                 :dirty? (boolean preserve?)))
    (when-not preserve? (set-form! form))))

(defn select-segment!
  "Select and hydrate a different segment without carrying the previous edit."
  [^js state detail index {:keys [set-seg-idx! set-form!]}]
  (let [segment (logic/find-segment detail index)]
    (when (not= (segment-key segment) (:segment (.-current state)))
      (clear! state)
      (hydrate! state segment set-form!))
    (set-seg-idx! index)))

(defn- install-detail!
  "Preserve the current segment only while it still belongs to this authority."
  [^js state selected detail {:keys [set-detail! set-seg-idx! set-form!]}]
  (when (= (authority selected) (:authority (.-current state)))
    (set-detail! (bind-detail selected detail))
    (when-not (some #(= (segment-key %) (:segment (.-current state)))
                    (:segments detail))
      (clear! state)
      (set-seg-idx! nil)
      (set-form! logic/default-label))))

(defn editor-setters
  "Name user-edit, canonical-install and explicit context-reset boundaries."
  [state {:keys [set-form!] :as setters}]
  {:set-draft-work! #(select-work! state %)
   :reset-draft! #(clear! state)
   :install-detail! #(install-detail! state %1 %2 setters)
   :select-segment! #(select-segment! state %1 %2 setters)
   :edit-form! (fn [form] (edit! state form) (set-form! form))})
