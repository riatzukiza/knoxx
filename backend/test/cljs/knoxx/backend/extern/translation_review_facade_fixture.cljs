(ns knoxx.backend.extern.translation-review-facade-fixture
  "Instance-owned native scheduling controls over real in-memory stores."
  (:require ["node:crypto" :as crypto]
            [clojure.string :as str]
            [knoxx.backend.domain.publication-resolver :as resolver]
            [knoxx.backend.infra.translation-evidence-store :as evidence]
            [knoxx.backend.infra.translation-split-store :as splits]
            [knoxx.backend.law.translation-dispatch :as dispatch]
            [knoxx.backend.law.translation-split :as split]))

(def scope
  "One tenant with deliberate nil slots in the operation principal tuple."
  {:org-id "org-1" :project "knoxx-session"
   :principal {:principal/user-id "reviewer-1"}})

(def admitted-at "The immutable turn's admission time." "2026-09-01T10:00:00.000Z")
(def completed-at "The original completion evidence time." "2026-09-01T10:01:00.000Z")
(def source-revision "One concrete source revision." "source-review-v1")
(def document-id "The resource-owned document selector." :knoxx.docs/probe)
(def garden-id "The resource-owned garden selector." :knoxx.gardens/promethean)

(defn control
  "Create isolated trace, deferred gates, failures, and observed native tasks."
  []
  {:events (atom []) :waiters (atom []) :gates (atom {})
   :pending (atom []) :failures (atom {}) :digests (atom []) :ticks (atom 0)
   :public-outcome (atom nil)})

(defn events
  "Return the complete ordered event trace as plain data."
  [ctl]
  @(:events ctl))

(defn- settle-waiter!
  "Prefer an attained count; otherwise reject only a terminal public outcome."
  [ctl {:keys [kind amount resolve reject]}]
  (let [observed (count (filter #(= kind (first %)) (events ctl)))
        terminal @(:public-outcome ctl)]
    (cond
      (<= amount observed) (resolve nil)
      terminal
      (reject (if (contains? terminal :error)
                (:error terminal)
                (ex-info "public task completed before expected events"
                         {:fixture/error :public-completed-before-events
                          :kind kind :expected amount :observed observed}))))))

(defn event!
  "Record an event and settle the instance-owned causal observers."
  [ctl tag value]
  (swap! (:events ctl) conj [tag value])
  (doseq [waiter @(:waiters ctl)]
    (settle-waiter! ctl waiter)))

(defn await-events!
  "Observe an attained count or the owned public task's terminal outcome."
  [ctl tag amount]
  (js/Promise.
   (fn [resolve reject]
     (let [waiter {:kind tag :amount amount :resolve resolve :reject reject}]
       (swap! (:waiters ctl) conj waiter)
       (settle-waiter! ctl waiter)))))

(defn hold!
  "Install one deferred gate before the corresponding public call starts."
  [ctl tag value]
  (let [key [tag value]
        promise (js/Promise.
                 (fn [resolve reject]
                   (swap! (:gates ctl) assoc key
                          {:resolve resolve :reject reject})))]
    (swap! (:gates ctl) assoc-in [key :promise] promise)))

(defn release!
  "Release exactly one gate; native promise settlement is idempotent."
  [ctl tag value]
  ((get-in @(:gates ctl) [[tag value] :resolve]) nil))

(defn reject!
  "Reject exactly one gate with the caller's original opaque error."
  [ctl tag value error]
  ((get-in @(:gates ctl) [[tag value] :reject]) error))

(defn error
  "Create a native error whose identity must survive the public facade."
  [message]
  (js/Error. message))

(defn fail!
  "Fail one observed protocol operation before invoking its actual delegate."
  [ctl tag value error]
  (swap! (:failures ctl) assoc [tag value] error))

(defn- ^:async through-step!
  "Trace and optionally suspend one real store operation without global state."
  [ctl tag value work]
  (event! ctl tag value)
  (when-let [gate (get @(:gates ctl) [tag value])]
    (await (:promise gate)))
  (when-let [failure (get @(:failures ctl) [tag value])]
    (throw failure))
  (let [result (await (work))]
    (event! ctl (keyword (str (name tag) "-done")) value)
    result))

(defn ^:async outcome!
  "Capture the exact value or rejection without translating native errors."
  [work]
  (try
    {:value (await (work))}
    (catch :default failure {:error failure})))

(defn- ^:async public-outcome!
  "Publish only the start!-owned task's terminal result to event observers."
  [ctl work]
  (let [result (await (outcome! work))]
    (reset! (:public-outcome ctl) result)
    (doseq [waiter @(:waiters ctl)]
      (settle-waiter! ctl waiter))
    result))

(defn- through!
  "Observe every branch so cleanup also drains work after the public task rejects."
  [ctl tag value work]
  (let [task (through-step! ctl tag value work)]
    (swap! (:pending ctl) conj (outcome! (constantly task)))
    task))

(defn start!
  "Own the public outcome separately from independently observed store steps."
  [ctl work]
  (reset! (:public-outcome ctl) nil)
  (let [observed (public-outcome! ctl work)]
    (swap! (:pending ctl) conj observed)
    observed))

(defn ^:async cleanup!
  "Release every outstanding branch and drain every tracked public task."
  [ctl]
  (doseq [{:keys [resolve]} (vals @(:gates ctl))]
    (resolve nil))
  (await (js/Promise.all (to-array @(:pending ctl))))
  (reset! (:waiters ctl) [])
  nil)

(defn digest
  "Hash exact UTF-8 strings and retain every input for compatibility assertions."
  [ctl text]
  (swap! (:digests ctl) conj text)
  (.digest (.update (.createHash crypto "sha256") text "utf8") "hex"))

(defn operation-inputs
  "Select raw single/bulk operation inputs from the observed digest boundary."
  [ctl]
  (filterv #(str/starts-with? % "[\"translation-candidate-set-")
           @(:digests ctl)))

(defn clock
  "Sample an observable monotone clock owned by this fixture."
  [ctl]
  (event! ctl :clock nil)
  (.toISOString (js/Date. (+ 1788260400000 (swap! (:ticks ctl) inc)))))

(defrecord EvidenceTrace [delegate ctl]
  evidence/ITranslationEvidenceStore
  (reserve-dispatch! [_ record] (evidence/reserve-dispatch! delegate record))
  (resolve-dispatch! [_ expected outcome detail]
    (evidence/resolve-dispatch! delegate expected outcome detail))
  (bind-dispatch-batch! [_ expected batch-id]
    (evidence/bind-dispatch-batch! delegate expected batch-id))
  (claim-dispatch-completion! [_ expected]
    (evidence/claim-dispatch-completion! delegate expected))
  (finish-dispatch-completion! [_ expected detail]
    (evidence/finish-dispatch-completion! delegate expected detail))
  (dispatch-for-key! [_ key]
    (through! ctl :dispatch key #(evidence/dispatch-for-key! delegate key)))
  (dispatch-for-batch-document! [_ batch-id document-wire-id]
    (evidence/dispatch-for-batch-document! delegate batch-id document-wire-id))
  (dispatch-for-batch! [_ batch-id]
    (evidence/dispatch-for-batch! delegate batch-id))
  (record-translation! [_ receipt]
    (evidence/record-translation! delegate receipt))
  (completed-translations! [_ query]
    (through! ctl :receipts query
              #(evidence/completed-translations! delegate query)))
  (record-approval! [_ approval]
    (through! ctl :approval (:review/translation-revision approval)
              #(evidence/record-approval! delegate approval)))
  (approvals! [_ query]
    (through! ctl :approvals query #(evidence/approvals! delegate query))))

(defrecord SplitTrace [delegate ctl]
  splits/ITranslationSplitStore
  (admit-turn! [_ turn] (splits/admit-turn! delegate turn))
  (turn-for-run! [_ run-id] (splits/turn-for-run! delegate run-id))
  (turn-by-id! [_ turn-id] (splits/turn-by-id! delegate turn-id))
  (append-candidate-split! [_ turn-id candidate]
    (splits/append-candidate-split! delegate turn-id candidate))
  (candidate-splits-for-turn! [_ turn-id]
    (splits/candidate-splits-for-turn! delegate turn-id))
  (complete-candidate-set! [_ turn-id candidate-set]
    (splits/complete-candidate-set! delegate turn-id candidate-set))
  (candidate-set-for-turn! [_ turn-id]
    (splits/candidate-set-for-turn! delegate turn-id))
  (candidate-set-by-id! [_ set-id]
    (through! ctl :candidate-set set-id
              #(splits/candidate-set-by-id! delegate set-id)))
  (turn-for-candidate-set! [_ set-id]
    (through! ctl :turn set-id #(splits/turn-for-candidate-set! delegate set-id)))
  (append-review-receipt! [_ receipt]
    (through! ctl :append (:review/split-id receipt)
              #(splits/append-review-receipt! delegate receipt)))
  (review-history-for-split! [_ set-id split-id]
    (through! ctl :history split-id
              #(splits/review-history-for-split! delegate set-id split-id)))
  (applicable-memory! [_ query] (splits/applicable-memory! delegate query)))

(defn resources
  "Build resource-derived desired work for exact locale relations."
  [locales]
  (let [document {:document/id document-id :document/title "Probe"
                  :document/source-locale :en
                  :document/source {:path "docs/probe.md"}}
        garden {:garden/id garden-id :garden/title "Promethean"
                :garden/status :active :garden/locales (into [:en] locales)}
        intents (mapv (fn [locale]
                        {:publication/id (keyword "knoxx.publications"
                                                  (str "probe-" (name locale)))
                         :publication/document document-id
                         :publication/garden garden-id :publication/locale locale
                         :publication/revision :source/current
                         :publication/state :published
                         :publication/path (str "/probe-" (name locale))
                         :translation/review :required}) locales)]
    {:publication-index (resolver/publication-index (into [document garden] intents))
     :source-revisions {document-id source-revision}}))

(defn- manifest
  "Construct the same authenticated source seed as the existing facade suite."
  [hash-text]
  (split/split-manifest
   hash-text {:org-id (:org-id scope) :project (:project scope)
              :garden garden-id :document document-id
              :source-locale :en :target-locale :es
              :source-revision source-revision
              :source-text "First source.\n\nSecond source.\n"
              :source-parts ["First source.\n\n" "Second source.\n"]}))

(defn- candidate-context
  "Construct an admitted two-member turn using only existing public laws."
  [hash-text dispatch-key]
  (let [manifest-value (manifest hash-text)
        claim (split/candidate-claim hash-text manifest-value "candidate-review-v1")
        candidates (mapv #(split/candidate-split hash-text %1 %2)
                         (:candidate-claim/members claim)
                         ["Primera candidata.\n\n" "Segunda candidata.\n"])
        candidate-set (split/complete-candidate-set
                       hash-text manifest-value claim (reverse candidates))
        execution (split/execution-snapshot
                   hash-text {:agent-id "publication_translator" :model "test-model"
                              :thinking :medium :system-prompt "Translate exact splits."
                              :tool-ids ["save_translation"]})
        turn (split/translation-turn-admission
              hash-text {:dispatch-key dispatch-key :run-id "run-review-1"
                         :admitted-at admitted-at :manifest manifest-value
                         :candidate-claim claim :execution execution
                         :memory (split/memory-snapshot {:status :empty :examples []})})]
    {:manifest manifest-value :claim claim :candidates candidates
     :candidate-set candidate-set :turn turn}))

(defn receipt
  "Describe one content-bound completion, optionally with authenticated splits."
  ([] (receipt nil))
  ([{:keys [manifest claim candidate-set turn]}]
   (cond-> {:receipt/type :translation/completed
            :translation/document document-id :translation/garden garden-id
            :translation/source-locale :en :translation/locale :es
            :translation/source-revision source-revision
            :translation/revision "candidate-review-v1"
            :translation/content-digest "candidate-content"
            :translation/dispatch-key "legacy-key"
            :translation/org-id (:org-id scope) :translation/project (:project scope)
            :translation/at completed-at}
     candidate-set
     (assoc :translation/dispatch-key (:translation-turn/dispatch-key turn)
            :translation/split-manifest-id (:split-manifest/id manifest)
            :translation/candidate-claim-id (:candidate-claim/id claim)
            :translation/candidate-set-id (:candidate-set/id candidate-set)
            :translation/candidate-set-digest (:candidate-set/digest candidate-set)
            :translation/split-count (count (:candidate-set/members candidate-set))
            :translation/split-turn-admitted-at admitted-at))))

(defn approval-request
  "Return a valid whole-output request without trusted attribution fields."
  []
  {:review/document document-id :review/garden garden-id :review/locale :es
   :review/revision source-revision :review/translation-revision "candidate-review-v1"})

(defn- ^:async persist-context!
  "Persist actual split authority and complete its exact evidence-store attempt."
  [split-store evidence-store record context]
  (let [{:keys [turn candidates candidate-set]} context
        turn-id (:translation-turn/id turn)
        reserved (:record (await (evidence/reserve-dispatch! evidence-store record)))
        bound (await (evidence/bind-dispatch-batch! evidence-store reserved "run-review-1"))]
    (await (splits/admit-turn! split-store turn))
    (doseq [candidate candidates]
      (await (splits/append-candidate-split! split-store turn-id candidate)))
    (await (splits/complete-candidate-set! split-store turn-id candidate-set))
    (await (evidence/claim-dispatch-completion! evidence-store bound))
    (await (evidence/record-translation!
            evidence-store (assoc (receipt context)
                                  :translation/dispatch-attempt-id
                                  (:dispatch/attempt-id bound))))
    (await (evidence/finish-dispatch-completion! evidence-store bound "completed"))))

(defn- accepted-dispatch
  "Use the same exact-attempt dispatch seed as the original test suite."
  []
  (dispatch/dispatch-record
   {:document document-id :locale :es :revision source-revision :replace-stale? false}
   {:dispatch/garden "knoxx.gardens/promethean"
    :dispatch/document-wire-id "knoxx.docs/probe" :dispatch/source-locale :en
    :dispatch/org-id (:org-id scope) :dispatch/project (:project scope)
    :dispatch/membership-id "membership-1"}
   :dispatch/accepted admitted-at :attempt-id "attempt-review-1"))

(defn ^:async seeded!
  "Create real stores and their traced delegates, with no live external service."
  []
  (let [ctl (control)
        hash-text #(digest ctl %)
        evidence-store (evidence/memory-store)
        split-store (splits/memory-store hash-text)
        record (accepted-dispatch)
        context (candidate-context hash-text (:dispatch/key record))]
    (await (persist-context! split-store evidence-store record context))
    (reset! (:digests ctl) [])
    {:control ctl :context context :raw-evidence evidence-store :raw-splits split-store
     :evidence-store (->EvidenceTrace evidence-store ctl)
     :split-store (->SplitTrace split-store ctl) :digest-hex hash-text}))

(defn ^:async project!
  "Observe injected projector arguments and return plain inert projection data."
  [fixture params set-id]
  (let [ctl (:control fixture)]
    (event! ctl :project [set-id params])
    (when-let [failure (get @(:failures ctl) [:project set-id])]
      (throw failure))
    {:translation/review-status :fixture-projected
     :translation/receipt (receipt (:context fixture))}))

(defn deps
  "Supply public dependencies; the injected projector has no publication effect."
  [fixture]
  (merge (select-keys fixture [:evidence-store :split-store :digest-hex])
         (resources [:es])
         {:clock #(clock (:control fixture)) :content-root "/translated"
          :project-reviewed-output! (partial project! fixture)}))

(defn ^:async authorize!
  "Expose completion of the injected content authorizer as its own causal gate."
  [ctl _checked _receipt]
  (await (through! ctl :authorize nil (constantly true))))

(defn dispatch-key
  "Derive an exact public dispatch-law key for one desired locale relation."
  [locale]
  (dispatch/dispatch-key
   {:org-id (:org-id scope) :project (:project scope)
    :garden "knoxx.gardens/promethean" :document document-id
    :source-locale :en :locale locale :revision source-revision}))

(defn ^:async seed-dispatch!
  "Persist an actual terminal non-completion outcome for one inventory relation."
  [fixture locale outcome]
  (let [record (dispatch/dispatch-record
                {:document document-id :locale locale :revision source-revision
                 :replace-stale? false}
                {:dispatch/garden "knoxx.gardens/promethean"
                 :dispatch/document-wire-id "knoxx.docs/probe"
                 :dispatch/source-locale :en :dispatch/org-id (:org-id scope)
                 :dispatch/project (:project scope) :dispatch/membership-id "membership-1"}
                :dispatch/accepted admitted-at :attempt-id (str "attempt-" (name locale)))
        evidence-store (:raw-evidence fixture)
        reserved (:record (await (evidence/reserve-dispatch! evidence-store record)))]
    (await (evidence/resolve-dispatch! evidence-store reserved outcome "fixture outcome"))))
