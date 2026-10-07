(ns knoxx.backend.infra.event-dispatch
  "Runtime entry point composing spawn capabilities before event dispatch."
  (:require [knoxx.backend.domain.event.dispatch :as dispatch]
            [knoxx.backend.domain.models :as models]
            [knoxx.backend.infra.agent.action-capabilities :as capabilities]
            [knoxx.backend.infra.config :as runtime-config]))

(defn dispatch!
  "Dispatch an internal event through the runtime-owned action capabilities."
  ([event] (dispatch! (models/enrich-config (runtime-config/cfg)) event))
  ([config event] (dispatch/dispatch! (capabilities/attach config) event)))

(defn dispatch-external!
  "Compose runtime capabilities while preserving untrusted event provenance."
  ([event] (dispatch-external! (models/enrich-config (runtime-config/cfg)) event))
  ([config event] (dispatch/dispatch-external! (capabilities/attach config) event)))
