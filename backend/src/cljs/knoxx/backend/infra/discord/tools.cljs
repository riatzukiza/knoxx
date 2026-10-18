(ns knoxx.backend.infra.discord.tools
  "Discord runtime tool registration and execution adapters."
  (:require [clojure.string :as str]
            [knoxx.backend.domain.discord.tools :as decisions]
            [knoxx.backend.domain.text :as text]
            [knoxx.backend.domain.tools :as tools]
            [knoxx.backend.extern.discord :as xdiscord]
            [knoxx.backend.infra.auth.authz :as authz]
            [knoxx.backend.infra.discord.api :as api]))

(defn ^:async discord-send-execute [runtime config _tool-call-id params a b c]
  (let [params (xdiscord/normalize-tool-params params)
        on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        channel-id (or (decisions/pget params :channel_id :channelId) "")
        text (or (decisions/pget params :text :content) "")
        reply-to (decisions/pget params :reply_to :replyTo)
        attachment-urls (->> (or (decisions/pget params :attachment_urls :attachmentUrls) [])
                             (map xdiscord/trim-path-delims)
                             (remove str/blank?)
                             vec)]
    (tools/maybe-tool-update! on-update (str "Sending Discord message to " channel-id "…"))
    (let [result (await (api/discord-send-message! runtime config channel-id text reply-to attachment-urls))]
      (text/tool-text-result (str "Sent Discord message " (:messageId result) " to channel " channel-id)
                        result))))
(def send-params
  [:map
   [:channel_id {:description "Discord channel ID to send the message to. Use discord.list.channels to discover IDs."} :string]
   [:text {:description "Message content to send. Long messages will be chunked automatically."} :string]
   [:reply_to {:optional true :description "Optional message ID to reply to."} :string]
   [:attachment_urls {:optional true
                      :description "Optional attachment sources to upload: HTTP(S) URLs, data URLs, absolute file paths, or workspace-relative paths (e.g. sandbox output files, generated images)."}
    [:vector :string]]])
(def discord-send-tool (partial tools/create-tool-obj "discord.send" "Discord Send" "Send a message to a Discord channel, optionally as a reply to an existing message."
                          "Send a Discord message or reply to a specific message id."
                          ["Use discord.publish or discord.send to share results in a Discord channel."
                                    "Provide channelId/channel_id and content/text."
                                    "Include attachmentUrls/attachment_urls to upload files, images, or generated assets."
                                    "Pass file paths as plain strings (e.g. Graphics/seal.svg or Voice/clip.mp3). Do NOT wrap them in <|\"| delimiters."
                                    "To mention a user, use <@user_id> in the text. Do NOT use @username — it will not ping."]
                          send-params
                          discord-send-execute))


;; ---------------------------------------------------------------------------
;; Hoisted tool definitions: params, execute, tool-obj per tool
;; ---------------------------------------------------------------------------

(def channel-messages-params
  [:map
   [:channel_id {:description "Discord channel ID to fetch messages from."} :string]
   [:limit {:optional true :description "Maximum number of messages to fetch (default 50, max 100)."} [:int {:min 1 :max 100}]]
   [:before {:optional true :description "Fetch messages before this message ID."} :string]
   [:after {:optional true :description "Fetch messages after this message ID."} :string]
   [:around {:optional true :description "Fetch messages around this message ID."} :string]])

(defn ^:async channel-messages-execute [runtime config _tool-call-id params a b c]
  (let [params (xdiscord/normalize-tool-params params)
        on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        channel-id (or (decisions/pget params :channel_id :channelId) "")]
    (tools/maybe-tool-update! on-update (str "Fetching Discord messages from channel " channel-id "…"))
    (let [result (await (api/discord-fetch-channel-messages! runtime config channel-id {:limit (decisions/pget params :limit)
                                                                                   :before (decisions/pget params :before)
                                                                                   :after (decisions/pget params :after)
                                                                                   :around (decisions/pget params :around)}))
          messages (await (api/attach-openplanner-labels! config (:messages result)))
          filtered (assoc result :messages messages :count (count messages))]
      (text/tool-text-result (decisions/discord-messages-text (str "Fetched " (:count filtered) " non-bad messages from channel " channel-id ".") messages)
                        filtered))))

(def channel-messages-tool (partial tools/create-tool-obj "discord.channel.messages" "Discord Channel Messages"
                                    "Fetch messages from a Discord channel with before/after/around cursors."
                                    "Fetch channel messages from Discord with pagination cursors when you need exact transcript context."
                                    ["Use this when you know the channel id and need exact message history."
                                              "Use before/after/around for precise pagination."]
                                    channel-messages-params
                                    channel-messages-execute))

(def channel-scroll-params
  [:map
   [:channel_id {:description "Discord channel ID to fetch older messages from."} :string]
   [:oldest_seen_id {:description "Oldest message ID already seen; fetch messages before this."} :string]
   [:limit {:optional true :description "Maximum number of older messages to fetch."} [:int {:min 1 :max 100}]]])

(defn ^:async channel-scroll-execute [runtime config _tool-call-id params a b c]
  (let [params (xdiscord/normalize-tool-params params)
        on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        channel-id (or (decisions/pget params :channel_id :channelId) "")
        oldest-seen-id (or (decisions/pget params :oldest_seen_id :oldestSeenId) "")]
    (tools/maybe-tool-update! on-update (str "Scrolling older Discord messages in channel " channel-id "…"))
    (let [result (await (api/discord-scroll-channel-messages! runtime config channel-id oldest-seen-id (decisions/pget params :limit)))
          messages (await (api/attach-openplanner-labels! config (:messages result)))
          filtered (assoc result :messages messages :count (count messages))]
      (text/tool-text-result (decisions/discord-messages-text (str "Fetched older non-bad messages before " oldest-seen-id ".") messages)
                        filtered))))

(def channel-scroll-tool (partial tools/create-tool-obj "discord.channel.scroll" "Discord Channel Scroll"
                                  "Scroll older channel messages by fetching messages before the oldest already-seen message id."
                                  "Scroll backwards in a Discord channel once you already know the oldest seen id."
                                  ["Use discord.channel.scroll as sugar over discord.channel.messages before=oldest_seen_id."
                                            "Useful for paging backward through long histories."]
                                  channel-scroll-params
                                  channel-scroll-execute))

(def dm-messages-params
  [:map
   [:user_id {:description "Discord user ID whose DM channel should be read."} :string]
   [:limit {:optional true :description "Maximum number of DM messages to fetch."} [:int {:min 1 :max 100}]]
   [:before {:optional true :description "Fetch DM messages before this message ID."} :string]])

(defn ^:async dm-messages-execute [runtime config _tool-call-id params a b c]
  (let [params (xdiscord/normalize-tool-params params)
        on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        user-id (or (decisions/pget params :user_id :userId) "")]
    (tools/maybe-tool-update! on-update (str "Fetching Discord DM messages for user " user-id "…"))
    (let [result (await (api/discord-fetch-dm-messages! runtime config user-id {:limit (decisions/pget params :limit)
                                                                            :before (decisions/pget params :before)}))
          messages (await (api/attach-openplanner-labels! config (:messages result)))
          filtered (assoc result :messages messages :count (count messages))]
      (text/tool-text-result (decisions/discord-messages-text (str "Fetched " (:count filtered) " non-bad DM messages for user " user-id ".") messages)
                        filtered))))

(def dm-messages-tool (partial tools/create-tool-obj "discord.dm.messages" "Discord DM Messages"
                               "Fetch messages from the DM channel shared with a Discord user."
                               "Read DM history with a Discord user by user id."
                               ["Use this when the relevant conversation is in DMs rather than a guild channel."
                                         "Provide the target user id."]
                               dm-messages-params
                               dm-messages-execute))

(def search-params
  [:map
   [:scope {:description "Search scope: channel or dm."} :string]
   [:channel_id {:optional true :description "Discord channel ID to search when scope=channel."} :string]
   [:user_id {:optional true :description "Discord user ID to search against when scope=dm or to filter author."} :string]
   [:query {:optional true :description "Optional substring query to filter messages by content."} :string]
   [:limit {:optional true :description "Maximum number of matching messages to return."} [:int {:min 1 :max 100}]]
   [:before {:optional true :description "Fetch messages before this message ID."} :string]
   [:after {:optional true :description "Fetch messages after this message ID."} :string]
   [:since_hours {:optional true :description "Prefer matching messages within this many hours (default 168); pass a larger value to override the timeframe."} [:int {:min 1}]]])

(defn ^:async search-execute [runtime config _tool-call-id params a b c]
  (let [params (xdiscord/normalize-tool-params params)
        on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        scope (or (decisions/pget params :scope) "channel")
        channel-id (decisions/pget params :channel_id :channelId)
        user-id (decisions/pget params :user_id :userId)
        query (or (decisions/pget params :query) "")]
    (tools/maybe-tool-update! on-update (str "Searching Discord messages in scope " scope "…"))
    (let [result (await (api/discord-search-messages! runtime config scope {:channel-id channel-id
                                                                        :user-id user-id
                                                                        :query query
                                                                        :limit (decisions/pget params :limit)
                                                                        :before (decisions/pget params :before)
                                                                        :after (decisions/pget params :after)
                                                                        :since-hours (decisions/pget params :since_hours :sinceHours)}))]
      (text/tool-text-result (decisions/discord-messages-text (str "Found " (:count result) " matching Discord messages.") (:messages result))
                        result))))

(def search-tool (partial tools/create-tool-obj "discord.search" "Discord Search"
                          "Search channel or DM messages by content and/or author using client-side filtering."
                          "Search Discord messages by text and scope to find relevant discussion quickly."
                          ["Use scope=channel with channel_id for guild channels or scope=dm with user_id for DMs."
                                    "Messages marked bad in OpenPlanner labels are never shown."
                                    "Matching good-marked messages are returned first in chronological order, then unbad messages chronologically."
                                    "The default timeframe is 168 hours; pass since_hours to override when needed."
                                    "This falls back to client-side filtering when native search is unavailable."]
                          search-params
                          search-execute))

(def list-servers-params
  [:map])

(defn ^:async list-servers-execute [runtime _config _tool-call-id _params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))]
    (tools/maybe-tool-update! on-update "Listing Discord servers…")
    (let [result (await (api/discord-list-guilds! runtime))
          lines (->> (:servers result)
                     (map (fn [server] (str (:name server) " (" (:id server) ")")))
                     (str/join "\n"))]
      (text/tool-text-result (str "Discord servers:\n" lines) result))))

(def list-servers-tool (partial tools/create-tool-obj "discord.list.servers" "Discord List Servers"
                                "List all Discord servers/guilds the bot can access."
                                "List Discord servers before choosing channels or replying into a guild."
                                ["Use this before discord.list.channels when you need discovery."
                                          "Do not guess guild ids."]
                                list-servers-params
                                list-servers-execute))

(def guilds-tool (partial tools/create-tool-obj "discord.guilds" "Discord Guilds"
                          "List Discord guilds/servers the bot is in."
                          "List Discord guilds to discover available servers."
                          ["Alias for discord.list.servers."
                                    "Use before listing channels or posting to a specific server."]
                          list-servers-params
                          list-servers-execute))

(def list-channels-params
  [:map
   [:guild_id {:optional true :description "Optional guild/server ID. If omitted, returns channels across all visible guilds."} :string]])

(defn ^:async list-channels-execute [runtime _config _tool-call-id params a b c]
  (let [params (xdiscord/normalize-tool-params params)
        on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        guild-id (decisions/pget params :guild_id :guildId)]
    (tools/maybe-tool-update! on-update "Listing Discord channels…")
    (let [result (await (api/discord-list-channels! runtime guild-id))
          lines (->> (:channels result)
                     (map (fn [channel] (str "#" (:name channel) " (" (:id channel) ") guild=" (:guildId channel))))
                     (str/join "\n"))]
      (text/tool-text-result (str "Discord channels:\n" lines) result))))

(def list-channels-tool (partial tools/create-tool-obj "discord.list.channels" "Discord List Channels"
                                 "List channels in one Discord guild or across all visible guilds."
                                 "List Discord channels to discover readable/postable targets."
                                 ["If guild_id is omitted, returns channels across all visible guilds."
                                           "Use returned channel ids with discord.channel.messages or discord.send."]
                                 list-channels-params
                                 list-channels-execute))

(def channels-params
  [:map
   [:guild_id {:description "Discord guild ID to list channels for."} :string]])

(defn channels-execute [runtime config tool-call-id params a b c]
  (let [params (xdiscord/normalize-tool-params params)]
    (list-channels-execute runtime config tool-call-id
                           {:guild_id (decisions/pget params :guildId :guild_id)}
                           a b c)))

(def channels-tool (partial tools/create-tool-obj "discord.channels" "Discord Channels"
                            "List channels in a Discord guild."
                            "List channels in a Discord guild to find the right channel for reading or posting."
                            ["Alias for discord.list.channels."
                                      "Use guildId/guild_id when you already know the server."]
                            channels-params
                            channels-execute))

(def react-params
  [:map
   [:channel_id {:description "Discord channel ID containing the message to react to."} :string]
   [:message_id {:description "Discord message ID to react to."} :string]
   [:emoji {:description "Emoji to react with (e.g. 👍, 🎉, 💀)."} :string]])

(defn ^:async react-execute [runtime _config _tool-call-id params a b c]
  (let [params (xdiscord/normalize-tool-params params)
        on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        channel-id (or (decisions/pget params :channel_id :channelId) "")
        message-id (or (decisions/pget params :message_id :messageId) "")
        emoji (or (decisions/pget params :emoji) "")]
    (tools/maybe-tool-update! on-update (str "Reacting to message " message-id " with " emoji "…"))
    (let [result (await (api/discord-react! runtime channel-id message-id emoji))]
      (text/tool-text-result (str "Reacted with " emoji " to message " message-id) result))))

(def react-tool (partial tools/create-tool-obj "discord.react" "Discord React"
                         "Add an emoji reaction to a Discord message."
                         "React to a Discord message with an emoji."
                         ["Use discord.react to add emoji reactions to messages."
                                   "Provide channel_id, message_id, and an emoji (e.g. 👍, 🎉, 💀)."]
                         react-params
                         react-execute))

(def thread-create-params
  [:map
   [:channel_id {:description "Discord channel ID to create the thread in."} :string]
   [:message_id {:optional true :description "Optional message ID to create a thread from. If omitted, creates a standalone thread in the channel."} :string]
   [:name {:description "Name of the thread (max 100 chars)."} :string]
   [:auto_archive_duration {:optional true :description "Auto-archive duration in minutes: 60, 1440 (default), 4320, or 10080."} :int]])

(defn ^:async thread-create-execute [runtime _config _tool-call-id params a b c]
  (let [params (xdiscord/normalize-tool-params params)
        on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        channel-id (or (decisions/pget params :channel_id :channelId) "")
        message-id (decisions/pget params :message_id :messageId)
        name (or (decisions/pget params :name) "")
        auto-archive (decisions/pget params :auto_archive_duration :autoArchiveDuration)]
    (tools/maybe-tool-update! on-update (str "Creating thread '" name "' in channel " channel-id "…"))
    (let [result (await (api/discord-thread-create! runtime channel-id message-id name auto-archive))]
      (text/tool-text-result (str "Created thread " (:threadId result) " named '" name "'")
                        result))))

(def thread-create-tool (partial tools/create-tool-obj "discord.thread.create" "Discord Thread Create"
                                 "Create a Discord thread from a message or in a channel."
                                 "Create a thread to spin off a conversation."
                                 ["Use discord.thread.create to start a thread from a message or in a channel."
                                           "Provide channel_id and a name. Optionally pass message_id to create a thread from that message."
                                           "After creating a thread, use the returned threadId as channel_id with discord.send to post in it."]
                                 thread-create-params
                                 thread-create-execute))

;; ---------------------------------------------------------------------------
;; Wrapper tools: remap params then delegate to an existing execute
;; ---------------------------------------------------------------------------

(def publish-params
  [:map
   [:channel_id {:description "Discord channel ID to post the message to."} :string]
   [:content {:description "Message content to post to the Discord channel."} :string]
   [:attachment_urls {:optional true
                      :description "Optional attachment sources to upload: HTTP(S) URLs, data URLs, absolute file paths, or workspace-relative paths (e.g. sandbox output files, generated images)."}
    [:vector :string]]])

(defn publish-execute [runtime config tool-call-id params a b c]
  (let [params (xdiscord/normalize-tool-params params)]
    (discord-send-execute runtime config tool-call-id
                          {:channel_id (or (decisions/pget params :channel_id :channelId) "")
                           :text (or (decisions/pget params :content :text) "")
                           :attachment_urls (or (decisions/pget params :attachment_urls :attachmentUrls) [])}
                          a b c)))

(def publish-tool (partial tools/create-tool-obj "discord.publish" "Discord Publish"
                           "Post a message to a Discord channel using the configured Knoxx Discord bot."
                           "Post updates, summaries, or notifications to Discord channels."
                           ["Use discord.publish or discord.send to share results in a Discord channel."
                                     "Provide channelId/channel_id and content/text."
                                     "Include attachmentUrls/attachment_urls to upload files, images, or generated assets."
                                     "To mention a user, use <@user_id> in the text. Do NOT use @username — it will not ping."]
                           publish-params
                           publish-execute))

(def read-params
  [:map
   [:channel_id {:description "Discord channel ID to read messages from."} :string]
   [:limit {:optional true :description "Maximum number of messages to return."} [:int {:min 1 :max 100}]]])

(defn read-execute [runtime config tool-call-id params a b c]
  (let [params (xdiscord/normalize-tool-params params)]
    (channel-messages-execute runtime config tool-call-id
                              {:channel_id (or (decisions/pget params :channel_id :channelId) "")
                               :limit (decisions/pget params :limit)}
                              a b c)))

(def read-tool (partial tools/create-tool-obj "discord.read" "Discord Read"
                        "Read recent messages from a Discord channel."
                        "Read recent messages from a Discord channel to understand context."
                        ["Use discord.read as a simple alias for discord.channel.messages."
                                  "For pagination or cursors, use discord.channel.messages or discord.channel.scroll directly."]
                        read-params
                        read-execute))

;; ---------------------------------------------------------------------------
;; create-discord-custom-tools: gate + collect
;; ---------------------------------------------------------------------------

(defn create-discord-custom-tools
  ([runtime config] (create-discord-custom-tools runtime config nil))
  ([runtime config auth-context]
   (let [allowed? (fn [tool-id]
                    (or (nil? auth-context)
                        (authz/ctx-tool-allowed? auth-context tool-id)))]
     (xdiscord/tool-array
      (remove nil?
              [(when (allowed? "discord.publish") (publish-tool runtime config))
               (when (allowed? "discord.send") (discord-send-tool runtime config))
               (when (allowed? "discord.react") (react-tool runtime config))
               (when (allowed? "discord.thread.create") (thread-create-tool runtime config))
               (when (allowed? "discord.read") (read-tool runtime config))
               (when (allowed? "discord.channel.messages") (channel-messages-tool runtime config))
               (when (allowed? "discord.channel.scroll") (channel-scroll-tool runtime config))
               (when (allowed? "discord.dm.messages") (dm-messages-tool runtime config))
               (when (allowed? "discord.search") (search-tool runtime config))
               (when (allowed? "discord.guilds") (guilds-tool runtime config))
               (when (allowed? "discord.list.servers") (list-servers-tool runtime config))
               (when (allowed? "discord.channels") (channels-tool runtime config))
               (when (allowed? "discord.list.channels") (list-channels-tool runtime config))])))))
