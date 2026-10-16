(ns knoxx.backend.infra.discord.gateway
  "Default and actor Discord gateway state and existing public orchestration."
  (:require [clojure.string :as str]
            [knoxx.backend.domain.discord.gateway :as decisions]
            [knoxx.backend.extern.discord-gateway :as native]))

(declare set-manager!)

(defn createDiscordGatewayManager
  "Create a Discord gateway manager. Returns a JS object with async methods."
  [opts]
  (let [{:keys [log set-default?]} (native/parse-gateway-manager-opts opts)
        manager (native/create-gateway-manager-object! log (native/gateway-manager-state))]
    (when set-default?
      (set-manager! manager))
    manager))

(defonce ^:private manager* (atom nil))

(defonce ^:private actor-managers* (atom {}))

(defn set-manager!
  "Store the gateway manager instance for CLJS API access."
  [m]
  (reset! manager* m))

(defn gateway-manager
  "Returns the legacy/default gateway manager instance, or an actor-owned manager."
  ([] @manager*)
  ([actor-id]
   (if-let [id (decisions/normalize-actor-id actor-id)]
     (get @actor-managers* id)
     @manager*)))

(defn set-actor-manager!
  "Store `manager` for exactly one actor, or remove it when manager is nil.

   The actor id is mandatory even for removal. That keeps a blank actor from
   becoming an alias for the legacy/default manager, which would turn an
   actor-scoped caller into a process-scoped one."
  [actor-id manager]
  (let [actor-id (decisions/normalize-actor-id actor-id)]
    (when-not actor-id
      (throw (native/error "actor id is required for Discord actor gateway")))
    (if (nil? manager)
      (swap! actor-managers* dissoc actor-id)
      (swap! actor-managers* assoc actor-id manager))
    manager))

(defn gateway-managers
  "Returns a map of actor-id to actor-owned Discord gateway managers."
  []
  @actor-managers*)

(defn- credential-secret-value
  [credential & ks]
  (let [secrets (native/credential-value credential :secretJson)]
    (some (fn [k]
            (some-> (or (native/credential-value secrets k)
                        (native/credential-value secrets (keyword k))
                        (native/credential-value secrets (name k)))
                    str
                    str/trim
                    not-empty))
          ks)))

(defn- credential-actor-id
  [credential]
  (some-> (or (native/credential-value credential :actorId)
              (native/credential-value credential :actor-id)
              (native/credential-value credential :actor_id))
          str
          str/trim
          not-empty))

(defn- credential-bot-token
  [credential]
  (credential-secret-value credential :botToken :bot-token :token))

(defn ensure-actor-manager!
  [actor-id]
  (let [actor-id (decisions/normalize-actor-id actor-id)]
    (when-not actor-id
      (throw (native/error "actor id is required for Discord actor gateway")))
    (or (get @actor-managers* actor-id)
        (let [manager (createDiscordGatewayManager (native/actor-manager-options))]
          (set-actor-manager! actor-id manager)
          manager))))

(defn ^:async start-actor-gateway!
  [actor-id token]
  (let [manager (ensure-actor-manager! actor-id)]
    (await (native/manager-start manager token))
    {:actorId actor-id
     :status (native/decoded-status manager)}))

(defn- credential->actor-gateway-start
  [credential]
  (let [actor-id (credential-actor-id credential)
        token (credential-bot-token credential)]
    (when (and actor-id token)
      {:actorId actor-id :token token})))

(defn- actor-gateway-starts
  [credentials]
  (->> (native/decode-credentials credentials)
       (keep credential->actor-gateway-start)
       vec))

(defn- stop-inactive-actor-gateways!
  [active-actor-ids]
  (doseq [[actor-id manager] @actor-managers*]
    (when-not (contains? active-actor-ids actor-id)
      (try
        (native/stop-inactive-manager! manager)
        (finally
          (swap! actor-managers* dissoc actor-id))))))

(defn- start-actor-gateway-best-effort! [row]
  (native/actor-start-best-effort! start-actor-gateway! row))

(defn ^:async start-actor-gateways!
  [credentials]
  (let [valid (actor-gateway-starts credentials)
        active-actor-ids (set (map :actorId valid))
        starts (native/promise-array (mapv start-actor-gateway-best-effort! valid))]
    (stop-inactive-actor-gateways! active-actor-ids)
    (native/decode-results (await (native/all-starts starts)))))

(defn started?
  "Returns true if the gateway client exists."
  []
  (some? @manager*))

(defn ready?
  "Returns true if the gateway client is connected and ready."
  []
  (when-let [manager @manager*]
    (let [s (native/manager-status manager)]
      (native/status-ready? s))))

(defn status
  "Get gateway status as a JS object."
  []
  (when-let [manager @manager*]
    (native/manager-status manager)))

(defn start!
  "Start the Discord gateway with the given token."
  [token]
  (when-let [manager @manager*]
    (native/manager-start manager token)))

(defn stop!
  "Stop the Discord gateway client."
  []
  (when-let [manager @manager*]
    (native/manager-stop manager)))

(defn restart!
  "Stop and restart with the given token."
  [token]
  (when-let [manager @manager*]
    (native/manager-restart manager token)))

(defn on-message!
  "Register a message listener. Returns an unsubscribe function."
  [listener]
  (when-let [manager @manager*]
    (native/manager-on-message manager listener)))

(defn on-reaction!
  "Register a reaction listener. Returns an unsubscribe function."
  [listener]
  (when-let [manager @manager*]
    (native/manager-on-reaction manager listener)))

(defn on-voice-state-update!
  "Register a voice state update listener. Returns an unsubscribe function."
  [listener]
  (when-let [manager @manager*]
    (native/manager-on-voice-state-update manager listener)))

(defn list-servers
  "List all guilds the bot is in. Returns a Promise."
  []
  (when-let [manager @manager*]
    (native/manager-list-servers manager)))

(defn list-channels
  "List channels in a guild (or all guilds if guild-id is nil). Returns a Promise."
  ([]
   (when-let [manager @manager*]
     (native/manager-list-channels manager)))
  ([guild-id]
   (when-let [manager @manager*]
     (native/manager-list-channels manager guild-id))))

(defn fetch-channel-messages
  "Fetch messages from a channel. Returns a Promise."
  [channel-id opts]
  (when-let [manager @manager*]
    (native/manager-fetch-channel-messages manager channel-id opts)))

(defn fetch-dm-messages
  "Fetch DM messages with a user. Returns a Promise."
  [user-id opts]
  (when-let [manager @manager*]
    (native/manager-fetch-dm-messages manager user-id opts)))

(defn search-messages
  "Search messages in a channel or DM. Returns a Promise."
  [scope opts]
  (when-let [manager @manager*]
    (native/manager-search-messages manager scope opts)))

(defn send-message
  "Send a message to a channel. Returns a Promise."
  ([channel-id text reply-to]
   (send-message channel-id text reply-to nil))
  ([channel-id text reply-to attachments]
   (when-let [manager @manager*]
     (native/manager-send-message manager channel-id text reply-to attachments))))

(defn join-voice
  "Join a voice channel. Returns a Promise."
  [channel-id]
  (when-let [manager @manager*]
    (native/manager-join-voice manager channel-id)))

(defn leave-voice
  "Leave a voice channel for a guild. Returns a Promise."
  [guild-id]
  (when-let [manager @manager*]
    (native/manager-leave-voice manager guild-id)))

(defn play-audio
  "Play an audio buffer in a voice channel. Returns a Promise."
  [guild-id audio-buffer]
  (when-let [manager @manager*]
    (native/manager-play-audio manager guild-id audio-buffer)))

(defn start-voice-listener
  "Start listening for voice input. Returns a Promise of a stop function."
  [guild-id on-start on-audio]
  (when-let [manager @manager*]
    (native/manager-start-voice-listener manager guild-id on-start on-audio)))

(defn get-voice-connection
  "Get the current voice connection for a guild."
  [guild-id]
  (when-let [manager @manager*]
    (native/manager-get-voice-connection manager guild-id)))

(defn list-voice-members
  "List members in a voice channel. Returns a Promise."
  [guild-id channel-id]
  (when-let [manager @manager*]
    (native/manager-list-voice-members manager guild-id channel-id)))
