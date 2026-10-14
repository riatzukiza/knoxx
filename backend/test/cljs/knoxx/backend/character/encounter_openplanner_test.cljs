(ns knoxx.backend.character.encounter-openplanner-test
  (:require [cljs.test :refer [deftest is]]
            [knoxx.backend.character.encounter-test :as fixture]
            [knoxx.backend.infra.character.encounter-admission :as admission]
            [knoxx.backend.infra.character.encounter-openplanner :as storage]
            [knoxx.backend.infra.clients.openplanner :as openplanner]))

(deftest ^:async adapter-queries-existing-events-with-owner-and-stream-pins
  (let [queries* (atom [])
        row (first (:events (fixture/prepared [(fixture/item "one" "retained")])))]
    (with-redefs [openplanner/assert-event-projection-repair-supported! (fn [_client] nil)
                  openplanner/mongo-query! (fn [_client query]
                                            (swap! queries* conj query)
                                            {:ok true :rows [row]})]
      (let [ports (storage/openplanner-ports :fixture-client)
            existing (await ((:find-event! ports) fixture/owner (:id row)))]
        (is (= row existing))
        (is (= {:collection "events" :limit 2
                :filter {:id (:id row) :extra.org_id "org-local"
                         :extra.project "creator-local" :extra.character_id "creator"}}
               (first @queries*)))))))

(deftest ^:async duplicate-durable-ids-fail-visibly-instead-of-claiming-exactly-once
  (let [row (first (:events (fixture/prepared [(fixture/item "one" "retained")])))]
    (with-redefs [openplanner/assert-event-projection-repair-supported! (fn [_client] nil)
                  openplanner/mongo-query! (fn [_client _query] {:ok true :rows [row row]})]
      (let [ports (storage/openplanner-ports :fixture-client)
            error-data (try
                         (await ((:find-event! ports) fixture/owner (:id row)))
                         nil
                         (catch :default error (ex-data error)))]
        (is (= :encounter/duplicate-durable-id (:code error-data)))))))

(deftest ^:async checkpoint-sequence-ties-expose-concurrent-writers
  (let [prepared (fixture/prepared [(fixture/item "one" "retained")])
        row (:checkpoint-event prepared)]
    (with-redefs [openplanner/assert-event-projection-repair-supported! (fn [_client] nil)
                  openplanner/mongo-query! (fn [_client _query] {:ok true :rows [row row]})]
      (let [ports (storage/openplanner-ports :fixture-client)
            error-data (try
                         (await ((:latest-checkpoint! ports) fixture/owner (:stream-id prepared)))
                         nil
                         (catch :default error (ex-data error)))]
        (is (= :encounter/cursor-writer-conflict (:code error-data)))))))

(deftest ^:async failed-task-does-not-poison-the-owned-writer
  (with-redefs [openplanner/assert-event-projection-repair-supported! (fn [_client] nil)]
    (let [ports (storage/openplanner-ports :fixture-client)
          failed (try
                   (await ((:with-exclusive! ports)
                           (fn [] (throw (ex-info "Injected fixture failure" {:fixture true})))))
                   nil
                   (catch :default error (ex-data error)))
          next-result (await ((:with-exclusive! ports) (fn [] :recovered)))]
      (is (:fixture failed))
      (is (= :recovered next-result)))))

(deftest ^:async unconfirmed-append-does-not-mint-cursor-progress
  (with-redefs [openplanner/assert-event-projection-repair-supported! (fn [_client] nil)
                openplanner/mongo-query! (fn [_client _query] {:ok true :rows []})
                openplanner/events! (fn [_client _events] {:ok true})]
    (let [ports (storage/openplanner-ports :fixture-client)
          error-data (try
                       (await (admission/admit-page!
                               ports fixture/digest fixture/owner
                               (fixture/page [(fixture/item "one" "unconfirmed")]) fixture/authority))
                       nil
                       (catch :default error (ex-data error)))]
      (is (= :encounter/page-incomplete (:code error-data)))
      (is (= :partial (get-in error-data [:progress :status])))
      (is (nil? (get-in error-data [:progress :cursor]))))))
