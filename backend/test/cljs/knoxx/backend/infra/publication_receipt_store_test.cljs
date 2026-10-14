(ns knoxx.backend.infra.publication-receipt-store-test
  "Real canonical ledger controls for the publication-specific receipt adapter."
  (:require [clio.extern.js.fs :as fs]
            [cljs.test :refer [deftest is]]
            [knoxx.backend.extern.clio-store-fixture :as fixture]
            [knoxx.backend.extern.publication-receipt-store :as edge]
            [knoxx.backend.infra.publication-receipt-store :as store]))

(def scope {:org-id "receipt-store-test" :project "owned-project"})
(def receipt
  {:receipt/type :publication/noop :reason :unchanged
   :correlation/trigger :publication.reconcile/http-request
   :correlation/origin :route :correlation/publication :receipt-store-test/home
   :correlation/revision "actual-concrete-revision"
   :fixture/identity #uuid "09528b4b-c793-4e21-b1d7-72dba9ee8db6"
   :fixture/observed-at #inst "2026-10-14T00:00:00.000-00:00"})

(deftest exact-payload-and-prepared-retry-retain-every-immutable-field
  (let [directory (fixture/temp-directory!)]
    (try
      (let [writer (store/open-writer! directory scope)
            fact (store/prepare-event! writer receipt)]
        (is (= 64 (count (edge/sha256-hex "Unicode 🦄\n"))))
        (is (= :appended (store/append-prepared! writer fact)))
        (let [bytes (fs/read-text (:file writer))
              same-inode (:file-identity writer)
              original-sync edge/sync-existing!
              syncs (atom [])]
          (with-redefs [edge/sync-existing! (fn [file inode]
                                             (swap! syncs conj [file inode])
                                             (original-sync file inode))]
            (is (= :already-present (store/append-prepared! writer fact))))
          (is (= [[(:file writer) same-inode]] @syncs))
          (is (= bytes (fs/read-text (:file writer))))
          (is (= [fact] (store/read-events! directory scope)))
          (is (= [receipt] (store/read-receipts! directory scope)))
          (is (= [receipt] (store/read-receipts! directory scope))
              "each independent reader decodes exact retained EDN, UUID and instant")
          (with-redefs [edge/sync-existing! (fn [_ _] (throw (ex-info "Owned sync refusal" {})))]
            (is (thrown? js/Error (store/append-prepared! writer fact))))
          (is (= bytes (fs/read-text (:file writer))) "an unacknowledged retry appends nothing")))
      (finally (fs/remove-tree! directory)))))

(deftest equal-looking-observations-are-distinct-facts-and-scope-is-exact
  (let [directory (fixture/temp-directory!)]
    (try
      (let [writer (store/open-writer! directory scope)]
        (dotimes [_ 206]
          (store/append-prepared! writer (store/prepare-event! writer receipt)))
        (let [events (store/read-events! directory scope)
              foreign (assoc scope :org-id "another-org")
              other-project (assoc scope :project "another-project")]
          (is (= 206 (count events)))
          (is (= 206 (count (set (map :event/id events)))))
          (is (= (vec (range 1 207)) (mapv :event/seq events)))
          (is (= (vec (repeat 206 receipt)) (store/read-receipts! directory scope)))
          (doseq [unknown [foreign other-project]]
            (is (= [] (store/read-receipts! directory unknown)))
            (is (not (fs/exists? (:directory (edge/namespace-paths directory unknown)))))
            (is (not= (:directory writer) (:directory (edge/namespace-paths directory unknown)))))
          (is (not= (:directory (edge/namespace-paths directory {:org-id "a/b" :project "c"}))
                    (:directory (edge/namespace-paths directory {:org-id "a" :project "b/c"}))))))
      (finally (fs/remove-tree! directory)))))

(deftest unavailable-corrupt-and-replaced-store-refuse-without-volatile-fallback
  (let [directory (fixture/temp-directory!)]
    (try
      (doseq [bad [nil "" "  " "relative/path"]]
        (is (thrown? js/Error (store/open-writer! bad scope))))
      (doseq [bad [nil {} (assoc scope :org-id "  ") (assoc scope :project "")]]
        (is (thrown? js/Error (store/open-writer! directory bad))))
      (let [writer (store/open-writer! directory scope)
            fact (store/prepare-event! writer receipt)
            file (:file writer)]
        (store/append-prepared! writer fact)
        (let [original (fs/read-text file)]
          (doseq [changed [(assoc-in fact [:event/data :receipt/scope :org-id] "foreign")
                           (assoc-in fact [:event/data :receipt/edn] (pr-str (assoc receipt :reason :forged)))
                           (assoc fact :event/stream "foreign-stream")]]
            (fs/write-text! file (str (pr-str changed) "\n"))
            (is (thrown? js/Error (store/read-receipts! directory scope))))
          (fs/write-text! file original)
          (let [replacement (str file ".replacement")]
            (fs/write-text! replacement original)
            (fs/rename! replacement file)
            (is (thrown? js/Error (store/append-prepared! writer fact)))
            (is (= original (fs/read-text file)))
            (is (= [receipt] (store/read-receipts! directory scope)))))
        (fs/delete-if-exists! file)
        (is (thrown? js/Error (store/read-receipts! directory scope)))
        (is (thrown? js/Error (store/open-writer! directory scope)))
        (is (not (fs/exists? file))))
      (finally (fs/remove-tree! directory)))))

(deftest ^:async callback-compatibility-remains-explicit
  (let [prior (^:async fn [value] value)
        directory (fixture/temp-directory!)]
    (try
      (is (identical? prior (store/receipt-sink {} scope prior)))
      (is (identical? prior (store/receipt-sink {:publication-receipt-ledger-directory nil} scope prior)))
      (is (thrown? js/Error (store/receipt-sink {:publication-receipt-ledger-directory ""} scope prior)))
      (is (thrown? js/Error (store/receipt-sink {:publication-receipt-ledger-directory directory} scope nil)))
      (let [config {:publication-receipt-ledger-directory directory}
            delivered (atom [])
            sink (store/receipt-sink config scope (^:async fn [value] (swap! delivered conj value) value))]
        (is (= receipt (await (sink receipt))))
        (is (= [receipt] @delivered))
        (let [refusal (store/receipt-sink config scope (^:async fn [_] (throw (ex-info "Custom sink failed" {}))))]
          (is (= :refused (try (await (refusal receipt)) :accepted (catch :default _ :refused))))
          (is (= [receipt receipt] (store/read-receipts! directory scope))
              "durable facts persist, while a custom callback failure still refuses route success")))
      (finally (fs/remove-tree! directory)))))
