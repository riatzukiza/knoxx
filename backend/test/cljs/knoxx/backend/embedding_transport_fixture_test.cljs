(ns knoxx.backend.embedding-transport-fixture-test
  "The registered SDK alias must never forward any embedding request externally."
  (:require ["@open-hax/openplanner-sdk" :as sdk-mod]
            [cljs.test :refer [deftest is]]
            [knoxx.backend.extern.openplanner-sdk :as planner]
            [knoxx.backend.infra.routes.document-admission :as admission]))

(deftest ^:async unexpected-embedding-route-is-an-observable-fixture-refusal
  (let [before (count (array-seq sdk-mod/__calls))]
    (try
      (await (js/fetch "http://owned-unavailable.invalid:11434/v1/embeddings"
                       #js {:method "POST" :body "{}"}))
      (is false "the test alias must refuse before DNS or HTTP")
      (catch :default error
        (is (= "Owned SDK alias refuses external embedding transport" (.-message error)))))
    (is (= before (count (array-seq sdk-mod/__calls)))
        "an unexpected route gets no synthetic success or recorded request")))


(deftest ^:async cross-namespace-existing-source-replay-uses-owned-transport
  (sdk-mod/__setEventVectorMode "missing")
  (let [event {:schema "openplanner.event.v1" :id "owned-transport-cross-namespace-replay"
               :ts "2026-10-14T00:00:00Z" :source "knoxx-publication" :kind "docs"
               :text "complete cross-namespace source tail"}
        before (count (array-seq sdk-mod/__calls))]
    (try
      (await (planner/events! [event]))
      (let [replayed (await (admission/persist-openplanner-event! {} event))
            stored (await (planner/mongo-query {:collection "events" :filter {:id (:id event)}}))
            transport (->> (drop before (array-seq sdk-mod/__calls))
                           (filter #(= "checkedEmbeddingTransport" (aget % "name")))
                           (mapv #(js->clj (aget % "args") :keywordize-keys true)))]
        (is (true? (:existing replayed)))
        (is (= [(:id event)] (get-in replayed [:index-result :repaired-event-ids])))
        (is (= 1 (:total stored)))
        (is (= (:text event) (:text (first (:rows stored)))))
        (is (= 1 (count transport)))
        (is (= "http://127.0.0.1:11434/v1/embeddings" (:url (first transport))))
        (is (= {:model "qwen3-embedding:8b" :dimensions 1024 :input [(:text event)]}
               (:request (first transport)))))
      (finally (sdk-mod/__setEventVectorMode "valid")))))
