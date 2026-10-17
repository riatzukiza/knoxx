(ns knoxx.backend.extern.memory-sessions
  "Native clock/hash and batch-result representation for memory-session reads."
  (:require [knoxx.backend.domain.error-observatory :as observatory]
            ["node:crypto" :as crypto]))

(defn now-ms
  "Read wall time for the cache's explicit timestamp input."
  []
  (.now js/Date))

(defn cache-key
  "Preserve the v1 JSON property order and UTF-8 SHA256 cache identity."
  [data]
  (-> (.createHash crypto "sha256")
      (.update (.stringify js/JSON (clj->js data)))
      (.digest "hex")))

(defn degraded!
  "Observe once without letting a broken observer change the original fallback."
  [boundary fallback error]
  (let [context {:fallback fallback :error? (some? error)}]
    (try
      (observatory/log-warning! boundary context)
      (catch :default observer-error
        {:observed? false :observation-unavailable? true
         :boundary boundary :context context
         :observer-error? (some? observer-error)}))))

(defn ^:async all-results!
  "Preserve ordered Promise.all plus the old deep keywordized decode."
  [promises]
  (let [results (await (.all js/Promise (clj->js (vec promises))))]
    (vec (js->clj results :keywordize-keys true))))
