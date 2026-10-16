(ns knoxx.backend.extern.node-cli-test
  "CLI conversion and safe-error controls without a child or environment read."
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [knoxx.backend.extern.node-cli :as cli]
            [knoxx.backend.extern.translation-review-verification-fixture :as fixture]))

(deftest arguments-are-plain-ordered-strings
  (is (= ["legacy-read"] (cli/arguments ["legacy-read"])))
  (is (= [] (cli/arguments nil)))
  (is (try (cli/arguments ["legacy-read" (fixture/native-object)]) false
           (catch :default _ true))))

(deftest ^:async successful-result-is-one-json-line-and-does-not-set-failure
  (let [result {:action "materialization" :materialized false}
        capture (await (fixture/capture-cli! (constantly result)))]
    (is (= [result] (:stdout capture)))
    (is (empty? (:stderr capture)))
    (is (= 0 (:exit-code capture)))))

(deftest ^:async native-or-malformed-output-is-refused-before-serialization
  (let [capture (await (fixture/capture-cli! fixture/native-object))]
    (is (= 1 (:exit-code capture)))
    (is (empty? (:stdout capture)))
    (is (= 1 (count (:stderr capture))))))

(deftest ^:async failure-output-excludes-native-errors-settings-content-and-credentials
  (let [secret "private-user:private-password@private-host/raw-content"
        capture (await (fixture/capture-cli!
                        #(throw (ex-info secret {:code secret :settings {:mongo-uri secret}
                                                 :content secret :cause secret}))))
        output (str/join "\n" (:stderr capture))]
    (is (= 1 (:exit-code capture)))
    (is (empty? (:stdout capture)))
    (is (= 1 (count (:stderr capture))))
    (is (str/includes? output "translation_verification_failed"))
    (is (not (str/includes? output secret)))))

(deftest ^:async known-safe-first-cause-categories-remain-distinct
  (doseq [code [:verification-legacy-collision :verification-ownership-mismatch
                :verification-owned-row-scope-mismatch :translation-verification-contract-invalid
                :verification-setting-missing]]
    (let [capture (await (fixture/capture-cli! #(throw (ex-info "unsafe raw text" {:code code}))))
          output (first (:stderr capture))]
      (is (= 1 (:exit-code capture)))
      (is (str/includes? output (str "\"code\":\"" (name code) "\"")))
      (is (not (str/includes? output "unsafe raw text")))))
  (let [primary (ex-info "unsafe primary" {:code :verification-legacy-collision})
        combined (ex-info "unsafe cleanup" {:code :verification-primary-and-cleanup-failed
                                            :primary-thrown? true :primary-error-present? true
                                            :cleanup-failed? true} primary)
        capture (await (fixture/capture-cli! #(throw combined)))]
    (is (str/includes? (first (:stderr capture)) "verification-legacy-collision"))
    (is (str/includes? (first (:stderr capture)) "\"cleanup_failed\":true"))))

(deftest ^:async native-os-categories-are-allowlisted-and-secret-codes-are-redacted
  (doseq [[native-code expected] [["EEXIST" "native-file-exists"]
                                 ["ENOENT" "native-path-missing"]
                                 ["raw-secret-code" "unclassified"]]]
    (let [capture (await (fixture/capture-cli! #(throw (fixture/native-error native-code))))
          output (first (:stderr capture))]
      (is (str/includes? output expected))
      (is (not (str/includes? output "raw-secret-code")))
      (is (not (str/includes? output "do-not-print-native-error-message")))
      (is (= 1 (:exit-code capture))))))

(deftest ^:async nil-failure-is-still-a-failure-with-one-safe-diagnostic
  (let [capture (await (fixture/capture-cli! fixture/throw-nil!))]
    (is (= 1 (:exit-code capture)))
    (is (empty? (:stdout capture)))
    (is (= 1 (count (:stderr capture))))))

(deftest ^:async throwing-native-code-accessors-remain-observable-without-replacing-primary
  (doseq [null? [false true]]
    (let [primary (fixture/code-getter-failure null?)
          capture (await (fixture/capture-cli! #(throw primary)))
          text (first (:stderr capture))]
      (is (= 1 (:exit-code capture)))
      (is (empty? (:stdout capture)))
      (is (= 1 (count (:stderr capture))))
      (is (str/includes? text "\"native_code_unavailable\":true"))
      (is (str/includes? text "\"primary_error_present\":true"))
      (is (str/includes? text "\"code\":\"unclassified\""))
      (is (not (str/includes? text "do-not-print"))))
    (let [primary (fixture/code-getter-failure null?)
          combined (ex-info "do-not-print-cleanup-message"
                            {:code :verification-primary-and-cleanup-failed
                             :primary-thrown? true :primary-error-present? true
                             :cleanup-failed? true} primary)
          capture (await (fixture/capture-cli! #(throw combined)))
          text (first (:stderr capture))]
      (is (= 1 (:exit-code capture)))
      (is (= 1 (count (:stderr capture))))
      (is (str/includes? text "\"native_code_unavailable\":true"))
      (is (str/includes? text "verification-primary-and-cleanup-failed"))
      (is (str/includes? text "\"cleanup_failed\":true"))
      (is (not (str/includes? text "do-not-print"))))))
