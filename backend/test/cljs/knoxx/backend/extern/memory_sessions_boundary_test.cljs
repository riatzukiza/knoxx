(ns knoxx.backend.extern.memory-sessions-boundary-test
  "Post-extraction native codec and explicit degraded-outcome controls."
  (:require [cljs.test :refer [deftest is]]
            [knoxx.backend.domain.error-observatory :as observatory]
            [knoxx.backend.domain.memory-sessions-cache :as cache-data]
            [knoxx.backend.infra.memory-sessions.cache :as cache]
            [knoxx.backend.extern.memory-sessions :as runtime]
            [knoxx.backend.extern.memory-sessions-fixture :as fixture]
            [knoxx.backend.infra.routes.memory :as routes]
            [knoxx.backend.infra.stores.mongo-memory-sessions :as mongo]))

(deftest ^:async ordered-native-results-retain-deep-keywordization
  (let [one (js/Promise.resolve #js {:nested #js {:value 1}})
        two (js/Promise.resolve #js {:nested #js {:value 2}})]
    (is (= [{:nested {:value 1}} {:nested {:value 2}}]
           (await (runtime/all-results! [one two]))))))

(deftest cache-codec-retains-original-v1-eight-field-wire
  (let [data {:v 1 :project "p" :limit 12 :offset 0 :actor-id "actor"
              :exclude-actor-ids '("b" "c") :contract-id "contract"
              :auth {:system-admin? false :cross-session? true
                     :org-id "org" :membership-id "member"
                     :user-id "user" :actor-id "auth-actor"}}]
    (is (= "{\"v\":1,\"project\":\"p\",\"limit\":12,\"offset\":0,\"actor-id\":\"actor\",\"exclude-actor-ids\":[\"b\",\"c\"],\"contract-id\":\"contract\",\"auth\":{\"system-admin?\":false,\"cross-session?\":true,\"org-id\":\"org\",\"membership-id\":\"member\",\"user-id\":\"user\",\"actor-id\":\"auth-actor\"}}" (.stringify js/JSON (clj->js data))))
    (is (= "baa158ad8aaba8f73c896fcf6350c8d6bdbe1488f95a93554944f0a31e22da3a" (runtime/cache-key data)))))

(defn- warning-recorder
  [seen]
  (fn [boundary context]
    (swap! seen conj {:boundary boundary :context context})
    {:ok false :failed false}))

(defn- safe-warning?
  [boundary fallback row]
  (= {:boundary boundary :context {:fallback fallback :error? true}} row))

(defn- rejecting-mongo-read
  [calls]
  (fn
    ([key] (swap! calls conj [:read key]) (fixture/rejected))
    ([_db key] (swap! calls conj [:read key]) (fixture/rejected))))

(defn- rejecting-mongo-write
  [calls]
  (fn
    ([key _entry] (swap! calls conj [:write key]) (fixture/rejected))
    ([_db key _entry] (swap! calls conj [:write key]) (fixture/rejected))))

(deftest ^:async unavailable-mongo-read-and-write-emit-safe-observations
  (let [seen (atom []) calls (atom [])]
    (with-redefs [observatory/log-warning! (warning-recorder seen)
                  mongo/get-cache-entry! (rejecting-mongo-read calls)
                  mongo/set-cache-entry! (rejecting-mongo-write calls)]
      (is (nil? (await (routes/mongo-memory-sessions-hit! "opaque"))))
      (is (nil? (await (routes/write-memory-sessions-cache-to-mongo! "opaque" {}))))
      (is (= [[:read "opaque"] [:write "opaque"]] @calls))
      (is (= 2 (count @seen)))
      (is (safe-warning? "memory-sessions/mongo-read" :miss (first @seen)))
      (is (safe-warning? "memory-sessions/mongo-write" :miss (second @seen))))))

(deftest ^:async unavailable-visibility-remains-excluded-with-safe-observations
  (let [seen (atom []) fetch! (fn [& _] (fixture/rejected))]
    (with-redefs [observatory/log-warning! (warning-recorder seen)]
      (is (= {:row {:session "s"} :visible false}
             (await (routes/filter-page-actor-row!
                     {} fetch! (constantly true) "a" [] nil {:session "s"}))))
      (is (= {:session "s" :visible false}
             (await (routes/search-hit-session-visibility!
                     {} fetch! (constantly true) "a" [] "s"))))
      (is (= 2 (count @seen)))
      (is (safe-warning? "memory-sessions/page-visibility" :excluded (first @seen)))
      (is (safe-warning? "memory-sessions/search-visibility" :excluded (second @seen))))))

(deftest ^:async clock-boundary-reads-the-controlled-value
  (await (fixture/at! 123456
            (^:async fn [] (is (= 123456 (runtime/now-ms)))))))

(deftest pure-cache-retention-preserves-cap-expiry-and-tie-order
  (let [entries (into {} (map (fn [n] [n {:cached-at n :expires-at 1000}])
                              (range 257)))
        tied {:a {:cached-at 1 :expires-at 2} :b {:cached-at 1 :expires-at 2}}]
    (is (= (set (range 1 257)) (set (keys (cache-data/retained-entries entries 0 256)))))
    (is (= {:b {:cached-at 1 :expires-at 2}} (cache-data/retained-entries tied 0 1)))
    (is (= {} (cache-data/retained-entries tied 2 256)))))

(deftest ^:async broken-observer-cannot-replace-any-primary-fallback
  (let [attempts (atom 0) calls (atom []) fetch! (fn [& _] (fixture/rejected))]
    (with-redefs [observatory/log-warning! (fn [& _] (swap! attempts inc) (fixture/throw!))
                  mongo/get-cache-entry! (rejecting-mongo-read calls)
                  mongo/set-cache-entry! (rejecting-mongo-write calls)]
      (is (nil? (await (routes/mongo-memory-sessions-hit! "opaque"))))
      (is (nil? (await (routes/write-memory-sessions-cache-to-mongo! "opaque" {}))))
      (is (= {:row {:session "s"} :visible false}
             (await (routes/filter-page-actor-row!
                     {} fetch! (constantly true) "a" [] nil {:session "s"}))))
      (is (= {:session "s" :visible false}
             (await (routes/search-hit-session-visibility!
                     {} fetch! (constantly true) "a" [] "s"))))
      (is (= [[:read "opaque"] [:write "opaque"]] @calls))
      (is (= 4 @attempts)))))

(deftest observer-failure-returns-only-explicit-safe-diagnostic-data
  (with-redefs [observatory/log-warning! (fn [& _] (fixture/throw!))]
    (is (= {:observed? false :observation-unavailable? true
            :boundary "memory-sessions/test" :context {:fallback :miss :error? true}
            :observer-error? true}
           (runtime/degraded! "memory-sessions/test" :miss (js/Error. "not logged"))))))

(deftest ^:async original-callable-surface-and-service-share-one-cache
  (await
   (fixture/with-cache!
    (^:async fn [_state]
      (let [calls (atom 0)
            fetch! (fn [] (swap! calls inc) (fixture/resolved {:rows [:same]}))]
        (await (routes/cached-memory-sessions-source! "shared" fetch!))
        (let [hit (await (cache/cached-memory-sessions-source! "shared" fetch!))]
          (is (= 1 @calls))
          (is (= {:rows [:same]} (:value hit)))
          (is (= "memory" (get-in hit [:cache :tier])))))))))

(deftest nil-thrown-by-observer-is-still-unavailable
  (with-redefs [observatory/log-warning! (fn [& _] (fixture/throw-nil!))]
    (is (= {:observed? false :observation-unavailable? true
            :boundary "memory-sessions/test" :context {:fallback :miss :error? false}
            :observer-error? false}
           (runtime/degraded! "memory-sessions/test" :miss nil)))))

