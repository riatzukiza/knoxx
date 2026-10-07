(ns knoxx.backend.law.action-capabilities
  "Portable contracts for runtime-injected action capabilities."
  (:require [malli.core :as m]))

(def registry
  "The named spawn capability and the context that carries it."
  {::SpawnAgent [:=> [:cat :map :map] :any]
   ::SpawnContext [:map [:spawn-agent! [:ref ::SpawnAgent]]]})

(def SpawnContext
  "An action context carrying a callable, two-map spawn capability."
  [:schema {:registry registry} [:ref ::SpawnContext]])

(def spawn-context-validator
  "The executable validator for the named context/call shape."
  (m/validator SpawnContext))

(defn spawn-context?
  "Require a runtime function, not callable collection/keyword data.

  Malli's function schema admits IFn values on the JVM; function identity is the
  additional portable admission invariant. Neither check invokes a capability."
  [ctx]
  (and (fn? (:spawn-agent! ctx))
       (spawn-context-validator ctx)))

(defn assert-spawn-agent!
  "Return an admitted spawn capability or refuse before any action effects."
  [ctx]
  (if (spawn-context? ctx)
    (:spawn-agent! ctx)
    (throw (ex-info "No callable :spawn-agent! in action context"
                    {:refusal/type :action/missing-capability
                     :capability :spawn-agent!}))))
