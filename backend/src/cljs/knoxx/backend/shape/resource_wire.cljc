(ns knoxx.backend.shape.resource-wire
  "Plain resource and compatibility wire projections.")

(defn wire-key
  [key]
  (if (keyword? key)
    (if-let [key-ns (namespace key)]
      (str key-ns "/" (name key))
      (name key))
    key))

(defn wire-value
  [value]
  (cond
    (keyword? value) (wire-key value)
    (symbol? value) (wire-key value)
    (map? value) (into {} (map (fn [[k v]] [(wire-key k) (wire-value v)])) value)
    (set? value) (mapv wire-value value)
    (sequential? value) (mapv wire-value value)
    :else value))

(defn keywordish-name
  [value]
  (cond
    (keyword? value) (wire-key value)
    (symbol? value) (wire-key value)
    (some? value) (str value)
    :else nil))

(defn record-definition
  [record]
  (or (:resource/definition record)
      (:contract record)))

(defn record-class
  [record]
  (or (:resource/class record)
      (:contractClass record)))

(defn trigger-summary
  [record]
  (let [resource (record-definition record)
        events (wire-value (:trigger/events resource))]
    {:kind   (keywordish-name (:trigger/kind resource))
     :target (or (:trigger/target resource)
                 (get-in resource [:trigger/with :agent-id])
                 (:trigger/agent resource))
     :events events
     :source {:events events}
     :filters (wire-value (get-in resource [:data :filters]))
     :context (wire-value (get-in resource [:data :context]))}))

(defn action-summary
  [record]
  {:handler (get-in (record-definition record) [:action/handler])})

(defn resource-list-summary
  [record resource-kind]
  (let [resource-class (record-class record)
        summary {:id (:resource/id record)
                 :resource/id (:resource/id record)
                 :resource/kind resource-kind
                 :resourceClass resource-class
                 :kind (some-> resource-kind name)
                 :path (str resource-class "/" (:resource/id record) ".edn")}]
    (cond-> summary
      (= :trigger resource-kind)
      (assoc :trigger (trigger-summary record))

      (= :action resource-kind)
      (assoc :action (action-summary record)))))

(defn contract-list-summary
  "Compatibility summary for old /contracts clients."
  [record resource-kind]
  (assoc (resource-list-summary record resource-kind)
         :contractClass (record-class record)))

(defn wire-validation
  [validation]
  (cond-> validation
    (:contract validation) (update :contract wire-value)))
