(ns validate-generator-planning-receipt
  (:require [clojure.string :as str]
            [eta-mu.receipt-river.api :as api]
            ["node:fs" :as fs]))
(let [[ledger] *command-line-args*
      lines (str/split-lines (.readFileSync fs ledger "utf8"))
      results (mapv (fn [idx line]
                      (select-keys (api/validate-line line (inc idx))
                                   [:ok :line-number :source/schema :errors]))
                    (range) lines)
      own (last results)]
  (prn {:total-lines (count lines)
        :own-addition own
        :historical-not-qualification
        {:count (dec (count lines))
         :errors (filterv (comp not :ok) (butlast results))}})
  (when-not (and (= 17 (count lines)) (:ok own)
                 (= :declared (get-in own [:source/schema :status])))
    (set! (.-exitCode js/process) 1)))
