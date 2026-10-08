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

(defn- ^:async ensure-record!
  [ports digest prepared event]
  (let [{:keys [owner source]} prepared
        existing (await ((:find-event! ports) owner (:id event)))]
    (if existing
      (law/assert-existing-record!
       digest (shape/wire->record (get-in event [:extra :encounter]))
       (event-record! digest owner source existing))
      (do
        (await ((:append-event! ports) event))
        (if-let [stored (await ((:find-event! ports) owner (:id event)))]
          (law/assert-existing-record!
           digest (shape/wire->record (get-in event [:extra :encounter]))
           (event-record! digest owner source stored))
          (throw (ex-info "Encounter append did not become durably readable"
                          {:code :encounter/append-unconfirmed})))))))

(defn- ^:async confirm-checkpoint!
  [ports digest prepared]
  (let [event (:checkpoint-event prepared)
        expected (:checkpoint prepared)
        owner (:owner prepared)
        existing (await ((:find-event! ports) owner (:id event)))]
    (when-not existing
      (await ((:append-event! ports) event)))
    (if-let [stored (or existing (await ((:find-event! ports) owner (:id event))))]
      (let [checkpoint (shape/wire->checkpoint (get-in stored [:extra :encounter_cursor]))]
        (law/assert-checkpoint! digest owner (:source prepared) checkpoint)
        (when-not (and (= "character.encounter-cursor" (:kind stored))
                       (= (:id stored) (:id expected))
                       (= expected checkpoint))
          (throw (ex-info "Encounter cursor retry conflicts with durable progress"
                          {:code :encounter/checkpoint-conflict})))
        checkpoint)
      (throw (ex-info "Encounter cursor append did not become durably readable"
                      {:code :encounter/checkpoint-unconfirmed})))))

(defn- ^:async admit-exclusive!
  [ports digest owner page authorize!]
  ;; Resolve authority again inside the owned writer, before any content read
  ;; or append. The callback is wired by the host to its existing auth adapter.
  (let [decision (await (authorize! owner (:source page)))
        _ (law/assert-authorized! owner (:source page) decision)
        stream-id (law/stream-id digest owner (:source page))
        checkpoint (await ((:latest-checkpoint! ports) owner stream-id))
        prepared (encounter/prepare-page digest owner page decision checkpoint)
        confirmed* (atom [])]
    (try
      (doseq [event (:events prepared)]
        (await (ensure-record! ports digest prepared event))
        (swap! confirmed* conj (:id event)))
      (await (confirm-checkpoint! ports digest prepared))
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

(defn ^:async load-context!
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
                admitted (mapv #(event-record! digest owner source %) bounded)]
            (recur (next remaining) (into records admitted)
                   (assoc decisions stream-id decision)))
          (recur (next remaining) records decisions)))
      (context/assemble-context digest owner records decisions options))))
