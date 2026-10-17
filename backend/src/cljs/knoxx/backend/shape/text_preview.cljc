;; SPDX-License-Identifier: GPL-3.0-or-later
(ns knoxx.backend.shape.text-preview
  "Portable structured previews with caller-owned field priority and limits."
  (:require [clojure.string :as str]))

(declare summarize-structured markdown-bullets)

(defn- safe-get
  [m k]
  (when (map? m)
    (or (get m k)
        (when (keyword? k) (get m (name k)))
        (when (string? k) (get m (keyword k))))))

(defn- format-preview-scalar
  [value]
  (cond
    (nil? value) "null"
    (string? value) (let [trimmed (str/trim value)]
                      (if (str/blank? trimmed) "" trimmed))
    (number? value) (str value)
    (boolean? value) (str value)
    :else (str value)))

(defn- preview-scalar?
  [value]
  (or (string? value) (number? value) (boolean? value) (nil? value)))

(defn- scalar-summary
  [value]
  (let [s (format-preview-scalar value)]
    (when-not (str/blank? s) s)))

(defn- summarize-keys
  [value depth preferred-keys collection-keys field-keys]
  (some (fn [k]
          (when-let [hit (summarize-structured (safe-get value k) (inc depth)
                                             preferred-keys collection-keys)]
            hit))
        field-keys))

(defn- content-part-text
  [part]
  (or (safe-get part :text) (safe-get part "text")))

(defn- content-parts?
  [items]
  (and (seq items)
       (every? map? items)
       (every? (fn [part]
                 (let [text (content-part-text part)
                       part-type (or (safe-get part :type) (safe-get part "type"))]
                   (and (string? text)
                        (or (nil? part-type) (= part-type "text") (= part-type "output_text")))))
               items)))

(defn- summarize-content-parts
  [items]
  (let [joined (->> items
                    (map content-part-text)
                    (map #(str/trim (str %)))
                    (remove str/blank?)
                    (str/join "\n\n"))]
    (when-not (str/blank? joined) joined)))

(defn- summarize-items
  [items depth preferred-keys collection-keys]
  (let [summaries (->> items
                       (map #(summarize-structured % (inc depth)
                                                  preferred-keys collection-keys))
                       (remove #(or (nil? %) (str/blank? %))))]
    (when (seq summaries)
      (str/join "\n" (map (fn [item] (str "- " item)) summaries)))))

(defn summarize-structured
  "Summarize up to eight items below depth four, preserving field fallbacks."
  [value depth preferred-keys collection-keys]
  (when (< depth 4)
    (cond
      (preview-scalar? value) (scalar-summary value)
      (map? value)
      (or (summarize-keys value depth preferred-keys collection-keys preferred-keys)
          (summarize-keys value depth preferred-keys collection-keys collection-keys)
          nil)
      (sequential? value)
      (let [items (vec (take 8 value))]
        (if (content-parts? items)
          (summarize-content-parts items)
          (summarize-items items depth preferred-keys collection-keys)))
      :else nil)))

(defn- markdown-label
  [field]
  (cond
    (keyword? field) (name field)
    (string? field) field
    :else (str field)))

(defn- markdown-entry
  [value depth preferred-keys collection-keys field]
  (let [child (get value field)
        label (markdown-label field)
        nested (markdown-bullets child (inc depth) preferred-keys collection-keys)]
    (if (and nested (or (map? child) (sequential? child)))
      (str "- " label ":\n" (str/replace nested #"(?m)^-" "  -"))
      (str "- " label ": " (or (summarize-structured child (inc depth)
                                                   preferred-keys collection-keys)
                               (format-preview-scalar child))))))

(defn- markdown-map
  [value depth preferred-keys collection-keys]
  (let [field-keys (take 20 (keys value))]
    (when (seq field-keys)
      (str/join "\n" (map #(markdown-entry value depth preferred-keys collection-keys %)
                           field-keys)))))

(defn- markdown-items
  [value depth preferred-keys collection-keys]
  (let [rendered (->> (take 20 value)
                      (map #(markdown-bullets % (inc depth)
                                             preferred-keys collection-keys))
                      (remove #(or (nil? %) (str/blank? %))))]
    (when (seq rendered) (str/join "\n" rendered))))

(defn markdown-bullets
  "Render up to twenty fields/items below depth four with existing indentation."
  [value depth preferred-keys collection-keys]
  (when (< depth 4)
    (cond
      (preview-scalar? value) (when-let [s (scalar-summary value)] (str "- " s))
      (map? value) (markdown-map value depth preferred-keys collection-keys)
      (sequential? value) (markdown-items value depth preferred-keys collection-keys)
      :else nil)))
