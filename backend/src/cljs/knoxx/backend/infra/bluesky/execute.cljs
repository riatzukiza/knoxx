(ns knoxx.backend.infra.bluesky.execute
  "Bluesky tool callbacks preserving validation, updates and result contracts."
  (:require [clojure.string :as str]
            [knoxx.backend.domain.bluesky.bluesky :as decisions]
            [knoxx.backend.domain.text :as text]
            [knoxx.backend.domain.tools :as tools]
            [knoxx.backend.extern.bluesky :as xbluesky]
            [knoxx.backend.infra.bluesky.api :as api]))

(defn ^:async publish-execute [runtime config _tool-call-id params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        text (or (xbluesky/tool-param params "text") "")
        images (or (xbluesky/tool-param params "images") [])
        image-alts (or (xbluesky/tool-param params "imageAlts") [])
        reply-to (or (xbluesky/tool-param params "replyTo") "")]
    (when (str/blank? (str/trim text))
      (throw (xbluesky/error "text is required")))
    (tools/maybe-tool-update! on-update "Publishing to Bluesky…")
    (let [result (await (api/bluesky-publish! runtime config text images image-alts reply-to))]
      (text/tool-text-result (str "Published Bluesky post\n" (or (:url result) (:uri result) ""))
                        result))))

(defn ^:async profile-execute [runtime _config _tool-call-id params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        actor (or (xbluesky/tool-param params "actor") "")]
    (tools/maybe-tool-update! on-update "Reading Bluesky profile…")
    (let [profile (await (api/bluesky-profile! runtime actor))]
      (text/tool-text-result
       (str "Bluesky profile: " (or (:displayName profile) (:handle profile) "unknown")
            (when-not (str/blank? (str (:handle profile))) (str " (@" (:handle profile) ")"))
            "\nFollowers: " (:followersCount profile)
            " | Following: " (:followsCount profile)
            " | Posts: " (:postsCount profile)
            (when-not (str/blank? (str (:description profile)))
              (str "\n\n" (:description profile)))
            (when-not (str/blank? (str (:url profile)))
              (str "\n\n" (:url profile))))
       profile))))

(defn ^:async search-execute [runtime _config _tool-call-id params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        query (or (xbluesky/tool-param params "query") "")
        kind (or (xbluesky/tool-param params "kind") "posts")
        limit (max 1 (min 25 (or (xbluesky/tool-param params "limit") 5)))]
    (when (str/blank? (str/trim query))
      (throw (xbluesky/error "query is required")))
    (tools/maybe-tool-update! on-update "Searching Bluesky…")
    (let [result (await (api/bluesky-search! runtime query kind limit))]
      (text/tool-text-result (decisions/format-posts text/clip-text (str "Bluesky search (" (:kind result) ")") (:results result))
                        result))))

(defn ^:async author-feed-execute [_runtime _config _tool-call-id params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        actor (or (xbluesky/tool-param params "actor") "")
        limit (max 1 (min 25 (or (xbluesky/tool-param params "limit") 8)))]
    (when (str/blank? (str/trim actor))
      (throw (xbluesky/error "actor is required")))
    (tools/maybe-tool-update! on-update (str "Reading Bluesky feed for " actor "…"))
    (let [result (await (api/bluesky-author-feed! actor limit))]
      (text/tool-text-result (decisions/format-posts text/clip-text (str "Bluesky author feed: " actor) (:results result))
                        result))))

(defn ^:async timeline-execute [runtime _config _tool-call-id params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        limit (max 1 (min 25 (or (xbluesky/tool-param params "limit") 8)))
        cursor (or (xbluesky/tool-param params "cursor") "")]
    (tools/maybe-tool-update! on-update "Reading authenticated Bluesky timeline…")
    (let [result (await (api/bluesky-timeline! runtime limit cursor))]
      (text/tool-text-result (decisions/format-posts text/clip-text "Bluesky timeline" (:results result))
                        result))))

(defn ^:async repost-execute [runtime _config _tool-call-id params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        uri (or (xbluesky/tool-param params "uri") "")]
    (when (str/blank? uri)
      (throw (xbluesky/error "uri is required")))
    (tools/maybe-tool-update! on-update "Reposting on Bluesky…")
    (let [result (await (api/bluesky-repost! runtime uri))]
      (text/tool-text-result (str "Reposted Bluesky post\n" (or (:uri result) uri))
                        result))))

(defn ^:async like-execute [runtime _config _tool-call-id params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        uri (or (xbluesky/tool-param params "uri") "")]
    (when (str/blank? uri)
      (throw (xbluesky/error "uri is required")))
    (tools/maybe-tool-update! on-update "Liking Bluesky post…")
    (let [result (await (api/bluesky-like! runtime uri))]
      (text/tool-text-result (str "Liked Bluesky post\n" (or (:uri result) uri))
                        result))))

(defn ^:async unlike-execute [runtime _config _tool-call-id params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        uri (or (xbluesky/tool-param params "uri") "")]
    (when (str/blank? uri)
      (throw (xbluesky/error "uri is required")))
    (tools/maybe-tool-update! on-update "Removing Bluesky like…")
    (await (api/bluesky-unlike! runtime uri))
    (text/tool-text-result (str "Removed like from " uri) {})))

(defn ^:async follow-execute [runtime _config _tool-call-id params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        actor (or (xbluesky/tool-param params "actor") "")]
    (when (str/blank? actor)
      (throw (xbluesky/error "actor is required")))
    (tools/maybe-tool-update! on-update (str "Following " actor " on Bluesky…"))
    (let [result (await (api/bluesky-follow! runtime actor))]
      (text/tool-text-result (str "Followed " actor "\n" (or (:uri result) ""))
                        result))))

(defn ^:async unfollow-execute [runtime _config _tool-call-id params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        uri (or (xbluesky/tool-param params "uri") "")]
    (when (str/blank? uri)
      (throw (xbluesky/error "uri is required")))
    (tools/maybe-tool-update! on-update "Unfollowing on Bluesky…")
    (await (api/bluesky-unfollow! runtime uri))
    (text/tool-text-result (str "Unfollowed " uri) {})))

(defn ^:async delete-execute [runtime _config _tool-call-id params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        uri (or (xbluesky/tool-param params "uri") "")]
    (when (str/blank? uri)
      (throw (xbluesky/error "uri is required")))
    (tools/maybe-tool-update! on-update "Deleting Bluesky post…")
    (await (api/bluesky-delete-post! runtime uri))
    (text/tool-text-result (str "Deleted Bluesky post " uri) {})))

(defn ^:async thread-execute [_runtime _config _tool-call-id params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        uri (or (xbluesky/tool-param params "uri") "")
        depth (max 1 (min 10 (or (xbluesky/tool-param params "depth") 6)))]
    (when (str/blank? uri)
      (throw (xbluesky/error "uri is required")))
    (tools/maybe-tool-update! on-update "Reading Bluesky thread…")
    (let [result (await (api/bluesky-thread! uri depth))]
      (text/tool-text-result (str "Bluesky thread\n" (str/join "\n" (:lines result)))
                        result))))

(defn ^:async notifications-execute [runtime _config _tool-call-id params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        limit (max 1 (min 50 (or (xbluesky/tool-param params "limit") 20)))]
    (tools/maybe-tool-update! on-update "Reading Bluesky notifications…")
    (let [payload (await (api/bluesky-notifications! runtime limit))]
      (text/tool-text-result (decisions/notifications-text text/clip-text payload) payload))))

(defn ^:async followers-execute [_runtime _config _tool-call-id params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        actor (or (xbluesky/tool-param params "actor") "")
        limit (max 1 (min 50 (or (xbluesky/tool-param params "limit") 25)))]
    (when (str/blank? actor)
      (throw (xbluesky/error "actor is required")))
    (tools/maybe-tool-update! on-update (str "Reading followers of " actor "…"))
    (let [result (await (api/bluesky-followers! actor limit))]
      (text/tool-text-result (decisions/format-posts text/clip-text (str "Followers of " actor) (:results result))
                        result))))

(defn ^:async follows-execute [_runtime _config _tool-call-id params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        actor (or (xbluesky/tool-param params "actor") "")
        limit (max 1 (min 50 (or (xbluesky/tool-param params "limit") 25)))]
    (when (str/blank? actor)
      (throw (xbluesky/error "actor is required")))
    (tools/maybe-tool-update! on-update (str "Reading who " actor " follows…"))
    (let [result (await (api/bluesky-follows! actor limit))]
      (text/tool-text-result (decisions/format-posts text/clip-text (str "Follows of " actor) (:results result))
                        result))))

(defn ^:async chat-list-execute [runtime _config _tool-call-id params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        limit (max 1 (min 50 (or (xbluesky/tool-param params "limit") 20)))]
    (tools/maybe-tool-update! on-update "Listing Bluesky conversations…")
    (let [payload (await (api/bluesky-chat-list! runtime limit))]
      (text/tool-text-result (decisions/chat-list-text text/clip-text payload) payload))))

(defn ^:async chat-send-execute [runtime _config _tool-call-id params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        convo-id (or (xbluesky/tool-param params "convoId") "")
        text (or (xbluesky/tool-param params "text") "")
        reply-to (or (xbluesky/tool-param params "replyToMessageId") "")]
    (when (str/blank? convo-id)
      (throw (xbluesky/error "convoId is required")))
    (when (str/blank? text)
      (throw (xbluesky/error "text is required")))
    (tools/maybe-tool-update! on-update "Sending Bluesky DM…")
    (let [result (await (api/bluesky-chat-send! runtime convo-id text reply-to))]
      (text/tool-text-result (str "Sent DM in conversation " convo-id)
                        result))))

(defn ^:async chat-read-execute [runtime _config _tool-call-id params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        convo-id (or (xbluesky/tool-param params "convoId") "")
        limit (max 1 (min 100 (or (xbluesky/tool-param params "limit") 25)))]
    (when (str/blank? convo-id)
      (throw (xbluesky/error "convoId is required")))
    (tools/maybe-tool-update! on-update (str "Reading Bluesky DMs in " convo-id "…"))
    (let [payload (await (api/bluesky-chat-messages! runtime convo-id limit))]
      (text/tool-text-result (decisions/chat-read-text text/clip-text payload) payload))))

(defn ^:async chat-react-execute [runtime _config _tool-call-id params a b c]
  (let [on-update (or (when (fn? a) a) (when (fn? b) b) (when (fn? c) c))
        convo-id (or (xbluesky/tool-param params "convoId") "")
        message-id (or (xbluesky/tool-param params "messageId") "")
        emoji (or (xbluesky/tool-param params "emoji") "")]
    (when (str/blank? convo-id)
      (throw (xbluesky/error "convoId is required")))
    (when (str/blank? message-id)
      (throw (xbluesky/error "messageId is required")))
    (when (str/blank? emoji)
      (throw (xbluesky/error "emoji is required")))
    (tools/maybe-tool-update! on-update (str "Reacting to message " message-id "…"))
    (let [result (await (api/bluesky-chat-react! runtime convo-id message-id emoji))]
      (text/tool-text-result (str "Reacted " emoji " to message " message-id)
                        result))))

