(ns knoxx.backend.extern.discord-gateway-fixture
  "Inert public SDK seams around the real original gateway factory. No login/network."
  (:require [knoxx.backend.domain.discord.gateway :as api]
            [knoxx.backend.domain.error-observatory :as observatory]
            ["discord.js" :as discord]))

(def instant "2026-01-02T03:04:05.000Z")
(def guild-id "gateway-fixture-guild")
(def channel-id "gateway-fixture-channel")
(def token "gateway-fixture-inert-token")

(defn fail! [message] (throw (js/Error. message)))
(defn note! [world op & args] (swap! (:calls world) conj [op (vec args)]))
(defn calls [world op] (filterv #(= op (first %)) @(:calls world)))
(defn snapshot [value] (js->clj value :keywordize-keys true))
(defn latest-client [world] (peek @(:clients world)))
(defn manager [world] @(:manager world))
(defn set-option! [world k value] (swap! (:options world) assoc k value))
(defn value [object k] (aget object k))
(defn identical-value? [a b] (identical? a b))
(defn event [name] (aget (aget discord "Events") name))
(defn emit! [world name & args] (.apply (.-emit (latest-client world)) (latest-client world) (to-array (cons (event name) args))))
(defn invoke! [object method & args] (.apply (aget object method) object (to-array args)))
(defn started-status [world] (snapshot (.status (manager world))))
(defn public-started [] (api/started?))
(defn public-ready [] (api/ready?))
(defn public-default [] (api/gateway-manager))
(defn public-actor [actor] (api/gateway-manager actor))
(defn actor-map [] (api/gateway-managers))
(defn set-actor! [actor manager] (api/set-actor-manager! actor manager))
(defn actor-object [start stop status]
  #js {:start start :stop stop :status (fn [] (clj->js status))})
(defn start-actor! [actor token-value] (api/start-actor-gateway! actor token-value))
(defn ensure-actor! [actor] (api/ensure-actor-manager! actor))
(defn start-actors! [rows] (api/start-actor-gateways! (clj->js rows)))
(defn start! [world] (.start (manager world) token))
(defn stop! [world] (.stop (manager world)))
(defn restart! [world token-value] (.restart (manager world) token-value))
(defn ^:async rejected [promise]
  (try {:resolved (await promise)} (catch :default error {:rejected error})))
(defn message
  ([] (message "message-a" "hello" instant))
  ([id content timestamp]
   #js {:id id :channelId channel-id :content content
        :guild #js {:id guild-id} :author #js {:id "author-a" :username "alice" :bot false}
        :member #js {:roles #js {:cache (js/Map. #js [#js ["role-a" #js {}]])}}
        :createdAt (js/Date. timestamp) :attachments (js/Map.) :embeds #js []}))
(defn throwing-property! [world object field label]
  (js/Object.defineProperty object field
    #js {:configurable true :get (fn [] (note! world label) (fail! "inert-private-error-text"))}))
(defn map-entries [rows] (js/Map. (into-array (map (fn [[k v]] #js [k v]) rows))))
(defn set-messages! [world rows] (reset! (:messages world) rows))
(defn set-send-result! [world handler] (reset! (:send world) handler))
(defn sent [world] (mapv #(snapshot (first (second %))) (calls world :send)))
(defn add-listener! [world kind listener] (invoke! (manager world) kind listener))

(defn- descriptor [object field]
  (let [d (js/Object.getOwnPropertyDescriptor object field)]
    (when-not (and d (true? (.-configurable d)) (true? (.-writable d)) (fn? (.-value d)))
      (fail! (str "Unsupported public fixture property: " field)))
    d))
(defn- same-descriptor? [a b]
  (every? #(identical? (aget a %) (aget b %)) ["value" "writable" "enumerable" "configurable" "get" "set"]))
(defn- replace! [object field original replacement]
  (js/Object.defineProperty object field
    #js {:value replacement :writable (.-writable original)
         :enumerable (.-enumerable original) :configurable (.-configurable original)}))
(defn- logger [world]
  (let [log #js {}]
    (doseq [level [:info :warn :error :debug]]
      (aset log (name level)
        (fn [& args]
          (this-as receiver
            (note! world :logger level (identical? receiver log) (count args))
            (when (= level (:logger-error @(:options world))) (fail! "inert-logger-failed"))))))
    log))
(defn- channel [world]
  #js {:id channel-id :name "fixture-text" :guildId guild-id :type 0
       :guild #js {:voiceAdapterCreator (fn [& _] (note! world :forbidden-adapter) (fail! "No voice adapter may be opened"))}
       :isTextBased (fn [] true)
       :members (map-entries [["member-a" #js {:user #js {:id "member-a" :username "member-name" :bot true}
                                                   :displayName "Member Display" :mute true :deaf false}]])
       :messages #js {:fetch (fn [opts] (note! world :fetch-messages (snapshot opts)) (map-entries @(:messages world)))}
       :send (fn [payload] (note! world :send payload) (@(:send world) payload))})
(defn- install-client-data! [world client]
  (let [c (channel world)
        dm #js {:id "fixture-dm" :messages (.-messages c)}
        guild #js {:id guild-id :name "Fixture Guild" :memberCount 7
                   :channels #js {:fetch (fn [id] (note! world :guild-channels id)
                                           (if id (get @(:channels world) id) (map-entries @(:channels world))))}}
        originals (mapv (fn [field] [field (js/Object.getOwnPropertyDescriptor client field)]) ["channels" "guilds" "users" "user"])]
    (swap! (:client-fields world) conj [client originals])
    (reset! (:channels world) {channel-id c})
    (aset client "channels" #js {:fetch (fn [id] (note! world :fetch-channel id) (get @(:channels world) id))})
    (aset client "guilds" #js {:cache (map-entries [[guild-id guild]])
                             :fetch (fn [id] (note! world :fetch-guild id) (when (= id guild-id) guild))})
    (aset client "users" #js {:fetch (fn [id] (note! world :fetch-user id)
                                            #js {:createDM (fn [] (note! world :create-dm id) dm)})})
    (aset client "user" #js {:id "fixture-self" :tag "fixture#0001"})))
(defn- restore-client-fields! [world client]
  (when-let [[_ fields] (some (fn [[candidate :as row]] (when (identical? candidate client) row)) @(:client-fields world))]
    (doseq [[field d] fields] (js/Object.defineProperty client field d))))
(defn- login [world]
  (fn [actual-token]
    (this-as client
      (note! world :login client actual-token)
      (swap! (:clients world) conj client)
      (install-client-data! world client)
      (if-let [error (:login-error @(:options world))]
        (js/Promise.reject error)
        (js/Promise.resolve client)))))
(defn- destroy [world original]
  (fn []
    (this-as client
      (note! world :destroy client)
      (restore-client-fields! world client)
      (let [cleanup (.call original client)]
        (swap! (:cleanups world) conj cleanup)
        (swap! (:destroyed world) conj client)
        (if (:destroy-error? @(:options world))
          (fail! "inert-destroy-failed")
          cleanup)))))
(defn- ready [world]
  (fn []
    (this-as client
      (note! world :is-ready client)
      (when (:ready-error? @(:options world)) (fail! "inert-ready-failed"))
      true)))
(defn- warning [world]
  (fn [boundary context]
    (swap! (:warnings world) conj [boundary context])
    (when-let [mode (:observer-error @(:options world))]
      (if (= :nil mode) (throw nil) (fail! "inert-observer-failed")))
    {:boundary boundary :context context}))
(defn warning-rows [world] @(:warnings world))

(defn- world-state [options]
  (when (js/Object.prototype.hasOwnProperty.call (.-env js/process) "DISCORD_TOKEN")
    (fail! "Fixture refuses an inherited DISCORD_TOKEN property"))
  (let [prototype (.-prototype (aget discord "Client"))]
    {:prototype prototype
     :originals (mapv (fn [field] [field (descriptor prototype field)]) ["login" "destroy" "isReady"])
     :prior-default (api/gateway-manager) :prior-actors (api/gateway-managers)
     :options (atom options) :calls (atom []) :clients (atom [])
     :destroyed (atom []) :cleanups (atom []) :client-fields (atom []) :manager (atom nil)
     :channels (atom {}) :messages (atom []) :send (atom (fn [_] (js/Promise.resolve #js {:id "discarded-send-id"})))
     :warnings (atom []) :restoration (atom nil)}))
(defn- install-world! [{:keys [prototype originals prior-actors] :as world}]
  (replace! prototype "login" (second (first originals)) (login world))
  (replace! prototype "destroy" (second (second originals)) (destroy world (.-value (second (second originals)))))
  (replace! prototype "isReady" (second (nth originals 2)) (ready world))
  (doseq [[actor _] prior-actors] (api/set-actor-manager! actor nil))
  (reset! (:manager world) (api/createDiscordGatewayManager #js {:log (logger world) :setDefault false})))
(defn- restore-registry-and-descriptors! [{:keys [prototype originals prior-actors prior-default] :as world}]
  (doseq [[field d] originals] (js/Object.defineProperty prototype field d))
  (doseq [[actor _] (api/gateway-managers)] (api/set-actor-manager! actor nil))
  (doseq [[actor actor-manager] prior-actors] (api/set-actor-manager! actor actor-manager))
  (api/set-manager! prior-default)
  (let [descriptors? (every? (fn [[field d]] (same-descriptor? d (js/Object.getOwnPropertyDescriptor prototype field))) originals)
        registries? (and (identical? prior-default (api/gateway-manager)) (= prior-actors (api/gateway-managers)))]
    (reset! (:restoration world) {:descriptors descriptors? :registries registries?})
    (when-not (and descriptors? registries?) (fail! "Gateway fixture restoration failed"))))
(defn- ^:async restore-world! [world]
  (try
    (doseq [client @(:clients world)]
      (when-not (some #(identical? client %) @(:destroyed world))
        (restore-client-fields! world client)
        (swap! (:cleanups world) conj (.call (.-value (second (second (:originals world)))) client))))
    (await (js/Promise.all (to-array @(:cleanups world))))
    (finally (restore-registry-and-descriptors! world))))
(defn ^:async with-world
  "Keep native descriptors and gateway registries under exact finally custody."
  [options action]
  (let [world (world-state options)]
    (try
      (install-world! world)
      (with-redefs [observatory/log-warning! (warning world)] (await (action world)))
      (finally (await (restore-world! world))))))

(defn ^:async run-lifecycle! [world]
  (let [m (manager world) first-client (await (.start m (str "  " token "  ")))
        second-client (await (.start m token))
        before (started-status world)]
    (await (.restart m "replacement-inert-token"))
    (let [restarted (latest-client world)]
      (await (.start m "   "))
      {:same-client (identical? first-client second-client)
       :before before :after (started-status world) :restart-new (not (identical? first-client restarted))
       :login-tokens (mapv #(nth (second %) 1) (calls world :login))
       :destroy-count (count (calls world :destroy))
       :false-still-default (identical? m (api/gateway-manager))})))

(defn ^:async run-listings-and-messages! [world]
  (await (start! world))
  (set-messages! world [["old" (message "old" "Hello older" "2026-01-01T00:00:00.000Z")]
                        ["new" (message "new" "hello newest" instant)]])
  (let [m (manager world)
        servers (await (.listServers m)) channels (await (.listChannels m)) specific (await (.listChannels m guild-id))
        messages (await (.fetchChannelMessages m channel-id #js {:limit 500 :before "before" :after "after" :around "around"}))
        dm (await (.fetchDmMessages m "target-user" #js {:limit 0 :before "dm-before"}))
        search (await (.searchMessages m "CHANNEL" #js {:channelId channel-id :query "HELLO" :userId "author-a" :limit 1}))
        dm-search (await (.searchMessages m "dm" #js {:userId "target-user" :query "newest" :limit 3}))]
    {:servers (snapshot servers) :channels (snapshot channels) :specific (snapshot specific)
     :messages (snapshot messages) :dm (snapshot dm) :search (snapshot search) :dm-search (snapshot dm-search)
     :fetch-options (mapv #(first (second %)) (calls world :fetch-messages))}))

(defn ^:async run-message-events! [world]
  (await (start! world))
  (let [seen (atom []) m (manager world) raw (message)
        remove-message (.onMessage m (fn [mapped message-value] (swap! seen conj [:message (snapshot mapped) (identical? raw message-value)])))
        remove-reaction (.onReaction m (fn [mapped _raw user] (swap! seen conj [:reaction (snapshot mapped) (.-id user)])))
        remove-voice (.onVoiceStateUpdate m (fn [mapped _old _new] (swap! seen conj [:voice (snapshot mapped)])))
        reaction #js {:message raw :emoji #js {:name "wave"} :partial true}
        full #js {:message raw :emoji #js {:name "wave"} :partial false}]
    (aset reaction "fetch" (fn [] (note! world :reaction-fetch) (js/Promise.resolve full)))
    (aset raw "partial" true)
    (aset raw "fetch" (fn [] (note! world :reaction-message-fetch) (js/Promise.resolve raw)))
    (emit! world "MessageCreate" raw)
    (let [reaction-handlers (.listeners (latest-client world) (event "MessageReactionAdd"))]
      (await (.call (aget reaction-handlers 0) (latest-client world) reaction #js {:id "reactor" :username "reactor-name"})))
    (emit! world "VoiceStateUpdate" #js {:channelId nil} #js {:channelId "voice-channel" :member #js {:id "voice-user" :user "native-user-value"} :guild #js {:id guild-id}})
    (remove-message) (remove-reaction) (remove-voice)
    (emit! world "MessageCreate" raw)
    {:seen @seen :partial-order (mapv first (filterv #(contains? #{:reaction-fetch :reaction-message-fetch} (first %)) @(:calls world)))}))

(defn ^:async run-send! [world text attachments]
  (await (start! world))
  (let [result (await (.sendMessage (manager world) channel-id text "reply-id" (clj->js attachments)))]
    {:result (snapshot result) :sent (sent world)}))

(def forwarding-spec
  [[:status "status" [] api/status] [:start "start" ["t"] api/start!]
   [:stop "stop" [] api/stop!] [:restart "restart" ["t"] api/restart!]
   [:message "onMessage" [:listener] api/on-message!] [:reaction "onReaction" [:listener] api/on-reaction!]
   [:voice "onVoiceStateUpdate" [:listener] api/on-voice-state-update!]
   [:servers "listServers" [] api/list-servers] [:channels-zero "listChannels" [] api/list-channels]
   [:channels-one "listChannels" ["g"] api/list-channels]
   [:fetch "fetchChannelMessages" ["c" :opts] api/fetch-channel-messages]
   [:dm "fetchDmMessages" ["u" :opts] api/fetch-dm-messages]
   [:search "searchMessages" ["dm" :opts] api/search-messages]
   [:send-three "sendMessage" ["c" "body" "reply"] api/send-message]
   [:send-four "sendMessage" ["c" "body" "reply" :files] api/send-message]
   [:join "joinVoice" ["c"] api/join-voice] [:leave "leaveVoice" ["g"] api/leave-voice]
   [:audio "playAudio" ["g" :audio] api/play-audio]
   [:listen "startVoiceListener" ["g" :start :audio] api/start-voice-listener]
   [:connection "getVoiceConnection" ["g"] api/get-voice-connection]
   [:members "listVoiceMembers" ["g" "c"] api/list-voice-members]])
(defn forwarding-results! []
  (let [m #js {} seen (atom []) sentinel #js {}]
    (doseq [method (distinct (map second forwarding-spec))]
      (aset m method (fn [& args] (this-as receiver (swap! seen conj [method (identical? m receiver) (vec args)]) sentinel))))
    (api/set-manager! m)
    (let [results (mapv (fn [[id method args f]]
                          {:id id :method method :args (if (= id :send-three) (conj args nil) args)
                           :same-return (identical? sentinel (apply f args))}) forwarding-spec)]
      (api/set-manager! nil)
      {:results results :calls @seen :nil-returns (every? (fn [[_ _ args f]] (nil? (apply f args))) forwarding-spec)
       :started-false (false? (api/started?)) :ready-nil (nil? (api/ready?))})))

(defn ^:async run-voice-members! [world]
  (await (start! world))
  (snapshot (await (.listVoiceMembers (manager world) guild-id channel-id))))

(defn ^:async run-listener-failures! [world]
  (await (start! world))
  (let [seen (atom []) m (manager world) raw (message)
        listener (fn [& _] (fail! "inert-listener-error"))]
    (.onMessage m listener)
    (.onMessage m (fn [& _] (swap! seen conj :message)))
    (.onReaction m listener)
    (.onReaction m (fn [& _] (swap! seen conj :reaction)))
    (.onVoiceStateUpdate m listener)
    (.onVoiceStateUpdate m (fn [& _] (swap! seen conj :voice)))
    (emit! world "MessageCreate" raw)
    (let [handlers (.listeners (latest-client world) (event "MessageReactionAdd"))]
      (await (.call (aget handlers 0) (latest-client world) #js {:message raw :emoji #js {:name "wave"}} #js {:id "r"})))
    (emit! world "VoiceStateUpdate" #js {:channelId "old"} #js {:channelId "new" :member #js {:id "u"} :guild #js {:id guild-id}})
    {:seen @seen :error-log-count (count (filter #(= :error (first (second %))) (calls world :logger)))}))
