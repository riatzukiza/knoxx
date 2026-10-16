(ns knoxx.backend.extern.fastify.app.data
  (:require-macros [knoxx.backend.macros :refer [defroute]])
  (:require [clojure.string :as str]
            [knoxx.backend.extern.fastify.app.core :as core]
            [knoxx.backend.extern.fastify.app.ingestion :as ingestion]
            [knoxx.backend.extern.promise :as promise]
            [knoxx.backend.infra.clients.openplanner :as openplanner-client]
            [knoxx.backend.infra.http :refer [json-response! fetch-json request-body]]))

(declare mongo-collections-ok)

(defn ^:async send-openplanner-v1-json!
  [config reply method path body]
  (try
    (json-response! reply 200 (await (openplanner-client/v1-json!
                                      (openplanner-client/client config) method path body)))
    (catch :default err
      (ingestion/fetch-json-err reply err))))

(defn ^:async send-json-promise!
  [reply request-promise]
  (try
    (json-response! reply 200 (await request-promise))
    (catch :default err
      (ingestion/fetch-json-err reply err))))

(defn ^:async send-mongo-collections!
  [config reply]
  (let [client (openplanner-client/client config)]
    (try
      (let [[docs graph] (await (promise/all-vec
                                 [(openplanner-client/documents-stats! client)
                                  (openplanner-client/graph-monitoring! client)]))]
        (mongo-collections-ok reply [{:ok true :body docs}
                                     {:ok true :body graph}]))
      (catch :default err
        (ingestion/fetch-json-err reply err)))))

(defn ^:async send-data-browse!
  [reply target-url]
  (try
    (let [resp (await (fetch-json target-url nil))]
      (json-response! reply
                      (or (:status resp) (aget resp "status") 200)
                      (or (:body resp) (aget resp "body"))))
    (catch :default err
      (ingestion/fetch-json-err reply err))))

(defn- response-body
  [resp]
  (or (:body resp) (aget resp "body")))

(defn- mongo-collections-ok [reply results]
  (json-response! reply 200
                  {:ok true
                   :documents (response-body (nth results 0))
                   :graph (response-body (nth results 1))}))

(defroute api-data-op-get! []
  "GET" "/api/data/op/*"
  (let [path (aget request "params" "*")
        raw-url (aget request "raw" "url")
        query-idx (.indexOf raw-url "?")
        qs (if (>= query-idx 0) (subs raw-url query-idx) "")]
    (send-openplanner-v1-json! config reply "GET" (str path qs) nil)))

(defroute api-data-op-post! [authorize-openplanner-proxy-post!]
  "POST" "/api/data/op/*"
  (let [path (aget request "params" "*")
        body (authorize-openplanner-proxy-post!
              ctx path (aget request "body"))]
    (send-openplanner-v1-json! config reply "POST" path body)))

(defroute api-data-op-delete! []
  "DELETE" "/api/data/op/*"
  (let [path (aget request "params" "*")]
    (send-openplanner-v1-json! config reply "DELETE" path nil)))

(defroute api-data-op-patch! []
  "PATCH" "/api/data/op/*"
  (let [path (aget request "params" "*")
        body (aget request "body")]
    (send-openplanner-v1-json! config reply "PATCH" path body)))

(defroute api-data-health! []
  "GET" "/api/data/health"
  (core/send-data-health! config reply))

(defroute api-data-mongo-collections! []
  "GET" "/api/data/mongo/collections"
  (send-mongo-collections! config reply))

(defroute api-data-mongo-list! []
  "GET" "/api/data/mongo/list"
  (send-json-promise! reply (openplanner-client/mongo-collections! (openplanner-client/client config))))

(defroute api-data-mongo-query! [authorize-mongo-query!]
  "POST" "/api/data/mongo/query"
  (let [body (authorize-mongo-query! ctx ensure-permission!
                                     (request-body request))]
    (send-json-promise! reply (openplanner-client/mongo-query! (openplanner-client/client config) body))))

(defroute api-data-pg-tables! []
  "GET" "/api/data/pg/tables"
  ;; PostgreSQL was removed in the E14 Mongo migration (kanban 14-05).
  ;; The Mongo explorer at /api/data/mongo/* is the replacement surface.
  (json-response! reply 410 {:error "pg_removed"
                             :detail "PostgreSQL backend removed; use /api/data/mongo/collections"}))

(defroute api-data-jobs-build-semantic-edges! []
  "POST" "/api/data/jobs/build-semantic-edges"
  (let [body (request-body request)
        k (or (aget body "k") 8)
        min-sim (or (aget body "minSimilarity") 0.3)]
    (send-json-promise! reply
                        (openplanner-client/build-semantic-edges!
                         (openplanner-client/client config)
                         {:k k :minSimilarity min-sim}))))

(defroute api-data-pg-query! []
  "POST" "/api/data/pg/query"
  ;; PostgreSQL was removed in the E14 Mongo migration (kanban 14-05).
  (json-response! reply 410 {:error "pg_removed"
                             :detail "PostgreSQL backend removed; use /api/data/mongo/collections"}))

(defroute api-data-browse! []
  "GET" "/api/data/browse"
  (let [qs (aget request "query")
        path (or (aget qs "path") "")
        ingestion-base (:ingestion-base-url config)
        target-url (str ingestion-base "/api/ingestion/browse" (if (str/blank? path) "" (str "?path=" (js/encodeURIComponent path))))]
    (send-data-browse! reply target-url)))

(defroute api-data-file! []
  "GET" "/api/data/file"
  (let [qs (aget request "query")
        path (or (aget qs "path") "")
        ingestion-base (:ingestion-base-url config)]
    (ingestion/send-fetch-json! reply
                      (str ingestion-base "/api/ingestion/file?path=" (js/encodeURIComponent path))
                      nil
                      ingestion/fetch-json-err)))

(defroute api-data-graphql! []
  "POST" "/api/data/graphql"
  (let [body (request-body request)
        gw-url "http://127.0.0.1:8796/graphql"]
    (ingestion/send-fetch-json! reply
                      gw-url
                      {:method "POST"
                       :headers {"Content-Type" "application/json"}
                       :body (or (some-> body js/JSON.stringify) "{}")}
                      ingestion/fetch-json-err)))

(defroute api-data-graph-status! []
  "GET" "/api/data/graph/status"
  (ingestion/send-fetch-json! reply "http://127.0.0.1:8796/api/status" {:method "GET"} ingestion/fetch-json-err))

;; Embed the graph-weaver WebGL view URL for the frontend
(defroute api-data-graph-view-url! []
  "GET" "/api/data/graph/view-url"
  (json-response! reply 200 {:url "http://127.0.0.1:8796"}))
