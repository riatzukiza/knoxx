(ns knoxx.backend.infra.discord.api
  "Discord credential, label and API workflows; decisions and native representation remain separate."
  (:require [clojure.string :as str]
            [knoxx.backend.domain.discord.rest-client :as discord-rest]
            [knoxx.backend.domain.discord.tools :as decisions]
            [knoxx.backend.domain.label.quality :as quality-labels]
            [knoxx.backend.extern.discord :as xdiscord]
            [knoxx.backend.infra.actor.credentials :as actor-credentials]
            [knoxx.backend.infra.clients.openplanner :as openplanner-client]
            [knoxx.backend.infra.discord.attachments :as attachments]))

(defn ^:async discord-token!
  [runtime]
  (let [credential (await (actor-credentials/get-credential! runtime "discord_bot"))
        token (actor-credentials/secret-value credential :botToken :bot-token :token)]
    (when (str/blank? (str token))
      (throw (xdiscord/error "Discord bot actor credential must include botToken.")))
    token))

(defn ^:async discord-client!
  [runtime]
  (discord-rest/client (await (discord-token! runtime))))

(defn- message-observation
  [message]
  {:message message
   :quality (quality-labels/quality-label message)
   :timestamp-ms (xdiscord/timestamp-ms (:timestamp message))})

(defn- order-messages
  [messages]
  (decisions/good-first-then-not-bad (mapv message-observation messages)))

(defn ^:async attach-openplanner-labels!
  [config messages]
  (let [client (openplanner-client/client config)]
    (if (or (empty? messages) (not (openplanner-client/enabled? client)))
      (order-messages messages)
      (try
        (let [ids (mapv decisions/discord-record-id messages)
              response (await (openplanner-client/record-labels! client ids))
              labels (:labels response)]
          (->> messages
               (mapv (fn [message]
                       (assoc message :openplannerLabels
                              (decisions/label-for-record-id labels (decisions/discord-record-id message)))))
               order-messages))
        (catch :default error
          (xdiscord/warn-label-lookup! error)
          [])))))

(defn ^:async discord-fetch-channel-messages!
  [runtime _config channel-id {:keys [limit before after around]}]
  (when (str/blank? channel-id)
    (throw (xdiscord/error "channel_id is required")))
  (let [client (await (discord-client! runtime))
        payload (await (discord-rest/channel-messages! client channel-id {:limit limit
                                                                          :before before
                                                                          :after after
                                                                          :around around}))
        messages (->> (or payload [])
                      (map decisions/discord-message->map)
                      vec)]
    {:messages messages
     :count (count messages)
     :channelId channel-id}))

(defn ^:async discord-scroll-channel-messages!
  [runtime config channel-id oldest-seen-id limit]
  (assoc (await (discord-fetch-channel-messages! runtime config channel-id {:limit limit :before oldest-seen-id}))
         :oldestSeenId oldest-seen-id))

(defn ^:async discord-open-dm-channel!
  [runtime user-id]
  (when (str/blank? user-id)
    (throw (xdiscord/error "user_id is required")))
  (let [client (await (discord-client! runtime))]
    (await (discord-rest/open-dm-channel! client user-id))))

(defn ^:async discord-fetch-dm-messages!
  [runtime config user-id {:keys [limit before]}]
  (let [channel (await (discord-open-dm-channel! runtime user-id))
        channel-id (or (:id channel) "")
        result (await (discord-fetch-channel-messages! runtime config channel-id {:limit limit :before before}))]
    (assoc result :dmChannelId channel-id :userId user-id)))

(defn ^:async discord-search-messages!
  [runtime config scope {:keys [channel-id user-id query limit before after since-hours]}]
  (let [scope (str/lower-case (str (or scope "channel")))
        timeframe-hours (xdiscord/positive-hours since-hours 168)
        result (if (= scope "dm")
                 (await (discord-fetch-dm-messages! runtime config user-id {:limit 100 :before before}))
                 (await (discord-fetch-channel-messages! runtime config channel-id {:limit 100 :before before :after after})))
        labelled (await (attach-openplanner-labels! config (:messages result)))]
    (decisions/discord-search-result
     scope timeframe-hours user-id query limit result
     (mapv (fn [message]
             (let [row (message-observation message)]
               (cond-> row
                 (some? (:timestamp-ms row)) (assoc :observed-at-ms (xdiscord/now-ms)))))
           labelled))))

(defn- post-discord-message-chunk!
  [client channel-id reply-to file-list chunk state]
  (discord-rest/create-channel-message-form!
   client
   channel-id
   (xdiscord/message-form-data {:payload (decisions/discord-message-payload chunk reply-to state)
                                :files file-list})))

(defn ^:async post-discord-message-chunks!
  [client channel-id reply-to file-list chunks]
  (loop [[chunk & remaining] (seq chunks)
         state nil]
    (if chunk
      (recur remaining
             (await (post-discord-message-chunk! client channel-id reply-to file-list chunk state)))
      state)))

(defn ^:async resolve-discord-upload-attachment!
  [runtime config idx url]
  (await (attachments/maybe-render-svg! (await (attachments/fetch-discord-upload-attachment! runtime config url idx)))))

(defn ^:async discord-send-message!
  [runtime config channel-id text reply-to attachment-urls]
  (let [raw-text (str/trim (str (or text "")))
        {:keys [text svg-blocks]} (decisions/extract-inline-svg-code-blocks raw-text)
        attachmentUrls (mapv xdiscord/svg-data-url svg-blocks)
        extracted-urls (when (and (not (str/blank? text)) (str/includes? text "data:image/"))
                         (vec (re-seq #"data:image/[^;]+;base64,[A-Za-z0-9+/=]+" text)))
        text-for-discord (if (seq extracted-urls)
                           (reduce (fn [txt url] (str/replace txt url "[image]")) text extracted-urls)
                           text)
        normalized (str/trim text-for-discord)
        all-urls (vec (concat (or attachment-urls []) (or attachmentUrls []) extracted-urls))]
    (when (str/blank? channel-id)
      (throw (xdiscord/error "channel_id is required")))
    (when (and (str/blank? normalized) (empty? all-urls))
      (throw (xdiscord/error "text or attachment_urls is required")))
    (let [file-list (await (xdiscord/promise-all-vector
                            (map-indexed (partial resolve-discord-upload-attachment! runtime config)
                                         all-urls)))
          client (await (discord-client! runtime))
          chunks (decisions/discord-message-chunks normalized)
          result (await (post-discord-message-chunks! client channel-id reply-to file-list chunks))]
      {:channelId channel-id
       :messageId (or (:id result) "")
       :sent true
       :timestamp (or (:timestamp result) "")
       :chunkCount (count chunks)
       :attachmentCount (count file-list)})))

(defn ^:async discord-react!
  "Add an emoji reaction to a Discord message."
  [runtime channel-id message-id emoji]
  (when (str/blank? channel-id)
    (throw (xdiscord/error "channel_id is required")))
  (when (str/blank? message-id)
    (throw (xdiscord/error "message_id is required")))
  (when (str/blank? emoji)
    (throw (xdiscord/error "emoji is required")))
  (let [client (await (discord-client! runtime))]
    (await (discord-rest/add-reaction! client channel-id message-id emoji))
    {:channelId channel-id
     :messageId message-id
     :emoji emoji
     :reacted true}))

(defn ^:async discord-thread-create!
  "Create a thread in a channel or from a message."
  [runtime channel-id message-id name auto-archive-duration]
  (when (str/blank? channel-id)
    (throw (xdiscord/error "channel_id is required")))
  (when (str/blank? name)
    (throw (xdiscord/error "name is required")))
  (let [body {:name name
              :auto_archive_duration (or auto-archive-duration 1440)
              :type 11}
        client (await (discord-client! runtime))
        result (await (discord-rest/create-thread! client channel-id message-id body))]
    {:threadId (or (:id result) "")
     :channelId channel-id
     :messageId (or message-id "")
     :name name
     :created true}))

(defn ^:async discord-list-guilds!
  [runtime]
  (let [client (await (discord-client! runtime))
        payload (await (discord-rest/current-user-guilds! client))
        servers (->> (or payload [])
                     (mapv (fn [guild]
                             {:id (or (:id guild) "")
                              :name (or (:name guild) "")
                              :memberCount (:approximate_member_count guild)})))]
    {:servers servers
     :count (count servers)}))

(defn ^:async discord-list-guild-channels!
  [runtime guild-id]
  (let [client (await (discord-client! runtime))
        payload (await (discord-rest/guild-channels! client guild-id))
        channels (->> (or payload [])
                      (filter decisions/text-channel-type?)
                      (mapv (fn [channel]
                              {:id (or (:id channel) "")
                               :name (or (:name channel) "")
                               :guildId guild-id
                               :type (str (or (:type channel) ""))})))]
    {:channels channels
     :count (count channels)}))

(defn ^:async discord-list-channels!
  [runtime guild-id]
  (if (str/blank? (str (or guild-id "")))
    (let [result (await (discord-list-guilds! runtime))
          payloads (await (xdiscord/promise-all-vector
                           (mapv (fn [server]
                                   (discord-list-guild-channels! runtime (:id server)))
                                 (:servers result))))
          channels (->> payloads
                        (mapcat :channels)
                        vec)]
      {:channels channels
       :count (count channels)})
    (await (discord-list-guild-channels! runtime guild-id))))
