(ns knoxx.backend.memory-recall-budget-test
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [knoxx.backend.domain.graph.expansion-policy :as expansion-policy]
            [knoxx.backend.domain.graph.policy-registry :as policy-registry]
            [knoxx.backend.extern.tools :as tools-boundary]
            [knoxx.backend.infra.agent.hydration :as hydration]
            [knoxx.backend.infra.clients.openplanner :as client]
            [knoxx.backend.infra.core-memory :as core-memory]
            [knoxx.backend.infra.openplanner.memory :as memory]
            [knoxx.backend.infra.openplanner.tools :as tools]
            [knoxx.backend.infra.routes.memory :as routes]))

(def ^:private config {:session-project-name "recall-fixture"})

(def ^:private context
  {:orgId "allowed-org" :membershipId "allowed-member" :userId "allowed-user"
   :actorId "creator" :permissions ["agent.memory.read"]
   :toolPolicies [{:toolId "memory_search" :effect "allow"}]})

(defn- hit
  [id session text]
  {:id id :document text :metadata {:session session :role "assistant"} :distance 0.1})

(defn- vector-response
  [hits]
  {:result {:ids [(mapv :id hits)]
            :documents [(mapv :document hits)]
            :metadatas [(mapv :metadata hits)]
            :distances [(mapv :distance hits)]}})

(defn- session-rows
  [session]
  [{:role "assistant"
    :extra {:org_id (if (str/starts-with? session "denied") "other-org" "allowed-org")
            :membership_id "allowed-member"
            :actor_id (if (= "wrong-actor" session) "other-actor" "creator")}}])

(defn- ^:async with-fixture-search!
  [hits requests* task!]
  (with-redefs [client/client (fn
                               ([_config] :fixture-client)
                               ([_config _options] :fixture-client))
                client/enabled? (fn [_client] true)
                client/vector-search! (fn [_client payload]
                                        (swap! requests* conj payload)
                                        (vector-response (vec (take (:k payload) hits))))
                client/session! (fn [_client session _options] {:rows (session-rows session)})
                policy-registry/get-policy (fn
                                             ([] (expansion-policy/default-expansion-policy))
                                             ([_policy-name] (expansion-policy/default-expansion-policy)))]
    (await (task!))))

(defn- leading-denied-hits
  []
  [(hit "denied-one" "denied-one" "DENIED FIRST PRIVATE TEXT")
   (hit "denied-two" "denied-two" "DENIED SECOND PRIVATE TEXT")
   (hit "allowed-one" "allowed-one" "first authorized memory")
   (hit "allowed-two" "allowed-two" "second authorized memory")
   (hit "allowed-three" "allowed-three" "third authorized memory")])

(defn- ^:async explicit-tool-result!
  [query k]
  (let [execute (tools/make-memory-search-execute context)
        tool (tools-boundary/registered-tool
              (tools-boundary/tool-definition
               {:name "memory_search" :parameters-schema tools/memory-search-params
                :runtime nil :config config
                :execute (fn [runtime config id params signal update!]
                           (execute runtime config id params signal update! nil))}))]
    (await ((:execute tool) "recall-fixture-call" {:query query :k k} nil nil))))

(defn- ^:async api-result!
  [k actor-id actor-reads*]
  (let [result* (atom nil)]
    (await (routes/send-memory-search!
            {:config config :ctx context :reply nil
             :json-response! (fn [_reply status result]
                               (is (= 200 status))
                               (reset! result* result))}
            (fn [_config session]
              (swap! actor-reads* conj session)
              (session-rows session))
            core-memory/session-matches-page-actor-filter?
            {:query "remember prior work" :bounded-k k :session-id ""
             :actor-id actor-id :exclude-actor-ids []}))
    @result*))

(deftest ^:async internal-candidates-defer-final-budget-without-forwarding-control-flags
  (let [requests* (atom [])]
    (await (with-fixture-search!
            (leading-denied-hits) requests*
            (^:async fn []
              (let [legacy (await (memory/openplanner-memory-search! config {:query "remember" :k 2}))
                    candidates (await (memory/openplanner-memory-search!
                                       config {:query "remember" :k 2 :defer-limit? true}))]
                (is (= ["denied-one" "denied-two"] (mapv :id (:hits legacy))))
                (is (= 5 (count (:hits candidates))))
                (is (= [{:q "remember" :k 6 :source "knoxx" :project "recall-fixture"}
                        {:q "remember" :k 6 :source "knoxx" :project "recall-fixture"}]
                       @requests*))))))))

(deftest ^:async passive-hydration-authorizes-before-final-budget-and-prompt-assembly
  (await (with-fixture-search!
          (leading-denied-hits) (atom [])
          (^:async fn []
            (let [result (await (hydration/passive-memory-hydration!
                                 config "fixture-conversation" "remember prior work" context
                                 {:memory-hydration {:enabled? true :mode :always :k 2}}))
                  prompt (hydration/build-agent-user-message "remember prior work" nil result)]
              (is (= ["allowed-one" "allowed-two"] (mapv :id (:hits result))))
              (is (= 2 (count (:hits result))))
              (is (str/includes? prompt "first authorized memory"))
              (is (not (str/includes? prompt "DENIED"))))))))

(deftest ^:async explicit-tool-authorizes-before-final-budget-and-serialization
  (await (with-fixture-search!
          (leading-denied-hits) (atom [])
          (^:async fn []
            (let [result (await (explicit-tool-result! "remember prior work" 2))]
              (is (= ["allowed-one" "allowed-two"] (mapv :id (get-in result [:details :hits]))))
              (is (not (str/includes? (pr-str result) "DENIED"))))))))

(deftest ^:async api-authorizes-and-applies-actor-filter-before-final-budget
  (let [hits (into (subvec (leading-denied-hits) 0 2)
                   (cons (hit "wrong-actor" "wrong-actor" "ACTOR EXCLUDED TEXT")
                         (subvec (leading-denied-hits) 2)))
        actor-reads* (atom [])]
    (await (with-fixture-search!
            hits (atom [])
            (^:async fn []
              (let [result (await (api-result! 2 "creator" actor-reads*))]
                (is (= ["allowed-one" "allowed-two"] (mapv :id (:hits result))))
                (is (not-any? #(str/starts-with? % "denied") @actor-reads*))
                (is (not (str/includes? (pr-str result) "DENIED")))
                (is (not (str/includes? (pr-str result) "ACTOR EXCLUDED")))))))))

(deftest ^:async all-denied-hydration-tool-and-api-return-truthful-empty-context
  (await (with-fixture-search!
          (subvec (leading-denied-hits) 0 2) (atom [])
          (^:async fn []
            (let [passive (await (hydration/passive-memory-hydration!
                                  config "fixture-conversation" "remember prior work" context
                                  {:memory-hydration {:enabled? true :mode :always :k 2}}))
                  explicit (await (explicit-tool-result! "remember prior work" 2))
                  api (await (api-result! 2 nil (atom [])))
                  prompt (hydration/build-agent-user-message "remember prior work" nil passive)]
              (is (zero? (count (:hits passive))))
              (is (= [] (get-in explicit [:details :hits])))
              (is (= [] (:hits api)))
              (is (= "User request:\nremember prior work" prompt))
              (is (not (str/includes? (pr-str [passive explicit api]) "DENIED"))))))))

(deftest ^:async deferred-candidates-retain-quality-reasoning-and-provider-error-exclusions
  (let [hits [(hit "plain-first" "allowed" "unlabeled authorized memory")
              (assoc-in (hit "bad" "allowed" "BAD LABEL TEXT") [:metadata :quality_label] "bad")
              (assoc-in (hit "reasoning" "allowed" "REASONING TEXT") [:metadata :role] "reasoning")
              (hit "provider-error" "allowed" "403 No upstream providers are allowed")
              (assoc-in (hit "denied-good" "denied-good" "DENIED GOOD TEXT") [:metadata :quality_label] "good")
              (assoc-in (hit "good-first" "allowed" "good authorized memory") [:metadata :quality_label] "good")
              (assoc-in (hit "good-second" "allowed" "second good authorized memory") [:metadata :quality_label] "good")
              (hit "plain-second" "allowed" "second unlabeled authorized memory")]]
    (await (with-fixture-search!
            hits (atom [])
            (^:async fn []
              (let [candidates (await (memory/openplanner-memory-search!
                                       config {:query "remember" :k 3 :defer-limit? true}))
                    authorized (await (core-memory/filter-authorized-memory-hits! config context (:hits candidates)))
                    result (memory/limit-authorized-memory-result candidates authorized 3)]
                (is (= ["denied-good" "good-first" "good-second" "plain-first" "plain-second"]
                       (mapv :id (:hits candidates))))
                (is (= ["good-first" "good-second" "plain-first"] (mapv :id (:hits result))))
                (is (not (re-find #"BAD LABEL|REASONING|providers are allowed|DENIED" (pr-str result))))))))))

(deftest ^:async fetched-candidate-and-final-recall-budgets-stay-bounded
  (let [hits (mapv #(hit (str "allowed-" %) "allowed" (str "authorized memory " %)) (range 20))
        requests* (atom [])]
    (await (with-fixture-search!
            hits requests*
            (^:async fn []
              (let [candidates (await (memory/openplanner-memory-search!
                                       config {:query "remember" :k 2 :defer-limit? true}))
                    authorized (await (core-memory/filter-authorized-memory-hits! config context (:hits candidates)))
                    result (memory/limit-authorized-memory-result candidates authorized 2)]
                (is (= 6 (:k (first @requests*))))
                (is (= 6 (count (:hits candidates))))
                (is (= 2 (count (:hits result))))
                (is (= ["allowed-0" "allowed-1"] (mapv :id (:hits result))))))))))
