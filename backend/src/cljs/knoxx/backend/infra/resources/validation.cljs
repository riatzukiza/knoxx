(ns knoxx.backend.infra.resources.validation
  "Existing resource normalization, EDN validation and identity rewrite composition."
  (:require [clojure.string :as str]
            [knoxx.backend.domain.actor.scope :as actor-scope]
            [knoxx.backend.domain.resources.editing :as editing]
            [knoxx.backend.domain.resources.loader :as resources]
            [knoxx.backend.extern.resources :as native]
            [knoxx.backend.law.contracts :as validator]))

(defn normalize-resource-class
  [raw]
  (native/resource-class raw))

(defn normalize-contract-class
  "Compatibility alias for old contract route clients."
  [raw]
  (normalize-resource-class raw))

(defn- resource-warnings [resource-class resource]
  (if (and (= (normalize-resource-class resource-class) "agents") (map? resource))
    (let [source (get-in resource [:data :source])]
      (editing/resource-warnings "agents" resource
                                (native/positive-int (or (:max-messages source) (:maxMessages source)))))
    []))

(defn validate-resource-edn
  [resource-class edn-text]
  (let [trimmed (str/trim (str edn-text))]
    (if (str/blank? trimmed)
      {:ok false
       :contract nil
       :errors [{:path [] :message "EDN text is empty"}]
       :warnings []}
      (try
        (let [raw-resource (native/read-edn trimmed)
              resource (if (= (normalize-resource-class resource-class) "agents")
                         (actor-scope/normalize-agent-contract raw-resource)
                         raw-resource)
              base (validator/validate resource-class resource)]
          {:ok (:ok base)
           :contract resource
           :errors (:errors base)
           :warnings (resource-warnings resource-class resource)})
        (catch :default err
          {:ok false
           :contract nil
           :errors [{:path [] :message (str "EDN parse error: " (native/error-message err))}]
           :warnings []})))))

(defn validate-contract-edn
  "Compatibility alias for old contract route clients."
  [contract-class edn-text]
  (validate-resource-edn contract-class edn-text))

(defn safe-resource-id
  [raw-id]
  (try
    {:ok true
     :id (resources/safe-resource-id! raw-id)}
    (catch :default err
      {:ok false
       :error (or (native/error-message err) (str err))})))

(defn safe-contract-id
  "Compatibility alias for old contract route clients."
  [raw-id]
  (safe-resource-id raw-id))

(defn safe-resource-class
  [raw-class]
  (try
    {:ok true
     :class (normalize-resource-class raw-class)}
    (catch :default err
      {:ok false
       :error (or (native/error-message err) (str err))})))

(defn safe-contract-class
  "Compatibility alias for old contract route clients."
  [raw-class]
  (safe-resource-class raw-class))

(defn update-resource-id-in-edn-text
  "Keep parsing limited to the three original qualified-resource cases."
  [resource-class edn-text new-id]
  (let [klass (normalize-resource-class resource-class)
        parsed (when (contains? #{"documents" "gardens" "publications"} klass)
                 (native/read-edn edn-text))]
    (editing/update-resource-id-in-edn-text klass edn-text new-id parsed)))
