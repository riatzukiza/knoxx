(ns knoxx.backend.infra.character.mode-runtime
  "Small capability surface over existing authorized closures.
   GPL-3.0-or-later. No provider catalog mutation or alternate authority store."
  (:require [knoxx.backend.domain.character.tool-modes :as modes]
            [knoxx.backend.law.character.tool-modes :as law]
            [malli.core :as m]
            [malli.json-schema :as json-schema]))

(def MenuArguments [:map {:closed true} [:mode {:optional true} :string]])
(def InvokeArguments [:map {:closed true} [:tool :string] [:arguments :map]])

(defn- descriptors [catalog]
  (when-not (and (vector? catalog) (every? map? catalog))
    (throw (ex-info "Invalid capability registry" {:reason :invalid-catalog})))
  (mapv #(select-keys % [:id :name :description]) catalog))

(defn- result [data]
  {:content [{:type :text :text (pr-str data)}] :details data})

(defn- admitted-snapshot [snapshot]
  (when-let [reason (law/catalog-refusal (descriptors (:catalog snapshot)))]
    (throw (ex-info "Invalid authorized capability registry" {:reason reason})))
  snapshot)

(defn- valid-arguments! [schema args]
  (when-not (and schema (m/validate schema args))
    (throw (ex-info "Invalid capability arguments" {:reason :invalid-arguments}))))

(defn- valid-tool-arguments! [tool arguments]
  (when-not (cond
              (:parameters-schema tool) (m/validate (:parameters-schema tool) arguments)
              (:validate-arguments tool) ((:validate-arguments tool) arguments)
              :else false)
    (throw (ex-info "Capability arguments failed trusted schema validation"
                    {:reason (if (or (:parameters-schema tool) (:validate-arguments tool))
                               :invalid-arguments :unsupported-argument-schema)}))))

(defn- selection [state configuration snapshot mode]
  (let [admitted (modes/enter-mode state configuration (descriptors (:catalog snapshot))
                                   (:allowed-ids snapshot) mode)
        selected (set (get-in admitted [:state :visible-names]))]
    (if (and (= :accepted (:status admitted))
             (some #(and (contains? selected (:name %))
                         (not (or (:parameters-schema %) (:validate-arguments %)))) (:catalog snapshot)))
      {:status :refused :reason :unsupported-argument-schema :state state}
      admitted)))

(defn- menu-data [configuration state snapshot]
  {:status :accepted :mode (:mode state)
   :modes (filterv #(= :accepted (:status (selection state configuration snapshot (:mode %))))
                   (modes/mode-menu configuration (descriptors (:catalog snapshot)) (:allowed-ids snapshot)))
   :capabilities (mapv #(select-keys % [:name :description :parameters])
                      (filter #(contains? (set (:visible-names state)) (:name %)) (:catalog snapshot)))})

(defn- ^:async enter-mode! [state* configuration refresh! _id args _signal _update]
  (valid-arguments! MenuArguments args)
  (let [snapshot (admitted-snapshot (await (refresh!)))
        admitted (selection @state* configuration snapshot (or (:mode args) (:mode @state*)))]
    (if (= :refused (:status admitted))
      (result {:status :refused :reason (:reason admitted) :mode (:mode @state*)})
      (do (reset! state* (:state admitted))
          (result (menu-data configuration @state* snapshot))))))

(defn- ^:async invoke-capability! [state* refresh! id args signal update!]
  (valid-arguments! InvokeArguments args)
  ;; Resolve current policy immediately before lookup/execution. Construction-time
  ;; grants cannot admit the call; the current mode is authoritative in results.
  (let [snapshot (admitted-snapshot (await (refresh!)))
        admitted (modes/authorize-invocation @state* (descriptors (:catalog snapshot))
                                              (:allowed-ids snapshot) (:tool args))]
    (if (= :refused (:status admitted))
      (result (assoc admitted :mode (:mode @state*)))
      (let [tool (first (filter #(= (:tool-id admitted) (:id %)) (:catalog snapshot)))]
        (valid-tool-arguments! tool (:arguments args))
        (await ((:execute tool) id (:arguments args) signal update!))))))

(defn ^:async make-controller!
  "refresh! resolves CURRENT actor/contract/resource/credential policy and builds
   CURRENT trusted closures before every menu/dispatch, returning {:catalog
   vector :allowed-ids set}. The supplied character projection is preserved;
   this layer does not manufacture personality evolution."
  [configuration initial-state refresh!]
  (let [snapshot (admitted-snapshot (await (refresh!)))
        admitted (selection initial-state configuration snapshot (:initial configuration))]
    (when (= :refused (:status admitted))
      (throw (ex-info "Initial capability mode refused" (dissoc admitted :state))))
    (let [state* (atom (:state admitted))]
      {:state* state*
       :tools [{:name "capabilities" :label "Capabilities"
                :description "Inspect the current mode or enter a named mode. Returns only its authorized tool descriptions and argument schemas."
                :parameters (json-schema/transform MenuArguments)
                :execute (partial enter-mode! state* configuration refresh!)}
               {:name "invoke" :label "Invoke capability"
                :description "Invoke a capability currently revealed by capabilities. Supply its exact name and validated arguments. Hidden or revoked capabilities are refused."
                :parameters (json-schema/transform InvokeArguments)
                :execute (partial invoke-capability! state* refresh!)}]})))
