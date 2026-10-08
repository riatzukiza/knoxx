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
