(ns knoxx.backend.infra.character.social-encounters
  "Pull bounded source pages through current authorized registered tool closures."
  (:require [knoxx.backend.domain.character.social-encounters :as social]
            [knoxx.backend.law.character.encounter :as law]
            [knoxx.backend.shape.character.encounter :as shape]))

(defn- assert-ports!
  [ports]
  (doseq [key [:read! :authorize! :normalize-instant]]
    (when-not (fn? (get ports key))
      (throw (ex-info "Social source requires an existing trusted boundary operation"
                      {:code :encounter/missing-social-port :port key}))))
  ports)

(defn ^:async pull-source!
  "Reauthorize the exact fixed owner/source before any read; never write or publish."
  [ports owner spec checkpoint observed-at]
  (assert-ports! ports)
  (social/assert-source-config! spec)
  (law/assert-shape! shape/Owner owner :social-owner)
  (law/assert-shape! shape/Instant observed-at :social-observed-at)
  (let [source (:source spec)
        decision (await ((:authorize! ports) owner source))]
    (if-not (law/authorized? owner source decision)
      {:status :denied :source source}
      (do
        (when checkpoint
          (law/assert-shape! shape/Checkpoint checkpoint :social-checkpoint)
          (when-not (and (= owner (:owner checkpoint)) (= source (:source checkpoint)))
            (throw (ex-info "Social cursor belongs to another owner or source"
                            {:code :encounter/checkpoint-conflict}))))
        (let [{:keys [tool-id parameters]} (social/read-request spec checkpoint)
              result (await ((:read! ports) tool-id parameters))]
          (when (or (not (map? result)) (:isError result))
            (throw (ex-info "Authorized social read did not return successful tool details"
                            {:code :encounter/social-read-failed})))
          (social/project-page spec checkpoint observed-at (:details result)
                               (:normalize-instant ports)))))))
