(ns knoxx.backend.law.action-capabilities-test
  (:require [clojure.test :refer [deftest is testing]]
            [knoxx.backend.law.action-capabilities :as law]))

(deftest spawn-capability-admission-does-not-execute-the-capability
  (let [called* (atom 0)
        spawn! (fn [_config _payload] (swap! called* inc))]
    (is (law/spawn-context? {:spawn-agent! spawn!}))
    (is (identical? spawn! (law/assert-spawn-agent! {:spawn-agent! spawn!})))
    (is (zero? @called*))))

(deftest ordinary-ifn-data-is-not-a-runtime-capability
  (testing "maps/keywords/sets/vectors may be IFn but cannot spawn agents"
    (doseq [value [nil {} :spawn "spawn" #{} [] 1 false]]
      (is (false? (law/spawn-context? {:spawn-agent! value}))))))
