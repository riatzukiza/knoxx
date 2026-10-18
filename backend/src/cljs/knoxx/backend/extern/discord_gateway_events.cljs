(ns knoxx.backend.extern.discord-gateway-events
  "Discord SDK events, native observations and gateway-specific degraded logging."
  (:require [knoxx.backend.domain.discord.gateway :as decisions]
            [knoxx.backend.domain.error-observatory :as observatory]
            ["discord.js" :as discord]))

(defonce ^:private observation-unavailable* (atom nil))

(defn degraded!
  "Attempt one sanitized gateway observation; observer failure cannot replace a fallback."
  [operation fallback error]
  (let [context (decisions/degraded-context operation fallback (some? error))]
    (try
      (observatory/log-warning! "discord-gateway.degraded" context)
      (catch :default observer-error
        (let [diagnostic {:observation-unavailable? true
                          :boundary "discord-gateway.degraded" :context context
                          :observer-error? (some? observer-error)}]
          (reset! observation-unavailable* diagnostic)
          diagnostic)))))

(defn intent-bits [] (aget discord "GatewayIntentBits"))

(defn partials-enum [] (aget discord "Partials"))

(defn events-enum [] (aget discord "Events"))

(defn channel-type-enum [] (aget discord "ChannelType"))

(defn Client-class [] (aget discord "Client"))

(defn member-role-ids
  [member]
  (try
    (let [roles (when member (.-roles member))
          cache (when roles (.-cache roles))]
      (if cache
        (into-array (for [[role-id _role] cache] role-id))
        #js []))
    (catch js/Error boundary-error
      (degraded! :member-role-ids :empty-array boundary-error)
      #js [])))

(defn map-message
  "Convert a discord.js Message to a plain JS map."
  [message]
  (let [author (.-author message)
        guild (.-guild message)
        member (.-member message)]
    #js {:id (.-id message)
         :channelId (.-channelId message)
         :guildId (or (when guild (.-id guild)) "")
         :content (or (.-content message) "")
         :authorId (or (when author (.-id author)) "")
         :authorUsername (or (when author (.-username author)) "unknown")
         :authorIsBot (boolean (when author (.-bot author)))
         :authorRoleIds (member-role-ids member)
         :timestamp (try (.toISOString (.-createdAt message))
                         (catch js/Error boundary-error
                            (degraded! :message-timestamp :current-time boundary-error)
                            (.toISOString (js/Date.))))
         :attachments (into-array
                       (for [[_id att] (.-attachments message)]
                         #js {:id (.-id att)
                              :filename (or (.-name att) "")
                              :contentType (or (.-contentType att) nil)
                              :size (or (.-size att) 0)
                              :url (or (.-url att) "")}))
         :embeds (into-array
                  (for [embed (.-embeds message)]
                    #js {:title (or (.-title embed) nil)
                         :description (or (.-description embed) nil)
                         :url (or (.-url embed) nil)}))}))

(defn readable-text-channel?
  "Check if a channel is a text-based channel we can read."
  [channel]
  (and channel
       (fn? (.-isTextBased channel))
       (.isTextBased channel)))

(defn log-fn
  "Return a logger function (or nil) for a given level keyword.

   We avoid the old (.-info? log) style because js/console doesn't expose
   predicate fields; it only exposes methods like .info/.warn/.error."
  [log level]
  (let [candidate (case level
                    :info  (aget log "info")
                    :warn  (aget log "warn")
                    :error (aget log "error")
                    :debug (aget log "debug")
                    nil)]
    (when (fn? candidate)
      (fn [& args]
        (try
          (.apply candidate log (to-array args))
          (catch js/Error boundary-error
            (degraded! :logger-call :nil boundary-error)
            nil))))))

(defn notify-message!
  [listeners log message]
  (let [mapped (map-message message)
        log-error (log-fn log :error)]
    (.forEach @listeners
              (fn [listener]
                (try
                  (listener mapped message)
                  (catch js/Error error
                    (when log-error
                      (log-error "[discord-gateway] listener failed" error))))))))

(defn notify-reaction!
  [reaction-listeners log reaction user]
  (let [message (.-message reaction)
        emoji (.-emoji reaction)
        mapped #js {:emoji (or (.-name emoji) "")
                    :message (when message (map-message message))
                    :messageId (or (when message (.-id message)) "")
                    :channelId (or (when message (.-channelId message)) "")
                    :userId (or (when user (.-id user)) "")
                    :userUsername (or (when user (.-username user)) "unknown")}
        log-error (log-fn log :error)]
    (.forEach @reaction-listeners
              (fn [listener]
                (try
                  (listener mapped reaction user)
                  (catch js/Error error
                    (when log-error
                      (log-error "[discord-gateway] reaction listener failed" error))))))))

(defn notify-voice-state!
  [voice-state-listeners log old-state new-state]
  (let [old-channel-id (when old-state (.-channelId old-state))
        new-channel-id (when new-state (.-channelId new-state))
        user (when new-state (.-member new-state))
        user-id (when user (.-id user))
        guild-id (when new-state (.-guild new-state) (.-id (.-guild new-state)))
        action (decisions/voice-state-action old-channel-id new-channel-id)
        mapped #js {:action action
                    :userId user-id
                    :username (or (when user (.-user user)) (when user (.-username user)) "unknown")
                    :guildId guild-id
                    :channelId (or new-channel-id old-channel-id)
                    :oldChannelId old-channel-id
                    :newChannelId new-channel-id}
        log-error (log-fn log :error)]
    (when action
      (.forEach @voice-state-listeners
                (fn [listener]
                  (try
                    (listener mapped old-state new-state)
                    (catch js/Error error
                      (when log-error
                        (log-error "[discord-gateway] voice state listener failed" error)))))))))

(defn handle-client-ready
  [log-info ready-client]
  (when log-info
    (log-info (str "[discord-gateway] ready as "
                   (or (when (.-user ready-client) (.-tag (.-user ready-client))) "unknown")
                   " in " (.. ready-client -guilds -cache -size) " guilds"))))

(defn handle-message-create
  [notify-message message]
  (notify-message message))

(defn ^:async fetch-partial!
  [x]
  (if (.-partial x)
    (await (.fetch x))
    x))

(defn ^:async fetch-reaction-message!
  [reaction]
  (let [message (.-message reaction)]
    (when (and message (.-partial message))
      (await (.fetch message)))
    message))

(defn ^:async handle-reaction-add
  [log-warn notify-reaction reaction user]
  (try
    (let [full-reaction (await (fetch-partial! reaction))]
      (await (fetch-reaction-message! full-reaction))
      (notify-reaction full-reaction user))
    (catch js/Error error
      (when log-warn
        (log-warn "[discord-gateway] reaction ingest failed" error)))))

(defn handle-client-error
  [log-error error]
  (when log-error
    (log-error "[discord-gateway] client error" error)))

(defn handle-voice-state-update
  [notify-voice-state old-state new-state]
  (notify-voice-state old-state new-state))

(defn build-discord-client
  "Create a new discord.js Client and attach event listeners."
  [log notify-message notify-reaction notify-voice-state]
  (let [Client (Client-class)
        GatewayIntentBits (intent-bits)
        Partials (partials-enum)
        Events (events-enum)
        log-info (log-fn log :info)
        log-warn (log-fn log :warn)
        log-error (log-fn log :error)
        next-client (new Client
                        (clj->js {:intents [(.-Guilds GatewayIntentBits)
                                            (.-GuildMessages GatewayIntentBits)
                                            (.-DirectMessages GatewayIntentBits)
                                            (.-GuildMessageReactions GatewayIntentBits)
                                            (.-DirectMessageReactions GatewayIntentBits)
                                            (.-GuildVoiceStates GatewayIntentBits)
                                            (.-MessageContent GatewayIntentBits)]
                                  :partials [(.-Channel Partials)
                                             (.-Message Partials)
                                             (.-Reaction Partials)]}))]
    (.on next-client (.-ClientReady Events) (partial handle-client-ready log-info))
    (.on next-client (.-MessageCreate Events) (partial handle-message-create notify-message))
    (.on next-client (.-MessageReactionAdd Events) (partial handle-reaction-add log-warn notify-reaction))
    (.on next-client (.-Error Events) (partial handle-client-error log-error))
    (.on next-client (.-VoiceStateUpdate Events) (partial handle-voice-state-update notify-voice-state))
    next-client))
