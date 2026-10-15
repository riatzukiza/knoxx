(ns knoxx.backend.extern.openplanner-history-fixture
  "Complete OpenPlanner client fixtures for memory pages and transcript history.

   Only explicitly configured observations are available. All unrelated protocol
   calls throw, so an unexpected production dependency cannot pass as undefined.
   The actual client protocol remains the authority; no transport is opened."
  (:require [knoxx.backend.infra.clients.openplanner :as openplanner-client]))

(defn- unexpected! [operation]
  (throw (ex-info (str "Unexpected OpenPlanner history fixture operation: " (name operation))
                  {:operation operation})))

(defrecord HistoryClient [enabled-reader session-reader sessions-reader]
  openplanner-client/IOpenPlannerClient
  (enabled? [client]
    (if enabled-reader (enabled-reader client) (unexpected! :enabled?)))
  (health! [_] (unexpected! :health!))
  (events! [_ _events] (unexpected! :events!))
  (session! [client conversation-id opts]
    (if session-reader
      (session-reader client conversation-id opts)
      (unexpected! :session!)))
  (sessions! [client opts]
    (if sessions-reader (sessions-reader client opts) (unexpected! :sessions!)))
  (vector-search! [_ _payload] (unexpected! :vector-search!))
  (graph-memory! [_ _payload] (unexpected! :graph-memory!))
  (graph-export! [_ _opts] (unexpected! :graph-export!))
  (upsert-document! [_ _document] (unexpected! :upsert-document!))
  (documents-stats! [_] (unexpected! :documents-stats!))
  (graph-monitoring! [_] (unexpected! :graph-monitoring!))
  (mongo-collections! [_] (unexpected! :mongo-collections!))
  (mongo-query! [_ _payload] (unexpected! :mongo-query!))
  (build-semantic-edges! [_ _payload] (unexpected! :build-semantic-edges!))
  (record-labels! [_ _record-ids] (unexpected! :record-labels!))
  (record-reaction! [_ _record-id _payload] (unexpected! :record-reaction!))
  (translation-segments! [_ _opts] (unexpected! :translation-segments!))
  (translation-segment! [_ _segment-id _opts] (unexpected! :translation-segment!))
  (create-translation-segment! [_ _segment] (unexpected! :create-translation-segment!))
  (label-translation-segment! [_ _segment-id _payload] (unexpected! :label-translation-segment!))
  (translation-export-manifest! [_ _opts] (unexpected! :translation-export-manifest!))
  (translation-export-sft! [_ _opts] (unexpected! :translation-export-sft!))
  (create-translation-segments-batch! [_ _payload] (unexpected! :create-translation-segments-batch!))
  (translation-documents! [_ _opts] (unexpected! :translation-documents!))
  (translation-document! [_ _document-id _target-lang _opts] (unexpected! :translation-document!))
  (review-translation-document! [_ _document-id _target-lang _payload] (unexpected! :review-translation-document!))
  (create-translation-batch! [_ _payload] (unexpected! :create-translation-batch!))
  (translation-batches! [_ _opts] (unexpected! :translation-batches!))
  (next-translation-batch! [_ _opts] (unexpected! :next-translation-batch!))
  (translation-batch! [_ _batch-id _opts] (unexpected! :translation-batch!))
  (update-translation-batch-status! [_ _batch-id _payload] (unexpected! :update-translation-batch-status!))
  (v1-json! [_ _method _path _body] (unexpected! :v1-json!))
  (forward-v1! [_ _request] (unexpected! :forward-v1!)))

(defn sessions-client
  "Use the supplied original page callback, including its actual client receiver.
   Even enabled? is unavailable, since the old page fixtures did not supply it."
  [read-page!]
  (->HistoryClient nil nil read-page!))

(defn message-client
  "Return the original enabled transcript fixture with its supplied rows."
  [rows]
  (->HistoryClient (constantly true)
                   (fn [_client _conversation-id _opts]
                     (js/Promise.resolve {:rows rows}))
                   nil))

(defn failing-message-client
  "Reject transcript restore with the same native error on every invocation."
  []
  (->HistoryClient (constantly true)
                   (fn [_client _conversation-id _opts]
                     (js/Promise.reject (js/Error. "OpenPlanner request failed")))
                   nil))
