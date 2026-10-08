(ns knoxx.backend.law.character.encounter
  "Encounter admissibility, ownership, retry identity and cursor contracts."
  (:require [clojure.string :as str]
            [knoxx.backend.shape.character.encounter :as shape]
            [malli.core :as m]))

(defn assert-shape!
  "Fail closed at a named encounter boundary without echoing private input."
  [schema value boundary]
  (when-not (m/validate schema value)
    (throw (ex-info "Invalid encounter boundary data"
                    {:code :encounter/invalid-shape :boundary boundary})))
  value)

(defn authorized?
  "Require fresh adapter evidence bound to every ownership and source coordinate."
  [owner source decision]
  (and (m/validate shape/Authorization decision)
       (= owner (:owner decision))
       (= source (:source decision))))

(defn- assert-content-evidence!
  [item]
  (when-not (or (not (str/blank? (:text item)))
                (seq (:reactions item)) (seq (:media item)))
    (throw (ex-info "Encounter has no text or source reference evidence"
                    {:code :encounter/empty-content})))
  item)

(defn assert-item!
  "Admit text, reactions or reference-only media while rejecting an empty observation."
  [item]
  (assert-shape! shape/Item item :item)
  (assert-content-evidence! item))

(defn assert-authorized!
  "Reject absent, denied or mismatched evidence before content is persisted."
  [owner source decision]
  (when-not (authorized? owner source decision)
    (throw (ex-info "Encounter source is outside the authorized scope"
                    {:code :encounter/source-denied})))
  decision)

(defn stream-id
  "Bind a durable cursor stream to one owner and exact authorized source."
  [digest owner source]
  (str "encounter-stream:"
       (digest (pr-str [(shape/owner-binding owner)
                        (shape/source-binding source)]))))

(defn source-id
  "Identify the external source item independently from its content revisions."
  [digest source external-id]
  (str "encounter-source:"
       (digest (pr-str [(shape/source-binding source) external-id]))))

(defn record-id
  "Identify an immutable source revision, including self-output provenance."
  [digest record]
  (str "encounter:"
       (digest (pr-str (shape/record-binding record)))))

(defn page-id
  "Identify an opaque bounded page without assuming provider cursor ordering."
  ([digest stream cursor-before cursor-after event-ids]
   (page-id digest stream cursor-before cursor-after event-ids nil))
  ([digest stream cursor-before cursor-after event-ids coverage]
   (str "encounter-page:"
        (digest (pr-str (cond-> [stream cursor-before cursor-after event-ids]
                          coverage (conj (shape/coverage-binding coverage))))))))

(defn checkpoint-id
  "Identify one cursor commit, even when an adapter legitimately reuses a page cursor."
  [digest checkpoint]
  (str "encounter-cursor:"
       (digest (pr-str (mapv checkpoint [:stream-id :page-id :sequence :previous-id])))))

(defn assert-record!
  "Verify schema, content digest, source identity and retained admission evidence."
  [digest record owner source]
  (assert-shape! shape/Record record :record)
  (assert-content-evidence! record)
  (assert-authorized! owner source (:authorization record))
  (when-not (and (= owner (:owner record))
                 (= source (:source record))
                 (= (:content-hash record) (digest (:text record)))
                 (= (:source-id record) (source-id digest source (:external-id record)))
                 (= (:id record) (record-id digest record)))
    (throw (ex-info "Encounter record does not match its immutable source facts"
                    {:code :encounter/record-conflict})))
  record)

(defn assert-existing-record!
  "An existing stable id must carry the same immutable content and source facts."
  [digest expected existing]
  (assert-record! digest existing (:owner expected) (:source expected))
  (when-not (= (shape/record-binding expected) (shape/record-binding existing))
    (throw (ex-info "Encounter retry conflicts with an existing event"
                    {:code :encounter/retry-conflict})))
  existing)

(defn assert-checkpoint!
  "Validate a checkpoint's scope and cursor stream before using it as progress."
  [digest owner source checkpoint]
  (when checkpoint
    (assert-shape! shape/Checkpoint checkpoint :checkpoint)
    (when-not (and (= owner (:owner checkpoint))
                   (= source (:source checkpoint))
                   (= (stream-id digest owner source) (:stream-id checkpoint))
                   (= (:id checkpoint) (checkpoint-id digest checkpoint))
                   (= (:page-id checkpoint)
                      (page-id digest (:stream-id checkpoint) (:cursor-before checkpoint)
                               (:cursor-after checkpoint) (:event-ids checkpoint) (:coverage checkpoint)))
                   (= (= 1 (:sequence checkpoint)) (nil? (:previous-id checkpoint))))
      (throw (ex-info "Encounter checkpoint is outside the requested scope"
                      {:code :encounter/checkpoint-conflict}))))
  checkpoint)

(defn assert-cursor!
  "Opaque cursors must match the last confirmed page; their provider meaning is never inferred."
  [checkpoint cursor-before]
  (when-not (= (:cursor-after checkpoint) cursor-before)
    (throw (ex-info "Encounter page does not begin at the durable cursor"
                    {:code :encounter/stale-cursor}))))

(defn assert-ports!
  "Validate injected existing storage and trusted authorization operations."
  [ports authorize! digest]
  (doseq [key [:find-event! :append-event! :latest-checkpoint!
               :recent-encounters! :with-exclusive!]]
    (when-not (fn? (get ports key))
      (throw (ex-info "Encounter adapter requires an existing storage operation"
                      {:code :encounter/missing-port :port key}))))
  (when-not (and (fn? authorize!) (fn? digest))
    (throw (ex-info "Encounter adapter requires trusted authorization and hashing"
                    {:code :encounter/missing-adapter})))
  ports)
