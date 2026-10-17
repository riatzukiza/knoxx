(ns knoxx.backend.extern.discord-gateway-messages
  "Discord SDK collection/fetch/send and native message representation boundary."
  (:require [knoxx.backend.domain.discord.gateway :as decisions]
            [knoxx.backend.extern.discord-gateway-events :as events]))

(defn sort-newest-first
  "Sort an array of message maps by timestamp, newest first."
  [messages]
  (js/Array.from (.sort (into-array messages)
                        (fn [a b]
                          (.localeCompare (str (aget b "timestamp"))
                                          (str (aget a "timestamp")))))))

(defn split-message
  "Split text into chunks of ≤2000 chars, preferring paragraph/line/word breaks."
  [text]
  (let [normalized (.trim (str (or text "")))]
    (if (<= (.-length normalized) 2000)
      #js [normalized]
      (let [parts (atom #js [])
            remaining (atom normalized)]
        (while (> (.-length @remaining) 2000)
          (let [r @remaining
                split-at-para (.lastIndexOf r "\n\n" 2000)
                split-at-line (.lastIndexOf r "\n" 2000)
                split-at-space (.lastIndexOf r " " 2000)
                split-at (decisions/preferred-split-index split-at-para split-at-line split-at-space)]
            (swap! parts (fn [p] (.concat p #js [(.trimEnd (.slice r 0 split-at))])))
            (reset! remaining (.trimStart (.slice r split-at)))))
        (when (> (.-length @remaining) 0)
          (swap! parts (fn [p] (.concat p #js [@remaining]))))
        @parts))))

(defn attachment-value
  "Read an attachment field from either a CLJS map or a plain JS object."
  [attachment k js-key]
  (or (when (map? attachment) (get attachment k))
      (when (object? attachment) (aget attachment js-key))))

(defn discord-file-payload
  [attachment]
  (let [buffer (or (attachment-value attachment :buffer "buffer")
                   (attachment-value attachment :attachment "attachment"))
        name (or (attachment-value attachment :name "name")
                 (attachment-value attachment :filename "filename")
                 "attachment.bin")]
    (when-not buffer
      (throw (js/Error. "Discord attachment is missing file data")))
    #js {:attachment buffer
         :name name}))

(defn guild->server
  [guild]
  #js {:id (.-id guild)
       :name (.-name guild)
       :memberCount (or (.-memberCount guild) nil)})

(defn ^:async gw-list-servers
  "List all guilds the bot is in."
  [ensure-client]
  (let [active-client (await (ensure-client))]
    (into-array
     (for [[_id guild] (.. active-client -guilds -cache)]
       (guild->server guild)))))

(defn guild-channel-entry
  [guild channel]
  #js {:id (.-id channel)
       :name (or (.-name channel) "")
       :guildId (.-id guild)
       :type (str (.-type channel))})

(defn listing-channel?
  [ChannelType channel]
  (and channel
       (events/readable-text-channel? channel)
       (not= (.-type channel) (.-DM ChannelType))))

(defn ^:async collect-guild-channels!
  [ChannelType guild]
  (let [fetched (await (.fetch (.. guild -channels)))]
    (into-array
     (for [[_id channel] fetched
           :when (listing-channel? ChannelType channel)]
       (guild-channel-entry guild channel)))))

(defn concat-js-arrays
  [arrays]
  (let [flat (atom #js [])]
    (doseq [arr arrays]
      (swap! flat (fn [acc] (.concat acc arr))))
    @flat))

(defn ^:async safe-collect-guild-channels!
  [log ChannelType guild]
  (try
    (await (collect-guild-channels! ChannelType guild))
    (catch js/Error err
      (when-let [log-warn (events/log-fn log :warn)]
        (log-warn "[discord-gateway] listChannels guild failed" (.-id guild) err))
      #js [])))

(defn ^:async list-all-guild-channels!
  [active-client log ChannelType]
  (let [promises (clj->js
                  (mapv (fn [[_id guild]]
                          (safe-collect-guild-channels! log ChannelType guild))
                        (.. active-client -guilds -cache)))
        results (await (js/Promise.all promises))]
    (concat-js-arrays results)))

(defn ^:async gw-list-channels
  "List channels in a guild or all guilds."
  [ensure-client log guild-id]
  (let [active-client (await (ensure-client))
        ChannelType (events/channel-type-enum)]
    (if guild-id
      (let [guild (.. active-client -guilds -cache (get guild-id))]
        (when-not guild
          (throw (js/Error. (str "Guild not found: " guild-id))))
        (await (collect-guild-channels! ChannelType guild)))
      (await (list-all-guild-channels! active-client log ChannelType)))))

(defn js-opt
  [opts k]
  (when opts
    (aget opts k)))

(defn bounded-limit
  [opts default-limit]
  (decisions/bounded-message-limit (js-opt opts "limit") default-limit))

(defn message-fetch-options
  [opts]
  (clj->js {:limit (bounded-limit opts 50)
            :before (js-opt opts "before")
            :after (js-opt opts "after")
            :around (js-opt opts "around")}))

(defn messages-array
  [fetched]
  (sort-newest-first
   (map events/map-message (for [[_id msg] fetched] msg))))

(defn ^:async fetch-readable-channel!
  [active-client channel-id]
  (let [channel (await (.fetch (.. active-client -channels) channel-id))]
    (when (or (not channel) (not (events/readable-text-channel? channel)))
      (throw (js/Error. (str "Channel not found or not text-based: " channel-id))))
    channel))

(defn ^:async gw-fetch-channel-messages
  "Fetch messages from a channel."
  [ensure-client channel-id opts]
  (let [active-client (await (ensure-client))
        channel (await (fetch-readable-channel! active-client channel-id))
        fetched (await (.fetch (.. channel -messages) (message-fetch-options opts)))]
    (messages-array fetched)))

(defn dm-message-options
  [opts]
  (clj->js {:limit (bounded-limit opts 50)
            :before (js-opt opts "before")}))

(defn dm-messages-response
  [dm fetched]
  #js {:dmChannelId (.-id dm)
       :messages (messages-array fetched)})

(defn ^:async gw-fetch-dm-messages
  "Fetch DM messages with a user."
  [ensure-client user-id opts]
  (let [active-client (await (ensure-client))
        user (await (.fetch (.. active-client -users) user-id))
        dm (await (.createDM user))
        fetched (await (.fetch (.. dm -messages) (dm-message-options opts)))]
    (dm-messages-response dm fetched)))

(defn search-filter-fn
  "Create a filter function for message search."
  [opts]
  (let [needle (.toLowerCase (str (or (js-opt opts "query") "")))
        target-user-id (js-opt opts "userId")]
    (fn [message]
      (let [content-ok (or (= needle "")
                           (.includes (.toLowerCase (or (aget message "content") "")) needle))
            author-ok (or (not target-user-id)
                          (= (aget message "authorId") target-user-id))]
        (and content-ok author-ok)))))

(defn search-limit
  [opts]
  (or (js-opt opts "limit") 50))

(defn search-result
  [source key-name key-value messages opts]
  (let [filtered (.filter messages (search-filter-fn opts))
        limit (search-limit opts)
        result #js {:messages (.slice filtered 0 limit)
                    :count (min (.-length filtered) limit)
                    :source source}]
    (aset result key-name key-value)
    result))

(defn ^:async gw-search-messages
  "Search messages in a channel or DM."
  [this-fn scope opts]
  (let [normalized-scope (.toLowerCase (str (or scope "channel")))]
    (if (= normalized-scope "dm")
      (let [result (await (.fetchDmMessages this-fn (js-opt opts "userId")
                                            (clj->js {:limit 100 :before (js-opt opts "before")})))]
        (search-result "gateway-cache" "dmChannelId" (aget result "dmChannelId") (aget result "messages") opts))
      (let [messages (await (.fetchChannelMessages this-fn (js-opt opts "channelId")
                                                   (clj->js {:limit 100
                                                             :before (js-opt opts "before")
                                                             :after (js-opt opts "after")})))]
        (search-result "gateway-cache" "channelId" (js-opt opts "channelId") messages opts)))))

(defn attachment-count
  [attachments]
  (cond
    (nil? attachments) 0
    (array? attachments) (.-length attachments)
    :else (count attachments)))

(defn message-body-text [text attachments]
  (decisions/message-content-text text (boolean (seq attachments))))

(defn send-message-payload
  [chunk index reply-to attachments]
  (let [payload (clj->js {:content chunk})]
    (when (and (zero? index) reply-to)
      (aset payload "reply" (clj->js {:messageReference reply-to})))
    (when (and (zero? index) (seq attachments))
      (aset payload "files" (into-array (map discord-file-payload attachments))))
    payload))

(defn ^:async send-message-chunks!
  [channel chunks reply-to attachments]
  (doseq [[index chunk] (map-indexed vector (array-seq chunks))]
    (await (.send channel (send-message-payload chunk index reply-to attachments)))))

(defn send-message-result
  [channel-id chunks attachments]
  #js {:channelId channel-id
       :messageId ""
       :sent true
       :timestamp (.toISOString (js/Date.))
       :chunkCount (.-length chunks)
       :attachmentCount (attachment-count attachments)})

(defn ^:async gw-send-message
  "Send a message to a channel, splitting into chunks if needed."
  [ensure-client channel-id text reply-to attachments]
  (let [active-client (await (ensure-client))
        channel (await (fetch-readable-channel! active-client channel-id))
        chunks (split-message (message-body-text text attachments))]
    (await (send-message-chunks! channel chunks reply-to attachments))
    (send-message-result channel-id chunks attachments)))
