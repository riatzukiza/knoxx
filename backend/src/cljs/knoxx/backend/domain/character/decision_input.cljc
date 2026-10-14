(ns knoxx.backend.domain.character.decision-input
  "Bounded decision material from already admitted, freshly authorized encounters.
   GPL-3.0-or-later. This projection grants no tool/source authority or new affect."
  (:require [clojure.string :as str]))

(defn- excerpt
  [text limit]
  (subs (or text "") 0 (min limit (count (or text "")))))

(defn memory-query
  "Fresh external experience can trigger related scoped recall. Self-output
   alone cannot replace the caller's query; source references remain data."
  [request context]
  (let [external (remove :self-output? (:encounters context))]
    (if (seq external)
      (str "Remember related earlier experience for this decision.\n"
           (excerpt request 500) "\n"
           (excerpt (str/join "\n" (map #(pr-str (select-keys % [:text :reactions :media])) external)) 3000))
      request)))

(defn append-context
  "Append quoted admitted observations after the user request/recall sections.
   Source content cannot become part of trusted personality or system rules."
  [prompt context]
  (if (str/blank? (:prompt-context context))
    prompt
    (str prompt "\n\n" (:prompt-context context))))

(defn inclusion-evidence
  "Retain causal admission/inclusion facts without duplicating source text."
  [context]
  (when context
    (select-keys context [:owner :event-ids :causal-source-ids :inclusion-evidence :character-count])))
