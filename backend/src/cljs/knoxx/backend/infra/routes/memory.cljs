(ns knoxx.backend.infra.routes.memory
  (:require-macros [knoxx.backend.macros :refer [defroute]])
  (:require [clojure.string :as str]
            [knoxx.backend.infra.http :refer [http-error]]
            [knoxx.backend.infra.clients.openplanner :as openplanner-client]
            [knoxx.backend.infra.core-memory :refer [fetch-openplanner-session-rows!
                                                    session-visible?
                                                    filter-authorized-memory-hits!]]
            [knoxx.backend.infra.openplanner.memory :refer [openplanner-memory-search!]]
            [knoxx.backend.domain.graph.expansion-policy :as expansion-policy]
            [knoxx.backend.domain.graph.policy-registry :as policy-registry]
            [knoxx.backend.domain.realtime :refer [broadcast-ws!]]
            [knoxx.backend.infra.stores.mongo-session-store :as session-store]
            [knoxx.backend.infra.stores.session-titles :refer [session-titles*
                                                  session-title-promises*
                                                  session-title-backfill*
                                                  session-title-seed-text
                                                  heuristic-session-title
                                                  resolve-session-title!
                                                  normalize-session-title
                                                  cache-session-title!
                                                  start-session-title-backfill!]]
            [knoxx.backend.infra.auth.authz :refer [ctx-permitted? system-admin?
                                                  ensure-permission!]]
            [knoxx.backend.domain.memory-sessions-active :as active]
            [knoxx.backend.infra.memory-sessions.cache :as cache]
            [knoxx.backend.infra.memory-sessions.pages :as pages]
            [knoxx.backend.shape.memory-sessions :as memory-shape]
            [knoxx.backend.shape.parse :refer [parse-positive-int truthy-param?]]
            [knoxx.backend.domain.time :refer [now-iso]]
            ["node:crypto" :as crypto]))



;; Preserve the original public callable surface; no cross-reload Var claim.
(def interactive-session-id? pages/interactive-session-id?)
(def memory-sessions-cache-ttl-seconds cache/memory-sessions-cache-ttl-seconds)
(def clear-memory-sessions-cache! cache/clear-memory-sessions-cache!)
(def memory-sessions-auth-scope cache/memory-sessions-auth-scope)
(def memory-sessions-cache-key cache/memory-sessions-cache-key)
(def write-memory-sessions-cache-to-mongo! cache/write-memory-sessions-cache-to-mongo!)
(def mongo-memory-sessions-hit! cache/mongo-memory-sessions-hit!)
(def fetch-and-cache-memory-sessions! cache/fetch-and-cache-memory-sessions!)
(def cached-memory-sessions-source! cache/cached-memory-sessions-source!)
(def filter-page-actor-row! pages/filter-page-actor-row!)
(def filter-page-actor-rows! pages/filter-page-actor-rows!)
(def fetch-contract-session-pages-from-mongo! pages/fetch-contract-session-pages-from-mongo!)
(def fetch-authorized-session-pages! pages/fetch-authorized-session-pages!)
(def search-hit-session-visibility! pages/search-hit-session-visibility!)
(def filter-search-hits-by-actor! pages/filter-search-hits-by-actor!)

(defn- openplanner-ready?
  [config]
  (openplanner-client/enabled? (openplanner-client/client config)))




(defn ^:async run-warm-title-cache!
  [session-id config runtime]
  (try
    (let [title-rows (await (fetch-openplanner-session-rows! config session-id))
          seed-text (session-title-seed-text title-rows)
          fallback-title (heuristic-session-title seed-text)]
      (try
        (let [entry (await (resolve-session-title! config seed-text))]
          (cache-session-title! runtime config session-id
                                (or (normalize-session-title (:title entry) fallback-title)
                                    fallback-title)
                                (:title_model entry)))
        (catch :default _
          (cache-session-title! runtime config session-id fallback-title nil))))
    (catch :default _
      (cache-session-title! runtime config session-id "Untitled session" nil))))

(defn- warm-title-cache! [session-id config runtime]
  (let [session-id (str (or session-id ""))]
    (when (and (not (str/blank? session-id))
               (not (contains? @session-titles* session-id))
               (not (contains? @session-title-promises* session-id)))
      (let [title-promise (run-warm-title-cache! session-id config runtime)]
        (swap! session-title-promises* assoc session-id title-promise)
        title-promise))))


(defn ^:async enrich-row [row]
  (let [session-id (str (:session row))
        titled-row (if-let [title-entry (get @session-titles* session-id)]
                     (assoc row
                            :title (:title title-entry)
                            :title_model (:title_model title-entry))
                     row)]
    (try
      (let [active-session-id (await (session-store/get-conversation-active-session session-id))]
        (if (str/blank? (str active-session-id))
          (active/inactive-row titled-row)
          (try
            (let [active-session (await (session-store/get-session active-session-id))
                  status (or (:status active-session) "inactive")
                  is-active (contains? #{"running" "waiting_input"} status)]
              (assoc titled-row
                     :active_session_id active-session-id
                     :is_active is-active
                     :active_status status
                      :has_active_stream (boolean (:has_active_stream active-session))))
              (catch :default _
                (active/inactive-row titled-row)))))
        (catch :default _
          (active/inactive-row titled-row)))))

(defn memory-sessions-request-options
  [config ctx request]
  (let [{:keys [limit offset actor-id exclude-actor-ids contract-id] :as opts}
        (memory-shape/query-options (aget request "query"))]
    (assoc opts
           :cache-key (memory-sessions-cache-key {:config config
                                                  :ctx ctx
                                                  :limit limit
                                                  :offset offset
                                                  :actor-id actor-id
                                                  :exclude-actor-ids exclude-actor-ids
                                                  :contract-id contract-id}))))




(defn- warm-memory-session-title-rows!
  [rows config runtime]
  (doseq [row rows]
    (warm-title-cache! (str (:session row)) config runtime)))

(defn- send-enriched-memory-sessions!
  [{:keys [reply json-response!]} page-state enriched-rows]
  (json-response! reply 200
                  (memory-shape/response-payload
                   (assoc page-state
                          :rows (vec (js->clj enriched-rows :keywordize-keys true)))))
  nil)

(defn ^:async send-memory-session-rows!
  [{:keys [config runtime error-response! reply] :as env} page-state rows]
  (warm-memory-session-title-rows! rows config runtime)
  (try
    (let [enriched-rows (await (.all js/Promise (clj->js (mapv enrich-row rows))))]
      (send-enriched-memory-sessions! env page-state enriched-rows))
    (catch :default err
      (error-response! reply err 502)
      nil)))


(defn ^:async send-memory-sessions-live-ids!
  [{:keys [error-response! reply] :as env}
   {:keys [page-rows actor-id exclude-actor-ids contract-id] :as page-state}
   live-ids]
  (try
    (let [live-js (await (.all js/Promise
                               (clj->js (mapv #(session-store/get-session %)
                                               (vec live-ids)))))
          live (vec (js->clj live-js :keywordize-keys true))
          synthetic (active/synthetic-active-session-rows live page-rows actor-id exclude-actor-ids contract-id)]
      (await (send-memory-session-rows! env page-state (vec (concat synthetic page-rows)))))
    (catch :default err
      (error-response! reply err 502)
      nil)))

(defn ^:async send-memory-sessions-result!
  [env {:keys [page-rows] :as page-state}]
  (try
    (let [live-ids (await (session-store/list-active-session-ids))]
      (if (seq live-ids)
        (await (send-memory-sessions-live-ids! env page-state live-ids))
        (await (send-memory-session-rows! env page-state page-rows))))
    (catch :default _
      (await (send-memory-session-rows! env page-state page-rows)))))

(defroute memory-sessions-route! [authorized-session-ids!
                                  fetch-openplanner-session-rows!
                                  session-matches-page-actor-filter?]
  "GET" "/api/memory/sessions"
  (if-not (openplanner-ready? config)
    (json-response! reply 503 {:detail "OpenPlanner is not configured"})
    (try
      (let [opts (memory-sessions-request-options config ctx request)
            env {:config config
                 :runtime runtime
                 :reply reply
                 :json-response! json-response!
                 :error-response! error-response!}
            {:keys [value cache]} (await (pages/fetch-memory-sessions-source! config ctx opts
                                                                         authorized-session-ids!
                                                                         fetch-openplanner-session-rows!
                                                                         session-matches-page-actor-filter?))]
        (await (send-memory-sessions-result!
                env
                (memory-shape/page-state value opts cache))))
      (catch :default err
        (error-response! reply err 502)
        nil))))

(defroute memory-session-titles-status-route! []
  "GET" "/api/memory/session-titles/status"
  (if-not (openplanner-ready? config)
    (json-response! reply 503 {:detail "OpenPlanner is not configured"})
    (json-response! reply 200 {:ok true
                               :status @session-title-backfill*
                               :cached_count (count @session-titles*)})))

(defroute memory-backfill-titles-route! [fetch-openplanner-session-rows!]
  "POST" "/api/memory/sessions/backfill-titles"
  (if-not (openplanner-ready? config)
    (json-response! reply 503 {:detail "OpenPlanner is not configured"})
    (try
      (let [body (or (aget request "body") (js/Object.))
            limit (or (parse-positive-int (aget body "limit"))
                      (parse-positive-int (aget request "query" "limit")))
            force? (or (truthy-param? (aget body "force"))
                       (truthy-param? (aget request "query" "force")))
            status (await (start-session-title-backfill! runtime config {:force force? :limit limit} fetch-openplanner-session-rows!))]
        (json-response! reply 202 {:ok true
                                   :status status
                                   :cached_count (count @session-titles*)}))
      (catch :default err
        (error-response! reply err 502)))))

(defroute memory-import-titles-route! []
  "POST" "/api/memory/sessions/import-titles"
  (if-not (openplanner-ready? config)
    (json-response! reply 503 {:detail "OpenPlanner is not configured"})
    (let [body (js->clj (or (aget request "body") (js/Object.)))
          titles (or (get body "titles") {})
          updated (reduce-kv (fn [total session-id entry]
                               (let [session-id (str session-id)
                                     raw-title (if (map? entry) (or (get entry "title") (get entry :title)) entry)
                                     title-model (when (map? entry)
                                                   (or (get entry "title_model")
                                                       (get entry "title-model")
                                                       (get entry "model")
                                                       (:title_model entry)
                                                       (:title-model entry)
                                                       (:model entry)))
                                     normalized (normalize-session-title raw-title)]
                                 (if (or (str/blank? session-id) (nil? normalized))
                                   total
                                   (do
                                     (cache-session-title! runtime config session-id normalized (or title-model "retro:heuristic"))
                                     (inc total)))))
                             0
                             titles)]
      (json-response! reply 200 {:ok true
                                 :updated updated
                                 :cached_count (count @session-titles*)}))))

(defroute memory-session-by-id-route! [fetch-openplanner-session-rows!]
  "GET" "/api/memory/sessions/:sessionId"
  (if-not (openplanner-ready? config)
    (json-response! reply 503 {:detail "OpenPlanner is not configured"})
    (try
      (let [session-id (or (aget request "params" "sessionId") "")
            requested-limit (parse-positive-int (aget request "query" "limit"))
            preview-limit (when requested-limit
                            (:limit (expansion-policy/bounded-preview-params
                                     (policy-registry/get-policy)
                                     {:limit requested-limit})))]
        (if (str/blank? session-id)
          (json-response! reply 400 {:detail "sessionId is required"})
          (let [rows (await (fetch-openplanner-session-rows! config session-id))]
            (if (session-visible? ctx rows)
              (json-response! reply 200 {:ok true
                                         :session session-id
                                         :rows (if preview-limit
                                                 (vec (take preview-limit rows))
                                                 rows)})
              (error-response! reply (http-error 403 "memory_scope_denied" "Session is outside the current Knoxx scope"))))))
      (catch :default err
        (error-response! reply err 502)))))

(defn- require-memory-read! [ctx]
  (ensure-permission! ctx "agent.memory.read"))

(defn ^:async send-memory-search!
  [{:keys [config ctx reply json-response!]}
   fetch-openplanner-session-rows!
   session-matches-page-actor-filter?
   {:keys [query bounded-k session-id actor-id exclude-actor-ids]}]
  (let [result (await (openplanner-memory-search! config {:query query
                                                          :k bounded-k
                                                          :session-id session-id}))
        hits (await (filter-authorized-memory-hits! config ctx (:hits result)))
        filtered-hits (await (filter-search-hits-by-actor! config
                                                           fetch-openplanner-session-rows!
                                                           session-matches-page-actor-filter?
                                                           actor-id
                                                           exclude-actor-ids
                                                           hits))]
    (json-response! reply 200 (assoc result :ok true :hits filtered-hits))))

(defn- memory-search-request-options [request]
  (let [body (or (aget request "body") (js/Object.))
        {bounded-k :k} (expansion-policy/bounded-search-params
                        (policy-registry/get-policy)
                        {:k (aget body "k")})]
    {:query (or (aget body "query") "")
     :bounded-k bounded-k
     :session-id (or (aget body "sessionId") (aget body "session_id") "")
     :actor-id (memory-shape/normalized-actor-id (or (aget body "actorId")
                                                     (aget body "actor_id")
                                                     (aget body "actor")))
     :exclude-actor-ids (memory-shape/normalized-actor-ids
                         (or (aget body "excludeActorIds")
                             (aget body "exclude_actor_ids")
                             (aget body "excludeActorId")
                             (aget body "exclude_actor_id")))}))

(defn- ensure-memory-search-scope! [ctx session-id]
  (require-memory-read! ctx)
  (when (and (str/blank? (str session-id))
             (not (ctx-permitted? ctx "agent.memory.cross_session"))
             (not (system-admin? ctx)))
    (throw (http-error 403 "memory_scope_denied" "Cross-session memory search is outside the current Knoxx scope"))))

(defroute memory-search-route! [fetch-openplanner-session-rows!
                                session-matches-page-actor-filter?]
  "POST" "/api/memory/search"
  (if-not (openplanner-ready? config)
    (json-response! reply 503 {:detail "OpenPlanner is not configured"})
    (try
      (let [{:keys [session-id] :as opts} (memory-search-request-options request)]
        (ensure-memory-search-scope! ctx session-id)
        (await (send-memory-search!
                {:config config :ctx ctx :reply reply :json-response! json-response!}
                fetch-openplanner-session-rows!
                session-matches-page-actor-filter?
                opts)))
      (catch :default err
        (error-response! reply err 502)))))

(defroute lounge-messages-list-route! [lounge-messages*]
  "GET" "/api/lounge/messages"
  (json-response! reply 200 {:messages @lounge-messages*}))

(defroute lounge-messages-create-route! [lounge-messages*]
  "POST" "/api/lounge/messages"
  (let [body (or (aget request "body") (js/Object.))
        session-id (str (or (aget body "session_id") ""))
        alias (str/trim (str (or (aget body "alias") "anonymous")))
        text (str/trim (str (or (aget body "text") "")))]
    (cond
      (str/blank? session-id) (json-response! reply 400 {:detail "session_id is required"})
      (str/blank? text) (json-response! reply 400 {:detail "text is required"})
      :else (let [msg {:id (str (.randomUUID crypto))
                       :timestamp (now-iso)
                       :session_id session-id
                       :alias (if (str/blank? alias) "anonymous" alias)
                       :text text}]
              (swap! lounge-messages* #(->> (conj (vec %) msg) (take-last 100) vec))
              (broadcast-ws! "lounge" msg)
              (json-response! reply 200 {:ok true :message msg})))))

(defn register-memory-routes!
  [app runtime config deps]
  (js/console.log "memory-sessions-route! ="
                  (.-name memory-sessions-route!))
  (memory-sessions-route! app runtime config deps)
  (memory-session-titles-status-route! app runtime config deps)
  (memory-backfill-titles-route! app runtime config deps)
  (memory-import-titles-route! app runtime config deps)
  (memory-session-by-id-route! app runtime config deps)
  (memory-search-route! app runtime config deps)
  (lounge-messages-list-route! app runtime config deps)
  (lounge-messages-create-route! app runtime config deps))
