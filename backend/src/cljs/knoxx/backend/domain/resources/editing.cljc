(ns knoxx.backend.domain.resources.editing
  "Resource identity and warning decisions over supplied plain observations."
  (:require [clojure.string :as str]))

(defn model-id->slug
  [model-id]
  (some-> model-id
          str
          (str/replace #"[^A-Za-z0-9._-]+" "_")
          (str/replace #"_+" "_")))

(defn qualified-id-with-local-slug
  [value new-id]
  (let [existing-ns (when (keyword? value) (namespace value))
        local-name (str/replace (str new-id) #"_" "-")]
    (keyword existing-ns local-name)))

(defn parsed-resource-id
  [resource-class value]
  (case resource-class
    "agents" (some-> (:contract/id value) str)
    "policies" (some-> (:contract/id value) str)
    "sources" (some-> (:contract/id value) str)
    "actions" (some-> (:contract/id value) str)
    "triggers" (some-> (:contract/id value) str)
    "sub_agents" (some-> (:contract/id value) str)
    "actors" (some-> (:actor/id value) str)
    "roles" (some-> (:role/id value) name)
    "capabilities" (some-> (:cap/id value) name)
    "model_families" (some-> (:model-family/id value) str)
    "models" (some-> (:model/id value) model-id->slug)
    nil))

(defn validation-warning
  [path message]
  {:path path
   :message message
   :severity "warn"})

(def ^:private mutable-agent-data-keys
  #{:world_state :world-state
    :plot_log :plot-log
    :composition_log :composition-log
    :last_tick_timestamp :last-tick-timestamp
    :composition_count :composition-count})

(defn role-ref-warnings
  [path value]
  (let [warn-bare (validation-warning path "Role refs should use :role/<kebab-slug>; bare or snake_case role refs are tolerated but cause drift.")
        warn-snake (validation-warning path "Role refs should use kebab-case, e.g. :role/contract-librarian, not underscores.")]
    (cond
      (keyword? value)
      (cond-> []
        (not= "role" (namespace value)) (conj warn-bare)
        (str/includes? (name value) "_") (conj warn-snake))

      (string? value)
      (cond-> []
        (str/includes? value "_") (conj warn-snake))

      :else [])))

(defn prompt-state-path-warnings
  [resource]
  (let [prompts [(get-in resource [:prompts :system])
                 (get-in resource [:prompts :task])]
        stale-ref? (some (fn [prompt]
                           (and (string? prompt)
                                (re-find #"(:data/|/world_state|/plot_log|:data/world_state|:data/plot_log)" prompt)))
                         prompts)]
    (if stale-ref?
      [(validation-warning ["prompts"] "Prompt references mutable :data paths (for example :data/world_state or :data/plot_log). Agent resource :data is static config; use a real state store or durable files instead.")]
      [])))

(defn- source-shape-warnings
  [resource max-messages]
  (let [data (:data resource)]
    (cond-> []
      (contains? data :filter)
      (conj (validation-warning ["data" "filter"] "Runtime ignores :data/:filter. Use :data/:filters."))
      (contains? resource :source)
      (conj (validation-warning ["source"] "Event-agent runtime ignores top-level :source. Use :data {:source ...}."))
      (contains? resource :capabilities)
      (conj (validation-warning ["capabilities"] "Top-level :capabilities is legacy/inert in resource resolution. Put capability refs under :actor {:capabilities [...]}, or grant them through roles."))
      (and max-messages (> max-messages 100))
      (conj (validation-warning ["data" "source" "max-messages"] "Event-agent source max-messages is clamped to 100 at runtime.")))))

(defn- source-mode-warnings
  [resource]
  (let [source-mode (:source-mode resource)
        filters (get-in resource [:data :filters])
        channels (or (:channels filters) [])
        publish-channels (or (:publishChannels filters) (:publish_channels filters) [])]
    (cond-> []
      (and (keyword? source-mode)
           (not= "source-mode" (namespace source-mode))
           (contains? #{:synthesize :template-synthesize} source-mode))
      (conj (validation-warning ["source-mode"] "Use :source-mode/discord-synthesis instead of opaque bare synthesis modes."))
      (and (= :source-mode/discord-synthesis source-mode)
           (empty? channels) (seq publish-channels))
      (conj (validation-warning ["data" "filters" "publishChannels"] ":publishChannels are output sinks only. Add explicit :channels or :guildIds for Discord source reads.")))))

(defn- mutable-state-warnings
  [data]
  (mapcat (fn [k]
            (when (contains? data k)
              [(validation-warning ["data" (name k)] "This looks like mutable runtime state inside a static resource. Prefer Mongo/OpenPlanner/durable files, not resource :data mutation.")]))
          mutable-agent-data-keys))

(defn- agent-resource-warnings
  [resource max-messages]
  (vec (concat (source-shape-warnings resource max-messages)
               (source-mode-warnings resource)
               (mutable-state-warnings (:data resource))
               (role-ref-warnings ["agent" "role"] (get-in resource [:agent :role]))
               (mapcat (fn [[idx value]]
                         (role-ref-warnings ["agent" "roles" (str idx)] value))
                       (map-indexed vector (or (get-in resource [:agent :roles]) [])))
               (prompt-state-path-warnings resource))))

(defn resource-warnings
  "Return the existing ordered warning vector for an observed normalized class."
  [resource-class resource max-messages]
  (if (and (= resource-class "agents") (map? resource))
    (agent-resource-warnings resource max-messages)
    []))

(defn- replace-or-prefix
  [edn-text id-key pattern replacement]
  (if (str/includes? edn-text (str id-key))
    (str/replace edn-text pattern (str id-key " " replacement))
    (str id-key " " replacement "\n" edn-text)))

(defn- string-contract-id
  [edn-text new-id]
  (replace-or-prefix edn-text :contract/id #":contract/id\s+\"[^\"]+\"" (str "\"" new-id "\"")))

(defn- source-contract-id
  [edn-text new-id]
  (let [source-id (str ":source/" (str/replace (str new-id) "_" "-"))]
    (cond-> (string-contract-id edn-text new-id)
      (str/includes? edn-text ":source/id")
      (str/replace #":source/id\s+:[^\s\]}]+" (str ":source/id " source-id)))))

(defn- qualified-resource-id
  [edn-text parsed new-id id-key pattern]
  (let [value (qualified-id-with-local-slug (get parsed id-key) new-id)]
    (str/replace edn-text pattern (str id-key " " value))))

(defn- rewrite-keyword-id
  [resource-class edn-text new-id]
  (case resource-class
    "roles" (replace-or-prefix edn-text :role/id #":role/id\s+:[^\s\]}]+"
                               (str ":role/" (str/replace new-id "_" "-")))
    "capabilities" (let [slug (str/replace (str new-id) #"^cap_" "")]
                     (replace-or-prefix edn-text :cap/id #":cap/id\s+:[^\s\]}]+"
                                        (str ":cap/" (str/replace slug "_" "-"))))
    edn-text))

(defn update-resource-id-in-edn-text
  "Rewrite exactly the old identity surface using a supplied parsed observation."
  [resource-class edn-text new-id parsed]
  (case resource-class
    ("agents" "policies") (string-contract-id edn-text new-id)
    "sources" (source-contract-id edn-text new-id)
    "actors" (replace-or-prefix edn-text :actor/id #":actor/id\s+\"[^\"]+\""
                                (str "\"" new-id "\""))
    ("roles" "capabilities") (rewrite-keyword-id resource-class edn-text new-id)
    "model_families" (replace-or-prefix edn-text :model-family/id #":model-family/id\s+\"[^\"]+\""
                                        (str "\"" new-id "\""))
    "models" (replace-or-prefix edn-text :model/id #":model/id\s+\"[^\"]+\""
                                (str "\"" (str/replace (str new-id) "_" ":") "\""))
    "documents" (qualified-resource-id edn-text parsed new-id :document/id #":document/id\s+:[^\s\]}]+")
    "gardens" (qualified-resource-id edn-text parsed new-id :garden/id #":garden/id\s+:[^\s\]}]+")
    "publications" (qualified-resource-id edn-text parsed new-id :publication/id #":publication/id\s+:[^\s\]}]+")
    edn-text))
