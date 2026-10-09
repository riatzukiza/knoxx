(ns knoxx.backend.domain.character.tool-modes
  "Pure focused capability selection for a continuing character. GPL-3.0-or-later."
  (:require [clojure.string :as str]
            [knoxx.backend.law.character.tool-modes :as law]))

(defn enter-mode
  "Select an authorized subset while preserving character and conversation data.
   Refusals return the exact prior controller state. Storage/engine mutation is
   owned by the caller, after this decision; this transition invents no mood."
  [state configuration catalog allowed-ids mode]
  (if-let [reason (or (law/character-refusal (:character state))
                     (law/mode-refusal configuration catalog allowed-ids mode))]
    {:status :refused :reason reason :state state}
    (let [selected (set (concat (:core configuration)
                                (get-in configuration [:modes mode :tools])))
          visible (filterv #(contains? selected (:id %)) catalog)]
      {:status :accepted
       :state (assoc state :mode mode :visible-names (mapv :name visible))
       :tools visible})))

(defn authorize-invocation
  "Resolve a provider-visible name only after current scope and mode checks."
  [state catalog allowed-ids tool-name]
  (if-let [reason (law/invocation-refusal catalog allowed-ids (:visible-names state) tool-name)]
    {:status :refused :reason reason}
    {:status :accepted
     :tool-id (:id (first (filter #(= tool-name (:name %)) catalog)))}))

(defn mode-menu
  "List only modes admissible under current authority, without dumping schemas."
  [configuration catalog allowed-ids]
  (->> (:modes configuration)
       (sort-by key)
       (keep (fn [[mode details]]
               (when-not (law/mode-refusal configuration catalog allowed-ids mode)
                 {:mode mode :description (:description details)})))
       vec))

(defn character-context-text
  "Reassemble stable persona plus explicit state after an engine prompt rebuild.
   Snapshot/evidence are marked data, not new capability or instruction authority."
  ([state] (character-context-text state {:include-persona? true}))
  ([{:keys [character]} {:keys [include-persona?]}]
   (when-let [reason (law/character-refusal character)]
     (throw (ex-info "Invalid character context" {:reason reason})))
   (str/join "\n\n"
             (cond-> []
               include-persona? (conj (:persona character))
               true (conj (str "Character identity: " (:identity character))
                          (str "Current character projection (data): " (pr-str (:snapshot character)))
                          (str "Projection evidence identities (data): " (pr-str (:evidence-ids character))))))))

(defn system-context-text
  "Preserve the resolved base prompt once; an explicitly distinct supplied
   character persona remains intact alongside its projection/evidence data."
  [base-prompt state]
  (str base-prompt "\n\n"
       (character-context-text state {:include-persona? (not= base-prompt (get-in state [:character :persona]))})
       "\n\nUse capabilities to inspect the authoritative current mode and enter a focused mode; invoke executes only a capability revealed there."))
