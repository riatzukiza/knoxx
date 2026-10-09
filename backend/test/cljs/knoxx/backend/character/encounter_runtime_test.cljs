(ns knoxx.backend.character.encounter-runtime-test
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [knoxx.backend.domain.action.character-intake]
            [knoxx.backend.infra.character.intake-action :as intake]
            [knoxx.backend.domain.action.registry :as actions]
            [knoxx.backend.domain.contracts.sources :as sources]
            [knoxx.backend.infra.actor.acting :as acting]
            [knoxx.backend.infra.actor.credentials :as credentials]
            [knoxx.backend.infra.agent.tool-catalog :as catalog]
            [knoxx.backend.infra.character.encounter-openplanner :as storage]
            [knoxx.backend.infra.character.encounter-runtime :as encounters]
            [knoxx.backend.infra.clients.openplanner :as openplanner]
            [knoxx.backend.infra.tooling :as tooling]
            [knoxx.backend.law.character.encounter :as law]
            [knoxx.backend.shape.character.encounter :as shape]))

(def ^:private actor-id "fixture-creator")
(def ^:private contract-id "fixture-creator-contract")
(def ^:private input-spec {:actor-id actor-id :contract-id contract-id})

(def ^:private discord-spec
  {:source {:kind "discord" :account-id "9001" :scope-id "fixture-channel" :visibility :private}
   :tool-id "discord.channel.messages" :parameters {:channel_id "fixture-channel"} :limit 4 :poll-mode :newest})

(def ^:private bluesky-spec
  {:source {:kind "bluesky" :account-id "did:plc:fixture-self" :scope-id "home" :visibility :public}
   :tool-id "bluesky.timeline" :parameters {} :limit 4 :poll-mode :newest})

(defn- source-resource [spec identifier]
  {:source/id (if (= "discord" (get-in spec [:source :kind])) :source/fixture-discord :source/fixture-bluesky)
   :source/actor actor-id :source/tools [(:tool-id spec)] :enabled true
   :source/filters {:encounter-source (:source spec) :account-identifier identifier}})

(defn- stored-context [actor]
  {:actor {:binding actor} :actorId actor :org {:id "fixture-org"}
   :membership {:id (str "fixture-member-" actor)} :role-slugs ["creator"]
   :tool-policies (mapv #(hash-map :tool-id (:tool-id %) :effect "allow") [discord-spec bluesky-spec])})

(defn- discord-row [id text]
  {:id id :channelId "fixture-channel" :authorId "external-discord-author"
   :timestamp "2026-10-07T10:00:00+00:00" :content text
   :reactions [{:emoji {:name "wave" :id "5678"} :count 2}]
   :attachments [{:id "image-fixture" :contentType "image/png" :filename "image.png"}]})

(defn- bluesky-row [id text]
  {:uri (str "at://did:plc:external/app.bsky.feed.post/" id) :authorId "did:plc:external"
   :createdAt "2026-10-07T10:00:00.123456Z" :text text
   :reactionCounts {:like 3} :embed {:images [{:fullsize "https://example.test/image.png" :alt "source-provided alt"}]}})

(defn- fixture []
  {:rows* (atom []) :queries* (atom []) :source-reads* (atom []) :authority-lookups* (atom [])
   :account-lookups* (atom []) :tool-builds* (atom []) :store-opens* (atom 0)
   :fail-after-append* (atom false) :after-read* (atom nil) :read-failures* (atom #{}) :store* (atom nil)
   :at* (atom "2026-10-07T10:01:00.000Z")
   :accounts* (atom {"discord" {:credential-id "fixture-discord-credential" :account-identifier "9001"}
                     "bluesky" {:credential-id "fixture-bluesky-credential" :account-identifier "creator.fixture.test"}})
   :contexts* (atom {actor-id (stored-context actor-id)})
   :selected* (atom {:id contract-id :actor-id actor-id :enabled true
                     :tool-ids #{"discord.channel.messages" "bluesky.timeline"}
                     :sources [(source-resource discord-spec "9001") (source-resource bluesky-spec "creator.fixture.test")]
                     :character-encounters {:sources [discord-spec bluesky-spec] :context {:max-encounters 4}}})
   :details* (atom {"discord.channel.messages" {:channelId "fixture-channel" :rawCount 1
                                                :messages [(discord-row "101" "Discord source 🙂 <:wave:5678>")]}
                    "bluesky.timeline" {:accountId "did:plc:fixture-self" :rawCount 1 :cursor "older-provider-page-one"
                                        :results [(bluesky-row "one" "Bluesky observation revision one")]}})})

(defn- field [row key]
  (get-in row (mapv keyword (str/split (name key) #"\."))))

(defn- sorted-rows [rows order]
  (sort (fn [left right]
          (or (some (fn [[key direction]]
                      (let [compared (* direction (compare (field left key) (field right key)))]
                        (when-not (zero? compared) compared))) order) 0)) rows))

(defn- query! [state payload]
  (swap! (:queries* state) conj payload)
  (is (= "events" (:collection payload)))
  {:ok true :rows (->> @(:rows* state)
                      (filter #(every? (fn [[key expected]] (= expected (field % key))) (:filter payload)))
                      (#(sorted-rows % (:sort payload)))
                      (take (:limit payload)) vec)})

(defn- append! [state events]
  (swap! (:rows* state) into events)
  (when (and @(:fail-after-append* state) (= "character.encounter" (:kind (first events))))
    (reset! (:fail-after-append* state) false)
    (throw (ex-info "Fixture transport failed after durable encounter append" {:fixture :partial-append})))
  {:ok true})

(defn- fixture-read-tools [state _runtime _config current selected allowed]
  (swap! (:tool-builds* state) conj {:actor (:actor-id selected) :allowed allowed})
  (mapv (fn [tool-id]
          {:id tool-id :parameters-schema [:map [:limit :int] [:channel_id {:optional true} :string]
                                           [:after {:optional true} :string]]
           :execute (fn [_call-id args _signal _update]
                      (is (= (:actor-id selected) (acting/current-actor-id)))
                      (is (= (get-in current [:org :id]) (acting/current-org-id)))
                      (is (= (get-in current [:membership :id]) (acting/current-membership-id)))
                      (swap! (:source-reads* state) conj {:tool tool-id :arguments args})
                      (when (contains? @(:read-failures* state) tool-id)
                        (throw (ex-info "Fixture source read: private credential or source text"
                                        {:code :fixture/operational-failure :private-text "MUST NOT ESCAPE"})))
                      (when-let [after-read @(:after-read* state)] (after-read))
                      {:content [] :details (get @(:details* state) tool-id)})}) (sort allowed)))

(defn- fixture-config [state]
  {:session-project-name "fixture-project"
   :resolve-agent-authority! (fn [scope]
                               (swap! (:authority-lookups* state) conj scope)
                               (get @(:contexts* state) (:actor-id scope)))
   :character-encounter-runtime-ports
   {:clock #(deref (:at* state))
    :resolve-spec (fn [_config requested-contract requested-actor]
                    (let [selected @(:selected* state)]
                      (when (and (= requested-contract (:id selected)) (= requested-actor (:actor-id selected))) selected)))
    :source-resources (fn [_config selected] (:sources selected))
    :resolve-account! (fn [_runtime current selected spec]
                        (swap! (:account-lookups* state) conj {:actor (:actor-id selected)
                                                              :org (get-in current [:org :id])
                                                              :membership (get-in current [:membership :id])})
                        (get @(:accounts* state) (get-in spec [:source :kind])))
    :read-tools (partial fixture-read-tools state)
    :event-ports! (fn [_config] (swap! (:store-opens* state) inc) @(:store* state))}})

(defn- ^:async with-runtime-fixture! [state task!]
  (with-redefs [openplanner/assert-event-projection-repair-supported! (fn [client] client)
                openplanner/mongo-query! (fn [_client payload] (query! state payload))
                openplanner/events! (fn [_client events] (append! state events))
                catalog/allowed-tool-ids (fn [_config _current selected] (:tool-ids selected))]
    (reset! (:store* state) (storage/openplanner-ports :fixture-event-client))
    (await (task! (fixture-config state)))))

(defn- scope-checkpoint [state source]
  (->> @(:rows* state)
       (filter #(and (= "character.encounter-cursor" (:kind %))
                      (= (:scope-id source) (get-in % [:extra :source_scope_id]))))
       (map #(shape/wire->checkpoint (get-in % [:extra :encounter_cursor])))
       (sort-by :sequence) last))

(defn- only-discord! [state]
  (swap! (:selected* state) assoc :sources [(source-resource discord-spec "9001")]
         :character-encounters {:sources [discord-spec]}))

(deftest ^:async native-clock-style-intake-stores-two-sources-without-starting-a-provider-or-publication
  (let [state (fixture)]
    (await (with-runtime-fixture!
            state
            (^:async fn [config]
              (with-redefs [tooling/resolve-agent-contract (fn
                                                            ([_config _id] @(:selected* state))
                                                            ([_config _id requested-actor]
                                                             (when (= actor-id requested-actor) @(:selected* state))))]
                (let [ctx {:config config :runtime :fixture-runtime :actor/id actor-id
                           :event {:event/type :clock.tick :event/payload {:actor-id "forged" :character-encounters {:sources []}}}
                           :run-agent! (fn [& _] (throw (ex-info "Fixture forbids provider invocation" {})))}
                      result (await (actions/run-action! ctx {:action/kind :actions/character-intake
                                                             :action/with {:agent-id contract-id :sources [{:untrusted true}]}}))]
                  (is (= intake/intake-handler! (actions/action-handler :actions/character-intake)))
                  (is (:ok result))
                  (is (= [:admitted :admitted] (mapv :status (get-in result [:observation :sources]))))
                  (is (= 4 (count @(:rows* state))))
                  (is (= ["discord.channel.messages" "bluesky.timeline"] (mapv :tool @(:source-reads* state))))
                  (is (= :private (get-in (scope-checkpoint state (:source discord-spec)) [:source :visibility])))
                  (is (= :provider-pagination (get-in (scope-checkpoint state (:source bluesky-spec)) [:coverage :reason])))
                  (is (nil? (:cursor-after (scope-checkpoint state (:source bluesky-spec)))))
                  (is (every? #(= actor-id (:actor %)) @(:tool-builds* state))))))))))

(deftest ^:async fresh-second-poll-reaches-later-context-with-fresh-inclusion-evidence-and-no-pagination-watermark
  (let [state (fixture)]
    (await (with-runtime-fixture!
            state
            (^:async fn [config]
              (await (encounters/observe! :fixture-runtime config input-spec))
              (let [before (await (encounters/decision-context! :fixture-runtime config input-spec nil))]
                (reset! (:at* state) "2026-10-07T10:02:00.000Z")
                (swap! (:details* state) assoc "bluesky.timeline"
                       {:accountId "did:plc:fixture-self" :rawCount 1 :cursor "older-provider-page-two"
                        :results [(bluesky-row "two" "New Bluesky material changes the next creative input")]})
                (await (encounters/observe! :fixture-runtime config input-spec))
                (reset! (:at* state) "2026-10-07T10:03:00.000Z")
                (let [after (await (encounters/decision-context! :fixture-runtime config input-spec nil))]
                  (is (not= (:prompt-context before) (:prompt-context after)))
                  (is (str/includes? (:prompt-context after) "New Bluesky material changes"))
                  (is (str/includes? (:prompt-context after) "wave"))
                  (is (str/includes? (:prompt-context after) "discord:attachment:image-fixture"))
                  (is (= 3 (count (:encounters after))))
                  (is (= (:event-ids after) (mapv :encounter-id (:inclusion-evidence after))))
                  (is (= (:causal-source-ids after) (mapv :causal-source-id (:inclusion-evidence after))))
                  (is (every? #(= "2026-10-07T10:03:00.000Z" (get-in % [:authorization :checked-at])) (:inclusion-evidence after)))
                  (is (every? #(law/authorized? (:owner after) (:source %) (:authorization %))
                              (map (fn [entry evidence] (assoc entry :authorization (:authorization evidence)))
                                   (:encounters after) (:inclusion-evidence after))))
                  (is (every? #(not (contains? (:arguments %) :cursor))
                              (filter #(= "bluesky.timeline" (:tool %)) @(:source-reads* state)))))))))))

(deftest ^:async reopening-existing-event-port-reuses-prior-rows-and-retains-bounded-coverage
  (let [state (fixture)]
    (await (with-runtime-fixture!
            state
            (^:async fn [config]
              (await (encounters/observe! :fixture-runtime config input-spec))
              (let [first-rows @(:rows* state) first-store @(:store* state)]
                (reset! (:store* state) (storage/openplanner-ports :reopened-fixture-event-client))
                (is (not (identical? first-store @(:store* state))))
                (let [replayed (await (encounters/observe! :fixture-runtime config input-spec))
                      loaded (await (encounters/decision-context! :fixture-runtime config input-spec nil))]
                  (is (= [:replayed :replayed] (mapv :status (:sources replayed))))
                  (is (= first-rows @(:rows* state)))
                  (is (= 2 (count (:encounters loaded))))
                  (is (= "older-provider-page-one" (get-in (scope-checkpoint state (:source bluesky-spec)) [:coverage :provider-cursor])))
                  (is (false? (get-in (scope-checkpoint state (:source bluesky-spec)) [:coverage :complete?]))))))))))

(deftest ^:async undeclared-private-disabled-account-mismatched-and-revoked-sources-never-read-or-open-events
  (doseq [change! [(fn [state] (swap! (:selected* state) assoc :sources []))
                   (fn [state] (swap! (:selected* state) assoc-in [:sources 0 :source/filters :encounter-source :scope-id] "other-channel"))
                   (fn [state] (swap! (:selected* state) assoc-in [:sources 0 :enabled] false))
                   (fn [state] (swap! (:selected* state) assoc-in [:sources 0 :source/actor] "different-actor"))
                   (fn [state] (swap! (:accounts* state) assoc-in ["discord" :account-identifier] "different-account"))
                   (fn [state] (swap! (:contexts* state) assoc-in [actor-id :tool-policies] []))]]
    (let [state (fixture)]
      (only-discord! state)
      (change! state)
      (await (with-runtime-fixture!
              state
              (^:async fn [config]
                (let [observed (await (encounters/observe! :fixture-runtime config input-spec))
                      loaded (await (encounters/decision-context! :fixture-runtime config input-spec nil))]
                  (is (= [:denied] (mapv :status (:sources observed))))
                  (is (empty? @(:source-reads* state)))
                  (is (empty? @(:rows* state)))
                  (is (empty? @(:queries* state)))
                  (is (zero? @(:store-opens* state)))
                  (is (= "" (:prompt-context loaded)))
                  (is (= [] (:inclusion-evidence loaded))))))))))

(deftest ^:async revoked-retained-source-is-neither-read-again-nor-exposed-after-admission
  (let [state (fixture)]
    (await (with-runtime-fixture!
            state
            (^:async fn [config]
              (await (encounters/observe! :fixture-runtime config input-spec))
              (reset! (:queries* state) [])
              (reset! (:source-reads* state) [])
              (reset! (:store-opens* state) 0)
              (swap! (:contexts* state) assoc-in [actor-id :tool-policies] [])
              (let [stored @(:rows* state)
                    result (await (encounters/observe! :fixture-runtime config input-spec))
                    loaded (await (encounters/decision-context! :fixture-runtime config input-spec nil))]
                (is (= [:denied :denied] (mapv :status (:sources result))))
                (is (= stored @(:rows* state)))
                (is (empty? @(:queries* state)))
                (is (empty? @(:source-reads* state)))
                (is (zero? @(:store-opens* state)))
                (is (= [] (:encounters loaded)))
                (is (= "" (:prompt-context loaded)))))))))

(deftest ^:async authority-revoked-by-the-source-read-cannot-admit-its-returned-content
  (let [state (fixture)]
    (only-discord! state)
    (reset! (:after-read* state) #(swap! (:contexts* state) assoc-in [actor-id :tool-policies] []))
    (await (with-runtime-fixture!
            state
            (^:async fn [config]
              (let [observed (await (encounters/observe! :fixture-runtime config input-spec))]
                (is (= [:denied] (mapv :status (:sources observed))))
                (is (= [:source-authority-revoked] (mapv :reason (:sources observed))))
                (is (= 1 (count @(:source-reads* state))))
                (is (empty? @(:rows* state)))))))))

(deftest ^:async another-current-owner-cannot-recall-the-first-actors-private-experience
  (let [state (fixture) other-actor "other-fixture-creator"]
    (await (with-runtime-fixture!
            state
            (^:async fn [config]
              (await (encounters/observe! :fixture-runtime config input-spec))
              (swap! (:contexts* state) assoc other-actor (stored-context other-actor))
              (swap! (:selected* state) assoc :actor-id other-actor
                     :sources (mapv #(assoc % :source/actor other-actor) (:sources @(:selected* state))))
              (reset! (:queries* state) [])
              (let [loaded (await (encounters/decision-context! :fixture-runtime config (assoc input-spec :actor-id other-actor)
                                                               (stored-context other-actor)))]
                (is (= other-actor (get-in loaded [:owner :character-id])))
                (is (= [] (:encounters loaded)))
                (is (= "" (:prompt-context loaded)))
                (is (every? #(= other-actor (get-in % [:filter :extra.character_id])) @(:queries* state)))
                (let [refused (try (await (encounters/decision-context! :fixture-runtime config (assoc input-spec :actor-id other-actor)
                                                                        (stored-context actor-id))) nil
                                   (catch :default error (ex-data error)))]
                  (is (= :invalid-actor-context (:reason refused))))))))))

(deftest ^:async partial-admission-preserves-confirmed-after-cursor-and-coverage-until-retry
  (let [state (fixture)]
    (only-discord! state)
    (swap! (:selected* state) assoc-in [:character-encounters :sources 0 :poll-mode] :after)
    (await (with-runtime-fixture!
            state
            (^:async fn [config]
              (await (encounters/observe! :fixture-runtime config input-spec))
              (let [checkpoint (scope-checkpoint state (:source discord-spec))]
                (is (= "101" (:cursor-after checkpoint)))
                (swap! (:details* state) assoc "discord.channel.messages"
                       {:channelId "fixture-channel" :rawCount 2
                        :messages [(discord-row "103" "third observed message") (discord-row "102" "second observed message")]})
                (reset! (:fail-after-append* state) true)
                (let [failed (await (encounters/observe! :fixture-runtime config input-spec))]
                  (is (= :partial (:status failed)))
                  (is (= [:partial] (mapv :status (:sources failed))))
                  (is (= [:source-admission-incomplete] (mapv :reason (:sources failed))))
                  (is (= checkpoint (scope-checkpoint state (:source discord-spec))))
                  (reset! (:store* state) (storage/openplanner-ports :reopened-fixture-event-client))
                  (let [retried (await (encounters/observe! :fixture-runtime config input-spec))
                        progressed (scope-checkpoint state (:source discord-spec))]
                    (is (= [:admitted] (mapv :status (:sources retried))))
                    (is (= "103" (:cursor-after progressed)))
                    (is (= 2 (get-in progressed [:coverage :admitted-count])))
                    (is (false? (get-in progressed [:coverage :complete?])))
                    (is (= 3 (count (filter #(= "character.encounter" (:kind %)) @(:rows* state)))))
                    (is (= [nil "101" "101"] (mapv #(get-in % [:arguments :after]) @(:source-reads* state))))))))))))

(deftest ^:async canonical-source-grant-removal-and-forged-composed-ref-or-request-overrides-cannot-widen
  (let [state (fixture)]
    (only-discord! state)
    (await (with-runtime-fixture!
            state
            (^:async fn [config]
              (let [canonical* (atom (first (:sources @(:selected* state))))
                    without-resource-port (update config :character-encounter-runtime-ports dissoc :source-resources)
                    forged-spec (assoc input-spec :sources [(source-resource discord-spec "9001")]
                                       :character-encounters {:sources [bluesky-spec]})]
                ;; Keep the real source composer: it merges canonical resource
                ;; A with a trusted selected ref carrying override B. The
                ;; runtime must retrieve A again before deciding authority.
                (with-redefs [sources/source-contract (fn [_config _ref] @canonical*)]
                  (await (encounters/observe! :fixture-runtime without-resource-port forged-spec))
                  (is (= ["discord.channel.messages"] (mapv :tool @(:source-reads* state))))
                  (doseq [canonical [(assoc-in @canonical* [:source/filters :encounter-source :scope-id] "canonical-other-channel")
                                     (assoc @canonical* :enabled false) nil]]
                    (reset! canonical* canonical)
                    (reset! (:source-reads* state) [])
                    (reset! (:queries* state) [])
                    (let [denied (await (encounters/observe! :fixture-runtime without-resource-port forged-spec))
                          loaded (await (encounters/decision-context! :fixture-runtime without-resource-port forged-spec nil))]
                      (is (= [:denied] (mapv :status (:sources denied))))
                      (is (empty? @(:source-reads* state)))
                      (is (empty? @(:queries* state)))
                      (is (= [] (:encounters loaded))))))))))))

(deftest ^:async default-account-port-binds-existing-credential-adapter-to-current-actor-org-and-membership
  (let [state (fixture) scopes* (atom [])]
    (only-discord! state)
    (await (with-runtime-fixture!
            state
            (^:async fn [config]
              (with-redefs [credentials/get-credential! (fn [_runtime provider]
                                                         (swap! scopes* conj {:provider provider :actor (acting/current-actor-id)
                                                                             :scope (acting/current-lookup-scope)})
                                                         {:id "fixture-real-port-credential" :accountIdentifier "9001"})]
                (await (encounters/observe! :fixture-runtime
                                           (update config :character-encounter-runtime-ports dissoc :resolve-account!) input-spec))
                (is (seq @scopes*))
                (is (every? #(= {:provider "discord_bot" :actor actor-id
                                  :scope {:org-id "fixture-org" :membership-id (str "fixture-member-" actor-id)}} %) @scopes*))
                (is (= 1 (count @(:source-reads* state))))))))))

(deftest ^:async first-source-read-projection-or-partial-write-failure-preserves-its-cursor-and-admits-fresh-second-source
  (doseq [failure [:read :projection :partial-write]]
    (let [state (fixture)]
      (swap! (:selected* state) assoc-in [:character-encounters :sources 0 :poll-mode] :after)
      (await (with-runtime-fixture!
              state
              (^:async fn [config]
                (await (encounters/observe! :fixture-runtime config input-spec))
                (let [confirmed (scope-checkpoint state (:source discord-spec))]
                  (reset! (:at* state) "2026-10-07T10:02:00.000Z")
                  (swap! (:details* state) assoc
                         "discord.channel.messages" {:channelId "fixture-channel" :rawCount 1
                                                     :messages [(discord-row "102" "next private Discord observation")]}
                         "bluesky.timeline" {:accountId "did:plc:fixture-self" :rawCount 1 :cursor "older-provider-page-two"
                                             :results [(bluesky-row "two" "Fresh second source after first source failure")]})
                  (case failure
                    :read (reset! (:read-failures* state) #{"discord.channel.messages"})
                    :projection (swap! (:details* state) assoc-in ["discord.channel.messages" :messages]
                                       [(dissoc (discord-row "102" "Malformed source without a stable author") :authorId)])
                    :partial-write (reset! (:fail-after-append* state) true))
                  (let [result (await (encounters/observe! :fixture-runtime config input-spec))]
                    (is (= :partial (:status result)))
                    (is (= (if (= :partial-write failure) :partial :failed) (get-in result [:sources 0 :status])))
                    (is (= :admitted (get-in result [:sources 1 :status])))
                    (is (= confirmed (scope-checkpoint state (:source discord-spec))))
                    (is (= "older-provider-page-two" (get-in (scope-checkpoint state (:source bluesky-spec)) [:coverage :provider-cursor])))
                    (is (not (re-find #"MUST NOT ESCAPE|private credential|next private Discord observation" (pr-str result))))
                    (is (= 2 (count (filter #(and (= "character.encounter" (:kind %))
                                                  (= "bluesky" (get-in % [:extra :source_kind]))) @(:rows* state)))))
                    (is (= (if (= :partial-write failure) 2 1)
                           (count (filter #(and (= "character.encounter" (:kind %))
                                                (= "discord" (get-in % [:extra :source_kind]))) @(:rows* state)))))))))))))

(deftest ^:async graph-authority-loads-all-current-candidates-before-the-prompt-cap
  (let [state (fixture)]
    (swap! (:contexts* state) assoc-in [actor-id :user] {:id "fixture-user"})
    (swap! (:contexts* state) assoc-in [actor-id :permissions] ["agent.memory.read"])
    (swap! (:selected* state) assoc-in [:character-encounters :context :max-encounters] 1)
    (await (with-runtime-fixture!
            state
            (^:async fn [config]
              (await (encounters/observe! :fixture-runtime config input-spec))
              (let [prompt (await (encounters/decision-context! :fixture-runtime config input-spec nil))
                    first-read (await (encounters/graph-authority! :fixture-runtime config input-spec nil))]
                (is (= 1 (count (:encounters prompt))))
                (is (= 2 (count (:records first-read))) "Graph admission is before the final prompt cap")
                (is (= actor-id (get-in first-read [:scope :actor-id])))
                (is (= "fixture-user" (get-in first-read [:scope :user-id])))
                (is (= "fixture-project" (:project first-read)))
                (is (every? #(seq (:text %)) (:records first-read)))
                (reset! (:at* state) "2026-10-07T10:03:00.000Z")
                (is (= first-read (await (encounters/graph-authority! :fixture-runtime config input-spec nil)))
                    "Observation time cannot mint a new grant revision")
                (swap! (:contexts* state) assoc-in [actor-id :tool-policies] [])
                (reset! (:queries* state) [])
                (is (nil? (await (encounters/graph-authority! :fixture-runtime config input-spec nil))))
                (is (= [] @(:queries* state)))))))))

(deftest ^:async graph-authority-never-borrows-an-admin-or-missing-user-memory-grant
  (doseq [context [(stored-context actor-id)
                   (assoc (stored-context actor-id) :role-slugs ["system-admin"] :user {:id "fixture-user"})
                   (assoc (stored-context actor-id) :permissions ["agent.memory.read"])]]
    (let [state (fixture)]
      (reset! (:contexts* state) {actor-id context})
      (await (with-runtime-fixture!
              state
              (^:async fn [config]
                (is (nil? (await (encounters/graph-authority! :fixture-runtime config input-spec nil))))
                (is (= [] @(:queries* state)))
                (is (= 0 @(:store-opens* state)))))))))

(deftest ^:async earlier-source-revocation-during-a-later-read-prevents-stale-graph-candidates
  (let [state (fixture)]
    (swap! (:contexts* state) assoc-in [actor-id :user] {:id "fixture-user"})
    (swap! (:contexts* state) assoc-in [actor-id :permissions] ["agent.memory.read"])
    (await (with-runtime-fixture!
            state
            (^:async fn [config]
              (await (encounters/observe! :fixture-runtime config input-spec))
              (let [read! (:recent-encounters! @(:store* state))]
                (swap! (:store* state) assoc :recent-encounters!
                       (^:async fn [owner stream-id limit]
                         (let [rows (await (read! owner stream-id limit))]
                           (when (some #(= "bluesky" (get-in % [:extra :source_kind])) rows)
                             (swap! (:contexts* state) assoc-in [actor-id :tool-policies]
                                    [{:tool-id "bluesky.timeline" :effect "allow"}]))
                           rows)))
                (let [result (await (encounters/graph-authority! :fixture-runtime config input-spec nil))]
                  (is (nil? result) "A cross-await source grant change refuses the complete held snapshot")
                  (is (not (str/includes? (pr-str result) "Discord source"))))))))))
