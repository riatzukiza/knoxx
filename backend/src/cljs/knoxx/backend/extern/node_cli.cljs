(ns knoxx.backend.extern.node-cli
  "Named argument, JSON-output and safe-failure boundary for small Node CLIs.

   Raw Error messages, stacks and data never become diagnostics. A caller may
   pass an opaque thrown value here, but receives only a bounded CLJS outcome."
  (:refer-clojure :exclude [run!])
  (:require [knoxx.backend.law.translation-review-verification-fixture :as law]))

(defn arguments
  "Admit command arguments as ordered CLJS strings, including an absent list."
  [values]
  (let [result (vec (or values []))]
    (law/assert-valid! ::law/arguments result)))

(def ^:private safe-codes
  #{:translation-verification-contract-invalid :translation-verification-contract-unknown
    :translation-verification-result-action-mismatch :translation-verification-result-scope-mismatch
    :translation-verification-artifact-path-invalid :translation-verification-resource-action-invalid
    :verification-setting-missing :verification-resource-invalid :verification-locale-invalid
    :verification-action-invalid :verification-ownership-file-invalid :verification-ownership-mismatch
    :verification-owned-row-ambiguous :verification-owned-row-scope-mismatch
    :verification-owned-row-missing :verification-owned-relation-ambiguous
    :verification-legacy-collision :verification-legacy-insert-uncertain
    :verification-owned-delete-uncertain :verification-dispatch-binding-mismatch
    :verification-legacy-segment-invalid :verification-legacy-normalized-scope-mismatch
    :verification-primary-and-cleanup-failed :verification-cleanup-failed
    :verification-resource-receipt-invalid})

(def ^:private native-codes
  {"EEXIST" "native-file-exists" "ENOENT" "native-path-missing"
   "EACCES" "native-access-refused" "EPERM" "native-operation-refused" "EIO" "native-io-failed"})

(defn- native-code-outcome [error]
  (try {:code (when (some? error) (aget error "code"))}
       (catch :default probe-error
         {:failure {:error-present? (some? probe-error)}})))

(defn- safe-code [error]
  (let [code (:code (ex-data error))
        native (native-code-outcome error)]
    {:code (or (when (contains? safe-codes code) (name code))
               (get native-codes (:code native)))
     :native-code-unavailable? (contains? native :failure)}))

(defn- safe-failure
  "Select only known contract coordinates and safe flags from an opaque error."
  [error]
  (let [data (ex-data error)
        contract (:contract data)
        fields (:fields data)
        primary-code (when (:primary-thrown? data) (safe-code (ex-cause error)))
        code (safe-code error)]
    (cond-> {:error "translation_verification_failed"
             :code (or (:code primary-code) (:code code) "unclassified")
             :primary_thrown true
             :primary_error_present (some? error)}
      (or (:native-code-unavailable? primary-code) (:native-code-unavailable? code))
      (assoc :native_code_unavailable true)
      (contains? law/registry contract) (assoc :contract (str contract))
      (and (vector? fields)
           (every? #(and (vector? %) (every? (fn [field] (or (keyword? field) (integer? field))) %)) fields))
      (assoc :fields (law/safe-field-paths fields))
      (true? (:cleanup-failed? data)) (assoc :cleanup_failed true)
      (true? (:primary-thrown? data))
      (assoc :primary_error_present (true? (:primary-error-present? data))))))

(defn ^:async run!
  "Emit one JSON result or one safe diagnostic, preserving a failing exit status.

   The operation owns request/result contracts. This extern owns serialization
   and never renders the supplied error, its message, stack or arbitrary data."
  [operation]
  (try
    (let [result (law/assert-valid! ::law/result (await (operation)))]
      (js/console.log (js/JSON.stringify (clj->js result)))
      {:failed? false})
    (catch :default error
      (js/console.error (js/JSON.stringify (clj->js (safe-failure error))))
      (set! (.-exitCode js/process) 1)
      {:failed? true})))
