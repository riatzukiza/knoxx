(ns knoxx.backend.domain.memory-sessions-cache
  "Portable cache identity/expiry/retention decisions; runtime time is an input.")

(defn cache-key-data
  [{:keys [project auth limit offset actor-id exclude-actor-ids contract-id]}]
  {:v 1
   :project (str (or project ""))
   :limit (max 1 (or limit 12))
   :offset (max 0 (or offset 0))
   :actor-id (str (or actor-id ""))
   :exclude-actor-ids (sort (map str (or exclude-actor-ids [])))
   :contract-id (str (or contract-id ""))
   :auth auth})

(defn live-entry?
  [entry at]
  (> (:expires-at entry 0) at))

(defn entry-at
  [value at ttl-ms]
  {:value value :cached-at at :expires-at (+ at ttl-ms)})

(defn cache-hit
  [entry tier at]
  {:value (:value entry)
   :cache {:hit true :tier tier :stale false
           :age_ms (max 0 (- at (:cached-at entry at)))}})

(defn retained-entries
  [entries at maximum]
  (->> entries
       (filter (fn [[_ entry]] (live-entry? entry at)))
       (sort-by (fn [[_ entry]] (:cached-at entry 0)))
       (take-last maximum)
       (into {})))
