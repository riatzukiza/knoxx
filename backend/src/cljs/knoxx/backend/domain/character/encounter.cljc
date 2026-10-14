(ns knoxx.backend.domain.character.encounter
  "Pure bounded encounter admission and canonical OpenPlanner event projection."
  (:require [knoxx.backend.law.character.encounter :as law]
            [knoxx.backend.shape.character.encounter :as shape]))

(defn normalize-item
  "Retain exact admitted text and source facts; never infer self-output from an author name."
  [digest owner source decision admitted-at item]
  (law/assert-item! item)
  (let [record (merge item
                      {:owner owner :source source
                       :source-id (law/source-id digest source (:external-id item))
                       :admitted-at admitted-at
                       :content-hash (digest (:text item))
                       :reactions (vec (:reactions item))
                       :media (vec (:media item))
                       :authorization decision})
        identified (assoc record :id (law/record-id digest record))]
    (law/assert-record! digest identified owner source)))

(defn record-event
  "Project admitted experience through the existing OpenPlanner event vocabulary."
  [stream-id record]
  (law/assert-shape! shape/Record record :record-event)
  (let [{:keys [owner source]} record]
    {:schema "openplanner.event.v1"
     :id (:id record)
     :ts (:admitted-at record)
     :source "knoxx"
     :kind "character.encounter"
     :source_ref {:project (:project owner)
                  :character (:character-id owner)
                  :external_id (:external-id record)
                  :account (:account-id source)
                  :scope (:scope-id source)}
     :text (:text record)
     :meta {:author (:author-id record)
            :role (if (:self-output? record) "assistant" "user")
            :tags ["character-encounter" (:kind source)]}
     :extra (assoc (shape/event-extra owner source stream-id)
                   :encounter (shape/record->wire record)
                   :content_hash (:content-hash record)
                   :encounter_source_id (:source-id record)
                   :provenance (if (:self-output? record) "self-output" "external"))}))

(defn checkpoint-event
  "Project confirmed cursor progress as a metadata event after its causal encounters."
  [checkpoint]
  (law/assert-shape! shape/Checkpoint checkpoint :checkpoint-event)
  {:schema "openplanner.event.v1"
   :id (:id checkpoint)
   :ts (:at checkpoint)
   :source "knoxx"
   :kind "character.encounter-cursor"
   :source_ref {:project (get-in checkpoint [:owner :project])
                :character (get-in checkpoint [:owner :character-id])}
   :text ""
   :meta {:tags ["character-encounter-cursor"]}
   :extra (assoc (shape/event-extra (:owner checkpoint) (:source checkpoint)
                                   (:stream-id checkpoint))
                 :encounter_cursor (shape/checkpoint->wire checkpoint)
                 :encounter_cursor_sequence (:sequence checkpoint))})

(defn- unique-records
  [records]
  (->> records
       (reduce (fn [{:keys [seen] :as result} record]
                 (if (contains? seen (:id record))
                   result
                   (-> result
                       (update :seen conj (:id record))
                       (update :records conj record))))
               {:seen #{} :records []})
       :records))

(defn- page-checkpoint
  [digest owner page records stream checkpoint]
  (let [ids (mapv :id records)
        page-id (law/page-id digest stream (:cursor-before page) (:cursor-after page) ids (:coverage page))]
    (if (= page-id (:page-id checkpoint))
      checkpoint
      (let [cursor (cond-> {:page-id page-id :stream-id stream :owner owner :source (:source page)
                           :sequence (inc (or (:sequence checkpoint) 0))
                           :previous-id (:id checkpoint)
                           :cursor-before (:cursor-before page) :cursor-after (:cursor-after page)
                           :event-ids ids :at (:observed-at page)}
                     (:coverage page) (assoc :coverage (:coverage page)))]
        (law/assert-cursor! checkpoint (:cursor-before page))
        (assoc cursor :id (law/checkpoint-id digest cursor))))))

(defn prepare-page
  "Validate authorization and cursor lineage, then prepare stable retry-safe envelopes.

  The source adapter supplies a complete bounded page and opaque before/after
  cursors. Authorization is an adapter result supplied separately from source
  content. Neither page order nor external id implies provider cursor semantics."
  [digest owner page decision checkpoint]
  (law/assert-shape! shape/Owner owner :owner)
  (law/assert-shape! shape/Page page :page)
  (law/assert-authorized! owner (:source page) decision)
  (law/assert-checkpoint! digest owner (:source page) checkpoint)
  (let [source (:source page)
        stream-id (law/stream-id digest owner source)
        records (unique-records
                 (mapv #(normalize-item digest owner source decision (:observed-at page) %)
                       (:items page)))
        cursor (page-checkpoint digest owner page records stream-id checkpoint)
        replay? (= (:id cursor) (:id checkpoint))]
    (law/assert-checkpoint! digest owner source cursor)
    {:owner owner :source source :stream-id stream-id :page-id (:page-id cursor)
     :records records :events (mapv #(record-event stream-id %) records)
     :checkpoint cursor :checkpoint-event (checkpoint-event cursor)
     :replay? replay?}))

(defn completion
  "Advance only after all encounter ids and the checkpoint are durably confirmed."
  [prepared confirmed-ids checkpoint-confirmed?]
  (let [required (set (map :id (:records prepared)))
        confirmed (set confirmed-ids)
        complete? (and checkpoint-confirmed? (every? confirmed required))]
    (cond-> {:status (if complete? (if (:replay? prepared) :replayed :admitted) :partial)
     :page-id (:page-id prepared)
     :stream-id (:stream-id prepared)
     :event-ids (filterv confirmed (mapv :id (:records prepared)))
     :causal-source-ids (->> (:records prepared)
                            (filter #(confirmed (:id %)))
                            (mapv :source-id))
     :cursor (get-in prepared [:checkpoint (if (or complete? (:replay? prepared))
                                            :cursor-after :cursor-before)])
             :checkpoint-id (when complete? (get-in prepared [:checkpoint :id]))}
      (get-in prepared [:checkpoint :coverage])
      (assoc :coverage (get-in prepared [:checkpoint :coverage])))))
