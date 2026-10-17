(ns knoxx.backend.extern.discord-gateway
  "Native gateway manager objects, SDK lifecycle and named opaque-handle invocation."
  (:require [knoxx.backend.extern.discord-gateway-events :as events]
            [knoxx.backend.extern.discord-gateway-messages :as messages]
            [knoxx.backend.extern.discord-gateway-voice :as voice-adapter]))

(defn ^:async ensure-client!
  [client-state ready-promise]
  (when-not @client-state
    (throw (js/Error. "Discord gateway client is not started")))
  (when @ready-promise
    (await @ready-promise))
  @client-state)

(defn reset-client-state!
  [client-state ready-promise current-token]
  (reset! client-state nil)
  (reset! ready-promise nil)
  (reset! current-token nil))

(defn log-login-failed!
  [log error]
  (when-let [log-error (events/log-fn log :error)]
    (log-error "[discord-gateway] login failed" error)))

(defn ^:async login-client!
  [client-state ready-promise current-token log new-client token]
  (try
    (await (.login new-client token))
    new-client
    (catch js/Error error
      (log-login-failed! log error)
      (try (.destroy new-client) (catch js/Error cleanup-error
                                         (events/degraded! :failed-login-destroy :original-error cleanup-error)))
      (reset-client-state! client-state ready-promise current-token)
      (throw error))))

(defn ^:async gw-start
  "Start the gateway client with a bot token."
  [client-state ready-promise current-token _listeners log this-stop build-client token]
  (let [next-token (.trim (str (or token "")))]
    (cond
      (= next-token "") (do (await (this-stop)) nil)
      (and @client-state (= @current-token next-token)) (if @ready-promise
                                                           (await @ready-promise)
                                                           @client-state)
      :else (do
              (await (this-stop))
              (reset! current-token next-token)
              (let [new-client (build-client)
                    login-promise (login-client! client-state ready-promise current-token log new-client next-token)]
                (reset! client-state new-client)
                (reset! ready-promise login-promise)
                (await login-promise))))))

(defn ^:async gw-stop
  "Stop the gateway client."
  [client-state ready-promise current-token]
  (when-let [client @client-state]
    (try
      (await (.destroy client))
      (catch js/Error boundary-error
        (events/degraded! :client-destroy :reset-state boundary-error)
        nil)))
  (reset-client-state! client-state ready-promise current-token)
  nil)

(defn gw-status
  "Get gateway status."
  [client-state]
  (let [c @client-state]
    (cond-> #js {:started (some? c)
                 :ready false
                 :userId nil
                 :userTag nil
                 :guildCount 0}
      c (doto
            (aset "ready" (try (.isReady c) (catch js/Error boundary-error
                                           (events/degraded! :status-ready :false boundary-error)
                                           false)))
          (aset "userId" (try (.-id (.-user c)) (catch js/Error boundary-error
                                           (events/degraded! :status-user-id :nil boundary-error)
                                           nil)))
          (aset "userTag" (try (.-tag (.-user c)) (catch js/Error boundary-error
                                           (events/degraded! :status-user-tag :nil boundary-error)
                                           nil)))
          (aset "guildCount" (try (.. c -guilds -cache -size) (catch js/Error boundary-error
                                           (events/degraded! :status-guild-count :0 boundary-error)
                                           0)))))))

(defn gateway-stop!
  [voice-connections this-stop]
  (voice-adapter/destroy-voice-connections! voice-connections)
  (this-stop))

(defn ^:async gateway-restart!
  [this-stop this-fn token]
  (await (this-stop))
  (await (.start (this-fn) token)))

(defn register-gateway-listener!
  [listener-set listener]
  (.add @listener-set listener)
  (fn [] (.delete @listener-set listener)))

(defn gateway-lifecycle-methods
  [client-state ready-promise current-token listeners log this-stop build-client this-fn voice-connections]
  #js {:start (fn [token] (gw-start client-state ready-promise current-token listeners log this-stop build-client token))
       :stop (fn [] (gateway-stop! voice-connections this-stop))
       :restart (fn [token] (gateway-restart! this-stop this-fn token))
       :status (fn [] (gw-status client-state))})

(defn gateway-listener-methods
  [listeners reaction-listeners voice-state-listeners]
  #js {:onMessage (fn [listener] (register-gateway-listener! listeners listener))
       :onReaction (fn [listener] (register-gateway-listener! reaction-listeners listener))
       :onVoiceStateUpdate (fn [listener] (register-gateway-listener! voice-state-listeners listener))})

(defn gateway-message-methods
  [ensure-client log this-fn]
  #js {:listServers (fn [] (messages/gw-list-servers ensure-client))
       :listChannels (fn [guild-id] (messages/gw-list-channels ensure-client log guild-id))
       :fetchChannelMessages (fn [channel-id opts] (messages/gw-fetch-channel-messages ensure-client channel-id opts))
       :fetchDmMessages (fn [user-id opts] (messages/gw-fetch-dm-messages ensure-client user-id opts))
       :searchMessages (fn [scope opts] (messages/gw-search-messages (this-fn) scope opts))
       :sendMessage (fn [channel-id text reply-to attachments] (messages/gw-send-message ensure-client channel-id text reply-to attachments))})

(defn gateway-voice-methods
  [ensure-client voice-connections]
  #js {:joinVoice (fn [channel-id] (voice-adapter/gateway-join-voice! ensure-client voice-connections channel-id))
       :leaveVoice (fn [guild-id] (voice-adapter/gw-leave-voice voice-connections guild-id) #js {:guildId guild-id :left true})
       :playAudio (fn [guild-id audio-buffer] (voice-adapter/gw-play-audio voice-connections guild-id audio-buffer))
       :subscribeVoice (fn [guild-id user-id callback] (voice-adapter/gw-subscribe-voice voice-connections guild-id user-id callback))
       :startVoiceListener (fn [guild-id on-start on-audio] (voice-adapter/gw-start-voice-listener voice-connections guild-id on-start on-audio))
       :getVoiceConnection (fn [guild-id] (voice-adapter/gateway-active-voice-connection voice-connections guild-id))
       :listVoiceMembers (fn [guild-id channel-id] (voice-adapter/gw-list-voice-members ensure-client guild-id channel-id))})

(defn build-gateway-manager-methods
  [client-state ready-promise current-token listeners reaction-listeners voice-state-listeners
   log this-stop build-client ensure-client voice-connections this-fn]
  (js/Object.assign
   #js {}
   (gateway-lifecycle-methods client-state ready-promise current-token listeners log this-stop build-client this-fn voice-connections)
   (gateway-listener-methods listeners reaction-listeners voice-state-listeners)
   (gateway-message-methods ensure-client log this-fn)
   (gateway-voice-methods ensure-client voice-connections)))

(defn parse-gateway-manager-opts
  "Parse gateway manager options from a CLJS map or JS object."
  [opts]
  {:log (or (when (map? opts) (:log opts))
            (when (object? opts) (aget opts "log"))
            js/console)
   :set-default? (not= false (or (when (map? opts) (:set-default? opts))
                                 (when (object? opts) (aget opts "setDefault"))
                                 true))})

(defn gateway-manager-state
  []
  {:client-state (atom nil)
   :ready-promise (atom nil)
   :current-token (atom nil)
   :listeners (atom (js/Set.))
   :reaction-listeners (atom (js/Set.))
   :voice-state-listeners (atom (js/Set.))
   :voice-connections (js/Map.)})

(defn gateway-manager-deps
  [log {:keys [client-state ready-promise listeners reaction-listeners voice-state-listeners]}]
  (let [notify-message (partial events/notify-message! listeners log)
        notify-reaction (partial events/notify-reaction! reaction-listeners log)
        notify-voice-state (partial events/notify-voice-state! voice-state-listeners log)]
    {:build-client (partial events/build-discord-client log notify-message notify-reaction notify-voice-state)
     :ensure-client (partial ensure-client! client-state ready-promise)}))

(defn create-gateway-manager-object!
  [log {:keys [client-state ready-promise current-token listeners reaction-listeners
               voice-state-listeners voice-connections] :as state}]
  (let [{:keys [build-client ensure-client]} (gateway-manager-deps log state)
        this-stop (fn [] (gw-stop client-state ready-promise current-token))
        this-obj (atom nil)]
    (letfn [(this-fn [] @this-obj)]
      (reset! this-obj
              (build-gateway-manager-methods
               client-state ready-promise current-token listeners reaction-listeners voice-state-listeners
               log this-stop build-client ensure-client voice-connections this-fn))
      @this-obj)))

(defn credential-value
  [credential k]
  (or (when (map? credential) (get credential k))
      (when (map? credential) (get credential (keyword k)))
      (when (map? credential) (get credential (name k)))
      (when (object? credential) (aget credential (name k)))))

(defn error [message] (js/Error. message))
(defn actor-manager-options [] #js {:log js/console :setDefault false})
(defn decode-credentials [credentials] (js->clj (or credentials #js []) :keywordize-keys true))
(defn promise-array [starts] (clj->js starts))
(defn all-starts [starts] (js/Promise.all starts))
(defn decode-results [results] (js->clj results :keywordize-keys true))
(defn status-ready? [status] (boolean (aget status "ready")))
(defn decoded-status [manager] (js->clj (.status manager) :keywordize-keys true))
(defn stop-inactive-manager! [manager]
  (try (.stop manager)
       (catch js/Error boundary-error
         (events/degraded! :inactive-actor-stop :remove-registry-entry boundary-error)
         nil)))

(defn ^:async actor-start-best-effort!
  [start-actor! {:keys [actorId token]}]
  (try
    (await (start-actor! actorId token))
    (catch js/Error err
      (.warn js/console "[discord-gateway] actor gateway start failed" actorId (.-message err))
      {:actorId actorId :error (.-message err)})))

(defn manager-start [manager token] (.start manager token))
(defn manager-stop [manager] (.stop manager))
(defn manager-restart [manager token] (.restart manager token))
(defn manager-status [manager] (.status manager))
(defn manager-on-message [manager listener] (.onMessage manager listener))
(defn manager-on-reaction [manager listener] (.onReaction manager listener))
(defn manager-on-voice-state-update [manager listener] (.onVoiceStateUpdate manager listener))
(defn manager-list-servers [manager] (.listServers manager))
(defn manager-list-channels
  ([manager] (.listChannels manager))
  ([manager guild-id] (.listChannels manager guild-id)))
(defn manager-fetch-channel-messages [manager channel-id opts] (.fetchChannelMessages manager channel-id opts))
(defn manager-fetch-dm-messages [manager user-id opts] (.fetchDmMessages manager user-id opts))
(defn manager-search-messages [manager scope opts] (.searchMessages manager scope opts))
(defn manager-send-message [manager channel-id text reply-to attachments] (.sendMessage manager channel-id text reply-to attachments))
(defn manager-join-voice [manager channel-id] (.joinVoice manager channel-id))
(defn manager-leave-voice [manager guild-id] (.leaveVoice manager guild-id))
(defn manager-play-audio [manager guild-id audio-buffer] (.playAudio manager guild-id audio-buffer))
(defn manager-start-voice-listener [manager guild-id on-start on-audio] (.startVoiceListener manager guild-id on-start on-audio))
(defn manager-get-voice-connection [manager guild-id] (.getVoiceConnection manager guild-id))
(defn manager-list-voice-members [manager guild-id channel-id] (.listVoiceMembers manager guild-id channel-id))
