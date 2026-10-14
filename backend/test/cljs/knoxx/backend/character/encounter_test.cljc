(ns knoxx.backend.character.encounter-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [clojure.string :as str]
            [knoxx.backend.domain.character.decision-input :as decision-input]
            [knoxx.backend.domain.character.encounter :as encounter]
            [knoxx.backend.domain.character.encounter-context :as context]
            [knoxx.backend.law.character.encounter :as law]
            [knoxx.backend.shape.character.encounter :as shape]))

(def owner
  "Fixture ownership scope."
  {:org-id "org-local" :project "creator-local" :character-id "creator"})

(def source
  "Fixture generic source scope."
  {:kind "discord" :account-id "local-bot" :scope-id "allowed-channel"
   :visibility :public})

(def at
  "Fixture canonical timestamp."
  "2026-10-07T10:00:00.000Z")

(defn digest
  "Deterministic fixture digest; production injects the existing SHA-256 boundary."
  [text]
  (str "fixture-" (hash text)))

(defn authority
  "Fixture already-resolved trusted adapter evidence."
  [owner source]
  {:allowed? true :owner owner :source source :principal-id "creator-actor"
   :evidence-id "fixture-policy" :checked-at at})

(defn item
  "Build a source-normalized external encounter fixture."
  [external-id text]
  {:external-id external-id :author-id "external-author" :occurred-at at
   :text text :self-output? false})

(defn page
  "Build a complete page whose opaque cursor belongs to the source adapter."
  [items]
  {:source source :items items :cursor-before nil
   :cursor-after "opaque-next" :observed-at at})

(defn prepared
  "Prepare an authorized fixture page without any I/O."
  [items]
  (encounter/prepare-page digest owner (page items) (authority owner source) nil))

(defn error-code
  "Return a pure contract failure code without depending on platform exception types."
  [task]
  (try
    (task)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (:code (ex-data error)))))

(deftest identity-survives-retry-renewed-authority-and-restart
  (let [input (page [(item "post-1" "fresh external material")])
        first-pass (encounter/prepare-page digest owner input (authority owner source) nil)
        renewed (assoc (authority owner source) :evidence-id "renewed-policy"
                        :checked-at "2026-10-07T10:01:00.000Z")
        retry (encounter/prepare-page digest owner
                                      (assoc input :observed-at "2026-10-07T10:01:00.000Z")
                                      renewed (:checkpoint first-pass))]
    (is (= (mapv :id (:records first-pass)) (mapv :id (:records retry))))
    (is (= (:page-id first-pass) (:page-id retry)))
    (is (:replay? retry))
    (is (= (:checkpoint first-pass) (:checkpoint retry)))
    (is (= :replayed (:status (encounter/completion retry
                                                         (mapv :id (:records retry)) true))))))

(deftest duplicate-items-share-one-stable-event
  (let [entry (item "post-1" "same bytes")
        result (prepared [entry entry entry])]
    (is (= 1 (count (:events result))))
    (is (= 1 (count (get-in result [:checkpoint :event-ids]))))))

(deftest partial-failure-cannot-advance-page-cursor
  (let [result (prepared [(item "one" "first") (item "two" "second")])
        first-id (get-in result [:records 0 :id])
        all-ids (mapv :id (:records result))]
    (testing "missing member and missing checkpoint both preserve the original cursor"
      (is (nil? (:cursor (encounter/completion result [first-id] false))))
      (is (nil? (:cursor (encounter/completion result [first-id] true))))
      (is (nil? (:cursor (encounter/completion result all-ids false))))
      (is (= [first-id] (:event-ids (encounter/completion result [first-id] false))))
      (is (= 1 (count (:causal-source-ids (encounter/completion result [first-id] false))))))
    (is (= "opaque-next" (:cursor (encounter/completion result all-ids true))))))

(deftest private-source-denial-precedes-content-admission
  (let [private-source (assoc source :visibility :private)
        input (assoc (page [(item "private-1" "restricted material")])
                     :source private-source)]
    (is (= :encounter/source-denied
           (error-code #(encounter/prepare-page digest owner input {:allowed? false} nil))))
    (is (= :encounter/source-denied
           (error-code #(encounter/prepare-page digest owner input
                                                (authority owner source) nil))))
    (is (= :encounter/source-denied
           (error-code #(encounter/prepare-page digest owner input
                                                (authority (assoc owner :org-id "other")
                                                           private-source) nil))))))

(deftest self-output-is-distinct-from-the-external-source
  (let [external (item "same-provider-id" "same exact text")
        own-output (assoc external :self-output? true)
        result (prepared [external own-output])
        records (:records result)
        decisions {(law/stream-id digest owner source) (authority owner source)}
        material (context/assemble-context digest owner records decisions {})]
    (is (= 2 (count (set (map :id records)))))
    (is (= ["external" "self-output"] (mapv #(get-in % [:extra :provenance]) (:events result))))
    (is (= 2 (count (:encounters material))))
    (is (= 1 (count (:encounters (context/assemble-context
                                 digest owner records decisions {:include-self-output? false})))))))

(deftest source-account-scope-and-owner-are-independent-identity-coordinates
  (let [input (page [(item "post-1" "same source bytes")])
        prepare (fn [owner source]
                  (encounter/prepare-page digest owner (assoc input :source source)
                                          (authority owner source) nil))
        cases [(prepare owner source)
               (prepare owner (assoc source :kind "bluesky"))
               (prepare owner (assoc source :kind "rss"))
               (prepare owner (assoc source :account-id "another-account"))
               (prepare owner (assoc source :scope-id "another-channel"))
               (prepare (assoc owner :org-id "another-org") source)]]
    (is (= (count cases) (count (set (map :stream-id cases)))))
    (is (= (count cases) (count (set (map #(get-in % [:records 0 :id]) cases)))))))

(deftest source-edits-change-context-while-preserving-causal-source-id
  (let [first-record (first (:records (prepared [(item "post-1" "first inspiration")])))
        edited-record (first (:records
                              (encounter/prepare-page
                               digest owner
                               (assoc (page [(item "post-1" "second inspiration")])
                                      :observed-at "2026-10-07T10:01:00.000Z")
                               (authority owner source) nil)))
        decisions {(law/stream-id digest owner source) (authority owner source)}
        before (context/assemble-context digest owner [first-record] decisions {})
        after (context/assemble-context digest owner [first-record edited-record] decisions {})]
    (is (= (:source-id first-record) (:source-id edited-record)))
    (is (not= (:id first-record) (:id edited-record)))
    (is (not= (:prompt-context before) (:prompt-context after)))
    (is (= [(:id edited-record)] (:event-ids after)))
    (is (= [(:source-id edited-record)] (:causal-source-ids after)))))

(deftest revoked-authority-and-cross-owner-content-are-not-recalled
  (let [record (first (:records (prepared [(item "post-1" "private inspiration")])))]
    (is (= "" (:prompt-context (context/assemble-context digest owner [record] {} {}))))
    (is (empty? (:encounters
                  (context/assemble-context digest (assoc owner :org-id "other") [record]
                                            {(law/stream-id digest owner source)
                                             (authority owner source)} {}))))))

(deftest cursor-lineage-does-not-infer-provider-order
  (let [checkpoint (:checkpoint (prepared [(item "one" "first page")]))
        input (assoc (page [(item "two" "second page")]) :cursor-before "opaque-next"
                     :cursor-after "opaque-finished")
        next-page (encounter/prepare-page digest owner input (authority owner source) checkpoint)]
    (is (= 2 (get-in next-page [:checkpoint :sequence])))
    (is (= (:id checkpoint) (get-in next-page [:checkpoint :previous-id])))
    (is (= :encounter/stale-cursor
           (error-code #(encounter/prepare-page digest owner
                                                (assoc input :cursor-before "unrelated")
                                                (authority owner source) checkpoint))))))

(deftest pages-and-prompt-material-are-bounded
  (is (= :encounter/invalid-shape
         (error-code #(prepared (mapv (fn [n] (item (str n) "text")) (range 25))))))
  (let [records (:records (prepared
                           (mapv (fn [n] (item (str n) (apply str (repeat 1800 "x"))))
                                 (range 12))))
        material (context/assemble-context
                  digest owner records
                  {(law/stream-id digest owner source) (authority owner source)}
                  {:max-encounters 3 :max-chars 3000 :excerpt-chars 400})]
    (is (<= (count (:encounters material)) 3))
    (is (<= (count (:prompt-context material)) 3000))
    (is (= (count (:prompt-context material)) (:character-count material)))
    (is (every? :excerpt? (:encounters material)))))

(deftest reusable-provider-cursors-still-produce-distinct-causal-checkpoints
  (let [first-page (prepared [(item "one" "first page")])
        second-input (assoc (page [(item "two" "second page")])
                            :cursor-before "opaque-next" :cursor-after nil)
        second-page (encounter/prepare-page digest owner second-input
                                           (authority owner source) (:checkpoint first-page))
        returned-input (page [(item "one" "first page")])
        returned-page (encounter/prepare-page digest owner returned-input
                                             (authority owner source) (:checkpoint second-page))]
    (is (= (:page-id first-page) (:page-id returned-page)))
    (is (not= (get-in first-page [:checkpoint :id])
              (get-in returned-page [:checkpoint :id])))
    (is (= 3 (get-in returned-page [:checkpoint :sequence])))
    (is (= (get-in second-page [:checkpoint :id])
           (get-in returned-page [:checkpoint :previous-id])))))

(deftest emoji-reaction-labels-and-media-references-survive-as-evidence
  (let [input (assoc (item "emote-post" "hello 🙂 <:custom_wave:12345>")
                     :reactions [{:label "custom_wave" :reference "discord:emote:12345" :count 2}]
                     :media [{:kind :emote :reference "discord:emote:12345" :label "custom_wave"}
                             {:kind :image :reference "https://example.test/image.png"}])
        result (prepared [input])
        record (first (:records result))
        roundtrip (shape/wire->record (shape/record->wire record))
        material (context/assemble-context
                  digest owner [record]
                  {(law/stream-id digest owner source) (authority owner source)} {})]
    (is (= (:text input) (:text record)))
    (is (= (:reactions input) (:reactions record)))
    (is (= (:media input) (:media record)))
    (is (= record roundtrip))
    (is (re-find #"custom_wave" (:prompt-context material)))
    (is (re-find #"image.png" (:prompt-context material)))
    (is (not= (:id record)
              (:id (first (:records (prepared [(assoc-in input [:reactions 0 :count] 3)]))))))))

(deftest attachment-only-and-reaction-only-encounters-retain-source-evidence
  (let [image-only (assoc (item "image-only" "")
                          :media [{:kind :image :reference "https://example.test/only-image.png"}])
        reaction-only (assoc (item "reaction-only" "")
                             :reactions [{:label "🙂" :count 1}])
        result (prepared [image-only reaction-only])
        material (context/assemble-context
                  digest owner (:records result)
                  {(law/stream-id digest owner source) (authority owner source)} {})]
    (is (= 2 (count (:records result))))
    (is (= 2 (count (:encounters material))))
    (is (re-find #"only-image.png" (:prompt-context material)))
    (is (= :encounter/empty-content (error-code #(prepared [(item "empty" "")]))))))

(deftest bounded-poll-coverage-is-durable-and-bound-to-page-identity
  (let [coverage {:mode :newest :complete? false :reason :provider-pagination
                  :fetched-count 1 :admitted-count 1 :overflow? true
                  :provider-cursor "older-provider-page" :newest-id nil :oldest-id nil}
        input (assoc (page [(item "poll-one" "fresh bounded material")]) :coverage coverage)
        result (encounter/prepare-page digest owner input (authority owner source) nil)
        stored (shape/wire->checkpoint (get-in result [:checkpoint-event :extra :encounter_cursor]))
        changed (encounter/prepare-page digest owner (assoc-in input [:coverage :provider-cursor] "different-older-page")
                                        (authority owner source) nil)]
    (is (= coverage (:coverage stored)))
    (is (= (:checkpoint result) stored))
    (is (= stored (law/assert-checkpoint! digest owner source stored)))
    (is (not= (:page-id result) (:page-id changed)))
    (is (= (mapv :id (:records result)) (mapv :id (:records changed))))
    (is (= :encounter/invalid-shape
           (error-code #(encounter/prepare-page digest owner (assoc-in input [:coverage :complete?] true)
                                                 (authority owner source) nil))))))

(defn- timed-record
  [source external-id text occurred-at admitted-at self-output?]
  (encounter/normalize-item digest owner source (authority owner source) admitted-at
                            (assoc (item external-id text) :occurred-at occurred-at :self-output? self-output?)))

(defn- selection-fixture
  []
  (let [discord (assoc source :visibility :private)
        bluesky {:kind "bluesky" :account-id "did:plc:creator" :scope-id "home-timeline" :visibility :public}
        other-channel (assoc discord :scope-id "other-channel")
        denied-visibility (assoc discord :visibility :public)
        fresh [(timed-record discord "discord-newest" "fresh Discord external observation"
                             "2026-10-07T12:00:00.000Z" "2026-10-07T12:01:00.000Z" false)
               (timed-record bluesky "blue-newest" "fresh Bluesky external observation"
                             "2026-10-07T11:30:00.000Z" "2026-10-07T11:31:00.000Z" false)
               (timed-record other-channel "other-newest" "fresh other-channel external observation"
                             "2026-10-07T11:20:00.000Z" "2026-10-07T11:21:00.000Z" false)]
        busy (into [(timed-record discord "discord-second" "busy Discord second external observation"
                                  "2026-10-07T11:59:00.000Z" "2026-10-07T15:00:00.000Z" false)]
                   (mapv #(timed-record discord (str "older-page-" %) (str "later-polled old page " %)
                                        (str "2026-10-07T09:0" % ":00.000Z") "2026-10-07T16:00:00.000Z" false)
                         (range 6)))
        own (mapv #(timed-record discord (str "own-" %) (str "later-created self output " %)
                                 (str "2026-10-07T18:0" % ":00.000Z") "2026-10-07T19:00:00.000Z" true)
                  (range 6))
        denied (timed-record denied-visibility "denied" "DENIED visibility scope must never enter recall"
                              "2026-10-07T20:00:00.000Z" "2026-10-07T21:00:00.000Z" false)]
    {:fresh fresh :busy busy :own own :denied denied
     :decisions (into {} (map #(vector (law/stream-id digest owner %) (authority owner %))
                              [discord bluesky other-channel]))}))

(deftest newer-external-input-from-distinct-scopes-survives-later-polled-busy-pages-and-self-output
  (let [{:keys [fresh busy own denied decisions]} (selection-fixture)
        records (vec (concat fresh busy own [denied]))
        material (context/assemble-context digest owner records decisions {:max-encounters 3})
        recall (decision-input/memory-query "choose the next creative direction" material)]
    (is (= (mapv :id fresh) (:event-ids material)))
    (is (= (mapv :source-id fresh) (:causal-source-ids material)))
    (is (= (mapv :source fresh) (mapv :source (:encounters material))))
    (is (= (mapv :occurred-at fresh) (mapv :occurred-at (:encounters material))))
    (is (= (mapv :admitted-at fresh) (mapv :admitted-at (:encounters material))))
    (is (= (mapv :authorization fresh) (mapv :authorization (:encounters material))))
    (is (every? (complement :self-output?) (:encounters material)))
    (doseq [record fresh]
      (is (str/includes? (:prompt-context material) (:text record)))
      (is (str/includes? recall (:text record))))
    (is (not (re-find #"DENIED|later-created self output|later-polled old page|busy Discord second" (:prompt-context material))))
    (is (not (re-find #"DENIED|later-created self output|later-polled old page|busy Discord second" recall)))
    (is (= 3 (count (:encounters material))))
    (is (<= (:character-count material) (:max-chars context/default-options)))))

(deftest latest-admitted-edit-survives-source-time-ordering-without-recalling-the-replaced-revision
  (let [{:keys [fresh busy own decisions]} (selection-fixture)
        original (second fresh)
        edited (timed-record (:source original) (:external-id original) "latest admitted Bluesky edit"
                             (:occurred-at original) "2026-10-07T22:00:00.000Z" false)
        material (context/assemble-context digest owner (vec (concat fresh busy own [edited])) decisions {:max-encounters 3})]
    (is (= [(:id (first fresh)) (:id edited) (:id (nth fresh 2))] (:event-ids material)))
    (is (= (:source-id original) (:source-id edited)))
    (is (str/includes? (:prompt-context material) "latest admitted Bluesky edit"))
    (is (not (str/includes? (:prompt-context material) (:text original))))))

(deftest self-output-is-labeled-and-uses-only-spare-count-and-character-budget
  (let [{:keys [fresh own decisions]} (selection-fixture)
        external (vec (take 2 fresh))
        records (vec (concat external own))
        material (context/assemble-context digest owner records decisions {:max-encounters 3})
        external-only (context/assemble-context digest owner records decisions {:max-encounters 3 :include-self-output? false})
        tight (context/assemble-context digest owner records decisions
                                        {:max-encounters 3 :max-chars (:character-count external-only)})
        recall (decision-input/memory-query "choose the next creative direction" material)]
    (is (= (conj (mapv :id external) (:id (last own))) (:event-ids material)))
    (is (= [false false true] (mapv :self-output? (:encounters material))))
    (is (str/includes? (:prompt-context material) ":provenance :self-output"))
    (is (str/includes? (:prompt-context material) (:text (last own))))
    (is (not (str/includes? recall "later-created self output")))
    (is (= (mapv :id external) (:event-ids external-only)))
    (is (= (:event-ids external-only) (:event-ids tight)))
    (is (= (:character-count external-only) (:character-count tight)))
    (is (<= (count (:encounters tight)) 3))))

(deftest first-round-distinguishes-account-and-visibility-within-the-same-provider-scope
  (let [private-source (assoc source :visibility :private)
        other-account (assoc private-source :account-id "another-bot")
        public-source (assoc private-source :visibility :public)
        exact-sources [private-source other-account public-source]
        records (mapv #(timed-record % "same-external-id" (str "exact source " (:account-id %) " " (:visibility %))
                                    at at false) exact-sources)
        busy (mapv #(timed-record private-source (str "busy-" %) "newer busy private source"
                                 "2026-10-07T11:00:00.000Z" "2026-10-07T12:00:00.000Z" false) (range 6))
        decisions (into {} (map #(vector (law/stream-id digest owner %) (authority owner %)) exact-sources))
        material (context/assemble-context digest owner (into records busy) decisions {:max-encounters 3})]
    (is (= (set exact-sources) (set (map :source (:encounters material)))))
    (is (= 3 (count (:encounters material))))
    (is (= private-source (get-in material [:encounters 0 :source])))))
