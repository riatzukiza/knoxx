(ns knoxx.backend.extern.translation-dispatch-observation
  "One secret-free warning attempt for the two existing dispatch nil fallbacks."
  (:require [knoxx.backend.domain.error-observatory :as observatory]
            [knoxx.backend.domain.translation-dispatch-outcome :as outcome]))

(defn degraded!
  "Keep observer failure explicit without replacing either original nil fallback."
  [operation primary-error]
  (let [context (outcome/degraded-context operation (some? primary-error))]
    (try
      (observatory/log-warning! "translation-dispatch.degraded" context)
      (catch :default observer-error
        {:observed? false :observation-unavailable? true
         :boundary "translation-dispatch.degraded" :context context
         :observer-error-present? (some? observer-error)}))))
