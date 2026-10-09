(ns knoxx.backend.infra.character.encounter-runtime
  "Scheduled intake and current decision context over existing actor/source/event ports.
   GPL-3.0-or-later. Host port injection is trusted configuration, never tool input."
  (:require [knoxx.backend.domain.character.encounter-context :as context]
            [knoxx.backend.domain.character.social-encounters :as social]
            [knoxx.backend.domain.contracts.sources :as sources]
            [knoxx.backend.domain.node.crypto :as crypto]
            [knoxx.backend.domain.time :as time]
            [knoxx.backend.extern.character-encounters :as timestamps]
            [knoxx.backend.extern.eta-mu :as eta-mu]
            [knoxx.backend.extern.tools :as tools]
            [knoxx.backend.infra.actor.acting :as acting]
            [knoxx.backend.infra.actor.credentials :as credentials]
            [knoxx.backend.infra.agent.tool-catalog :as catalog]
            [knoxx.backend.infra.auth.authz :as authz]
            [knoxx.backend.infra.character.authority :as authority]
            [knoxx.backend.infra.character.encounter-admission :as admission]
            [knoxx.backend.infra.character.encounter-openplanner :as events]
            [knoxx.backend.infra.character.social-encounters :as source-io]
            [knoxx.backend.infra.clients.openplanner :as openplanner]
            [knoxx.backend.infra.tooling :as tooling]
            [knoxx.backend.law.character.encounter :as law]
            [knoxx.backend.shape.character.encounter :as shape]
            [malli.core :as m]))

(def ^:private Configuration
  [:map {:closed true}
   [:sources [:vector {:max 8} social/SourceConfig]]
   [:context {:optional true} :map]])

(defonce ^:private shared-event-ports* (atom nil))

(defn shared-event-ports!
  "Reuse one process-owned serialized port for the existing singleton embedded
   OpenPlanner store. Recheck the selected adapter capability on every access;
   REST never borrows the embedded handle. No encounter data lives in this atom."
  [config]
  (let [client (openplanner/assert-event-projection-repair-supported! (openplanner/client config))]
    (or @shared-event-ports*
        (let [ports (events/openplanner-ports client)]
          (reset! shared-event-ports* ports)
          ports))))

(defn- current-source-resources [config agent-spec]
  ;; The existing composer tolerates unresolved refs. That convenience cannot
  ;; grant source access here: every retained resource must still resolve.
  (->> (sources/source-specs-for-agent config agent-spec)
       (keep #(sources/source-contract config %))
       vec))

(defn- current-read-tools [runtime config auth-context agent-spec allowed]
  (->> (catalog/custom-tools runtime config (catalog/effective-tool-auth-context auth-context allowed)
                             agent-spec allowed)
       eta-mu/tool-seq
       (mapv tools/registered-tool)))

(defn- actor-scope [auth-context agent-spec]
  {:actor-id (:actor-id agent-spec)
   :org-id (get-in auth-context [:org :id])
   :membership-id (get-in auth-context [:membership :id])})

(defn- ^:async current-account! [runtime auth-context agent-spec spec]
  (let [provider (case (get-in spec [:source :kind]) "discord" "discord_bot" "bluesky" "bluesky" nil)]
    (when-not provider
      (throw (ex-info "Encounter source credential provider is unsupported" {:reason :unsupported-source-account})))
    (let [credential (await (acting/run-as! (actor-scope auth-context agent-spec)
                                          #(credentials/get-credential! runtime provider)))]
      ;; The existing resolver only returns an active current-scoped credential.
      ;; Secret material stays inside that adapter; only mapping evidence escapes.
      {:credential-id (:id credential) :account-identifier (:accountIdentifier credential)})))

(defn- runtime-ports [config]
  (let [ports (merge {:digest crypto/sha256-hex :clock time/now-iso
                      :normalize-instant timestamps/normalize-instant
                      :resolve-spec tooling/resolve-agent-contract
                      :resolve-authority! authority/resolve-current!
                      :allowed-tool-ids catalog/focused-authorized-tool-ids
                      :source-resources current-source-resources
                      :resolve-account! current-account! :read-tools current-read-tools
                      :event-ports! shared-event-ports! :pull-source! source-io/pull-source!}
                     (:character-encounter-runtime-ports config))]
    (doseq [key [:digest :clock :normalize-instant :resolve-spec :resolve-authority! :allowed-tool-ids
                 :source-resources :resolve-account! :read-tools :event-ports! :pull-source!]]
      (law/assert-shape! [:fn fn?] (get ports key) key))
    ports))

(defn- explicit-identity [agent-spec]
  (let [identity (select-keys agent-spec [:actor-id :contract-id])]
    (when (or (seq (:character-encounters agent-spec))
              (and (:actor-id identity) (:contract-id identity)))
      (law/assert-shape! [:map [:actor-id shape/NonBlankString] [:contract-id shape/NonBlankString]]
                         identity :encounter-actor-contract)
      identity)))

(defn- ^:async selected-spec! [ports config agent-spec]
  (when-let [{:keys [actor-id contract-id]} (explicit-identity agent-spec)]
    (let [selected (await ((:resolve-spec ports) config contract-id actor-id))]
      (when-not (and (map? selected) (not (false? (:enabled selected)))
                     (= actor-id (:actor-id selected)) (= contract-id (:id selected)))
        (throw (ex-info "Encounter actor contract is not currently selected" {:reason :invalid-encounter-contract})))
      ;; Re-resolve the canonical contract. Caller/provider/event encounter
      ;; options and source overrides cannot become read or retention authority.
      (assoc selected :contract-id contract-id))))

(defn- encounter-configuration [selected]
  (when-let [configuration (:character-encounters selected)]
    (law/assert-shape! Configuration configuration :character-encounters)
    (doseq [spec (:sources configuration)] (social/assert-source-config! spec))
    (law/assert-shape! context/Options (merge context/default-options (:context configuration)) :encounter-context-options)
    (when-not (= (count (:sources configuration)) (count (set (map :source (:sources configuration)))))
      (throw (ex-info "Encounter configuration repeats an exact source" {:reason :duplicate-encounter-source})))
    configuration))

(defn- owner! [config selected auth-context]
  (law/assert-shape! shape/Owner
                     {:org-id (get-in auth-context [:org :id]) :project (:session-project-name config)
                      :character-id (:actor-id selected)} :encounter-owner))

(defn- denial [reason] {:allowed? false :reason reason})

(defn- authorization-evidence [ports owner source selected auth-context spec account resources allowed]
  (let [checked-at ((:clock ports))
        principal-id (get-in auth-context [:membership :id])
        binding [owner (shape/source-binding source) (:id selected) (:tool-id spec)
                 (:credential-id account) (:account-identifier account) principal-id checked-at
                 (sort allowed) (mapv :source/id resources)]]
    (law/assert-shape! shape/Authorization
                       {:allowed? true :owner owner :source source :principal-id principal-id
                        :evidence-id (str "encounter-authority:" ((:digest ports) (pr-str binding)))
                        :checked-at checked-at} :encounter-source-authority)))

(defn- ^:async admitted-source-state! [ports runtime config agent-spec auth-context owner source]
  (let [selected (await (selected-spec! ports config agent-spec))
        configuration (encounter-configuration selected)
        spec (first (filter #(= source (:source %)) (:sources configuration)))
        current (await ((:resolve-authority! ports) config auth-context selected))
        resources ((:source-resources ports) config selected)
        allowed ((:allowed-tool-ids ports) config current selected)]
    (if-not (and spec (= owner (owner! config selected current))
                 (social/source-scope-admitted? spec resources allowed))
      {:decision (denial :source-scope-denied)}
      (let [account (await ((:resolve-account! ports) runtime current selected spec))
            bound-resources (filterv #(and (social/source-scope-admitted? spec [%] allowed)
                                           (social/source-account-admitted? spec [%] (:actor-id selected)
                                                                            (:account-identifier account))) resources)]
        (if-not (and (m/validate shape/NonBlankString (:credential-id account))
                     (seq bound-resources))
          {:decision (denial :source-account-unbound)}
          {:selected selected :auth-context current :spec spec :allowed allowed
           ;; Current admitted facts, without observation timestamps or secret
           ;; material. Changes here invalidate a graph snapshot across awaits.
           :grant-binding [(shape/owner-binding owner) (shape/source-binding source)
                           (:id selected) (:actor-id selected) (:tool-id spec)
                           (:credential-id account) (:account-identifier account)
                           (get-in current [:membership :id]) (sort allowed)
                           (mapv :source/id bound-resources)]
           :decision (authorization-evidence ports owner source selected current spec account bound-resources allowed)})))))

(defn- ^:async current-source-state! [ports runtime config agent-spec auth-context owner source]
  (try
    (await (admitted-source-state! ports runtime config agent-spec auth-context owner source))
    ;; knoxx-lint/allow-silent-catch — an unavailable current grant is a denial;
    ;; its exception may contain credential data and cannot become prompt text.
    (catch :default _error {:decision (denial :current-source-authority-unavailable)})))

(defn- valid-read-arguments! [tool arguments]
  (when-not (and (fn? (:execute tool))
                 (cond (:parameters-schema tool) (m/validate (:parameters-schema tool) arguments)
                       (:validate-arguments tool) (true? ((:validate-arguments tool) arguments))
                       :else false))
    (throw (ex-info "Encounter read failed trusted argument validation" {:reason :invalid-encounter-read-arguments}))))

(defn- ^:async execute-current-read! [ports runtime config agent-spec auth-context owner source tool-id arguments]
  (let [{:keys [decision selected allowed spec] current :auth-context} (await (current-source-state!
                                                                       ports runtime config agent-spec auth-context owner source))]
    (law/assert-authorized! owner source decision)
    (when-not (and (= tool-id (:tool-id spec))
                   (contains? allowed tool-id))
      (throw (ex-info "Encounter read tool is outside its current source" {:reason :source-read-tool-denied})))
    (let [matching (filterv #(= tool-id (:id %)) ((:read-tools ports) runtime config current selected #{tool-id}))
          tool (when (= 1 (count matching)) (first matching))]
      (valid-read-arguments! tool arguments)
      (await (acting/run-as! (actor-scope current selected)
                            #((:execute tool) "character-intake" arguments nil nil))))))

(defn- source-ports [ports runtime config agent-spec auth-context owner source authorize!]
  {:authorize! authorize! :normalize-instant (:normalize-instant ports)
   :read! #(execute-current-read! ports runtime config agent-spec auth-context owner source %1 %2)})

(defn- ^:async observe-source! [ports runtime config agent-spec owner spec]
  (let [source (:source spec)
        authorize! (^:async fn [requested-owner requested-source]
                     (:decision (await (current-source-state! ports runtime config agent-spec nil requested-owner requested-source))))
        decision (await (authorize! owner source))]
    (if-not (law/authorized? owner source decision)
      {:source source :status :denied :reason (:reason decision)}
      (let [store ((:event-ports! ports) config)
            stream-id (law/stream-id (:digest ports) owner source)
            checkpoint (await ((:latest-checkpoint! store) owner stream-id))
            _ (law/assert-checkpoint! (:digest ports) owner source checkpoint)
            pulled (await ((:pull-source! ports) (source-ports ports runtime config agent-spec nil owner source authorize!)
                           owner spec checkpoint ((:clock ports))))]
        (if (= :denied (:status pulled))
          {:source source :status :denied}
          (do (when-not (= source (get-in pulled [:page :source]))
                (throw (ex-info "Encounter pull changed its fixed source" {:reason :source-read-conflict})))
              (assoc (await (admission/admit-page! store (:digest ports) owner (:page pulled) authorize!)) :source source)))))))

(defn- source-failure [source error]
  ;; Exception messages, provider payloads, credential material and arbitrary
  ;; ex-data never escape intake. Durable partial rows remain in their store;
  ;; this result neither retries nor claims a cursor was confirmed.
  (let [code (:code (ex-data error))]
    (merge {:source source}
           (case code
             :encounter/source-denied {:status :denied :reason :source-authority-revoked}
             :encounter/page-incomplete {:status :partial :reason :source-admission-incomplete}
             :encounter/social-read-failed {:status :failed :reason :source-read-failed}
             (:encounter/invalid-shape :encounter/empty-content :encounter/invalid-social-result
              :encounter/source-result-conflict :encounter/source-config-conflict)
             {:status :failed :reason :source-page-rejected}
             {:status :failed :reason :source-observation-failed}))))

(defn- ^:async observe-source-contained! [ports runtime config agent-spec owner spec]
  (try
    (await (observe-source! ports runtime config agent-spec owner spec))
    (catch :default error (source-failure (:source spec) error))))

(defn- observation-status [results]
  (if (some #(contains? #{:failed :partial} (:status %)) results) :partial :observed))

(defn ^:async observe!
  "Observe configured sources between creative opportunities. This starts no
   provider turn or publication. Current canonical source/account grants are
   checked before source reads and again before admission; cursors live only in
   existing OpenPlanner events, and partial admission does not advance them."
  [runtime config agent-spec]
  (let [ports (runtime-ports config)
        selected (await (selected-spec! ports config agent-spec))
        configuration (encounter-configuration selected)]
    (if-not configuration
      {:status :disabled :sources []}
      (let [current (await ((:resolve-authority! ports) config nil selected))
            owner (owner! config selected current)]
        (loop [remaining (:sources configuration) results []]
          (if-let [spec (first remaining)]
            (recur (next remaining) (conj results (await (observe-source-contained! ports runtime config selected owner spec))))
            {:status (observation-status results) :owner owner :sources results}))))))

(defn- inclusion-evidence [decisions encounters]
  (mapv (fn [record]
          {:encounter-id (:id record) :causal-source-id (:source-id record)
           :authorization (get decisions (:source record))}) encounters))

(defn- graph-principal-binding [current]
  [(:actorId current) (get-in current [:org :id])
   (get-in current [:membership :id]) (get-in current [:user :id])])

(defn- ^:async current-graph-principal! [ports config selected auth-context]
  (let [current (await ((:resolve-authority! ports) config auth-context selected))]
    (when (and (m/validate shape/NonBlankString (get-in current [:user :id]))
               (authz/ctx-permitted? current "agent.memory.read")) current)))

(defn- ^:async current-graph-bindings! [ports runtime config selected auth-context owner sources]
  (loop [remaining sources bindings []]
    (if-let [source (first remaining)]
      (let [state (await (current-source-state! ports runtime config selected auth-context owner source))]
        (when (law/authorized? owner source (:decision state))
          (recur (next remaining) (conj bindings (:grant-binding state)))))
      bindings)))

(defn- ^:async graph-candidates! [ports runtime config selected configuration auth-context owner]
  (let [bindings* (atom {})
        authorize! (^:async fn [requested-owner source]
                     (let [state (await (current-source-state! ports runtime config selected auth-context requested-owner source))]
                       (swap! bindings* assoc source (:grant-binding state))
                       (:decision state)))
        admitted (loop [remaining (map :source (:sources configuration)) allowed []]
                   (if-let [source (first remaining)]
                     (recur (next remaining)
                            (cond-> allowed (law/authorized? owner source (await (authorize! owner source)))
                              (conj source)))
                     allowed))]
    (when (seq admitted)
      (let [loaded (await (admission/load-eligible! ((:event-ports! ports) config) (:digest ports)
                                                   owner admitted authorize! (:context configuration)))
            sources (filterv #(law/authorized? owner %
                                               (get (:decisions loaded) (law/stream-id (:digest ports) owner %))) admitted)
            bindings (mapv #(get @bindings* %) admitted)
            after (await (current-graph-bindings! ports runtime config selected auth-context owner admitted))]
        (when (and (= sources admitted) (= bindings after))
          {:records (mapv #(select-keys % [:id :text]) (:records loaded))
           :bindings bindings})))))

(defn ^:async graph-authority!
  "Trusted current encounter candidate scope for graph recall, before prompt caps.
   Memory permission and user are explicit; legacy admin bypasses do not apply."
  [runtime config agent-spec auth-context]
  (let [ports (runtime-ports config)
        selected (await (selected-spec! ports config agent-spec))
        configuration (encounter-configuration selected)]
    (when configuration
      (when-let [current (await (current-graph-principal! ports config selected auth-context))]
        (let [owner (owner! config selected current)
              candidates (await (graph-candidates! ports runtime config selected configuration auth-context owner))
              after (await (current-graph-principal! ports config selected auth-context))]
          (when (and candidates after (= (graph-principal-binding current) (graph-principal-binding after)))
            {:scope {:actor-id (:actor-id selected) :org-id (:org-id owner)
                     :membership-id (get-in current [:membership :id]) :user-id (get-in current [:user :id])
                     :policy-revision (str "encounter-policy:"
                                           ((:digest ports) (pr-str [(graph-principal-binding current) (:bindings candidates)])))}
             :project (:project owner) :records (:records candidates)}))))))

(defn ^:async decision-context!
  "Reload bounded admitted experience for its exact current owner. Reauthorize
   each selected source/account at load time; historical admission is provenance,
   never a reusable grant. Inclusion evidence names the fresh read decision."
  [runtime config agent-spec auth-context]
  (let [ports (runtime-ports config)
        selected (await (selected-spec! ports config agent-spec))
        configuration (encounter-configuration selected)]
    (when configuration
      (let [current (await ((:resolve-authority! ports) config auth-context selected))
            owner (owner! config selected current)
            decisions* (atom {})
            authorize! (^:async fn [requested-owner source]
                         (let [state (await (current-source-state! ports runtime config selected auth-context requested-owner source))]
                           (swap! decisions* assoc source (:decision state))
                           (:decision state)))
            ;; Delay opening even the existing store until at least one source
            ;; has current authority; all-denied decisions expose no content.
            admitted (loop [remaining (map :source (:sources configuration)) allowed []]
                       (if-let [source (first remaining)]
                         (recur (next remaining) (cond-> allowed (law/authorized? owner source (await (authorize! owner source)))
                                                  (conj source)))
                         allowed))
            loaded (if (seq admitted)
                     (await (admission/load-context! ((:event-ports! ports) config) (:digest ports) owner admitted authorize!
                                                    (:context configuration)))
                     (context/assemble-context (:digest ports) owner [] {} (:context configuration)))]
        (assoc loaded :owner owner :inclusion-evidence (inclusion-evidence @decisions* (:encounters loaded)))))))
