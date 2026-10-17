(ns knoxx.backend.infra.discord-gateway-observation-test
  "Genuine observation RED: all namespaces/API paths already exist on original789."
  (:require [cljs.test :refer-macros [deftest is async]]
            [knoxx.backend.extern.discord-gateway-fixture :as fixture]
            [knoxx.backend.extern.discord-gateway-observation-fixture :as boundaries]))

(defn- ^:async finish! [done promise]
  (try (await promise)
    (catch :default error (is false (str "Gateway test failed: " error)))
    (finally (done))))
(def event-operations #{:member-role-ids :message-timestamp :logger-call :status-ready :status-user-id :status-user-tag :status-guild-count})
(def voice-operations #{:voice-user-stream-destroy :voice-user-decoder-destroy :voice-stop-stream-destroy :voice-stop-decoder-destroy :voice-connection-destroy})
(defn- assert-observations! [result expected]
  (let [rows (:warnings result)]
    (is (= (count expected) (count rows)) "One actual observation for each independently triggered original catch")
    (is (= expected (set (map #(get-in % [1 :operation]) rows))))
    (is (every? #(= "discord-gateway.degraded" (first %)) rows))
    (is (every? #(= #{:operation :fallback :error?} (set (keys (second %)))) rows))
    (is (every? #(and (keyword? (get-in % [1 :fallback])) (true? (get-in % [1 :error?]))) rows))))

(deftest all-fifteen-existing-catches-emit-only-fixed-sanitized-context
  (async done
    (finish! done
      ((^:async fn []
        (await (fixture/with-world {}
          (^:async fn [world]
            (let [result (await (boundaries/event-sites! world))]
              (is (= 5 (count (:positive-throws result))))
              (is (= 1 (count (:logger-calls result))))
              (is (= [] (get-in result [:mapped :authorRoleIds])))
              (is (= false (get-in result [:status :ready])))
              (assert-observations! result event-operations)))))
        (let [error (js/Error. "inert-login-private-error")]
          (await (fixture/with-world {:login-error error :destroy-error? true}
            (^:async fn [world]
              (let [result (await (boundaries/login-site! world error))]
                (is (:same-original-error result)) (is (= 1 (:destroy-count result)))
                (assert-observations! result #{:failed-login-destroy}))))))
        (await (fixture/with-world {:destroy-error? true}
          (^:async fn [world]
            (let [result (await (boundaries/stop-site! world))]
              (is (= 1 (:destroy-count result))) (is (false? (get-in result [:status :started])))
              (assert-observations! result #{:client-destroy})))))
        (await (fixture/with-world {}
          (^:async fn [world]
            (let [result (await (boundaries/voice-sites! world))]
              (is (= [2 2 1] ((juxt :stream-destroys :decoder-destroys :connection-destroys) result)))
              (is (:connection-removed result))
              (assert-observations! result voice-operations)))))
        (await (fixture/with-world {}
          (^:async fn [world]
            (let [result (await (boundaries/inactive-site! world))]
              (is (:removed result)) (is (= 1 (:stop-count result)))
              (assert-observations! result #{:inactive-actor-stop}))))))))))

(deftest observer-error-or-nil-throw-preserves-all-original-fallbacks-and-original-login-error
  (async done
    (finish! done
      ((^:async fn []
        (doseq [mode [:error :nil]]
          (await (fixture/with-world {:observer-error mode}
            (^:async fn [world]
              (let [result (await (boundaries/event-sites! world))]
                (is (= [] (get-in result [:mapped :authorRoleIds])))
                (is (= {:started true :ready false :userId nil :userTag nil :guildCount 0} (:status result)))
                (assert-observations! result event-operations)))))
          (let [error (js/Error. "original-inert-login-error")]
            (await (fixture/with-world {:observer-error mode :login-error error :destroy-error? true}
              (^:async fn [world]
                (let [result (await (boundaries/login-site! world error))]
                  (is (:same-original-error result))
                  (is (false? (get-in result [:status :started])))
                  (assert-observations! result #{:failed-login-destroy}))))))
          (await (fixture/with-world {:observer-error mode :destroy-error? true}
            (^:async fn [world]
              (let [result (await (boundaries/stop-site! world))]
                (is (false? (get-in result [:status :started])))
                (assert-observations! result #{:client-destroy})))))
          (await (fixture/with-world {:observer-error mode}
            (^:async fn [world]
              (let [result (await (boundaries/voice-sites! world))]
                (is (:connection-removed result))
                (is (= [2 2 1] ((juxt :stream-destroys :decoder-destroys :connection-destroys) result)))
                (assert-observations! result voice-operations)))))
          (await (fixture/with-world {:observer-error mode}
            (^:async fn [world]
              (let [result (await (boundaries/inactive-site! world))]
                (is (:removed result)) (is (= [] (:result result)))
                (assert-observations! result #{:inactive-actor-stop})))))))))))
