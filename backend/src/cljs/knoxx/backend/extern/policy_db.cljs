(ns knoxx.backend.extern.policy-db
  "Policy policy db boundary extracted from the Mongo-backed public facade."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(defn http-error [status message code]
  (doto (js/Error. message)
    (aset "statusCode" status)
    (aset "code" code)))

(defn- default-contracts-dir []
  (let [configured (some-> (aget js/process.env "CONTRACTS_DIR") str str/trim not-empty)
        cwd (.cwd js/process)]
    (or (some (fn [c] (when (.existsSync fs c) c))
              (map #(.resolve path cwd %)
                   (keep identity [configured "contracts" "../contracts"
                                   "packages/agents/knoxx/contracts"
                                   "orgs/open-hax/openplanner/packages/agents/knoxx/contracts"])))
        (.resolve path cwd (or configured "contracts")))))

(defn contracts-dir [] (default-contracts-dir))

(defn contracts-config [] {:contracts-dir (default-contracts-dir)})

(defn- read-only-contract-write-error?
  [err]
  (let [code (some-> (.-code err) str str/trim)
        message (some-> (.-message err) str)]
    (or (#{"EROFS" "EACCES" "EPERM"} code)
        (and message (boolean (re-find #"read-only file system|permission denied|operation not permitted" message))))))

(defn handle-read-only-actor-contract-error!
  [err]
  (if (read-only-contract-write-error? err)
    (do
      (.warn js/console
             "[knoxx-policy] actor contract write skipped; contracts dir is read-only/unwritable"
             (.-message err))
      nil)
    (throw err)))

(defn policy-with-constraints-json
  [p]
  (assoc p :constraints-json (js/JSON.stringify (clj->js (:constraints p)))))

(defn constraints-json->clj
  [value]
  (js->clj (or value (js-obj)) :keywordize-keys true))

(defn header-value [headers-like name]
  (when headers-like
    (if (fn? (aget headers-like "get"))
      (str/trim (or (.get headers-like name)
                    (.get headers-like (str/lower-case name)) ""))
      (str/trim (str (or (aget headers-like name)
                         (aget headers-like (str/lower-case name)) ""))))))

(defn org-row->map
  [{:keys [id slug name kind is_primary status member_count role_count data_lake_count created_at updated_at]}]
  {:id id :slug slug :name name :kind kind
   :is-primary is_primary :status status
   :member-count (js/Number (or member_count 0))
   :role-count (js/Number (or role_count 0))
   :data-lake-count (js/Number (or data_lake_count 0))
   :created-at created_at :updated-at updated_at})

(defn credential-row->map
  [{:keys [id provider kind account_identifier status secret_json created_at updated_at]}]
  {:id                 id
   :provider           provider
   :kind               kind
   :accountIdentifier  account_identifier
   :status             status
   :configuredFields   (vec (remove str/blank? (map str (keys (js->clj (or secret_json {}) :keywordize-keys true)))))
   :createdAt          created_at
   :updatedAt          updated_at})

(defn secret-json->clj
  [value]
  (cond
    (nil? value) {}
    (map? value) value
    (string? value) (js->clj (js/JSON.parse value) :keywordize-keys true)
    :else (js->clj value :keywordize-keys true)))

(defn invite-error [message status]
  (doto (js/Error. message)
    (aset "status" status)))

(defn actor-credential-response [row]
  {:credential (when row
                 (let [secret (js->clj (or (:secret_json row) {}) :keywordize-keys true)]
                   {:id                 (:id row)
                    :userId             (:user_id row)
                    :orgId              (:org_id row)
                    :provider           (:provider row)
                    :kind               (:kind row)
                    :accountIdentifier  (:account_identifier row)
                    :status             (:status row)
                    :secretJson         secret
                    :configuredFields   (vec (remove str/blank? (map name (keys secret))))}))})

(defn error [message] (js/Error. message))
(defn error-message [error] (.-message error))
(defn warn! [& values] (.apply (.-warn js/console) js/console (into-array values)))
(defn resolved [value] (js/Promise.resolve value))
(defn ^:async all! [values] (await (js/Promise.all (into-array values))))
(defn value [data] (clj->js data))
(defn keyword-map [data] (js->clj data :keywordize-keys true))
(defn json-string [data] (js/JSON.stringify (clj->js data)))
(defn options [data] (if (map? data) data (js->clj data :keywordize-keys true)))
(defn parse-role-slugs [data]
  (let [parsed (cond (nil? data) []
                     (string? data) (js->clj (js/JSON.parse data))
                     :else (js->clj data))]
    (if (sequential? parsed) (vec parsed) [])))
(defn invite-identity []
  {:code (.toString (.randomBytes crypto 8) "hex")
   :expires-at (.toISOString (js/Date. (+ (js/Date.now) (* 7 24 3600 1000))))})
