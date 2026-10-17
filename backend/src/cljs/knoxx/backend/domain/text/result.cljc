(ns knoxx.backend.domain.text.result
  "Portable text presentation of decoded search and graph result data."
  (:require [clojure.string :as str]))

(defn semantic-read-text
  "Retain corpus/path/content output and its original truncation notice."
  [database path content truncated]
  (str "Active corpus: " (:name database) "\n"
       "Path: " path
       (when truncated "\nNote: content truncated for tool safety.")
       "\n\n"
       content))

(defn websearch-text
  "Retain model/output/source order without interpreting native provider data."
  [output sources model]
  (str "Web search"
       (when model (str " via " model))
       "\n\n"
       (or output "")
       (when (seq sources)
         (str "\n\nSources:\n"
              (str/join "\n"
                        (map (fn [source]
                               (str "- " (or (:title source) (:url source))
                                    " — " (:url source)))
                             sources))))))

(defn graph-clusters-text
  "Present the already selected cluster sequence in its original order."
  [clusters]
  (str "\n\nClusters:\n"
       (str/join "\n"
                 (map (fn [c]
                        (str "  " (:lake c) ": " (:count c) " nodes"
                             (when-let [top (seq (:topNodes c))]
                               (str "\n    top: " (str/join ", " (map :id top))))))
                      clusters))))

(defn graph-query-text
  "Join prepared row text after node/edge formatting has completed in its owner."
  [result stats node-text edge-text clusters-text]
  (str "Knowledge graph query"
       (when-let [q (:query result)]
         (str "\nQuery: " q))
       (when-let [projects (seq (:projects result))]
         (str "\nProjects: " (str/join ", " projects)))
       (when stats
         (str "\nStats: " (pr-str stats)))
       "\n\nNodes:\n"
       node-text
       edge-text
       clusters-text))

(defn empty-graph-text
  "Retain the empty-result response and optional original stats form."
  [stats]
  (str "Knowledge graph query returned no matching nodes."
       (when stats (str " " (pr-str stats)))))
