(ns knoxx.backend.extern.publication-receipt-sink-retry-test
  "Native fsync failure at the real configured reconciler emission boundary."
  (:require ["node:fs" :as native-fs]
            [clio.extern.js.fs :as fs]
            [cljs.test :refer [deftest is]]
            [knoxx.backend.extern.clio-store-fixture :as fixture]
            [knoxx.backend.extern.publication-receipt-store :as edge]
            [knoxx.backend.infra.publication-receipt-store :as store]
            [knoxx.backend.infra.publication-reconciler :as reconciler]
            [knoxx.backend.infra.publication-target-memory :as memory]
            [knoxx.backend.infra.publication-target-registry :as registry]))

(defn- inode [stat]
  {:device (.toString (.-dev stat)) :inode (.toString (.-ino stat))})

(defn- fsync-fault!
  "Refuse one real native sync on the owned ledger after its append occurred."
  [file]
  (let [expected (edge/file-identity file)
        original (.-fsyncSync native-fs)
        remaining (atom 1)
        observations (atom [])]
    (set! (.-fsyncSync native-fs)
          (fn [fd]
            (if (= expected (inode (native-fs/fstatSync fd #js {:bigint true})))
              (let [failed? (pos? @remaining)]
                (swap! observations conj {:fd fd :identity expected :failed? failed?})
                (if failed?
                  (do (swap! remaining dec)
                      (throw (doto (js/Error. "Owned receipt fixture fsync EIO after append")
                               (aset "code" "EIO"))))
                  (original fd)))
              (original fd))))
    {:observations observations :restore! #(set! (.-fsyncSync native-fs) original)}))

(defn- fixture-reconciler [sink]
  (let [bundle (memory/memory-target {:id :sink-retry/memory})
        target :sink-retry/memory
        resources {:documents {:sink-retry/source
                               {:document/id :sink-retry/source :document/title "Retry source"
                                :document/source-locale :en :document/source {:path "owned/retry.md"}}}
                   :gardens {:sink-retry/garden
                             {:garden/id :sink-retry/garden :garden/title "Retry garden"
                              :garden/status :active :garden/locales [:en]}}
                   :publications [{:publication/id :sink-retry/page :publication/document :sink-retry/source
                                   :publication/garden :sink-retry/garden :publication/target target
                                   :publication/locale :en :publication/revision :source/current
                                   :publication/state :published :publication/path "/owned-retry"
                                   :translation/review :none}]}]
    {:target bundle
     :reconciler (reconciler/make-reconciler
                  {:registry (registry/make-registry
                              [{:publication-target/id target :publication-target/kind :publication-target/memory
                                :publication-target/config {} :publication-target/enabled? true}]
                              {:publication-target/memory (constantly (:target bundle))})
                   :store (:store (memory/memory-store))
                   :load-index! (^:async fn [] resources)
                   :evidence-facts (fn [_] {:current-source-revision (constantly "actual-sink-retry-revision")
                                            :translated-revision? (constantly true) :approved? (constantly true)
                                            :source-revision-superseded? (constantly false)})
                   :artifact-source (^:async fn [_ revision] {:artifact/content "<p>actual published bytes</p>"
                                                             :artifact/media-type "text/html" :artifact/encoding "utf-8"
                                                             :artifact/locale :en :artifact/revision revision})
                   :locale-admissible? (constantly true)
                   :emit-receipt! sink})}))

(deftest ^:async configured-emission-retains-an-explicit-retry-without-repeating-the-publication-effect
  (let [directory (fixture/temp-directory!)
        scope {:org-id "owned-sink-retry" :project "owned-project"}
        delivered (atom [])
        sink (store/receipt-sink {:publication-receipt-ledger-directory directory} scope
                                 (^:async fn [receipt] (swap! delivered conj receipt) receipt))
        file (:file (edge/namespace-paths directory scope))
        {:keys [observations restore!]} (fsync-fault! file)
        {:keys [reconciler target]} (fixture-reconciler sink)
        trigger {:trigger/id :sink-retry/request :trigger/origin :manual :publication/id :sink-retry/page}]
    (try
      (let [failure (try (await (reconciler/reconcile! reconciler trigger)) nil
                         (catch :default error error))
            first-events (store/read-events! directory scope)
            fact (first first-events)
            emitted (first (store/read-receipts! directory scope))
            public-before (memory/public-routes target)
            pending (:publication-receipt/pending (ex-data failure))
            retry! (:publication-receipt/retry! (ex-data failure))]
        (is (some? failure) "an ambiguous fsync refuses emission success")
        (is (= 1 (count first-events)) "the actual native append happened before fsync EIO")
        (is (= :publication/materialized (:receipt/type emitted)))
        (is (= 1 (memory/materialization-count target)))
        (is (= 1 (count @observations)))
        (is (true? (:failed? (first @observations))))
        (is (= [] @delivered) "the prior callback cannot report an unacknowledged commit")
        ;; No missing symbol or new arity is invoked on the predecessor. The
        ;; sink must expose its failed attempt, not require another reconciliation
        ;; or an equal-body lookup. Retry obligations have no RED credit yet.
        (is (= {:scope scope :event fact} pending)
            "the failure retains the exact originally prepared canonical envelope")
        (is (fn? retry!) "the failed emission exposes an explicit retry operation")
        (when (fn? retry!)
          (is (= emitted (await (retry!))))
          (is (= [fact] (store/read-events! directory scope)))
          (is (= [emitted] @delivered))
          (is (= 1 (memory/materialization-count target)))
          (is (= public-before (memory/public-routes target)))
          (is (= 2 (count @observations)))
          (is (false? (:failed? (last @observations))))
          (is (= (:identity (first @observations)) (:identity (last @observations)))))
        (let [noop (await (reconciler/reconcile! reconciler trigger))
              again (await (reconciler/reconcile! reconciler trigger))
              events (store/read-events! directory scope)]
          (is (= :publication/noop (:receipt/type noop)))
          (is (= noop again) "a distinct lawful demand may emit the same noop payload")
          (is (= 3 (count events)))
          (is (= 3 (count (set (map :event/id events)))))
          (is (= fact (first events)))
          (is (= [emitted noop again] (store/read-receipts! directory scope)))
          (is (= 1 (memory/materialization-count target)))
          (is (= public-before (memory/public-routes target)))))
      (finally (restore!) (fs/remove-tree! directory)))))
