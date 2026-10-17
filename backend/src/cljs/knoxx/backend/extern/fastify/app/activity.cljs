(ns knoxx.backend.extern.fastify.app.activity
  (:require-macros [knoxx.backend.macros :refer [defroute]])
  (:require [clojure.string :as str]
            [knoxx.backend.domain.action.run-state :as run-state :refer [runs* run-order*]]
            [knoxx.backend.domain.voice.turn-control :as turn-control]
            [knoxx.backend.infra.auth.authz :refer [run-visible?]]
            [knoxx.backend.infra.http :refer [json-response! error-response!]]
            [knoxx.backend.infra.stores.mongo-session-store :as session-store]
            [knoxx.backend.shape.app-activity-wire :as wire]
            [knoxx.backend.shape.parse :refer [parse-positive-int]]))

(defn- active-run-summary [run session]
  (wire/active-run-summary run session
    (boolean (and (:conversation_id run) (turn-control/active-turn (:conversation_id run))))))

(defn- active-session-summary [session]
  (wire/active-session-summary session
    (boolean (and (:conversation_id session) (turn-control/active-turn (:conversation_id session))))))

(defn- live-session-items [run-items include-all? sessions]
  (mapv active-session-summary (wire/live-session-rows run-items include-all? sessions)))

(defn- active-item-time-ms
  [item]
  (let [value (or (:updated_at item) (:created_at item))]
    (cond
      (number? value) value
      (string? value) (let [parsed (js/Date.parse value)]
                        (if (js/isNaN parsed) 0 parsed))
      :else 0)))

(defn- sort-active-items
  [limit items]
  (->> items
       (sort-by active-item-time-ms #(compare %2 %1))
       (take limit)
       vec))

(defn ^:async live-active-agent-summaries!
  [limit include-all?]
  (let [limit (max 1 (or limit 25))
         sessions-by-id (into {}
                             (map (fn [session]
                                    [(:session_id session) session]))
                             (session-store/active-session-snapshots))
        run-items (->> @run-order*
                       (map #(get @runs* %))
                       (filter some?)
                       (filter #(contains? #{"queued" "running" "waiting_input"} (:status %)))
                       (map (fn [run]
                              (active-run-summary run (get sessions-by-id (:session_id run)))))
                                               vec)]
    (sort-active-items limit (concat run-items (live-session-items run-items include-all? (await (session-store/list-active-sessions)))))))

(defn ^:async latest-run-event!
  [run-id]
  (let [run-id (str (or run-id ""))]
    (cond
      (str/blank? run-id)
      nil

      (seq (get-in @runs* [run-id :events]))
      (last (get-in @runs* [run-id :events]))

      :else
      nil)))

(defn- agents-active-ok [reply items]
  (json-response! reply 200 {:runs items :count (count items)}))

(defn- agents-active-err [reply err]
  (error-response! reply err 502))

(defn- run-events-ok [reply run-id events]
  (json-response! reply 200 {:run_id run-id :events events :count (count events)}))

(defn- run-events-err [reply err]
  (json-response! reply 500 {:error (str err)}))

(defn- build-active-runs [ctx limit]
  (let [sessions-by-id (into {}
                             (map (fn [session]
                                    [(:session_id session) session]))
                             (session-store/active-session-snapshots))]
    (->> @run-order*
         (map #(get @runs* %))
         (filter some?)
         (filter #(contains? #{"queued" "running" "waiting_input"} (:status %)))
         (filter #(run-visible? ctx %))
         (map (fn [run]
                (active-run-summary run (get sessions-by-id (:session_id run)))))
         (take limit)
         vec)))

(defroute api-knoxx-agents-active! []
  "GET" "/api/knoxx/agents/active"
  (when ctx (ensure-permission! ctx "agent.chat.use"))
  (let [limit-raw (aget request "query" "limit")
        limit (if (string? limit-raw)
                (max 1 (js/parseInt limit-raw 10))
                25)
        items (build-active-runs ctx limit)]
    (agents-active-ok reply items)))

(defroute api-admin-agents-active! []
  "GET" "/api/admin/agents/active"
  (ensure-permission! ctx "org.events.control")
  (let [limit-raw (aget request "query" "limit")
        limit (or (parse-positive-int limit-raw) 200)]
    (try
      (agents-active-ok reply (await (live-active-agent-summaries! limit false)))
      (catch :default err
        (agents-active-err reply err)))))

(defroute api-knoxx-run-events! []
  "GET" "/api/knoxx/run/:runId/events"
  (when ctx (ensure-permission! ctx "agent.chat.use"))
  (let [run-id (aget request "params" "runId")
        since (or (aget request "query" "since") "")]
    (if (str/blank? run-id)
      (json-response! reply 400 {:error "runId is required"})
      (try
        (run-events-ok reply run-id (await (run-state/get-run-events-since run-id since)))
        (catch :default err
          (run-events-err reply err))))))

(defroute api-knoxx-run-get! []
  "GET" "/api/knoxx/runs/:runId"
  (when ctx (ensure-permission! ctx "agent.chat.use"))
  (let [run-id (str (or (aget request "params" "runId") ""))]
    (cond
      (str/blank? run-id)
      (json-response! reply 400 {:error "runId required"})

      (some? (get @runs* run-id))
      (if-let [filtered (run-visible? ctx (get @runs* run-id))]
        (json-response! reply 200
                        {:ok true :source "memory" :run filtered})
        (json-response! reply 403 {:error "Access denied"}))

      :else
      (json-response! reply 404 {:ok false :error "Run not found"
                                 :run_id run-id}))))
