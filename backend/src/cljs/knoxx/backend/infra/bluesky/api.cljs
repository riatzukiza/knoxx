(ns knoxx.backend.infra.bluesky.api
  "Bluesky credential, media and provider orchestration at existing boundaries."
  (:require [clojure.string :as str]
            [knoxx.backend.domain.bluesky.bluesky :as decisions]
            [knoxx.backend.domain.bluesky.client :as bsky-client]
            [knoxx.backend.domain.media :as media]
            [knoxx.backend.domain.text :as text]
            [knoxx.backend.extern.bluesky :as xbluesky]
            [knoxx.backend.infra.actor.credentials :as actor-credentials]))

(defn- bluesky-client []
  (bsky-client/client))

(defn ^:async bluesky-auth-config! [runtime]
  (let [credential (await (actor-credentials/get-credential! runtime "bluesky"))
        identifier (or (actor-credentials/secret-value credential :identifier :handle :username)
                       (:accountIdentifier credential))
        password (actor-credentials/secret-value credential :appPassword :app-password :password)]
    (when (or (str/blank? (str identifier))
              (str/blank? (str password)))
      (throw (xbluesky/error "Bluesky actor credential must include identifier and appPassword.")))
    {:identifier identifier :password password}))

(defn ^:async bluesky-create-session! [runtime]
  (let [credentials (await (bluesky-auth-config! runtime))]
    (await (bsky-client/create-session! (bluesky-client) credentials))))

(defn- bluesky-upload-blob! [session buffer mime-type]
  (bsky-client/upload-blob! (bluesky-client) session buffer mime-type))

(defn- bluesky-create-record! [session collection record]
  (bsky-client/create-record! (bluesky-client) session collection record))

(defn- bluesky-delete-record! [session collection rkey]
  (bsky-client/delete-record! (bluesky-client) session collection rkey))

(defn- bluesky-resolve-post! [uri]
  (bsky-client/resolve-post! (bluesky-client) uri))

(defn ^:async bluesky-search! [runtime query kind limit]
  (let [kind (if (= kind "actors") "actors" "posts")
        session (await (bluesky-create-session! runtime))
        payload (if (= kind "actors")
                  (await (bsky-client/search-actors! (bluesky-client) session query limit))
                  (await (bsky-client/search-posts! (bluesky-client) session query limit)))]
    (decisions/search-result kind payload)))

(defn ^:async bluesky-profile! [runtime actor]
  (let [actor (some-> actor str str/trim)
        resolved-actor (if (str/blank? actor)
                         (let [session (await (bluesky-create-session! runtime))]
                           (or (:handle session) (:did session)))
                         actor)
        profile (await (bsky-client/profile! (bluesky-client) resolved-actor))]
    (decisions/profile-result profile)))

(defn ^:async bluesky-author-feed! [actor limit]
  (let [payload (await (bsky-client/actor-feed! (bluesky-client) nil actor limit))]
    {:actor actor :results (decisions/feed-rows payload)}))

(defn ^:async bluesky-timeline! [runtime limit cursor]
  (let [session (await (bluesky-create-session! runtime))
        payload (await (bsky-client/timeline! (bluesky-client) session {:limit limit :cursor cursor}))]
    {:cursor (:cursor payload) :results (decisions/feed-rows payload)}))

(defn ^:async load-and-upload-image! [runtime config session alts idx img-src]
  (let [source (await (media/load-media-source! runtime config img-src media/multimodal-upload-max-bytes))
        blob-result (await (bluesky-upload-blob! session (:buffer source) (:mime-type source)))
        blob (or (:blob blob-result) blob-result)
        alt (or (nth alts idx nil) "")]
    {:alt alt :image blob}))

(defn ^:async load-and-upload-images! [runtime config session images alts]
  (when (seq images)
    (let [uploaded (await (xbluesky/promise-all-vector
                           (map-indexed
                            (partial load-and-upload-image! runtime config session alts)
                            images)))]
      {"$type" "app.bsky.embed.images" :images uploaded})))

(defn ^:async resolve-reply-refs! [reply-to-uri]
  (when-not (str/blank? reply-to-uri)
    (let [parent (await (bluesky-resolve-post! reply-to-uri))]
      {:parent {:uri (:uri parent) :cid (:cid parent)}
       :root {:uri (:uri parent) :cid (:cid parent)}})))

(defn ^:async bluesky-publish! [runtime config text images image-alts reply-to]
  (let [session (await (bluesky-create-session! runtime))
        embed (await (load-and-upload-images! runtime config session images image-alts))
        reply-refs (await (resolve-reply-refs! reply-to))
        facets (decisions/build-facets (xbluesky/hashtag-matches text) (xbluesky/url-matches text))
        record (decisions/post-record text (xbluesky/now-iso) facets embed reply-refs)
        result (await (bluesky-create-record! session "app.bsky.feed.post" record))
        uri (or (:uri result) "")]
    {:uri uri :cid (or (:cid result) "")
     :url (or (decisions/bluesky-post-url (:handle session) uri) "")}))

(defn ^:async bluesky-repost! [runtime uri]
  (let [session (await (bluesky-create-session! runtime))
        post (await (bluesky-resolve-post! uri))]
    (await (bluesky-create-record!
            session "app.bsky.feed.repost"
            {"$type" "app.bsky.feed.repost"
             :subject {:uri (:uri post) :cid (:cid post)}
             :createdAt (xbluesky/now-iso)}))))

(defn ^:async bluesky-like! [runtime uri]
  (let [session (await (bluesky-create-session! runtime))
        post (await (bluesky-resolve-post! uri))]
    (await (bluesky-create-record!
            session "app.bsky.feed.like"
            {"$type" "app.bsky.feed.like"
             :subject {:uri (:uri post) :cid (:cid post)}
             :createdAt (xbluesky/now-iso)}))))

(defn ^:async bluesky-unlike! [runtime uri]
  (let [session (await (bluesky-create-session! runtime))
        {:keys [repo collection rkey]} (decisions/parse-at-uri uri)]
    (if (and repo collection rkey)
      (await (bluesky-delete-record! session collection rkey))
      (throw (xbluesky/error (str "Invalid AT-URI for unlike: " uri))))))

(defn ^:async bluesky-follow! [runtime actor]
  (let [session (await (bluesky-create-session! runtime))
        profile (await (bluesky-profile! runtime actor))]
    (await (bluesky-create-record!
            session "app.bsky.graph.follow"
            {"$type" "app.bsky.graph.follow"
             :subject (:did profile)
             :createdAt (xbluesky/now-iso)}))))

(defn ^:async bluesky-unfollow! [runtime uri]
  (let [session (await (bluesky-create-session! runtime))
        {:keys [repo collection rkey]} (decisions/parse-at-uri uri)]
    (if (and repo collection rkey)
      (await (bluesky-delete-record! session collection rkey))
      (throw (xbluesky/error (str "Invalid AT-URI for unfollow: " uri))))))

(defn ^:async bluesky-delete-post! [runtime uri]
  (let [session (await (bluesky-create-session! runtime))
        {:keys [repo collection rkey]} (decisions/parse-at-uri uri)]
    (if (and repo collection rkey)
      (await (bluesky-delete-record! session collection rkey))
      (throw (xbluesky/error (str "Invalid AT-URI for delete: " uri))))))

(defn ^:async bluesky-thread! [uri depth]
  (let [payload (await (bsky-client/thread! (bluesky-client) nil uri depth))]
    (decisions/thread-result text/clip-text payload depth)))

(defn ^:async bluesky-notifications! [runtime limit]
  (let [session (await (bluesky-create-session! runtime))]
    (await (bsky-client/notifications! (bluesky-client) session {:limit limit}))))

(defn ^:async bluesky-followers! [actor limit]
  (let [payload (await (bsky-client/followers! (bluesky-client) actor limit))]
    (decisions/social-result actor :followers payload)))

(defn ^:async bluesky-follows! [actor limit]
  (let [payload (await (bsky-client/follows! (bluesky-client) actor limit))]
    (decisions/social-result actor :follows payload)))

(defn ^:async bluesky-chat-list! [runtime limit]
  (let [session (await (bluesky-create-session! runtime))]
    (await (bsky-client/chat-list! (bluesky-client) session {:limit limit}))))

(defn ^:async bluesky-chat-messages! [runtime convo-id limit]
  (let [session (await (bluesky-create-session! runtime))]
    (await (bsky-client/chat-read! (bluesky-client) session convo-id {:limit limit}))))

(defn ^:async bluesky-chat-send! [runtime convo-id text reply-to-msg-id]
  (let [session (await (bluesky-create-session! runtime))
        msg (decisions/chat-message text reply-to-msg-id)]
    (await (bsky-client/chat-send! (bluesky-client) session convo-id msg))))

(defn ^:async bluesky-chat-react! [runtime convo-id message-id emoji]
  (let [session (await (bluesky-create-session! runtime))]
    (await (bsky-client/chat-react! (bluesky-client) session convo-id message-id emoji))))
