(ns knoxx.backend.infra.publication-receipt-store
  "Every emitted publication observation persisted through canonical Clio.
   Fresh reads never construct a publisher, translate, or provision storage."
  (:require [clio.extern.js.fs :as fs]
            [clio.infra.event :as event]
            [clio.infra.ledger :as ledger]
            [clio.infra.runtime :as runtime]
            [clio.infra.schema-store :as schema-store]
            [clio.shape.edn :as edn]
            [knoxx.backend.extern.publication-receipt-store :as edge]
            [knoxx.backend.law.publication-receipt-store :as law]
            [knoxx.backend.law.publication-receipts :as receipts]
            [knoxx.backend.law.publication-reconciler :as correlation]))

(def stream
  "The owning Clio stream within each admitted tenant/project partition."
  "knoxx/publication-receipts")

(defn selected?
  "An explicitly blank selection is configured and must refuse, not fall back."
  [config]
  (some? (:publication-receipt-ledger-directory config)))

(defn- decode-observation
  "Reuse Clio's EDN decoder and the existing receipt/correlation contracts."
  [scope fact]
  (let [{:receipt/keys [edn sha256] observed-scope :receipt/scope}
        (law/assert-observation! (:event/data fact))
        receipt (edn/read-one edn)]
    (when-not (and (= scope observed-scope)
                   (= stream (:event/stream fact))
                   (= :knoxx.publication/receipt-observed (:event/type fact))
                   (= sha256 (edge/sha256-hex edn)))
      (throw (ex-info "Publication receipt history has foreign or changed provenance"
                      {:cause :publication-receipt/history-conflict})))
    (receipts/assert-receipt! receipt)
    (correlation/assert-correlation!
     (select-keys receipt [:correlation/trigger :correlation/origin
                           :correlation/publication :correlation/revision]))
    receipt))

(defn read-events!
  "Read existing canonical envelopes without provisioning an unknown scope.
   A missing root or incomplete known partition is an unavailable store."
  [directory scope]
  (let [{:keys [directory file schemas]} (edge/namespace-paths directory scope)]
    (if-not (fs/exists? directory) []
      (do
        (when-not (and (fs/exists? file) (fs/exists? schemas))
          (throw (ex-info "Configured publication receipt partition is incomplete"
                          {:cause :publication-receipt/missing-partition})))
        (edge/file-identity file)
        (let [revisions (schema-store/load-revisions schemas)]
          (when-not (seq revisions)
            (throw (ex-info "Publication receipt schema history is missing"
                            {:cause :publication-receipt/missing-schemas})))
          (let [events (:canonical/events (ledger/canonicalize-files revisions [file]))]
            (doseq [fact events] (decode-observation scope fact))
            events))))))

(defn read-receipts!
  "Project all admitted observations in order, including equal-looking noops."
  [directory scope]
  (mapv #(decode-observation scope %) (read-events! directory scope)))

(defn open-writer!
  "Explicitly provision a new owned partition and validate its existing history."
  [directory scope]
  (let [{:keys [directory file schemas] :as paths} (edge/namespace-paths directory scope)
        known? (fs/exists? directory)]
    (when (and known? (not (and (fs/exists? file) (fs/exists? schemas))))
      (throw (ex-info "Existing publication receipt partition is incomplete"
                      {:cause :publication-receipt/missing-partition})))
    (when-not known?
      (fs/ensure-dir! directory)
      (ledger/create-ledger! file))
    (let [writer (assoc paths :scope scope :root-selection (:root paths)
                        :file-identity (edge/file-identity file)
                        :runtime (runtime/open schemas law/catalog))]
      (read-events! (:root-selection writer) scope)
      writer)))

(defn prepare-event!
  "Prepare one immutable envelope once; retry retains its complete value."
  [{:keys [scope runtime root-selection file file-identity]} receipt]
  (edge/assert-file-identity! file file-identity)
  (receipts/assert-receipt! receipt)
  (correlation/assert-correlation!
     (select-keys receipt [:correlation/trigger :correlation/origin
                           :correlation/publication :correlation/revision]))
  (let [encoded (pr-str receipt)
        history (read-events! root-selection scope)
        previous (last history)]
    (when-not (= receipt (edn/read-one encoded))
      (throw (ex-info "Publication receipt is not lossless portable EDN"
                      {:cause :publication-receipt/invalid-payload})))
    (event/make-event
     (:schema/current runtime) :knoxx.publication/receipt-observed
     {:event/stream stream :event/seq (inc (or (:event/seq previous) 0))
      :event/causes (if previous [(:event/id previous)] [])
      :event/actor "knoxx/publication-reconciler"
      :event/subject (str (:correlation/publication receipt))
      :event/data {:receipt/scope scope :receipt/edn encoded
                   :receipt/sha256 (edge/sha256-hex encoded)}})))

(defn append-prepared!
  "Use Clio admission unchanged; exact replay requires a new locked fsync ACK."
  [{:keys [scope runtime root-selection file file-identity]} fact]
  (decode-observation scope fact)
  (edge/assert-file-identity! file file-identity)
  (let [result (ledger/append-event! (:schema/revisions (runtime/refresh runtime)) file fact)]
    (when (= :already-present result) (edge/sync-existing! file file-identity))
    (edge/assert-file-identity! file file-identity)
    (when-not (some #(= fact %) (read-events! root-selection scope))
      (throw (ex-info "Acknowledged publication receipt has no exact canonical readback"
                      {:cause :publication-receipt/ack-readback-conflict})))
    result))

(defn receipt-sink
  "Persist one immutable envelope per emission; retain an explicit failed attempt.
   Retry never reconciles or compares receipt bodies. The prior callback runs
   only after ACK and at most once for this attempt; its failure stays distinct."
  [config scope prior-sink]
  (if-not (selected? config) prior-sink
    (do
      (when-not (fn? prior-sink)
        (throw (ex-info "Publication receipt callback must be callable"
                        {:cause :publication-receipt/invalid-sink})))
      (let [writer (open-writer! (:publication-receipt-ledger-directory config) scope)]
        (^:async fn [receipt]
          (let [pending (law/assert-pending-attempt!
                         {:scope scope :event (prepare-event! writer receipt)})
                delivery (delay ((^:async fn [] (await (prior-sink receipt)))))
                attempt! (fn ^:async retry-emission! []
                           (try
                             (append-prepared! writer (:event pending))
                             (catch :default cause
                               (throw
                                (ex-info "Publication receipt commit was not acknowledged"
                                         (law/assert-failed-attempt!
                                          {:cause :publication-receipt/commit-unacknowledged
                                           :publication-receipt/pending pending
                                           :publication-receipt/retry! retry-emission!})
                                         cause))))
                           ;; Callback errors are outside the commit catch. A
                           ;; retained retry cannot repeat callback side effects.
                           (await (force delivery)))]
            (await (attempt!))))))))
