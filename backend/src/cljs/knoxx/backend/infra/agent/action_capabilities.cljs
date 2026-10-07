(ns knoxx.backend.infra.agent.action-capabilities
  "Trusted composition of action capabilities at the runtime boundary."
  (:require [knoxx.backend.infra.agent.runner :as runner]
            [knoxx.backend.law.action-capabilities :as capabilities]))

(defn attach
  "Install the runtime-owned spawn function, replacing caller-supplied slots."
  [config]
  (let [context {:spawn-agent! runner/spawn-direct!}]
    (capabilities/assert-spawn-agent! context)
    (assoc config :action/capabilities context)))
