(ns validate-owned-receipts
  (:require [clojure.string :as str]
            [eta-mu.receipt-river.api :as api]
            ["node:fs" :as fs]))
(let [lines (str/split-lines (fs/readFileSync (first *command-line-args*) "utf8"))
      results (mapv (fn [i line] (api/validate-line line (inc i))) (range) lines)
      inherited (take 16 results)
      owned (drop 16 results)
      summary {:total (count results)
               :inherited-error-count (count (filter #(not (:ok %)) inherited))
               :owned (mapv #(select-keys % [:line-number :ok :errors :source/schema]) owned)}]
  (prn summary)
  (when-not (and (= 16 (:inherited-error-count summary))
                 (seq owned)
                 (every? :ok owned))
    (throw (ex-info "Owned validation or inherited error provenance changed" summary))))
