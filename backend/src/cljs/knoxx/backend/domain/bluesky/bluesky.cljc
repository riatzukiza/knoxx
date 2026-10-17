(ns knoxx.backend.domain.bluesky.bluesky
  "Portable Bluesky tool schemas, projections and presentation decisions."
  (:require [clojure.string :as str]))

(def publish-params
  [:map
   [:text {:description "Bluesky post text. Keep it concise and under platform limits."} :string]
   [:images {:optional true :description "Optional image sources (workspace paths, URLs, or data URLs). Up to 4 images."} [:vector :string]]
   [:imageAlts {:optional true :description "Optional alt text for each image."} [:vector :string]]
   [:replyTo {:optional true :description "Optional AT-URI of a post to reply to."} :string]])

(def profile-params
  [:map
   [:actor {:optional true :description "Optional Bluesky handle or DID. Defaults to the authenticated account."} :string]])

(def search-params
  [:map
   [:query {:description "Search query for Bluesky posts or actors."} :string]
   [:kind {:optional true :description "posts or actors. Defaults to posts."} :string]
   [:limit {:optional true :description "Maximum results to return."} [:int {:min 1 :max 25}]]])

(def feed-params
  [:map
   [:actor {:description "Bluesky handle or DID whose feed should be read."} :string]
   [:limit {:optional true :description "Maximum posts to return."} [:int {:min 1 :max 25}]]])

(def timeline-params
  [:map
   [:limit {:optional true :description "Maximum timeline posts to return."} [:int {:min 1 :max 25}]]
   [:cursor {:optional true :description "Optional pagination cursor from a previous bluesky.timeline call."} :string]])

(def post-uri-params
  [:map
   [:uri {:description "AT-URI of the Bluesky post (at://did/collection/rkey)."} :string]])

(def actor-params
  [:map
   [:actor {:description "Bluesky handle or DID."} :string]])

(def thread-params
  [:map
   [:uri {:description "AT-URI of the root post to read the thread for."} :string]
   [:depth {:optional true :description "Depth of replies to fetch. Default 6."} [:int {:min 1 :max 10}]]])

(def notifications-params
  [:map
   [:limit {:optional true :description "Maximum notifications to return."} [:int {:min 1 :max 50}]]
   [:cursor {:optional true :description "Optional pagination cursor."} :string]])

(def chat-send-params
  [:map
   [:convoId {:description "Conversation ID (from bluesky.chat.list)."} :string]
   [:text {:description "Message text to send."} :string]
   [:replyToMessageId {:optional true :description "Optional message ID to reply to within the conversation."} :string]])

(def chat-list-params
  [:map
   [:limit {:optional true :description "Maximum conversations to return."} [:int {:min 1 :max 50}]]])

(def chat-read-params
  [:map
   [:convoId {:description "Conversation ID (from bluesky.chat.list)."} :string]
   [:limit {:optional true :description "Maximum messages to return."} [:int {:min 1 :max 100}]]])

(def chat-react-params
  [:map
   [:convoId {:description "Conversation ID."} :string]
   [:messageId {:description "Message ID to react to."} :string]
   [:emoji {:description "Emoji reaction (e.g. ❤️, 🔥, 😂)."} :string]])

(defn parse-at-uri [uri]
  (let [parts (some-> uri str (str/split #"/"))
        repo (nth parts 2 nil)
        collection (nth parts 3 nil)
        rkey (nth parts 4 nil)]
    (when (and repo collection rkey)
      {:repo repo :collection collection :rkey rkey})))

(defn bluesky-post-url [handle uri]
  (let [post-id (some-> uri str (str/split #"/") last)]
    (when (and (not (str/blank? (str handle)))
               (not (str/blank? (str post-id))))
      (str "https://bsky.app/profile/" handle "/post/" post-id))))

(defn format-posts [clip prefix rows]
  (let [lines (->> rows
                   (map (fn [{:keys [displayName handle text url]}]
                          (str "- " (or (not-empty displayName) handle "unknown")
                               (when (not (str/blank? (str handle))) (str " (@" handle ")"))
                               ": " (clip (or text "") 220)
                               (when (not (str/blank? (str url))) (str "\n  " url)))))
                   (str/join "\n"))]
    (str prefix (when-not (str/blank? lines) (str "\n" lines)))))

(defn format-thread-reply [clip reply depth]
  (let [post (:post reply)
        author (when post (:author post))
        record (when post (:record post))
        handle (or (when author (:handle author)) "")
        display-name (or (when author (:displayName author)) "")
        text (or (when record (:text record)) "")
        uri (or (when post (:uri post)) "")
        indent (str/join "" (repeat depth "  "))]
    (str indent "- " (or (not-empty display-name) handle "unknown")
         (when-not (str/blank? handle) (str " (@" handle ")"))
         ": " (clip text 180)
         (when-not (str/blank? uri) (str "\n" indent "  " uri)))))

(defn collect-thread-replies [clip thread-node depth max-depth acc]
  (if (> depth max-depth)
    acc
    (reduce (fn [a reply]
              (let [new-acc (conj a (format-thread-reply clip reply depth))]
                (collect-thread-replies clip reply (inc depth) max-depth new-acc)))
            acc
            (or (:replies thread-node) []))))

(defn actor-result
  "Project the existing actor search/list presentation."
  [actor]
  {:handle (or (:handle actor) "")
   :displayName (or (:displayName actor) "")
   :description (or (:description actor) "")
   :did (or (:did actor) "")
   :url (when-let [handle (some-> (:handle actor) str not-empty)]
          (str "https://bsky.app/profile/" handle))})

(defn post-result
  "Project a provider post without discarding its original text."
  [post]
  (let [author (:author post)
        record (:record post)
        handle (or (:handle author) "")]
    {:handle handle :displayName (or (:displayName author) "")
     :text (or (:text record) "") :createdAt (or (:createdAt record) "")
     :uri (or (:uri post) "") :url (bluesky-post-url handle (:uri post))}))

(defn search-result
  "Preserve the selected search-kind result fields and row order."
  [kind payload]
  {:kind kind
   :results (if (= kind "actors")
              (mapv actor-result (or (:actors payload) []))
              (mapv post-result (or (:posts payload) [])))})

(defn profile-result
  "Project the current profile defaults and link."
  [profile]
  {:did (or (:did profile) "") :handle (or (:handle profile) "")
   :displayName (or (:displayName profile) "") :description (or (:description profile) "")
   :followersCount (or (:followersCount profile) 0) :followsCount (or (:followsCount profile) 0)
   :postsCount (or (:postsCount profile) 0)
   :url (when-let [handle (some-> (:handle profile) str not-empty)]
          (str "https://bsky.app/profile/" handle))})

(defn feed-rows
  "Project feed entries in provider order."
  [payload]
  (mapv (comp post-result :post) (or (:feed payload) [])))

(defn social-result
  "Project the existing followers/follows result without changing defaults."
  [actor field payload]
  {:actor actor
   :results (mapv (fn [row]
                    {:handle (or (:handle row) "")
                     :displayName (or (:displayName row) "")
                     :did (or (:did row) "")})
                  (or (get payload field) []))})

(defn facet
  "Turn supplied native UTF8-match observations into the original wire map."
  [kind {:keys [match tag byte-start byte-end]}]
  {"$type" "app.bsky.richtext.facet"
   :index {:byteStart byte-start :byteEnd byte-end}
   :features [(if (= kind :tag)
                {"$type" "app.bsky.richtext.facet#tag" :tag tag}
                {"$type" "app.bsky.richtext.facet#link" :uri match})]})

(defn build-facets
  "Preserve hashtag-before-URL ordering and nil when no match exists."
  [hashtags urls]
  (when (or (seq hashtags) (seq urls))
    (vec (concat (map (partial facet :tag) hashtags)
                 (map (partial facet :link) urls)))))

(defn post-record
  "Assemble a post from supplied clock, facet, embed and reply observations."
  [text created-at facets embed reply-refs]
  (cond-> {"$type" "app.bsky.feed.post" :text text :createdAt created-at}
    facets (assoc :facets facets)
    embed (assoc :embed embed)
    reply-refs (assoc :reply reply-refs)))

(defn thread-result
  "Retain the existing root/defaults, clip result rendering and reply traversal."
  [clip payload depth]
  (let [thread (:thread payload)
        root-post (when thread (:post thread))
        root-author (when root-post (:author root-post))
        root-record (when root-post (:record root-post))
        root-handle (or (when root-author (:handle root-author)) "")
        root-display (or (when root-author (:displayName root-author)) "")
        root-text (or (when root-record (:text root-record)) "")
        root-uri (or (when root-post (:uri root-post)) "")
        root-line (str "- " (or (not-empty root-display) root-handle "unknown")
                       (when-not (str/blank? root-handle) (str " (@" root-handle ")"))
                       ": " (clip root-text 200)
                       (when-not (str/blank? root-uri) (str "\n  " root-uri)))
        reply-lines (collect-thread-replies clip thread 1 depth [])]
    {:root {:uri root-uri :text root-text :handle root-handle}
     :lines (into [root-line] reply-lines)}))

(defn chat-message
  "Retain the optional immediate reply message shape."
  [text reply-to-msg-id]
  (cond-> {"$type" "chat.bsky.convo.defs#messageInput" :text text}
    (not (str/blank? reply-to-msg-id))
    (assoc :replyTo {"$type" "chat.bsky.convo.defs#messageRef"
                    :messageId reply-to-msg-id})))

(defn notifications-text
  "Preserve the existing clipped-vector interpolation."
  [clip payload]
  (let [notifications (or (:notifications payload) [])
        lines (mapv (fn [n]
                      (let [author (:author n)
                            reason (:reason n)
                            post (:post n)
                            text (or (get-in post [:record :text]) "")]
                        (str "- [" reason "] "
                             (or (:displayName author) "")
                             " (@" (or (:handle author) "") "): "
                             (clip text 120))))
                    notifications)]
    (str "Bluesky notifications (" (count notifications) ")\n"
         (str/join "\n" lines))))

(defn chat-list-text
  "Preserve conversation/member order and current preview rendering."
  [clip payload]
  (let [convos (or (:convos payload) [])
        lines (mapv (fn [convo]
                      (let [members (or (:members convo) [])
                            names (str/join ", " (map #(or (:displayName %) (:handle %) "") members))
                            last-msg (:lastMessage convo)]
                        (str "- " (:id convo) ": " names
                             (when last-msg
                               (str " — " (clip (or (:text last-msg) "") 80))))))
                    convos)]
    (str "Bluesky conversations (" (count convos) ")\n" (str/join "\n" lines))))

(defn chat-read-text
  "Preserve message order, identifiers and current preview rendering."
  [clip payload]
  (let [messages (or (:messages payload) [])
        lines (mapv (fn [msg]
                      (let [sender (:sender msg)
                            text (or (:text msg) "")
                            msg-id (or (:id msg) "")]
                        (str "- [" msg-id "] "
                             (or (:displayName sender) "")
                             " (@" (or (:handle sender) "") "): "
                             (clip text 200))))
                    messages)]
    (str "Bluesky DMs (" (count messages) ")\n" (str/join "\n" lines))))
