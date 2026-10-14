(ns knoxx.backend.infra.character.encounter-openplanner
  "Encounter ports over the existing embedded OpenPlanner event collection.

  This adapter creates no new storage authority. One constructed port owns a
  process-local serialized writer. Share it for all character streams in that
  process; multiple processes require OpenPlanner-owned unique/atomic append
  semantics before a global exactly-once claim is possible."
  (:require [knoxx.backend.extern.row-extra :as row-extra]
            [knoxx.backend.infra.clients.openplanner :as openplanner]
            [knoxx.backend.shape.character.encounter :as shape]))

(defn- owner-filter
  [owner]
  {:extra.org_id (:org-id owner)
   :extra.project (:project owner)
   :extra.character_id (:character-id owner)})

(defn- decode-event
  [row]
  (assoc row :extra (row-extra/parse-row-extra (:extra row))))

(defn- ^:async query-events!
  [client payload]
  (let [result (await (openplanner/mongo-query! client
                                               (assoc payload :collection "events")))]
    (when-not (and (not (false? (:ok result))) (vector? (:rows result))
                   (<= (count (:rows result)) (:limit payload)))
      (throw (ex-info "OpenPlanner encounter read did not return event rows"
                      {:code :encounter/store-read-invalid})))
    (mapv decode-event (:rows result))))

(defn- ^:async find-event!
  [client owner event-id]
  (let [rows (await (query-events! client
                                  {:filter (assoc (owner-filter owner) :id event-id)
                                   :limit 2}))]
    (when (> (count rows) 1)
      (throw (ex-info "Encounter event identity has duplicate durable rows"
                      {:code :encounter/duplicate-durable-id})))
    (first rows)))

(defn- ^:async latest-checkpoint!
  [client owner stream-id]
  (let [rows (await (query-events!
                    client
                    {:filter (assoc (owner-filter owner)
                                    :kind "character.encounter-cursor"
                                    :extra.encounter_stream_id stream-id)
                     :sort {:extra.encounter_cursor_sequence -1}
                     :limit 2}))
        checkpoints (mapv #(let [checkpoint (shape/wire->checkpoint
                                            (get-in % [:extra :encounter_cursor]))]
                             (when-not (= (:id %) (:id checkpoint))
                               (throw (ex-info "Encounter cursor row differs from its envelope"
                                               {:code :encounter/checkpoint-conflict})))
                             checkpoint) rows)]
    (when (and (= 2 (count checkpoints))
               (= (:sequence (first checkpoints)) (:sequence (second checkpoints))))
      (throw (ex-info "Encounter cursor has concurrent or duplicate progress events"
                      {:code :encounter/cursor-writer-conflict})))
    (first checkpoints)))

(defn- ^:async recent-encounters!
  [client owner stream-id limit]
  (await (query-events!
          client
          {:filter (assoc (owner-filter owner)
                          :kind "character.encounter"
                          :extra.encounter_stream_id stream-id)
           :sort {:ts -1 :id -1}
           :limit limit})))

(defn- ^:async after-previous!
  [previous task!]
  (when previous
    (try
      (await previous)
      ;; knoxx-lint/allow-silent-catch — failed admission must not poison the serialized writer.
      (catch :default _
        nil)))
  (await (task!)))

(defn openplanner-ports
  "Create one owned writer over the current same-store embedded event adapter.

  Requiring the existing projection-repair capability rejects the REST/direct
  store mixture already rejected by translation admission. Durable row reads,
  rather than vector-search timing, confirm retained experience and progress."
  [client]
  (openplanner/assert-event-projection-repair-supported! client)
  (let [tail* (atom nil)]
    {:find-event! #(find-event! client %1 %2)
     :append-event! #(openplanner/events! client [%])
     :latest-checkpoint! #(latest-checkpoint! client %1 %2)
     :recent-encounters! #(recent-encounters! client %1 %2 %3)
     :with-exclusive! (fn [task!]
                        (let [task (after-previous! @tail* task!)]
                          (reset! tail* task)
                          task))}))
