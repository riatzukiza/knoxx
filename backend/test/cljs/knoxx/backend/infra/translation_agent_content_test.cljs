(ns knoxx.backend.infra.translation-agent-content-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [knoxx.backend.domain.error-observatory :as observatory]
            [knoxx.backend.domain.node.fs :as fs]
            [knoxx.backend.extern.memory-sessions-fixture :as native]
            [knoxx.backend.infra.translation-agent-content :as content]
            [knoxx.backend.law.translation-dispatch :as dispatch-law]))

(defn- record
  []
  (dispatch-law/dispatch-record
   {:document :open-hax.documents/start-here
    :locale :de
    :revision "sha256-source"
    :replace-stale? false}
   {:dispatch/garden "open-hax.gardens/promethean"
    :dispatch/document-wire-id "open-hax.documents/start-here"
    :dispatch/source-locale :en
    :dispatch/org-id "open-hax"
    :dispatch/project "promethean"
    :dispatch/source-digest "sha256-source"}
   :dispatch/accepted
   "2026-08-30T12:00:00.000Z"
   :attempt-id "content-attempt-1"
   :batch-id "content-run-1"))

(deftest ^:async immutable-content-is-installed-from-a-complete-temp-file
  (let [root (str "/tmp/knoxx-translation-content-" (.randomUUID js/crypto))
        output-revision (dispatch-law/output-revision (record))
        final-path (content/entry-path root output-revision)
        orphan-path (str final-path ".tmp-interrupted-writer")
        expected "Vollständige Übersetzung.\n"]
    (fs/mkdir-sync! (content/store-dir root))
    ;; A killed writer may leave an arbitrary sibling temp file. It must not
    ;; claim or corrupt the immutable final path on the next attempt.
    (fs/write-file-sync! orphan-path "partial")

    (let [first-value (await (content/write! root (record) output-revision expected))
          retry-value (await (content/write! root (record) output-revision expected))]
      (testing "complete bytes are installed and equal retries reuse them"
        (is (= first-value retry-value))
        (is (= expected (:translation/content first-value)))
        (is (= (pr-str first-value) (fs/read-file-sync final-path))))

      (testing "changed bytes cannot replace the installed inode"
        (let [error (try
                      (await (content/write! root (record) output-revision
                                             "Andere Bytes.\n"))
                      nil
                      (catch :default err err))]
          (is (some? error))
          (is (= (pr-str first-value) (fs/read-file-sync final-path)))))

      (testing "successful installs clean their own random temp link"
        (is (= #{(fs/join (content/store-dir root)
                           (last (str/split final-path #"/")))
                 orphan-path}
               (into #{}
                     (map #(fs/join (content/store-dir root) %))
                     (fs/readdir-sync (content/store-dir root)))))))))

(defn- reader-state
  [dispatch text]
  (let [revision (dispatch-law/output-revision dispatch)
        value (content/entry dispatch revision text)]
    {:entry value :text text
     :receipt (dispatch-law/translation-receipt
               dispatch revision "2026-08-30T12:01:00.000Z"
               (:translation/content-digest value))}))

(defn- ^:async read-raw!
  [raw receipt]
  (with-redefs [fs/read-file-or-nil! (fn [_] (native/resolved raw))]
    (await (content/content-for-receipt! "/owned-inert-content-reader" receipt))))

(deftest ^:async reader-admits-only-the-exact-receipt-coordinates
  (let [{:keys [entry receipt text]} (reader-state (record) "Exact bytes.\n")
        raw (pr-str entry)
        mismatches [[:translation/document :other.documents/page]
                    [:translation/garden :other.gardens/site]
                    [:translation/locale :fr]
                    [:translation/source-locale :fr]
                    [:translation/source-revision "other-source"]
                    [:translation/revision "other-output"]
                    [:translation/content-digest "other-digest"]
                    [:translation/org-id "other-org"]
                    [:translation/project "other-project"]]]
    (is (= text (await (read-raw! raw receipt))))
    (doseq [[coordinate mismatch] mismatches]
      (is (nil? (await (read-raw! raw (assoc receipt coordinate mismatch))))
          (str "wrong " coordinate " cannot borrow another receipt's bytes")))))

(deftest ^:async reader-preserves-project-equivalence-and-byte-authentication
  (let [{:keys [entry receipt text]}
        (reader-state (dissoc (record) :dispatch/project) "Projectless bytes.\n")]
    (is (not (contains? entry :translation/project)))
    (is (contains? receipt :translation/project))
    (is (nil? (:translation/project receipt)))
    (is (= text (await (read-raw! (pr-str entry) receipt))))
    (is (nil? (await (read-raw! (pr-str (assoc entry :translation/content
                                               "Changed behind the digest."))
                                  receipt)))))
  (doseq [text ["" "   "]]
    (let [{:keys [entry receipt]} (reader-state (record) text)]
      (is (nil? (await (read-raw! (pr-str entry) receipt)))))))

(deftest ^:async ordinary-absence-and-valid-reads-do-not-observe-decode-errors
  (let [{:keys [entry receipt text]} (reader-state (record) "Reader bytes.\n")
        reads (atom 0)
        observations (atom [])]
    (with-redefs [observatory/log-warning! (fn [& args] (swap! observations conj args))
                  fs/read-file-or-nil! (fn [_] (swap! reads inc) (native/resolved nil))]
      (is (nil? (await (content/content-for-receipt! nil receipt))))
      (is (nil? (await (content/content-for-receipt! "/unused" nil))))
      (is (zero? @reads))
      (is (nil? (await (content/content-for-receipt! "/unused" receipt))))
      (is (= 1 @reads))
      (doseq [raw [nil "42" "[]" "nil" (pr-str (assoc entry :translation/revision ""))]]
        (is (nil? (await (read-raw! raw receipt)))))
      (is (= text (await (read-raw! (pr-str entry) receipt))))
      (is (empty? @observations)))))

(deftest ^:async filesystem-failures-still-escape-with-the-original-identity
  (let [{:keys [receipt]} (reader-state (record) "Unread bytes.")
        primary (ex-info "Owned filesystem read failure" {:code "EACCES"})]
    (doseq [[fail! expected] [[(fn [_] (throw primary)) primary]
                              [(fn [_] (native/throw-nil!)) nil]]]
      (with-redefs [fs/read-file-or-nil! fail!]
        (let [outcome (try
                        {:returned (await (content/content-for-receipt! "/unread" receipt))}
                        (catch :default error {:thrown? true :error error}))]
          (is (true? (:thrown? outcome)))
          (is (identical? expected (:error outcome))))))))

(defn- ^:async observed-malformed-read!
  [receipt decode! observer-fail!]
  (let [observations (atom [])]
    (with-redefs [edn/read-string decode!
                  fs/read-file-or-nil! (fn [_] (native/resolved "{malformed private text"))
                  observatory/log-warning!
                  (fn [boundary context]
                    (swap! observations conj [boundary context])
                    (when observer-fail! (observer-fail!)))]
      {:value (await (content/content-for-receipt! "/private-content-path" receipt))
       :observations @observations})))

(deftest ^:async malformed-entry-observation-preserves-the-nil-fallback
  (let [{:keys [receipt]} (reader-state (record) "Attested text.")
        actual-decode edn/read-string
        primary (ex-info "Owned private malformed entry" {:private "not logged"})
        observer (ex-info "Owned private observer failure" {:private "not logged"})
        decoders [actual-decode
                  (fn [_] (throw primary))
                  (fn [_] (native/throw-nil!))]
        observer-failures [nil (fn [] (throw observer)) native/throw-nil!]]
    (doseq [decode! decoders observer-fail! observer-failures]
      (let [outcome (await (observed-malformed-read! receipt decode! observer-fail!))]
        (is (nil? (:value outcome)) "malformed content remains absent even when observation fails")
        (is (= [["translation-agent-content.decode"
                 {:phase :stored-entry :reason :malformed-edn}]]
               (:observations outcome))
            "exactly one fixed observation contains no raw error, content, path or identity")))))
