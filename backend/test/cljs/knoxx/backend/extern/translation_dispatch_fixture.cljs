(ns knoxx.backend.extern.translation-dispatch-fixture
  "Native client and pause boundaries for isolated translation dispatch tests.

   The fixture delegates to the existing translation store and client protocols;
   it owns no dispatch policy, alternative persistence, or production runtime."
  (:require [knoxx.backend.infra.clients.openplanner :as openplanner-client]
            [knoxx.backend.infra.translation-dispatch :as dispatch]
            [knoxx.backend.infra.translation-evidence-store :as store]))

(def intent
  {:publication/id :knoxx.docs/probe-es
   :publication/document :knoxx.docs/probe
   :publication/garden :knoxx.docs/promethean
   :publication/locale :es
   :publication/revision "sha256-aaa111bbb222"
   :publication/state :published
   :publication/path "/probe"
   :translation/review :none
   :document/source-locale :en})

(def scope {:org-id "org-1" :membership-id "member-1"})

(def dispatched-revision "sha256-aaa111bbb222")

(def evidence-scope
  "The tenant and project this fixture dispatches under. Reads are scoped in the
   query now, so a test that read unscoped would see nothing."
  {:org-id "org-1" :project nil})

(def facts
  {:current-source-revision (constantly "sha256-aaa111bbb222")
   :translated-revision? (constantly false)
   :approved? (constantly false)
   :source-revision-superseded? (constantly false)})

(def clock (constantly "2026-08-22T09:00:00.000Z"))

(defn- batch-response
  "What `recover-settled-batch!` sees when it re-reads the batch.

   An explicit `batch-view` is used as given. A bare `batch-status` is expanded
   into the shape the real projection carries — the status route pushes a
   completed document onto `completed_documents`, so a `complete` batch names it.
   Neither configured means the batch is unreadable, which recovery treats as
   still running: the honest default for a test not exercising recovery."
  [batch-view batch-status]
  (cond
    batch-view (js/Promise.resolve batch-view)
    batch-status (js/Promise.resolve
                  {:status batch-status
                   :completed_documents (if (= "complete" batch-status)
                                          ["knoxx.docs/probe"]
                                          [])
                   :failed_documents []})
    :else (throw (ex-info "unexpected call to translation-batch!" {}))))

(defn- unexpected! [method]
  (throw (ex-info (str "unexpected call to " method) {})))

(defrecord TranslationClient [batches respond list-batches batch-status batch-view]
  openplanner-client/IOpenPlannerClient
  (enabled? [_] true)
  (create-translation-batch! [_ payload]
    (swap! batches conj payload)
    (js/Promise.resolve (respond payload (count @batches))))
  (translation-batches! [_ opts] (js/Promise.resolve (list-batches opts)))
  (translation-batch! [_ _ _] (batch-response batch-view batch-status))
  (health! [_] (unexpected! "health!"))
  (events! [_ _] (unexpected! "events!"))
  (session! [_ _ _] (unexpected! "session!"))
  (sessions! [_ _] (unexpected! "sessions!"))
  (vector-search! [_ _] (unexpected! "vector-search!"))
  (graph-memory! [_ _] (unexpected! "graph-memory!"))
  (graph-export! [_ _] (unexpected! "graph-export!"))
  (upsert-document! [_ _] (unexpected! "upsert-document!"))
  (documents-stats! [_] (unexpected! "documents-stats!"))
  (graph-monitoring! [_] (unexpected! "graph-monitoring!"))
  (mongo-collections! [_] (unexpected! "mongo-collections!"))
  (mongo-query! [_ _] (unexpected! "mongo-query!"))
  (build-semantic-edges! [_ _] (unexpected! "build-semantic-edges!"))
  (record-labels! [_ _] (unexpected! "record-labels!"))
  (record-reaction! [_ _ _] (unexpected! "record-reaction!"))
  (translation-segments! [_ _] (unexpected! "translation-segments!"))
  (translation-segment! [_ _ _] (unexpected! "translation-segment!"))
  (create-translation-segment! [_ _] (unexpected! "create-translation-segment!"))
  (label-translation-segment! [_ _ _] (unexpected! "label-segment!"))
  (translation-export-manifest! [_ _] (unexpected! "export-manifest!"))
  (translation-export-sft! [_ _] (unexpected! "export-sft!"))
  (create-translation-segments-batch! [_ _] (unexpected! "create-segments-batch!"))
  (translation-documents! [_ _] (unexpected! "translation-documents!"))
  (translation-document! [_ _ _ _] (unexpected! "translation-document!"))
  (review-translation-document! [_ _ _ _] (unexpected! "review-document!"))
  (next-translation-batch! [_ _] (unexpected! "next-batch!"))
  (update-translation-batch-status! [_ _ _] (unexpected! "update-status!"))
  (v1-json! [_ _ _ _] (unexpected! "v1-json!"))
  (forward-v1! [_ _] (unexpected! "forward-v1!")))

(defn fake-client
  "Fresh complete protocol client; unrelated operations fail explicitly."
  [{:keys [batches answer observed batch-status batch-view]}]
  (->TranslationClient batches
                       (or answer (fn [_request n] {:batch_id (str "batch-" n)}))
                       (or observed (fn [_opts] {:batches []}))
                       batch-status batch-view))

(defn fixture
  "Fresh store, client and recorded batch list for one test."
  [& {:keys [answer observed source-revision batch-status batch-view]}]
  (let [batches (atom [])]
    {:batches batches
     :deps {:evidence-store (store/memory-store)
            :client (fake-client {:batches batches
                                  :answer answer
                                  :observed observed
                                  :batch-status batch-status
                                  :batch-view batch-view})
            :clock clock
            ;; Defaults to agreeing with the dispatched revision, so completion
            ;; is not refused for source drift. The drift path overrides it.
            :observe-source-revision
            (constantly (js/Promise.resolve
                         (or source-revision dispatched-revision)))}}))

(defrecord CompletionFaultStore [inner maybe-fail!]
  store/ITranslationEvidenceStore
  (reserve-dispatch! [_ record] (store/reserve-dispatch! inner record))
  (resolve-dispatch! [_ record outcome detail]
    (store/resolve-dispatch! inner record outcome detail))
  (bind-dispatch-batch! [_ record batch-id]
    (store/bind-dispatch-batch! inner record batch-id))
  (claim-dispatch-completion! [_ record]
    (store/claim-dispatch-completion! inner record))
  (finish-dispatch-completion! [_ record detail]
    (maybe-fail! :before-finish)
    (store/finish-dispatch-completion! inner record detail))
  (dispatch-for-key! [_ dispatch-key]
    (store/dispatch-for-key! inner dispatch-key))
  (dispatch-for-batch-document! [_ batch-id document-wire-id]
    (store/dispatch-for-batch-document! inner batch-id document-wire-id))
  (dispatch-for-batch! [_ batch-id]
    (store/dispatch-for-batch! inner batch-id))
  (record-translation! [_ receipt]
    (maybe-fail! :before-receipt)
    (store/record-translation! inner receipt))
  (completed-translations! [_ requested-scope]
    (store/completed-translations! inner requested-scope))
  (record-approval! [_ approval] (store/record-approval! inner approval))
  (approvals! [_ requested-scope] (store/approvals! inner requested-scope)))

(defn completion-fault-store
  "Delegate to inner, throwing once at the selected completion phase."
  [inner fail-on]
  (let [failed? (atom false)
        maybe-fail! (fn [phase]
                      (when (and (= fail-on phase)
                                 (compare-and-set! failed? false true))
                        (throw (ex-info (str "simulated crash at " (name phase))
                                        {:phase phase}))))]
    (->CompletionFaultStore inner maybe-fail!)))

(defn source-observer
  "A stable async source observation, including an unreadable nil digest."
  [revision]
  (constantly (js/Promise.resolve revision)))

(defn paused-source-observer
  "Expose entry and release of the real completion observation boundary."
  []
  (let [entered-resolve (atom nil)
        entered (js/Promise. (fn [resolve _reject] (reset! entered-resolve resolve)))
        source-release (atom nil)
        source-result (js/Promise. (fn [resolve _reject] (reset! source-release resolve)))]
    {:entered entered
     :release! (fn [revision] (@source-release revision))
     :observe! (fn [_] (@entered-resolve true) source-result)}))

(defn work
  "Derive fixture work through the public dispatch API."
  []
  (:action/with (dispatch/derived-work intent facts)))

(defn context
  "Bind the same concrete source revision through the public dispatch API."
  []
  ;; The digest observed at dispatch time. Equal to the intent's revision here
  ;; because the fixture's revision IS a content digest; a pinned opaque revision
  ;; would differ, which is why the two are recorded separately.
  (dispatch/dispatch-context intent scope dispatched-revision))
