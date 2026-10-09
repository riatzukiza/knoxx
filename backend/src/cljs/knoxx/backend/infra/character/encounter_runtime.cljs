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

(defn- principal-binding [current]
  [(:actorId current) (get-in current [:org :id])
   (get-in current [:membership :id]) (get-in current [:user :id])])

(defn- source-spec [selected source]
  (first (filter #(= source (:source %)) (:sources (encounter-configuration selected)))))

(defn- source-scope [ports config selected current owner source]
  (let [spec (source-spec selected source)
        resources ((:source-resources ports) config selected)
        allowed ((:allowed-tool-ids ports) config current selected)]
    (when (and spec (= owner (owner! config selected current))
               (social/source-scope-admitted? spec resources allowed))
      {:spec spec :resources resources :allowed allowed})))

(defn- source-grant-binding [ports owner source selected current spec account resources]
  ;; Policy/resource facts only invalidate retained authority. Their constraint
  ;; grammar remains owned by the canonical authorization/tool boundaries.
  [(shape/owner-binding owner) (shape/source-binding source) (:id selected) (:actor-id selected)
   (:tool-id spec) (:credential-id account) (:account-identifier account) (principal-binding current)
   ((:digest ports) (pr-str [spec (authz/ctx-tool-policy current (:tool-id spec))
                            (authz/system-admin? current) (:resourcePolicies current) (:resource-policies selected)
                            (sort-by #(pr-str (:source/id %)) resources)]))])

(defn- source-state [ports config selected current owner source account]
  (if-let [{:keys [spec resources allowed]} (source-scope ports config selected current owner source)]
    (let [bound (filterv #(and (social/source-scope-admitted? spec [%] allowed)
                              (social/source-account-admitted? spec [%] (:actor-id selected)
                                                               (:account-identifier account))) resources)]
      (if-not (and (m/validate shape/NonBlankString (:credential-id account)) (seq bound))
        {:decision (denial :source-account-unbound)}
        {:selected selected :auth-context current :spec spec :allowed allowed :account account
         :grant-binding (source-grant-binding ports owner source selected current spec account bound)
         :decision (authorization-evidence ports owner source selected current spec account bound allowed)}))
    {:decision (denial :source-scope-denied)}))

(defn- ^:async admitted-source-state! [ports runtime config agent-spec auth-context owner source]
  (let [selected (await (selected-spec! ports config agent-spec))
        current (await ((:resolve-authority! ports) config auth-context selected))]
    (if-let [{:keys [spec]} (source-scope ports config selected current owner source)]
      (let [account (await ((:resolve-account! ports) runtime current selected spec))
            after-selected (await (selected-spec! ports config agent-spec))
            after (await ((:resolve-authority! ports) config auth-context after-selected))]
        ;; No source/tool access follows the account await on an earlier grant.
        (if (and (= (principal-binding current) (principal-binding after))
                 (= spec (source-spec after-selected source)))
          (source-state ports config after-selected after owner source account)
          {:decision (denial :source-authority-changed)}))
      {:decision (denial :source-scope-denied)})))

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

(defn- ^:async observe-source! [ports runtime config agent-spec auth-context owner spec]
  (let [source (:source spec)
        authorize! (^:async fn [requested-owner requested-source]
                     (:decision (await (current-source-state! ports runtime config agent-spec auth-context requested-owner requested-source))))
        decision (await (authorize! owner source))]
    (if-not (law/authorized? owner source decision)
      {:source source :status :denied :reason (:reason decision)}
      (let [store ((:event-ports! ports) config)
            stream-id (law/stream-id (:digest ports) owner source)
            checkpoint (await ((:latest-checkpoint! store) owner stream-id))
            _ (law/assert-checkpoint! (:digest ports) owner source checkpoint)
            pulled (await ((:pull-source! ports) (source-ports ports runtime config agent-spec auth-context owner source authorize!)
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

(defn- ^:async observe-source-contained! [ports runtime config agent-spec auth-context owner spec]
  (try
    (await (observe-source! ports runtime config agent-spec auth-context owner spec))
    (catch :default error (source-failure (:source spec) error))))

(defn- observation-status [results]
  (if (some #(contains? #{:failed :partial} (:status %)) results) :partial :observed))

(defn ^:async observe!
  "Observe configured sources between creative opportunities. This starts no
   provider turn or publication. Current canonical source/account grants are
   checked before source reads and again before admission; cursors live only in
   existing OpenPlanner events, and partial admission does not advance them.
   Scoped callers supply an authenticated host context; the legacy arity cannot
   synthesize one from a requested actor."
  ([runtime config agent-spec]
   (await (observe! runtime config agent-spec nil)))
  ([runtime config agent-spec auth-context]
  (let [ports (runtime-ports config)
        selected (await (selected-spec! ports config agent-spec))
        configuration (encounter-configuration selected)]
    (if-not configuration
      {:status :disabled :sources []}
      (let [current (await ((:resolve-authority! ports) config auth-context selected))
            owner (owner! config selected current)]
        (loop [remaining (:sources configuration) results []]
          (if-let [spec (first remaining)]
            (recur (next remaining) (conj results (await (observe-source-contained! ports runtime config selected auth-context owner spec))))
            {:status (observation-status results) :owner owner :sources results})))))))

(defn- inclusion-evidence [decisions encounters]
  (mapv (fn [record]
          {:encounter-id (:id record) :causal-source-id (:source-id record)
           :authorization (get decisions (:source record))}) encounters))

(defn- graph-principal? [current]
  (and (m/validate shape/NonBlankString (get-in current [:user :id]))
       (authz/ctx-permitted? current "agent.memory.read")))

(defn- ^:async current-graph-principal! [ports config selected auth-context]
  (let [current (await ((:resolve-authority! ports) config auth-context selected))]
    (when (graph-principal? current) current)))

(defn- ^:async source-candidates! [ports runtime config selected configuration auth-context owner]
  (let [states* (atom {})
        authorize! (^:async fn [requested-owner source]
                     (let [state (await (current-source-state! ports runtime config selected auth-context requested-owner source))]
                       (swap! states* assoc source state)
                       (:decision state)))
        admitted (loop [remaining (map :source (:sources configuration)) allowed []]
                   (if-let [source (first remaining)]
                     (recur (next remaining)
                            (cond-> allowed (law/authorized? owner source (await (authorize! owner source)))
                              (conj source)))
                     allowed))
        loaded (if (seq admitted)
                   (await (admission/load-eligible! ((:event-ports! ports) config) (:digest ports)
                                                    owner admitted authorize! (:context configuration)))
                   {:records [] :decisions {}})]
    (assoc loaded :states @states* :sources admitted)))

(defn- snapshot-source-states [ports config selected current owner states]
  ;; Synchronous batch: no early source decision survives a later source await
  ;; merely because it was once allowed. Account metadata was captured at its
  ;; own current credential boundary; all bindings use this final principal.
  (into {} (map (fn [[source captured]]
                  (let [fresh (source-state ports config selected current owner source (:account captured))]
                    [source (if (and (:grant-binding captured)
                                     (= (:grant-binding captured) (:grant-binding fresh)))
                              fresh {:decision (denial :source-authority-changed)})])) states)))

(defn- ^:async final-source-snapshot! [ports config selected auth-context owner states]
  (try
    (let [after-selected (await (selected-spec! ports config selected))
          current (await ((:resolve-authority! ports) config auth-context after-selected))]
      {:selected after-selected :configuration (encounter-configuration after-selected) :current current
       :states (snapshot-source-states ports config after-selected current owner states)})
    ;; knoxx-lint/allow-silent-catch — unavailable authority is a closed snapshot;
    ;; credential/policy exception payloads are never disclosure metadata.
    (catch :default _error nil)))

(defn- snapshot-decisions [ports owner snapshot]
  (into {} (map (fn [[source state]]
                  [(law/stream-id (:digest ports) owner source) (:decision state)]) (:states snapshot))))

(defn- graph-snapshot [ports owner candidates snapshot]
  (let [{:keys [selected configuration current states]} snapshot
        sources (:sources candidates)
        bindings (mapv #(get-in states [% :grant-binding]) sources)]
    (when (and snapshot (seq sources) (graph-principal? current)
               (every? #(law/authorized? owner % (get-in states [% :decision])) sources))
      {:scope {:actor-id (:actor-id selected) :org-id (:org-id owner)
               :membership-id (get-in current [:membership :id]) :user-id (get-in current [:user :id])
               :policy-revision (str "encounter-policy:"
                                     ((:digest ports) (pr-str [(principal-binding current) bindings])))}
       :project (:project owner)
       :records (mapv #(select-keys % [:id :text])
                      (context/eligible-records (:digest ports) owner (:records candidates)
                                                (snapshot-decisions ports owner snapshot)
                                                (merge context/default-options (:context configuration))))})))

(defn- ^:async graph-candidates! [ports runtime config selected configuration auth-context owner]
  (let [candidates (await (source-candidates! ports runtime config selected configuration auth-context owner))
        snapshot (await (final-source-snapshot! ports config selected auth-context owner (:states candidates)))]
    (when-let [graph (graph-snapshot ports owner candidates snapshot)]
      (assoc candidates :records (context/eligible-records (:digest ports) owner (:records candidates)
                                                          (snapshot-decisions ports owner snapshot)
                                                          (merge context/default-options (:context configuration)))
                        :states (:states snapshot) :bindings (mapv #(get-in snapshot [:states % :grant-binding]) (:sources candidates))
                        :scope (:scope graph)))))

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
              after (await (final-source-snapshot! ports config selected auth-context owner (:states candidates)))]
          (when (and candidates (= (principal-binding current) (principal-binding (:current after))))
            (graph-snapshot ports owner candidates after)))))))

(defn ^:async disclosure-snapshot!
  "Reload source candidates, then validate every captured source/account binding
   synchronously against one final canonical principal and current resources.
   Direct context requires source grants; only graph authority requires memory.
   This is an await-boundary check, not an atomic grant reservation."
  [runtime config agent-spec auth-context]
  (let [ports (runtime-ports config)
        selected (await (selected-spec! ports config agent-spec))
        configuration (encounter-configuration selected)]
    (when configuration
      (let [current (await ((:resolve-authority! ports) config auth-context selected))
            owner (owner! config selected current)
            candidates (await (source-candidates! ports runtime config selected configuration auth-context owner))
            snapshot (await (final-source-snapshot! ports config selected auth-context owner (:states candidates)))
            decisions (snapshot-decisions ports owner snapshot)
            loaded (context/assemble-context (:digest ports) owner (:records candidates) decisions
                                             (:context (:configuration snapshot)))
            by-source (into {} (map (fn [[source state]] [source (:decision state)]) (:states snapshot)))]
        {:context (assoc loaded :owner owner :inclusion-evidence (inclusion-evidence by-source (:encounters loaded)))
         :graph-authority (graph-snapshot ports owner candidates snapshot)
         :auth-context (:current snapshot)}))))

(defn ^:async decision-context!
  "Assemble freshly authorized direct source context before its prompt caps."
  [runtime config agent-spec auth-context]
  (:context (await (disclosure-snapshot! runtime config agent-spec auth-context))))
