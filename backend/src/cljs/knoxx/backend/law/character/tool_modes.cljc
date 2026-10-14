(ns knoxx.backend.law.character.tool-modes
  "Admission for focused tools; visibility never grants authority. GPL-3.0-or-later."
  (:require [knoxx.backend.shape.character.tool-modes :as shape]
            [malli.core :as m]))

(defn catalog-refusal
  "Refuse malformed descriptors and ambiguous canonical/runtime aliases."
  [catalog]
  (if-not (and (vector? catalog) (every? #(m/validate shape/ToolDescriptor %) catalog))
    :invalid-catalog
    (let [ids (map :id catalog)
          names (map :name catalog)]
      (cond
        (not= (count ids) (count (set ids))) :duplicate-tool-id
        (not= (count names) (count (set names))) :ambiguous-tool-name
        :else nil))))

(defn configuration-refusal
  "Validate the mode document and its selected initial mode."
  [configuration]
  (cond
    (not (m/validate shape/ModeConfiguration configuration)) :invalid-mode-configuration
    (not (contains? (:modes configuration) (:initial configuration))) :unknown-initial-mode
    :else nil))

(defn character-refusal
  "Character context is an explicit supplied projection, never inferred affect."
  [character]
  (when-not (m/validate shape/CharacterContext character) :invalid-character-context))

(defn mode-refusal
  "Refuse unknown, missing or unauthorized capabilities before mode admission."
  [configuration catalog allowed-ids mode]
  (or (configuration-refusal configuration)
      (catalog-refusal catalog)
      (let [known (set (map :id catalog))
            requested (concat (:core configuration) (get-in configuration [:modes mode :tools]))]
        (cond
          (not (contains? (:modes configuration) mode)) :unknown-mode
          (some #(not (contains? known %)) requested) :unknown-tool
          (some #(not (contains? allowed-ids %)) requested) :unauthorized-tool
          :else nil))))

(defn invocation-refusal
  "Recheck current authority and exposure for every actual invocation."
  [catalog allowed-ids visible-names tool-name]
  (or (catalog-refusal catalog)
      (let [descriptor (first (filter #(= tool-name (:name %)) catalog))]
        (cond
          (nil? descriptor) :unknown-tool
          (not (contains? allowed-ids (:id descriptor))) :unauthorized-tool
          (not (contains? (set visible-names) tool-name)) :hidden-tool
          :else nil))))
