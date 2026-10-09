(ns knoxx.backend.character.encounter-admission-test
  (:require [cljs.test :refer [deftest is]]
            [knoxx.backend.character.encounter-test :as fixture]
            [knoxx.backend.infra.character.encounter-admission :as admission]
            [knoxx.backend.shape.character.encounter :as shape]))

(defn- owner-matches?
  [requested-owner event]
  (= requested-owner (or (get-in event [:extra :encounter :owner])
                         (get-in event [:extra :encounter_cursor :owner]))))

(defn- matching-stream-rows
  [rows requested-owner stream-id kind]
  (filter #(and (= kind (:kind %))
                (owner-matches? requested-owner %)
                (= stream-id (get-in % [:extra :encounter_stream_id]))) rows))

(defn- fixture-ports
  "Inject test-only existing event rows, with a single sequential test caller."
  [rows* failure* reads*]
  {:find-event! (fn [requested-owner event-id]
                  (swap! reads* inc)
                  (first (filter #(and (= event-id (:id %))
                                       (owner-matches? requested-owner %)) @rows*)))
   :append-event! (fn [event]
                    (swap! rows* conj event)
                    (when (= (:kind event) @failure*)
                      (reset! failure* nil)
                      (throw (ex-info "Injected failure after durable append"
                                      {:code :fixture/partial-write})))
                    {:ok true})
   :latest-checkpoint! (fn [requested-owner stream-id]
                         (swap! reads* inc)
                         (->> (matching-stream-rows @rows* requested-owner stream-id
                                                   "character.encounter-cursor")
                              (map #(shape/wire->checkpoint (get-in % [:extra :encounter_cursor])))
                              (sort-by :sequence)
                              last))
   :recent-encounters! (fn [requested-owner stream-id limit]
                         (swap! reads* inc)
                         (->> (matching-stream-rows @rows* requested-owner stream-id
                                                   "character.encounter")
                              (sort-by :ts)
                              reverse
                              (take limit)
                              vec))
   :with-exclusive! (fn [task!] (task!))})

(defn- ^:async attempt!
  [ports input authorize!]
  (try
    (await (admission/admit-page! ports fixture/digest fixture/owner input authorize!))
    (catch :default error
      (ex-data error))))

(deftest ^:async durable-retry-and-new-port-after-restart-do-not-append-duplicates
  (let [rows* (atom [])
        failure* (atom nil)
        reads* (atom 0)
        ports (fixture-ports rows* failure* reads*)
        input (fixture/page [(fixture/item "one" "first") (fixture/item "two" "second")])
        first-pass (await (attempt! ports input fixture/authority))
        restart-ports (fixture-ports rows* failure* reads*)
        replay (await (attempt! restart-ports input fixture/authority))]
    (is (= :admitted (:status first-pass)))
    (is (= :replayed (:status replay)))
    (is (= (:event-ids first-pass) (:event-ids replay)))
    (is (= (:cursor first-pass) (:cursor replay)))
    (is (= 3 (count @rows*)))
    (is (= 3 (count (set (map :id @rows*)))))))

(deftest ^:async failure-after-encounter-write-preserves-cursor-and-retry-reuses-row
  (let [rows* (atom [])
        failure* (atom "character.encounter")
        reads* (atom 0)
        ports (fixture-ports rows* failure* reads*)
        input (fixture/page [(fixture/item "one" "first") (fixture/item "two" "second")])
        failed (await (attempt! ports input fixture/authority))]
    (is (= :encounter/page-incomplete (:code failed)))
    (is (= :partial (get-in failed [:progress :status])))
    (is (nil? (get-in failed [:progress :cursor])))
    (is (= ["character.encounter"] (mapv :kind @rows*)))
    (let [retried (await (attempt! ports input fixture/authority))]
      (is (= :admitted (:status retried)))
      (is (= "opaque-next" (:cursor retried)))
      (is (= 3 (count @rows*))))))

(deftest ^:async failure-after-cursor-write-reloads-durable-progress-on-retry
  (let [rows* (atom [])
        failure* (atom "character.encounter-cursor")
        reads* (atom 0)
        ports (fixture-ports rows* failure* reads*)
        input (fixture/page [(fixture/item "one" "first")])
        failed (await (attempt! ports input fixture/authority))
        retried (await (attempt! ports input fixture/authority))]
    (is (= :encounter/page-incomplete (:code failed)))
    (is (nil? (get-in failed [:progress :cursor])))
    (is (= :replayed (:status retried)))
    (is (= "opaque-next" (:cursor retried)))
    (is (= 2 (count @rows*)))))

(deftest ^:async denied-private-source-is-neither-read-nor-written
  (let [rows* (atom [])
        failure* (atom nil)
        reads* (atom 0)
        ports (fixture-ports rows* failure* reads*)
        private-source (assoc fixture/source :visibility :private)
        input (assoc (fixture/page [(fixture/item "private" "restricted")])
                     :source private-source)
        deny! (fn [_owner _source] {:allowed? false})
        failed (await (attempt! ports input deny!))
        recalled (await (admission/load-context! ports fixture/digest fixture/owner
                                                [private-source] deny! {}))]
    (is (= :encounter/source-denied (:code failed)))
    (is (empty? @rows*))
    (is (zero? @reads*))
    (is (= "" (:prompt-context recalled)))))

(deftest ^:async durable-reload-of-edited-input-changes-later-prompt-context
  (let [rows* (atom [])
        failure* (atom nil)
        reads* (atom 0)
        ports (fixture-ports rows* failure* reads*)
        first-page (fixture/page [(fixture/item "same-source" "old source observation")])]
    (await (attempt! ports first-page fixture/authority))
    (let [before (await (admission/load-context! ports fixture/digest fixture/owner
                                               [fixture/source] fixture/authority {}))
          next-page (assoc (fixture/page [(fixture/item "same-source" "changed source observation")])
                           :cursor-before "opaque-next" :cursor-after "opaque-second"
                           :observed-at "2026-10-07T10:01:00.000Z")]
      (await (attempt! ports next-page fixture/authority))
      (let [after (await (admission/load-context!
                         (fixture-ports rows* failure* reads*) fixture/digest fixture/owner
                         [fixture/source] fixture/authority {}))]
        (is (not= (:prompt-context before) (:prompt-context after)))
        (is (= (:causal-source-ids before) (:causal-source-ids after)))
        (is (= 1 (count (:encounters after))))
        (is (re-find #"changed source observation" (:prompt-context after)))))))

(deftest ^:async revoked-source-is-not-reloaded-from-existing-events
  (let [rows* (atom [])
        failure* (atom nil)
        reads* (atom 0)
        ports (fixture-ports rows* failure* reads*)]
    (await (attempt! ports (fixture/page [(fixture/item "one" "retained source")])
                    fixture/authority))
    (reset! reads* 0)
    (let [context (await (admission/load-context! ports fixture/digest fixture/owner
                                                [fixture/source]
                                                (fn [_owner _source] {:allowed? false}) {}))]
      (is (zero? @reads*))
      (is (= "" (:prompt-context context)))
      (is (empty? (:causal-source-ids context))))))

(deftest ^:async revocation-during-checkpoint-read-prevents-content-and-cursor-appends
  (let [rows* (atom []) allowed* (atom true)
        ports (fixture-ports rows* (atom nil) (atom 0))
        latest! (:latest-checkpoint! ports)
        held (assoc ports :latest-checkpoint!
                    (^:async fn [owner stream]
                      (let [checkpoint (latest! owner stream)]
                        (await (js/Promise.resolve nil))
                        (reset! allowed* false)
                        checkpoint)))
        authorize! (fn [owner source]
                     (if @allowed* (fixture/authority owner source) {:allowed? false}))
        result (await (attempt! held (fixture/page [(fixture/item "revoked" "private source")]) authorize!))]
    (is (= :encounter/source-denied (:code result)))
    (is (empty? @rows*) "No content or cursor write is allowed after the checkpoint await revokes access")))

(deftest ^:async revocation-during-record-lookup-prevents-the-following-append
  (let [rows* (atom []) allowed* (atom true)
        ports (fixture-ports rows* (atom nil) (atom 0))
        find! (:find-event! ports)
        held (assoc ports :find-event!
                    (^:async fn [owner id]
                      (let [record (find! owner id)]
                        (await (js/Promise.resolve nil))
                        (reset! allowed* false)
                        record)))
        authorize! (fn [owner source]
                     (if @allowed* (fixture/authority owner source) {:allowed? false}))
        result (await (attempt! held (fixture/page [(fixture/item "revoked" "private source")]) authorize!))]
    (is (= :encounter/page-incomplete (:code result)))
    (is (= :partial (get-in result [:progress :status])))
    (is (nil? (get-in result [:progress :checkpoint-id])))
    (is (empty? @rows*) "A previously allowed decision cannot authorize the later append")))

(deftest ^:async revocation-after-record-readback-preserves-row-without-advancing-cursor
  (let [rows* (atom []) allowed* (atom true)
        ports (fixture-ports rows* (atom nil) (atom 0))
        find! (:find-event! ports)
        held (assoc ports :find-event!
                    (^:async fn [owner id]
                      (let [record (find! owner id)]
                        (when (= "character.encounter" (:kind record))
                          (await (js/Promise.resolve nil))
                          (reset! allowed* false))
                        record)))
        authorize! (fn [owner source]
                     (if @allowed* (fixture/authority owner source) {:allowed? false}))
        input (fixture/page [(fixture/item "retained" "durable evidence")])
        result (await (attempt! held input authorize!))]
    (is (= :encounter/page-incomplete (:code result)))
    (is (= :partial (get-in result [:progress :status])))
    (is (nil? (get-in result [:progress :cursor])))
    (is (= ["character.encounter"] (mapv :kind @rows*))
        "The durable record remains, with no cursor event appended after its readback revoked access")
    (reset! allowed* true)
    (let [replayed (await (attempt! ports input authorize!))]
      (is (= :admitted (:status replayed)))
      (is (= 2 (count @rows*)) "Authorized retry reuses the retained content row"))))

(deftest ^:async earlier-source-revoked-during-later-read-is-excluded-before-final-budget
  (let [rows* (atom []) allowed-a* (atom true)
        source-b (assoc fixture/source :scope-id "second-channel")
        ports (fixture-ports rows* (atom nil) (atom 0))
        recent! (:recent-encounters! ports)
        held (assoc ports :recent-encounters!
                    (^:async fn [owner stream limit]
                      (let [rows (recent! owner stream limit)]
                        (when (some #(= "second-channel" (get-in % [:extra :source_scope_id])) rows)
                          (await (js/Promise.resolve nil))
                          (reset! allowed-a* false))
                        rows)))
        authorize! (fn [owner source]
                     (if (and (= fixture/source source) (not @allowed-a*))
                       {:allowed? false}
                       (fixture/authority owner source)))]
    (await (attempt! ports (fixture/page [(fixture/item "source-a" "REVOKED PRIVATE SOURCE A")]) fixture/authority))
    (await (attempt! ports (assoc (fixture/page [(fixture/item "source-b" "PERMITTED SOURCE B")])
                                  :source source-b) fixture/authority))
    (let [loaded (await (admission/load-context! held fixture/digest fixture/owner
                                                [fixture/source source-b] authorize! {:max-encounters 1}))]
      (is (false? @allowed-a*) "The later source read actually revoked the earlier source")
      (is (= [source-b] (mapv :source (:encounters loaded))))
      (is (not (re-find #"REVOKED PRIVATE SOURCE A" (:prompt-context loaded))))
      (is (re-find #"PERMITTED SOURCE B" (:prompt-context loaded))
          "Revoked candidates cannot consume the final one-encounter budget"))))

(deftest ^:async revocation-during-content-append-refuses-further-readback-and-progress
  (let [rows* (atom []) allowed* (atom true) reads-after-denial* (atom 0)
        ports (fixture-ports rows* (atom nil) (atom 0))
        append! (:append-event! ports) find! (:find-event! ports)
        held (assoc ports
                    :append-event! (^:async fn [event]
                                     (append! event)
                                     (await (js/Promise.resolve nil))
                                     (reset! allowed* false)
                                     {:ok true})
                    :find-event! (fn [owner id]
                                   (when-not @allowed* (swap! reads-after-denial* inc))
                                   (find! owner id)))
        authorize! (fn [owner source]
                     (if @allowed* (fixture/authority owner source) {:allowed? false}))
        result (await (attempt! held (fixture/page [(fixture/item "written" "retained partial fact")]) authorize!))]
    (is (= :encounter/page-incomplete (:code result)))
    (is (nil? (get-in result [:progress :checkpoint-id])))
    (is (= ["character.encounter"] (mapv :kind @rows*)))
    (is (zero? @reads-after-denial*) "No private readback is started after the content append revokes authority")))

(deftest ^:async revocation-during-cursor-lookup-refuses-cursor-append
  (let [rows* (atom []) allowed* (atom true)
        ports (fixture-ports rows* (atom nil) (atom 0))
        find! (:find-event! ports)
        input (fixture/page [(fixture/item "written" "retained partial fact")])
        cursor-id (get-in (fixture/prepared (:items input)) [:checkpoint :id])
        held (assoc ports :find-event!
                    (^:async fn [owner id]
                      (let [event (find! owner id)]
                        (when (= id cursor-id)
                          (await (js/Promise.resolve nil))
                          (reset! allowed* false))
                        event)))
        authorize! (fn [owner source]
                     (if @allowed* (fixture/authority owner source) {:allowed? false}))
        result (await (attempt! held input authorize!))]
    (is (= :encounter/page-incomplete (:code result)))
    (is (= 1 (count (get-in result [:progress :event-ids])))
        "The already confirmed content identity remains in partial progress")
    (is (nil? (get-in result [:progress :cursor])))
    (is (= ["character.encounter"] (mapv :kind @rows*)))))
