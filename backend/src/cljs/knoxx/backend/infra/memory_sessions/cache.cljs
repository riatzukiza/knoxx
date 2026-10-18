(ns knoxx.backend.infra.memory-sessions.cache
  "Auth-scoped local/Mongo memory-session cache and finite fetch ownership."
  (:require [knoxx.backend.domain.memory-sessions-cache :as cache-data]
            [knoxx.backend.extern.memory-sessions :as runtime]
            [knoxx.backend.infra.auth.authz :refer [ctx-actor-id ctx-membership-id
                                                  ctx-org-id ctx-permitted?
                                                  ctx-user-id system-admin?]]
            [knoxx.backend.infra.stores.mongo-memory-sessions :as mongo-memory-sessions]))

(def memory-sessions-cache-ttl-seconds 10)
(def ^:private memory-sessions-cache-ttl-ms (* memory-sessions-cache-ttl-seconds 1000))
(def ^:private memory-sessions-cache-max-entries 256)
(defonce ^:private memory-sessions-cache* (atom {}))
(defonce ^:private memory-sessions-cache-promises* (atom {}))

(defn clear-memory-sessions-cache!
  []
  (reset! memory-sessions-cache* {})
  (reset! memory-sessions-cache-promises* {})
  true)


(defn memory-sessions-auth-scope
  [ctx]
  {:system-admin? (boolean (system-admin? ctx))
   :cross-session? (boolean (ctx-permitted? ctx "agent.memory.cross_session"))
   :org-id (str (or (ctx-org-id ctx) ""))
   :membership-id (str (or (ctx-membership-id ctx) ""))
   :user-id (str (or (ctx-user-id ctx) ""))
   :actor-id (str (or (ctx-actor-id ctx) ""))})

(defn memory-sessions-cache-key
  [{:keys [config ctx limit offset actor-id exclude-actor-ids contract-id]}]
  (runtime/cache-key
   (cache-data/cache-key-data
    {:project (:session-project-name config)
     :limit limit :offset offset :actor-id actor-id
     :exclude-actor-ids exclude-actor-ids :contract-id contract-id
     :auth (memory-sessions-auth-scope ctx)})))

(defn- evict-memory-sessions-cache!
  []
  (swap! memory-sessions-cache* cache-data/retained-entries
         (runtime/now-ms) memory-sessions-cache-max-entries))

(defn- memory-sessions-cache-entry
  [value]
  (cache-data/entry-at value (runtime/now-ms) memory-sessions-cache-ttl-ms))

(defn- memory-sessions-local-hit
  [cache-key]
  (when-let [entry (get @memory-sessions-cache* cache-key)]
    (let [ts (runtime/now-ms)]
      (if (cache-data/live-entry? entry ts)
        (cache-data/cache-hit entry "memory" ts)
        (do
          (swap! memory-sessions-cache* dissoc cache-key)
          nil)))))

(defn- remember-memory-sessions-cache!
  [cache-key entry]
  (swap! memory-sessions-cache* assoc cache-key entry)
  (evict-memory-sessions-cache!)
  entry)

(defn ^:async write-memory-sessions-cache-to-mongo!
  [cache-key entry]
  (try
    (await (mongo-memory-sessions/set-cache-entry! cache-key entry))
    (catch :default error
      (runtime/degraded! "memory-sessions/mongo-write"
                                :miss error)
      nil)))

(defn- write-memory-sessions-cache!
  [cache-key value]
  (let [entry (memory-sessions-cache-entry value)]
    (remember-memory-sessions-cache! cache-key entry)
    (write-memory-sessions-cache-to-mongo! cache-key entry)
    entry))

(defn ^:async mongo-memory-sessions-hit!
  [cache-key]
  (try
    (let [entry (await (mongo-memory-sessions/get-cache-entry! cache-key))
          ts (runtime/now-ms)]
      (when (and entry (cache-data/live-entry? entry ts))
        (remember-memory-sessions-cache! cache-key entry)
        (cache-data/cache-hit entry "mongo" ts)))
    (catch :default error
      (runtime/degraded! "memory-sessions/mongo-read"
                                :miss error)
      nil)))

(defn ^:async fetch-and-cache-memory-sessions!
  [cache-key fetch-fn]
  (let [value (await (fetch-fn))]
    (write-memory-sessions-cache! cache-key value)
    {:value value
     :cache {:hit false
             :tier "miss"
             :stale false
             :age_ms 0}}))

(defn- forget-owned-memory-sessions-promise!
  [cache-key promise]
  (swap! memory-sessions-cache-promises*
         (fn [pending]
           (if (identical? promise (get pending cache-key))
             (dissoc pending cache-key)
             pending))))

(defn ^:async cached-memory-sessions-source!
  [cache-key fetch-fn]
  (if-let [hit (memory-sessions-local-hit cache-key)]
    hit
    (if-let [hit (await (mongo-memory-sessions-hit! cache-key))]
      hit
      (if-let [pending (get @memory-sessions-cache-promises* cache-key)]
        (await pending)
        (let [promise (fetch-and-cache-memory-sessions! cache-key fetch-fn)]
          (swap! memory-sessions-cache-promises* assoc cache-key promise)
          (try
            (await promise)
            (finally
              (forget-owned-memory-sessions-promise! cache-key promise))))))))


