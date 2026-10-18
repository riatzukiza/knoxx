(ns translation-split-review-fixture
  "Run-owned translation verification entry; all host effects use named externs."
  (:require [knoxx.backend.extern.node-cli :as cli]
            [knoxx.backend.extern.node-env :as node-env]
            [knoxx.backend.extern.openplanner-translation-mongo.verification-fixture :as fixture]))

(defn- required-env [field]
  (or (node-env/variable field)
      (throw (ex-info "Required verification setting is absent"
                      {:code :verification-setting-missing}))))

(defn- optional-value [field]
  (let [value (node-env/variable field)]
    (when-not (= "__NONE__" value) value)))

(defn- qualified-keyword! [value]
  (let [decoded (keyword value)]
    (when-not (namespace decoded)
      (throw (ex-info "Expected a qualified verification resource identity"
                      {:code :verification-resource-invalid})))
    decoded))

(defn- locale-keyword! [value]
  (let [decoded (keyword value)]
    (when (namespace decoded)
      (throw (ex-info "Expected an unqualified verification locale"
                      {:code :verification-locale-invalid})))
    decoded))

(defn- connection-settings []
  {:mongo-uri (or (node-env/variable "MONGODB_URI")
                  (node-env/variable "OPENPLANNER_MONGODB_URI")
                  "mongodb://localhost:27017")
   :mongo-db (or (node-env/variable "MONGODB_DB")
                 (node-env/variable "OPENPLANNER_MONGODB_DB") "openplanner")
   :source-commit (required-env "VERIFY_SOURCE_COMMIT")})

(defn- resource-request [action]
  {:action action
   :settings (assoc (connection-settings)
                    :source-file (required-env "VERIFY_SOURCE_FILE")
                    :content-root (required-env "KNOXX_PUBLICATION_CONTENT_ROOT"))
   :scope {:run-id (required-env "VERIFY_RUN_ID") :org-id (required-env "VERIFY_ORG_ID")
           :project (optional-value "VERIFY_PROJECT")
           :document (qualified-keyword! (required-env "VERIFY_DOCUMENT_ID"))
           :garden (qualified-keyword! (required-env "VERIFY_GARDEN_ID"))
           :publication (qualified-keyword! (required-env "VERIFY_PUBLICATION_ID"))
           :publication-path (required-env "VERIFY_PUBLICATION_PATH")
           :document-title (required-env "VERIFY_DOCUMENT_TITLE")
           :target-locale (locale-keyword! (or (node-env/variable "VERIFY_TARGET_LOCALE") "es"))}})

(defn- legacy-request [action]
  {:action action
   :settings (assoc (connection-settings)
                    :ownership-file (required-env "VERIFY_LEGACY_OWNERSHIP_FILE"))
   :scope {:run-id (required-env "VERIFY_RUN_ID") :org-id (required-env "VERIFY_ORG_ID")
           :project (required-env "VERIFY_PROJECT")
           :document-id (required-env "VERIFY_LEGACY_DOCUMENT_ID")
           :garden-id (optional-value "VERIFY_LEGACY_GARDEN_ID")
           :target-locale :es}})

(defn- ^:async execute! []
  (let [arguments (cli/arguments *command-line-args*)
        action (first arguments)]
    (when-not (= 1 (count arguments))
      (throw (ex-info "Expected exactly one verification fixture action"
                      {:code :verification-action-invalid})))
    (await (fixture/execute!
            (if (contains? #{"legacy-seed" "legacy-read" "legacy-cleanup"} action)
              (legacy-request action)
              (resource-request action))))))

(cli/run! execute!)
