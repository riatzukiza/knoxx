(ns knoxx.backend.infra.character.encounter-admission
  "Source-independent admission over injected existing event storage operations."
  (:require [knoxx.backend.domain.character.encounter :as encounter]
            [knoxx.backend.domain.character.encounter-context :as context]
            [knoxx.backend.law.character.encounter :as law]
            [knoxx.backend.shape.character.encounter :as shape]))

(defn- event-record!
  [digest owner source event]
  (when-not (= "character.encounter" (:kind event))
    (throw (ex-info "Existing event is not an encounter"
                    {:code :encounter/event-kind-conflict})))
  (let [record (shape/wire->record (get-in event [:extra :encounter]))]
    (law/assert-record! digest record owner source)
    (when-not (and (= (:id event) (:id record))
                   (= (:text event) (:text record)))
      (throw (ex-info "Encounter envelope differs from its retained source facts"
                      {:code :encounter/event-content-conflict})))
    record))

(defn- ^:async assert-current-authorized!
  [authorize! owner source]
  (law/assert-authorized! owner source (await (authorize! owner source))))

(defn- ^:async ensure-record!
  [ports digest prepared event authorize!]
  (let [{:keys [owner source]} prepared
        existing (await ((:find-event! ports) owner (:id event)))]
    (await (assert-current-authorized! authorize! owner source))
    (if existing
      (law/assert-existing-record!
       digest (shape/wire->record (get-in event [:extra :encounter]))
       (event-record! digest owner source existing))
      (do
        (await ((:append-event! ports) event))
        (await (assert-current-authorized! authorize! owner source))
        (let [stored (await ((:find-event! ports) owner (:id event)))]
          (await (assert-current-authorized! authorize! owner source))
          (if stored
            (law/assert-existing-record!
             digest (shape/wire->record (get-in event [:extra :encounter]))
             (event-record! digest owner source stored))
            (throw (ex-info "Encounter append did not become durably readable"
                            {:code :encounter/append-unconfirmed}))))))))

(defn- ^:async confirm-checkpoint!
  [ports digest prepared authorize!]
  (let [event (:checkpoint-event prepared)
        expected (:checkpoint prepared)
        owner (:owner prepared)
        source (:source prepared)
        _ (await (assert-current-authorized! authorize! owner source))
        existing (await ((:find-event! ports) owner (:id event)))]
    (await (assert-current-authorized! authorize! owner source))
    (when-not existing
      (await ((:append-event! ports) event))
      (await (assert-current-authorized! authorize! owner source)))
    (let [stored (or existing (await ((:find-event! ports) owner (:id event))))]
      (await (assert-current-authorized! authorize! owner source))
      (if stored
        (let [checkpoint (shape/wire->checkpoint (get-in stored [:extra :encounter_cursor]))]
          (law/assert-checkpoint! digest owner source checkpoint)
          (when-not (and (= "character.encounter-cursor" (:kind stored))
                         (= (:id stored) (:id expected))
                         (= expected checkpoint))
            (throw (ex-info "Encounter cursor retry conflicts with durable progress"
                            {:code :encounter/checkpoint-conflict})))
          checkpoint)
        (throw (ex-info "Encounter cursor append did not become durably readable"
                        {:code :encounter/checkpoint-unconfirmed}))))))

(defn- ^:async admit-exclusive!
  [ports digest owner page authorize!]
  ;; Resolve authority again inside the owned writer, before any content read
  ;; or append. The callback is wired by the host to its existing auth adapter.
  (let [_ (await (assert-current-authorized! authorize! owner (:source page)))
        stream-id (law/stream-id digest owner (:source page))
        checkpoint (await ((:latest-checkpoint! ports) owner stream-id))
        decision (await (assert-current-authorized! authorize! owner (:source page)))
        prepared (encounter/prepare-page digest owner page decision checkpoint)
        confirmed* (atom [])]
    (try
      (doseq [event (:events prepared)]
        (await (assert-current-authorized! authorize! owner (:source prepared)))
        (await (ensure-record! ports digest prepared event authorize!))
        (swap! confirmed* conj (:id event)))
      (await (confirm-checkpoint! ports digest prepared authorize!))
      (await (assert-current-authorized! authorize! owner (:source prepared)))
      (encounter/completion prepared @confirmed* true)
      (catch :default error
        ;; Partial writes remain durable evidence. No returned cursor advances;
        ;; retry point-reads the same ids before attempting the cursor event.
        (throw (ex-info "Encounter page remains at its last confirmed cursor"
                        {:code :encounter/page-incomplete
                         :progress (encounter/completion prepared @confirmed* false)}
                        error))))))

(defn ^:async admit-page!
  "Admit a normalized bounded page under one explicitly owned serialized writer.

  Ports must use the existing event store and provide exact read-after-write
  confirmation. No source fetching, credentials, model invocation or parallel
  state store is created here. Serialization must cover every writer sharing
  this stream; stable ids alone do not guarantee multi-process uniqueness."
  [ports digest owner page authorize!]
  (law/assert-ports! ports authorize! digest)
  (law/assert-shape! shape/Owner owner :owner)
  (law/assert-shape! shape/Page page :page)
  (await ((:with-exclusive! ports)
          #(admit-exclusive! ports digest owner page authorize!))))

(defn- ^:async refresh-decisions!
  [digest owner sources authorize! decisions]
  ;; Earlier sources can be revoked while later source reads are pending.
  ;; The host's final batch guard owns cross-source snapshot binding; this
  ;; generic callback layer does not claim an atomic grant reservation.
  (loop [remaining (seq (distinct sources)) current decisions]
    (if-let [source (first remaining)]
      (recur (next remaining)
             (assoc current (law/stream-id digest owner source)
                    (await (authorize! owner source))))
      current)))

(defn ^:async load-eligible!
  "Reload admitted experience from the existing store with fresh per-source authorization.

  Authorization denial omits that source without reading its content. The host
  supplies at most eight exact sources; each read is bounded to 48 rows. Cursor
  metadata is never recalled as experience."
  [ports digest owner sources authorize! options]
  (law/assert-ports! ports authorize! digest)
  (law/assert-shape! shape/Owner owner :context-owner)
  (law/assert-shape! [:vector {:max 8} shape/Source] sources :context-sources)
  (loop [remaining (seq (distinct sources))
         records []
         decisions {}]
    (if-let [source (first remaining)]
      (let [decision (await (authorize! owner source))]
        (if (law/authorized? owner source decision)
          (let [stream-id (law/stream-id digest owner source)
                events (await ((:recent-encounters! ports) owner stream-id 48))
                bounded (law/assert-shape! [:vector {:max 48} [:map {:closed false}]]
                                           events :recent-event-result)
                current (await (authorize! owner source))
                admitted (when (law/authorized? owner source current)
                           (mapv #(event-record! digest owner source %) bounded))]
            (recur (next remaining) (into records admitted)
                   (assoc decisions stream-id current)))
          (recur (next remaining) records decisions)))
      (let [current (await (refresh-decisions! digest owner sources authorize! decisions))]
        {:records (context/eligible-records digest owner records current
                                            (merge context/default-options options))
         :decisions current}))))

(defn ^:async load-context!
  "Reauthorize sources around event reads, then apply the final prompt budget."
  [ports digest owner sources authorize! options]
  (let [{:keys [records decisions]} (await (load-eligible! ports digest owner sources authorize! options))]
    (context/assemble-context digest owner records decisions options)))
