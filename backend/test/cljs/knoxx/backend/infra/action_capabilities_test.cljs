(ns knoxx.backend.infra.action-capabilities-test
  (:require [cljs.test :refer [deftest is]]
            [knoxx.backend.domain.event.dispatch :as dispatch]
            [knoxx.backend.domain.resources.loader :as resources]
            [knoxx.backend.domain.schedule.runtime :as schedule]
            [knoxx.backend.domain.source.runtime :as source]
            [knoxx.backend.domain.trigger.runtime :as trigger]
            [knoxx.backend.infra.agent.action-capabilities :as capabilities]
            [knoxx.backend.infra.agent.runner :as runner]
            [knoxx.backend.infra.event-dispatch :as ingress]
            [knoxx.backend.infra.event-runtime :as runtime]))

(deftest ^:async runtime-capability-is-independent-of-event-and-config-data
  (let [seen* (atom [])
        spawn (fn [_config _payload] {:ok true})
        poison (fn [& _] (throw (ex-info "Untrusted capability invoked" {})))
        contract {:contract/id "capture-capability"
                  :trigger/kind :event
                  :trigger/events [:test/capability]
                  :trigger/action :test/capture-capability
                  :action/fn (fn [ctx _action]
                               (swap! seen* conj ctx)
                               {:ok true})
                  :enabled true}
        record {:resource/id "capture-capability"
                :resource/kind :trigger
                :resource/definition contract}
        config {:action/capabilities {:spawn-agent! poison}}
        event {:event/id "external-capability"
               :event/type :test/capability
               :event/trusted? true
               :event/payload {:spawn-agent! poison}}]
    (with-redefs [runner/spawn-direct! spawn
                  resources/load-all-resources-sync (fn [_config] [record])
                  resources/resource-sync (fn [_config _kind _id] contract)]
      (dispatch/reset-dedup!)
      (await (ingress/dispatch-external! config event))
      (await (runtime/fire-trigger-external! config "capture-capability"))
      (await (ingress/dispatch! config (assoc event :event/id "internal-capability"))))
    (is (= 3 (count @seen*)))
    (is (every? #(identical? spawn (:spawn-agent! %)) @seen*))
    (is (= [false false true] (mapv :event/trusted? @seen*)))
    (is (identical? poison (get-in (first @seen*) [:trigger-ctx :spawn-agent!])))))

(deftest event-runtime-carries-the-capability-to-every-producer
  (let [seen* (atom [])
        spawn (fn [_config _payload] {:ok true})
        config {:action/capabilities {:spawn-agent! :caller-data}}
        previously-running? @runtime/running?*]
    (try
      (reset! runtime/running?* false)
      (with-redefs [runner/spawn-direct! spawn
                    trigger/start! (fn [cfg] (swap! seen* conj [:trigger cfg]))
                    schedule/start! (fn [cfg] (swap! seen* conj [:schedule cfg]))
                    source/start! (fn [cfg] (swap! seen* conj [:source cfg]))]
        (is (= :started (runtime/start! config))))
      (is (= [:trigger :schedule :source] (mapv first @seen*)))
      (is (every? #(identical? spawn (get-in (second %) [:action/capabilities :spawn-agent!]))
                  @seen*))
      (finally
        (reset! runtime/running?* previously-running?)))))

(deftest composition-replaces-the-entire-caller-capability-map
  (let [cfg (capabilities/attach {:action/capabilities {:spawn-agent! :data :untrusted :data}})]
    (is (= #{:spawn-agent!} (set (keys (:action/capabilities cfg)))))
    (is (identical? runner/spawn-direct! (get-in cfg [:action/capabilities :spawn-agent!])))))
