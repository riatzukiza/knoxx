(ns knoxx.backend.memory-sessions-cache-characterization-test
  (:require [cljs.test :refer [deftest is]]
            [knoxx.backend.extern.memory-sessions-fixture :as fixture]
            [knoxx.backend.infra.routes.memory :as routes]))

(deftest ^:async cache-expiry-is-strict-and-refetches-at-equality
  (await
   (fixture/with-cache!
    (^:async fn [_state]
      (let [calls (atom 0)
            fetch! (fn [] (fixture/resolved {:version (swap! calls inc)}))]
        (await (fixture/at! 1000 #(routes/cached-memory-sessions-source! "ttl" fetch!)))
        (let [hit (await (fixture/at! 10999 #(routes/cached-memory-sessions-source! "ttl" fetch!)))
              miss (await (fixture/at! 11000 #(routes/cached-memory-sessions-source! "ttl" fetch!)))]
          (is (= "memory" (get-in hit [:cache :tier])))
          (is (= 9999 (get-in hit [:cache :age_ms])))
          (is (= "miss" (get-in miss [:cache :tier])))
          (is (= 2 @calls))))))))

(deftest ^:async mongo-hit-promotes-to-memory-and-floors-future-age
  (await
   (fixture/with-cache!
    (^:async fn [{:keys [entries reads]}]
      (swap! entries assoc "mongo" {:value {:rows [:old]} :cached-at 1100 :expires-at 1200})
      (let [fetch! (fn [] (fixture/throw!))
            hit (await (fixture/at! 1000 #(routes/cached-memory-sessions-source! "mongo" fetch!)))
            local (await (fixture/at! 1001 #(routes/cached-memory-sessions-source! "mongo" fetch!)))]
        (is (= {:rows [:old]} (:value hit)))
        (is (= {:hit true :tier "mongo" :stale false :age_ms 0} (:cache hit)))
        (is (= "memory" (get-in local [:cache :tier])))
        (is (= ["mongo"] @reads)))))))

(deftest ^:async pending-fetches-coalesce-before-completion
  (await
   (fixture/with-cache!
    (^:async fn [_state]
      (let [calls (atom 0) hold (fixture/deferred)
            fetch! (fn [] (swap! calls inc) (:promise hold))
            one (routes/cached-memory-sessions-source! "pending" fetch!)
            two (routes/cached-memory-sessions-source! "pending" fetch!)]
        (await (fixture/tick!))
        (is (= 1 @calls))
        ((:resolve! hold) {:rows [:kept]})
        (let [results (await (fixture/all! [one two]))]
          (is (= [{:rows [:kept]} {:rows [:kept]}] (mapv :value results)))))))))

(deftest ^:async async-failed-fetch-is-removed-before-subsequent-retry
  (await
   (fixture/with-cache!
    (^:async fn [_state]
      (is (await (fixture/failure?
                  (routes/cached-memory-sessions-source! "reject" fixture/rejected))))
      (let [result (await (routes/cached-memory-sessions-source!
                          "reject" #(fixture/resolved {:rows [:retry]})))]
        (is (= {:rows [:retry]} (:value result)))
        (is (= "miss" (get-in result [:cache :tier]))))))))

(deftest ^:async synchronous-failed-fetch-does-not-poison-subsequent-retry
  (await
   (fixture/with-cache!
    (^:async fn [_state]
      (is (await (fixture/failure?
                  (routes/cached-memory-sessions-source! "throw" fixture/throw!))))
      ;; Observe failure instead of letting a rejected retry abort fixture cleanup.
      (let [result (try (await (routes/cached-memory-sessions-source!
                               "throw" #(fixture/resolved {:rows [:retry]})))
                        (catch :default _ :retry-refused))]
        (is (not= :retry-refused result))
        (is (= {:rows [:retry]} (:value result))))))))

(deftest v1-key-retains-exact-eight-field-json-order-and-sha
  (let [options {:config {:session-project-name "p"}
                 :ctx {:orgId "org" :membershipId "member" :userId "user"
                       :actorId "auth-actor"
                       :permissions ["agent.memory.cross_session"]}
                 :limit 12 :offset 0 :actor-id "actor"
                 :exclude-actor-ids ["c" "b"] :contract-id "contract"}]
    ;; Fixed UTF-8 SHA of the original eight-field JSON, not a second key builder.
    (is (= "baa158ad8aaba8f73c896fcf6350c8d6bdbe1488f95a93554944f0a31e22da3a" (routes/memory-sessions-cache-key options)))))

(deftest key-isolates-parameters-and-all-authorization-claims
  (let [base {:config {:session-project-name "knoxx"}
              :ctx {:orgId "o" :membershipId "m" :userId "u" :actorId "a"
                    :permissions ["agent.memory.cross_session"]}
              :limit 12 :offset 0 :actor-id "a"
              :exclude-actor-ids ["b" "c"] :contract-id "contract"}
        key (routes/memory-sessions-cache-key base)]
    (is (= key (routes/memory-sessions-cache-key
                (assoc base :exclude-actor-ids ["c" "b"]))))
    (doseq [changed [(assoc base :limit 13) (assoc base :offset 1)
                     (assoc-in base [:config :session-project-name] "other")
                     (assoc base :contract-id "other") (assoc base :actor-id "other")
                     (assoc-in base [:ctx :orgId] "other")
                     (assoc-in base [:ctx :membershipId] "other")
                     (assoc-in base [:ctx :userId] "other")
                     (assoc-in base [:ctx :actorId] "other")
                     (assoc-in base [:ctx :permissions] [])
                     (assoc-in base [:ctx :role-slugs] ["system_admin"])]]
      (is (not= key (routes/memory-sessions-cache-key changed))))))

