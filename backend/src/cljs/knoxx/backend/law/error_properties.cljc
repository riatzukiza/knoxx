(ns knoxx.backend.law.error-properties
  "The CLJS field map supplied to the native Fastify error-property boundary."
  (:require [malli.core :as m]))

(def ErrorPropertiesRegistry
  "Require both field names without changing the constructors' value semantics.
   Nil, false and other existing values remain values rather than missing keys."
  {::ErrorProperties [:map
                      [:status-code :any]
                      [:code :any]]})

(def ErrorProperties
  "A portable named map contract; unrelated metadata remains permissible."
  [:schema {:registry ErrorPropertiesRegistry} [:ref ::ErrorProperties]])

(def valid?
  "The executable field-map contract; it never inspects a native error handle."
  (m/validator ErrorProperties))

(defn assert-valid!
  "Return the original field map, or refuse it before native property mutation."
  [properties]
  (if (valid? properties)
    properties
    (throw (ex-info "Invalid Fastify error properties"
                    {:contract ::ErrorProperties}))))
