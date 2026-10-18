(ns knoxx.backend.extern.discord-gateway-observation-fixture
  "Trigger each existing original-source ignored catch through the published factory/API."
  (:require [knoxx.backend.extern.discord-gateway-fixture :as fixture]
            [knoxx.backend.extern.discord-gateway-voice-fixture :as voice-fixture]))

(defn ^:async event-sites! [world]
  (await (fixture/start! world))
  (let [mapped (atom nil) raw (fixture/message) client (fixture/latest-client world)]
    (fixture/add-listener! world "onMessage" (fn [m _] (reset! mapped (fixture/snapshot m))))
    (fixture/throwing-property! world (fixture/value raw "member") "roles" :throw-member-role)
    (fixture/throwing-property! world raw "createdAt" :throw-message-date)
    (fixture/emit! world "MessageCreate" raw)
    (fixture/set-option! world :logger-error :info)
    (fixture/emit! world "ClientReady" client)
    (fixture/set-option! world :ready-error? true)
    (fixture/throwing-property! world client "user" :throw-user)
    (fixture/throwing-property! world client "guilds" :throw-guilds)
    {:mapped @mapped :status (fixture/started-status world)
     :positive-throws (mapv first (filterv #(contains? #{:throw-member-role :throw-message-date :throw-user :throw-guilds} (first %)) @(:calls world)))
     :logger-calls (fixture/calls world :logger)
     :warnings (fixture/warning-rows world)}))
(defn ^:async login-site! [world error]
  (let [result (await (fixture/rejected (fixture/start! world)))]
    {:same-original-error (identical? error (:rejected result))
     :status (fixture/started-status world) :destroy-count (count (fixture/calls world :destroy))
     :warnings (fixture/warning-rows world)}))
(defn ^:async stop-site! [world]
  (await (fixture/start! world))
  (await (fixture/stop! world))
  {:status (fixture/started-status world) :destroy-count (count (fixture/calls world :destroy))
   :warnings (fixture/warning-rows world)})
(defn ^:async inactive-site! [world]
  (fixture/set-actor! "inactive" (fixture/actor-object (fn [_] nil)
                    (fn [] (fixture/note! world :inactive-stop) (fixture/fail! "inert-inactive-stop-error")) {}))
  (let [result (await (fixture/start-actors! []))]
    {:result result :removed (nil? (fixture/public-actor "inactive"))
     :stop-count (count (fixture/calls world :inactive-stop)) :warnings (fixture/warning-rows world)}))
(defn ^:async voice-sites! [world]
  (await (fixture/start! world))
  (await (voice-fixture/with-voice world
    (^:async fn [world]
      (fixture/set-option! world :voice-destroy-error? true)
      (let [stop (await (fixture/invoke! (fixture/manager world) "startVoiceListener" fixture/guild-id nil (fn [& _] nil)))]
        (voice-fixture/emit-speaking! world "start" "ended-user")
        (voice-fixture/emit-speaking! world "end" "ended-user")
        (voice-fixture/emit-speaking! world "start" "remaining-user")
        (stop)
        (fixture/set-option! world :connection-destroy-error? true)
        (await (fixture/stop! world))
        {:stream-destroys (count (fixture/calls world :audio-stream-destroy))
         :decoder-destroys (count (fixture/calls world :decoder-destroy))
         :connection-destroys (count (fixture/calls world :connection-destroy))
         :connection-removed (nil? (fixture/invoke! (fixture/manager world) "getVoiceConnection" fixture/guild-id))
         :status (fixture/started-status world) :warnings (fixture/warning-rows world)})))))
