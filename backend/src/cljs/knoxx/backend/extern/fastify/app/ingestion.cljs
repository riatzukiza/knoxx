(ns knoxx.backend.extern.fastify.app.ingestion
  (:require-macros [knoxx.backend.macros :refer [defroute]])
  (:require [clojure.string :as str]
            [knoxx.backend.infra.document-state :refer [normalize-relative-path]]
            [knoxx.backend.infra.http :refer [forward-knoxx-request! json-response! rewrite-localhost-url with-query-param fetch-json send-fetch-response! request-body]]
            ["node:fs/promises" :as fs]
            ["node:path" :as path]))

(defn- proxy-err [reply prefix err]
  (json-response! reply 502 {:detail (str prefix err)}))

(defn- fetch-json-ok [reply resp]
  (json-response! reply (or (:status resp) 200) (:body resp)))

(defn fetch-json-err
  "Project a failed data/ingestion fetch using its established 502 wire body."
  [reply err]
  (json-response! reply 502 {:error (.-message err)}))

(defn- fetch-json-err-detail [reply prefix err]
  (json-response! reply 502 {:detail (str prefix err)}))

(defn ^:async send-knoxx-proxy!
  [config request reply method path]
  (try
    (send-fetch-response! reply (await (forward-knoxx-request! config request method path nil)))
    (catch :default err
      (proxy-err reply "Proxy request failed: " err))))

(defn ^:async send-fetch-json!
  [reply target-url opts error-handler]
  (try
    (fetch-json-ok reply (await (fetch-json target-url opts)))
    (catch :default err
      (error-handler reply err))))

(defn ^:async send-fetch-json-detail!
  [reply target-url opts prefix]
  (send-fetch-json! reply target-url opts #(fetch-json-err-detail %1 prefix %2)))

(defn ^:async write-ingestion-file!
  [reply absolute-path content safe-path]
  (try
    (await (.mkdir fs (.dirname path absolute-path) (clj->js {:recursive true})))
    (await (.writeFile fs absolute-path content "utf8"))
    (json-response! reply 200 {:ok true :path safe-path})
    (catch :default err
      (json-response! reply 500 {:detail (str "Write failed: " err)}))))

(defn- shibboleth-ok [config reply request body data]
  (let [session (or (:session data) {})
        session-id (str (or (:id session) ""))
        ui-url (if (and (not (str/blank? session-id))
                        (not (str/blank? (:shibboleth-ui-url config))))
                 (with-query-param (rewrite-localhost-url (:shibboleth-ui-url config) request)
                   "session"
                   session-id)
                 "")]
    (if (str/blank? session-id)
      (json-response! reply 502 {:detail "Shibboleth import did not return a session id"})
      (json-response! reply 200 {:ok true
                                 :session_id session-id
                                 :ui_url ui-url
                                 :imported_item_count (count (or (:items body) []))}))))

(defn- shibboleth-import-failed [reply resp]
  (json-response! reply 502 {:detail (str "Shibboleth import failed: "
                                          (or (:raw (:body resp))
                                              (js/JSON.stringify (clj->js (:body resp)))))}))

(defn- shibboleth-unreachable [reply err]
  (json-response! reply 502 {:detail (str "Shibboleth is unreachable: " err)}))

(defroute api-knoxx-proxy-get! []
  "GET" "/api/knoxx/proxy/*"
  (send-knoxx-proxy! config request reply "GET" (aget request "params" "*")))

(defroute api-knoxx-proxy-post! []
  "POST" "/api/knoxx/proxy/*"
  (send-knoxx-proxy! config request reply "POST" (aget request "params" "*")))

(defroute api-knoxx-proxy-put! []
  "PUT" "/api/knoxx/proxy/*"
  (send-knoxx-proxy! config request reply "PUT" (aget request "params" "*")))

(defroute api-knoxx-proxy-patch! []
  "PATCH" "/api/knoxx/proxy/*"
  (send-knoxx-proxy! config request reply "PATCH" (aget request "params" "*")))

(defroute api-knoxx-proxy-delete! []
  "DELETE" "/api/knoxx/proxy/*"
  (send-knoxx-proxy! config request reply "DELETE" (aget request "params" "*")))

(defroute api-ingestion-browse! []
  "GET" "/api/ingestion/browse"
  (let [target-url (str (:ingestion-base-url config) "/api/ingestion/browse"
                        (request-query-string request))]
    (send-fetch-json! reply target-url {:method "GET"} fetch-json-err)))

(defroute api-ingestion-file! []
  "GET" "/api/ingestion/file"
  (let [target-url (str (:ingestion-base-url config) "/api/ingestion/file"
                        (request-query-string request))]
    (send-fetch-json! reply target-url {:method "GET"} fetch-json-err)))

(defroute api-ingestion-file-put! []
  "PUT" "/api/ingestion/file"
  (let [workspace-root (:workspace-root config)
        body (request-body request)
        file-path (or (aget body "path") "")
        content (or (aget body "content") "")
        safe-path (normalize-relative-path file-path)]
    (if (or (str/blank? safe-path) (str/starts-with? safe-path ".."))
      (json-response! reply 400 {:detail "Invalid or unsafe file path"})
      (let [absolute-path (.resolve path workspace-root safe-path)]
        (if (not (str/starts-with? absolute-path workspace-root))
          (json-response! reply 400 {:detail "Path escapes workspace"})
          (write-ingestion-file! reply absolute-path content safe-path))))))

(defroute api-ingestion-sources! []
  "GET" "/api/ingestion/sources"
  (send-fetch-json! reply
                    (str (:ingestion-base-url config) "/api/ingestion/sources")
                    {:method "GET"}
                    fetch-json-err))

(defroute api-ingestion-jobs-get! []
  "GET" "/api/ingestion/jobs"
  (let [target-url (str (:ingestion-base-url config) "/api/ingestion/jobs"
                        (request-query-string request))]
    (send-fetch-json! reply target-url {:method "GET"} fetch-json-err)))

(defroute api-ingestion-jobs-post! []
  "POST" "/api/ingestion/jobs"
  (let [body (aget request "body")]
    (send-fetch-json! reply
                      (str (:ingestion-base-url config) "/api/ingestion/jobs")
                      {:method "POST"
                       :headers {"Content-Type" "application/json"}
                       :body (or (some-> body js/JSON.stringify) "{}")}
                      fetch-json-err)))

(defroute api-ingestion-proxy-get! []
  "GET" "/api/ingestion-proxy/*"
  (let [ingestion-base (:ingestion-base-url config)
        path (aget request "params" "*")
        qs (request-query-string request)
        target-url (str ingestion-base "/api/ingestion/" path qs)]
    (send-fetch-json-detail! reply target-url {:method "GET"} "Ingestion proxy failed: ")))

(defroute api-ingestion-proxy-post! []
  "POST" "/api/ingestion-proxy/*"
  (let [ingestion-base (:ingestion-base-url config)
        path (aget request "params" "*")
        target-url (str ingestion-base "/api/ingestion/" path)
        body (aget request "body")]
    (send-fetch-json-detail! reply
                             target-url
                             {:method "POST"
                              :headers {"Content-Type" "application/json"}
                              :body (or (some-> body js/JSON.stringify) "{}")}
                             "Ingestion proxy failed: ")))

(defroute api-ingestion-proxy-delete! []
  "DELETE" "/api/ingestion-proxy/*"
  (let [ingestion-base (:ingestion-base-url config)
        path (aget request "params" "*")
        target-url (str ingestion-base "/api/ingestion/" path)]
    (send-fetch-json-detail! reply target-url {:method "DELETE"} "Ingestion proxy failed: ")))

(defroute api-shibboleth-handoff! []
  "POST" "/api/shibboleth/handoff"
  (let [body (request-body request)]
    (if (str/blank? (:shibboleth-base-url config))
      (json-response! reply 503 {:detail "SHIBBOLETH_BASE_URL is not configured"})
      (let [payload {:source_app "knoxx"
                     :model (:model body)
                     :system_prompt (:system_prompt body)
                     :provider (:provider body)
                     :conversation_id (:conversation_id body)
                     :fake_tools_enabled (boolean (:fake_tools_enabled body))
                     :items (or (:items body) [])}]
        (try
          (let [resp (await (fetch-json (str (:shibboleth-base-url config) "/api/chat/import")
                                        {:method "POST"
                                         :json payload}))]
            (if (:ok resp)
              (shibboleth-ok config reply request body (:body resp))
              (shibboleth-import-failed reply resp)))
          (catch :default err
            (shibboleth-unreachable reply err)))))))
