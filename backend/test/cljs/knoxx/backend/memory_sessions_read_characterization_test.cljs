(ns knoxx.backend.memory-sessions-read-characterization-test
  (:require [cljs.test :refer [deftest is]]
            [knoxx.backend.extern.memory-sessions-fixture :as fixture]
            [knoxx.backend.extern.openplanner-history-fixture :as history]
            [knoxx.backend.infra.clients.openplanner :as client]
            [knoxx.backend.infra.routes.memory :as routes]))

(defn- rows
  [actor contract]
  [{:extra (fixture/extra {:actor_id actor :contract_id contract})}])

(defn- allowed!
  [_config _ctx ids]
  (fixture/resolved (set (map str ids))))

(deftest ^:async empty-upstream-page-terminates-despite-has-more
  (let [calls (atom [])
        read! (fn [_ opts]
                (swap! calls conj (:offset opts))
                (fixture/resolved {:rows [] :has_more true}))
        result (await (routes/fetch-authorized-session-pages!
                       {:openplanner-client (history/sessions-client read!)}
                       {} nil [] nil allowed! (fn [& _] (fixture/throw!))
                       (constantly true) 10 0 [] 3))]
    (is (= [0] @calls))
    (is (= {:rows [] :has_more false} result))))

(deftest ^:async paging-advances-by-fetched-not-authorized-count
  (let [offsets (atom [])
        pages {0 {:rows [{:session "s1"} {:session "denied"} {:session "translation-x"}]
                  :has_more true}
               3 {:rows [{:session "s2"}] :has_more false}}
        read! (fn [_ opts] (swap! offsets conj (:offset opts))
                (fixture/resolved (get pages (:offset opts))))
        auth! (fn [_ _ ids] (fixture/resolved (disj (set ids) "denied")))
        result (await (routes/fetch-authorized-session-pages!
                       {:openplanner-client (history/sessions-client read!)}
                       {} nil [] nil auth! (fn [& _] (fixture/throw!))
                       (constantly true) 3 0 [] 2))]
    (is (= [0 3] @offsets))
    (is (= ["s1" "s2"] (mapv :session (:rows result))))
    (is (true? (:has_more result)))))

(deftest ^:async visibility-denial-and-failure-preserve-surviving-hit-order
  (let [seen (atom [])
        fetch! (fn [_ id]
                 (swap! seen conj id)
                 (if (= id "bad") (fixture/rejected)
                     (fixture/resolved (rows "actor" nil))))
        hits [{:session "s1" :rank 1} {:session "bad" :rank 2}
              {:metadata {:session "s1"} :rank 3} {:rank 4}]
        result (await (routes/filter-search-hits-by-actor!
                       {} fetch! (constantly true) "actor" [] hits))]
    (is (= ["s1" "bad"] @seen))
    (is (= [1 3] (mapv :rank result)))))

(deftest ^:async contract-mongo-query-and-first-seen-session-order-remain-fixed
  (let [queries (atom [])
        source [{:session "s2" :project "p" :ts 3 :extra (fixture/extra {:contract_id "c"})}
                {:session "s1" :project "p" :ts 2 :extra (fixture/extra {:contract_id "c"})}
                {:session "s2" :project "p" :ts 1 :extra (fixture/extra {:contract_id "c"})}]]
    (with-redefs [client/mongo-query! (fn [_ query]
                                      (swap! queries conj query)
                                      (fixture/resolved {:rows source :total 3}))]
      (let [result (await (routes/fetch-contract-session-pages-from-mongo!
                           {:session-project-name "p"} "c" 1))
            query (first @queries)]
        (is (= 1 (count @queries)))
        (is (= 500 (:limit query)))
        (is (= {"ts" -1} (:sort query)))
        (is (= {:collection "events"
                :filter {"project" "p"
                         "session" {"$type" "string" "$ne" ""}
                         "extra.contract_id" "c"}
                :projection {"_id" 0 "project" 1 "session" 1 "ts" 1 "extra" 1}
                :sort {"ts" -1} :limit 500}
               query))
        (is (= "c" (get-in query [:filter "extra.contract_id"])))
        (is (= ["s2"] (mapv :session (:rows result))))
        (is (= 2 (-> result :rows first :event_count)))
        (is (true? (:has_more result)))))))

(deftest ^:async active-overlay-filters-contract-and-exclusions-before-prepending
  (let [sessions {"a" {:conversation_id "live" :status "running" :run_id "r"
                       :agent_spec {:actor_id "allowed" :contract_id "c"}}
                  "b" {:conversation_id "hidden" :status "waiting_input"
                       :agent_spec {:actor_id "excluded" :contract_id "c"}}
                  "c" {:conversation_id "old" :status "running"
                       :agent_spec {:actor_id "allowed" :contract_id "c"}}}]
    (await
     (fixture/with-active!
      sessions {"live" "a" "old" "c"}
      (^:async fn [env responses failures]
        (await (routes/send-memory-sessions-live-ids!
                env {:page-rows [{:session "old"}] :actor-id nil
                     :exclude-actor-ids ["excluded"] :contract-id "c"
                     :offset 0 :limit 12 :visible-total 1 :page-has-more false}
                ["a" "b" "c"]))
        (is (empty? @failures))
        (is (= [200] (mapv :status @responses)))
        (is (= ["live" "old"] (mapv :session (-> @responses first :body :rows))))
        (is (true? (-> @responses first :body :rows first :is_active))))))))

