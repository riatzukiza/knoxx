(ns knoxx.backend.infra.discord-gateway-capabilities-test
  "Captured production gateway callbacks require runtime-owned action capabilities."
  (:require [cljs.test :refer [deftest is]]
            [knoxx.backend.domain.discord.source :as discord]
            [knoxx.backend.domain.source.runtime :as source]
            [knoxx.backend.infra.agent.runner :as runner]
            [knoxx.backend.infra.core :as core]))

(deftest ^:async both-gateway-callbacks-replace-missing-or-hostile-capabilities
  (doseq [caller-capabilities [nil {:spawn-agent! :caller-data :untrusted :data}]]
    (let [callbacks* (atom nil)
          seen* (atom [])
          spawn (fn [_config _payload] {:ok true})
          config {:marker :preserved :action/capabilities caller-capabilities}
          policy {:policy :opaque}
          message {:gatewayActorId "message-actor" :content "hello"}
          voice {:gatewayActorId "voice-actor" :channelId "voice-channel"}]
      (with-redefs [runner/spawn-direct! spawn
                    discord/bind-gateways! (fn [callbacks]
                                             (reset! callbacks* callbacks)
                                             (js/Promise.resolve :bound))
                    source/dispatch-driver-event! (fn [cfg driver actor event]
                                                    (swap! seen* conj [cfg driver actor event])
                                                    (js/Promise.resolve :dispatched))]
        (is (= :bound (await (#'core/bind-discord-actor-gateways! config policy))))
        (is (identical? policy (:policy-db @callbacks*)))
        (await ((:on-message! @callbacks*) message))
        (await ((:on-voice-state! @callbacks*) voice)))
      (is (= 2 (count @seen*)))
      (is (every? #(identical? spawn (get-in (first %) [:action/capabilities :spawn-agent!]))
                  @seen*))
      (is (every? #(= #{:spawn-agent!} (set (keys (:action/capabilities (first %)))))
                  @seen*))
      (is (every? #(= :preserved (:marker (first %))) @seen*))
      (is (= [:driver/discord :driver/discord] (mapv second @seen*)))
      (is (= ["message-actor" "voice-actor"] (mapv #(nth % 2) @seen*)))
      (is (= [{:event/type :discord.message :event/payload message}
              {:event/type :discord.voice.state-update :event/payload voice}]
             (mapv #(nth % 3) @seen*))))))
