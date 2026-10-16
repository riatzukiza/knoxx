(ns knoxx.backend.infra.bluesky-tools-characterization-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [knoxx.backend.extern.bluesky-tools-fixture :as fixture]))

(def expected-ids
  ["bluesky.publish" "bluesky.profile" "bluesky.search" "bluesky.author.feed"
   "bluesky.timeline" "bluesky.repost" "bluesky.like" "bluesky.unlike"
   "bluesky.follow" "bluesky.unfollow" "bluesky.delete" "bluesky.thread"
   "bluesky.notifications" "bluesky.followers" "bluesky.follows"
   "bluesky.chat.list" "bluesky.chat.read" "bluesky.chat.send" "bluesky.chat.react"])

(def expected-schemas
  [[:map
    [:text {:description "Bluesky post text. Keep it concise and under platform limits."} :string]
    [:images {:optional true :description "Optional image sources (workspace paths, URLs, or data URLs). Up to 4 images."} [:vector :string]]
    [:imageAlts {:optional true :description "Optional alt text for each image."} [:vector :string]]
    [:replyTo {:optional true :description "Optional AT-URI of a post to reply to."} :string]]
   [:map [:actor {:optional true :description "Optional Bluesky handle or DID. Defaults to the authenticated account."} :string]]
   [:map
    [:query {:description "Search query for Bluesky posts or actors."} :string]
    [:kind {:optional true :description "posts or actors. Defaults to posts."} :string]
    [:limit {:optional true :description "Maximum results to return."} [:int {:min 1 :max 25}]]]
   [:map
    [:actor {:description "Bluesky handle or DID whose feed should be read."} :string]
    [:limit {:optional true :description "Maximum posts to return."} [:int {:min 1 :max 25}]]]
   [:map
    [:limit {:optional true :description "Maximum timeline posts to return."} [:int {:min 1 :max 25}]]
    [:cursor {:optional true :description "Optional pagination cursor from a previous bluesky.timeline call."} :string]]
   [:map [:uri {:description "AT-URI of the Bluesky post (at://did/collection/rkey)."} :string]]
   [:map [:actor {:description "Bluesky handle or DID."} :string]]
   [:map
    [:uri {:description "AT-URI of the root post to read the thread for."} :string]
    [:depth {:optional true :description "Depth of replies to fetch. Default 6."} [:int {:min 1 :max 10}]]]
   [:map
    [:limit {:optional true :description "Maximum notifications to return."} [:int {:min 1 :max 50}]]
    [:cursor {:optional true :description "Optional pagination cursor."} :string]]
   [:map
    [:convoId {:description "Conversation ID (from bluesky.chat.list)."} :string]
    [:text {:description "Message text to send."} :string]
    [:replyToMessageId {:optional true :description "Optional message ID to reply to within the conversation."} :string]]
   [:map [:limit {:optional true :description "Maximum conversations to return."} [:int {:min 1 :max 50}]]]
   [:map
    [:convoId {:description "Conversation ID (from bluesky.chat.list)."} :string]
    [:limit {:optional true :description "Maximum messages to return."} [:int {:min 1 :max 100}]]]
   [:map
    [:convoId {:description "Conversation ID."} :string]
    [:messageId {:description "Message ID to react to."} :string]
    [:emoji {:description "Emoji reaction (e.g. ❤️, 🔥, 😂)."} :string]]])
(def created {:uri "at://did:self/collection/new" :cid "new-cid"})
(def empty-profile {:did "" :handle "" :displayName "" :description ""
                    :followersCount 0 :followsCount 0 :postsCount 0 :url nil})
(def empty-thread {:root {:uri "" :text "" :handle ""} :lines [(str "- : " ["" false])]})
(defn- wire-result [text details]
  {:content [{:type "text" :text text}] :details details})
(defn- execute! [world id params]
  (fixture/execute! world (fixture/tools nil) id params [:a :b :c]))
(defn- record-call [world]
  (:args (first (fixture/calls-of world :create))))
(def callback-cases
  [["bluesky.publish" {:text "hello"} :create "Published Bluesky post\nhttps://bsky.app/profile/self.test/post/new"
    (assoc created :url "https://bsky.app/profile/self.test/post/new")]
   ["bluesky.profile" {} :profile "Bluesky profile: \nFollowers: 0 | Following: 0 | Posts: 0" empty-profile]
   ["bluesky.search" {:query "q"} :posts "Bluesky search (posts)" {:kind "posts" :results []}]
   ["bluesky.author.feed" {:actor "alice"} :feed "Bluesky author feed: alice" {:actor "alice" :results []}]
   ["bluesky.timeline" {} :timeline "Bluesky timeline" {:cursor nil :results []}]
   ["bluesky.repost" {:uri "uri"} :create "Reposted Bluesky post\nat://did:self/collection/new" created]
   ["bluesky.like" {:uri "uri"} :create "Liked Bluesky post\nat://did:self/collection/new" created]
   ["bluesky.unlike" {:uri "at://foreign/collection/rkey"} :delete "Removed like from at://foreign/collection/rkey" {}]
   ["bluesky.follow" {:actor "alice"} :create "Followed alice\nat://did:self/collection/new" created]
   ["bluesky.unfollow" {:uri "at://foreign/collection/rkey"} :delete "Unfollowed at://foreign/collection/rkey" {}]
   ["bluesky.delete" {:uri "at://foreign/collection/rkey"} :delete "Deleted Bluesky post at://foreign/collection/rkey" {}]
   ["bluesky.thread" {:uri "uri"} :thread (str "Bluesky thread\n" (first (:lines empty-thread))) empty-thread]
   ["bluesky.notifications" {} :notifications "Bluesky notifications (0)\n" {}]
   ["bluesky.followers" {:actor "alice"} :followers "Followers of alice" {:actor "alice" :results []}]
   ["bluesky.follows" {:actor "alice"} :follows "Follows of alice" {:actor "alice" :results []}]
   ["bluesky.chat.list" {} :chat-list "Bluesky conversations (0)\n" {}]
   ["bluesky.chat.read" {:convoId "convo"} :chat-read "Bluesky DMs (0)\n" {}]
   ["bluesky.chat.send" {:convoId "convo" :text "hello"} :chat-send "Sent DM in conversation convo" {:id "dm-id"}]
   ["bluesky.chat.react" {:convoId "convo" :messageId "msg" :emoji "ok"} :chat-react "Reacted ok to message msg" {}]])

(deftest ^:async real-factory-order-thirteen-schemas-and-nil-auth
  (await (fixture/with-world! {}
           (^:async fn [world]
             (let [rows (fixture/descriptors (fixture/tools nil))]
               (is (= expected-ids (mapv :name rows)))
               (is (= expected-schemas (fixture/schemas)))
               (is (= [] (fixture/calls world)))
               (is (= "object" (get-in rows [0 :parameters :type])))
               (is (= 25 (get-in rows [13 :parameters :properties :limit :maximum])))
               (is (= 50 (get-in rows [15 :parameters :properties :limit :maximum]))))))))

(deftest ^:async nonnil-auth-filters-each-real-identity-before-construction
  (await (fixture/with-world! {:allow #{"bluesky.chat.read" "bluesky.profile"}}
           (^:async fn [world]
             (is (= ["bluesky.profile" "bluesky.chat.read"]
                    (mapv :name (fixture/descriptors (fixture/tools {:owner "fixture"})))))
             (is (= expected-ids (mapv :id (fixture/calls-of world :permission))))
             (is (every? #(= {:owner "fixture"} (:context %)) (fixture/calls world)))
             (is (empty? (fixture/calls-of world :credential))))))
  (await (fixture/with-world! {:allow #{}}
           (^:async fn [world]
             (is (empty? (fixture/descriptors (fixture/tools {}))))
             (is (= 19 (count (fixture/calls-of world :permission))))))))

(deftest ^:async all-nineteen-real-callbacks-preserve-results-and-call-intended-provider
  (doseq [[id params op text details] callback-cases]
    (await (fixture/with-world! {}
             (^:async fn [world]
               (is (= (wire-result text details) (await (execute! world id params))) id)
               (is (= 1 (count (fixture/calls-of world op))) id)
               (is (= [:a] (mapv :slot (fixture/calls-of world :update))) id)
               (is (= :update (:op (first (fixture/calls world)))) id)
               (is (every? #(= "bluesky" (:provider %)) (fixture/calls-of world :credential)) id))))))

(deftest ^:async first-callable-update-slot-wins-and-update-precedes-effects
  (doseq [slots [[:a :b :c] [nil :b :c] [nil nil :c] [nil nil nil]]]
    (await (fixture/with-world! {}
             (^:async fn [world]
               (await (fixture/execute! world (fixture/tools nil) "bluesky.chat.react"
                                       {:convoId "c" :messageId "m" :emoji "🔥"} slots))
               (is (= (vec (take 1 (remove nil? slots))) (mapv :slot (fixture/calls-of world :update))))
               (is (= 1 (count (fixture/calls-of world :chat-react))))
               (when (some some? slots)
                 (is (= {:content [{:type "text" :text "Reacting to message m…"}]}
                        (:payload (first (fixture/calls-of world :update)))))))))))

(def required-cases
  [["bluesky.publish" {:text " \n "} "text is required"]
   ["bluesky.search" {:query " "} "query is required"]
   ["bluesky.author.feed" {:actor " "} "actor is required"]
   ["bluesky.follow" {} "actor is required"]
   ["bluesky.followers" {} "actor is required"] ["bluesky.follows" {} "actor is required"]
   ["bluesky.repost" {} "uri is required"] ["bluesky.like" {} "uri is required"]
   ["bluesky.unlike" {} "uri is required"] ["bluesky.unfollow" {} "uri is required"]
   ["bluesky.delete" {} "uri is required"] ["bluesky.thread" {} "uri is required"]
   ["bluesky.chat.read" {} "convoId is required"] ["bluesky.chat.send" {} "convoId is required"]
   ["bluesky.chat.send" {:convoId "c" :text " "} "text is required"]
   ["bluesky.chat.react" {} "convoId is required"]
   ["bluesky.chat.react" {:convoId "c"} "messageId is required"]
   ["bluesky.chat.react" {:convoId "c" :messageId "m"} "emoji is required"]])

(deftest ^:async required-values-refuse-before-any-update-credential-or-provider
  (doseq [[id params message] required-cases]
    (await (fixture/with-world! {}
             (^:async fn [world]
               (is (= {:ok false :message message}
                      (await (fixture/outcome! #(execute! world id params)))) id)
               (is (= [] (fixture/calls world)) id))))))

(deftest ^:async credential-alias-precedence-trimming-and-account-fallback
  (doseq [[credential identifier password]
          [[{:secretJson {:identifier " first " :handle "second" :username "third"
                          :appPassword " a " :app-password "b" :password "c"}
             :accountIdentifier "fourth"} "first" "a"]
           [{:secretJson {:identifier " " :handle " h " :username "u" :app-password " p "}} "h" "p"]
           [{:secretJson {"username" " u " "password" " p "}} "u" "p"]
           [{:accountIdentifier " raw-account " :secretJson {:password "p"}} " raw-account " "p"]]]
    (await (fixture/with-world! {:credential credential}
             (^:async fn [world]
               (is (= {:identifier identifier :password password} (await (fixture/auth!))))
               (is (= [{:op :credential :runtime fixture/runtime :provider "bluesky"}] (fixture/calls world))))))))

(deftest ^:async missing-and-denied-credentials-refuse-before-client-session
  (doseq [[options message] [[{:credential {}} "Bluesky actor credential must include identifier and appPassword."]
                             [{:credential-error? true} "fixture-credential-denied"]]]
    (await (fixture/with-world! options
             (^:async fn [world]
               (is (= {:ok false :message message}
                      (await (fixture/outcome! #(fixture/publish! "text" [] [] "")))))
               (is (= 1 (count (fixture/calls-of world :credential))))
               (is (empty? (fixture/calls-of world :client))))))))

(def limit-cases
  [["bluesky.search" {:query "q"} :posts 2 5 25]
   ["bluesky.author.feed" {:actor "a"} :feed 2 8 25]
   ["bluesky.timeline" {:cursor "next"} :timeline 1 {:limit 8 :cursor "next"} {:limit 25 :cursor "next"}]
   ["bluesky.notifications" {:cursor "ignored"} :notifications 1 {:limit 20} {:limit 50}]
   ["bluesky.followers" {:actor "a"} :followers 1 25 50]
   ["bluesky.follows" {:actor "a"} :follows 1 25 50]
   ["bluesky.chat.list" {} :chat-list 1 {:limit 20} {:limit 50}]
   ["bluesky.chat.read" {:convoId "c"} :chat-read 2 {:limit 25} {:limit 100}]])

(deftest ^:async limits-default-clamp-and-cursor-forwarding-match-current-callbacks
  (doseq [[id params op index default upper] limit-cases
          [limit expected] [[nil default] [999 upper] [0 (if (map? default) (assoc default :limit 1) 1)]]]
    (await (fixture/with-world! {}
             (^:async fn [world]
               (await (execute! world id (cond-> params (some? limit) (assoc :limit limit))))
               (is (= 1 (count (fixture/calls-of world op))))
               (is (= expected (nth (:args (first (fixture/calls-of world op))) index))))))))

(deftest ^:async public-projections-default-fields-and-auth-boundaries
  (await (fixture/with-world! {:responses {:posts {:posts [{}]} :actors {:actors [{}]} :feed {:feed [{}]}
                                         :timeline {:feed [{}] :cursor "next"} :followers {:followers [{}]}
                                         :follows {:follows [{}]}}}
           (^:async fn [world]
             (let [post {:handle "" :displayName "" :text "" :createdAt "" :uri "" :url nil}
                   actor {:handle "" :displayName "" :description "" :did "" :url nil}]
               (is (= {:kind "posts" :results [post]} (await (fixture/search! "q" "other" 2))))
               (is (= {:kind "actors" :results [actor]} (await (fixture/search! "q" "actors" 2))))
               (is (= {:actor "a" :results [post]} (await (fixture/feed! "a" 2))))
               (is (= {:cursor "next" :results [post]} (await (fixture/timeline! 2 "cursor")))))
             (is (= {:actor "a" :results [{:handle "" :displayName "" :did ""}]} (await (fixture/followers! "a" 2))))
             (is (= {:actor "a" :results [{:handle "" :displayName "" :did ""}]} (await (fixture/follows! "a" 2))))
             (is (= [nil "a" 2] (:args (first (fixture/calls-of world :feed)))))
             (is (= 3 (count (fixture/calls-of world :session))))))))

(deftest ^:async profile-default-authenticates-but-explicit-trimmed-actor-is-public
  (doseq [[actor expected auth-count] [[" alice " "alice" 0] ["" "self.test" 1]]]
    (await (fixture/with-world! {}
             (^:async fn [world]
               (is (= empty-profile (await (fixture/profile! actor))))
               (is (= [expected] (:args (first (fixture/calls-of world :profile)))))
               (is (= auth-count (count (fixture/calls-of world :credential)))))))))

(deftest ^:async plain-publication-keeps-verbatim-text-clock-and-omits-empty-optionals
  (await (fixture/with-world! {}
           (^:async fn [world]
             (await (execute! world "bluesky.publish" {:text "  hello\n "}))
             (is (= [fixture/session "app.bsky.feed.post"
                     {"$type" "app.bsky.feed.post" :text "  hello\n " :createdAt fixture/instant}]
                    (record-call world)))
             (is (= [:update :credential :client :session :clock :client :create]
                    (mapv :op (fixture/calls world))))
             (is (empty? (fixture/calls-of world :media)))
             (is (empty? (fixture/calls-of world :resolve)))))))

(deftest ^:async rich-text-keeps-ascii-tags-utf8-offsets-url-punctuation-and-tag-first-order
  (await (fixture/with-world! {}
           (^:async fn [world]
             (await (fixture/publish! "é😀 é https://a.test, #tag #é #A_2" [] [] ""))
             (is (= [{"$type" "app.bsky.richtext.facet" :index {:byteStart 27 :byteEnd 31}
                      :features [{"$type" "app.bsky.richtext.facet#tag" :tag "tag"}]}
                     {"$type" "app.bsky.richtext.facet" :index {:byteStart 36 :byteEnd 40}
                      :features [{"$type" "app.bsky.richtext.facet#tag" :tag "A_2"}]}
                     {"$type" "app.bsky.richtext.facet" :index {:byteStart 11 :byteEnd 26}
                      :features [{"$type" "app.bsky.richtext.facet#link" :uri "https://a.test,"}]}]
                    (get-in (record-call world) [2 :facets])))
             (is (= 1 (count (fixture/calls-of world :create))))))))

(deftest ^:async image-completion-is-concurrent-but-embed-order-and-alt-index-stay-stable
  (await (fixture/with-world! {:defer-images? true}
           (^:async fn [world]
             (is (= {"$type" "app.bsky.embed.images"
                     :images [{:alt "alt-a" :image {:ref "a"}} {:alt "alt-b" :image {:ref "b"}}]}
                    (await (fixture/reverse-image-completion! world))))
             (is (= ["a" "b"] (mapv :source (fixture/calls-of world :media))))
             (is (= ["b" "a"] (mapv #(get-in % [:args 1]) (fixture/calls-of world :upload))))
             (is (= [26214400 26214400] (mapv :limit (fixture/calls-of world :media))))))))

(deftest ^:async publication-image-blob-fallback-reply-and-effect-order
  (await (fixture/with-world! {:responses {:upload {:ref "unwrapped"}}}
           (^:async fn [world]
             (await (fixture/publish! "text" ["image"] [] "original-parent"))
             (let [record (nth (record-call world) 2)]
               (is (= {"$type" "app.bsky.embed.images" :images [{:alt "" :image {:ref "unwrapped"}}]} (:embed record)))
               (is (= {:parent (select-keys fixture/post-ref [:uri :cid])
                       :root (select-keys fixture/post-ref [:uri :cid])} (:reply record))))
             (is (= [:credential :client :session :media :client :upload :client :resolve :clock :client :create]
                    (mapv :op (fixture/calls world))))))))

(deftest ^:async rejected-image-prevents-reply-clock-and-record-effects
  (await (fixture/with-world! {:media-error "bad"}
           (^:async fn [world]
             (is (= {:ok false :message "fixture-image-refused"}
                    (await (fixture/outcome! #(fixture/publish! "text" ["bad"] [] "reply")))))
             (is (= 1 (count (fixture/calls-of world :media))))
             (is (= 1 (count (fixture/calls-of world :session))))
             (is (every? empty? (map #(fixture/calls-of world %) [:upload :resolve :clock :create])))))))

(deftest ^:async record-operations-retain-collections-subjects-and-clock
  (doseq [[id collection subject]
          [["bluesky.repost" "app.bsky.feed.repost" (select-keys fixture/post-ref [:uri :cid])]
           ["bluesky.like" "app.bsky.feed.like" (select-keys fixture/post-ref [:uri :cid])]
           ["bluesky.follow" "app.bsky.graph.follow" "did:alice"]]]
    (await (fixture/with-world! {:responses {:profile {:did "did:alice"}}}
             (^:async fn [world]
               (await (execute! world id {:uri "original" :actor "alice"}))
               (is (= [fixture/session collection {"$type" collection :subject subject :createdAt fixture/instant}]
                      (record-call world)))
               (is (= 1 (count (fixture/calls-of world :session)))))))))

(deftest ^:async deletion-retains-loose-uri-and-authenticated-session-not-uri-repository
  (doseq [id ["bluesky.unlike" "bluesky.unfollow" "bluesky.delete"]]
    (await (fixture/with-world! {}
             (^:async fn [world]
               (await (execute! world id {:uri "nonsense://foreign/custom.collection/rkey/ignored"}))
               (is (= [fixture/session "custom.collection" "rkey"] (:args (first (fixture/calls-of world :delete)))))
               (is (= 1 (count (fixture/calls-of world :session)))))))))

(deftest ^:async invalid-nonblank-uri-refuses-after-authentication-with-exact-error
  (doseq [[id verb] [["bluesky.unlike" "unlike"] ["bluesky.unfollow" "unfollow"] ["bluesky.delete" "delete"]]]
    (await (fixture/with-world! {}
             (^:async fn [world]
               (is (= {:ok false :message (str "Invalid AT-URI for " verb ": invalid")}
                      (await (fixture/outcome! #(execute! world id {:uri "invalid"})))))
               (is (= 1 (count (fixture/calls-of world :session))))
               (is (empty? (fixture/calls-of world :delete))))))))

(deftest ^:async real-fetch-client-delete-wire-uses-session-repo-and-inert-transport
  (await (fixture/with-world! {:transport? true}
           (^:async fn [world]
             (await (execute! world "bluesky.delete" {:uri "at://foreign/custom.collection/key"}))
             (let [[auth deletion] (fixture/calls-of world :http)]
               (is (= :inert-http-client (:client deletion)))
               (is (= {:identifier "fixture.test" :password "inert-password"} (get-in auth [:request :opts :json])))
               (is (= {:repo "did:fixture:self" :collection "custom.collection" :rkey "key"}
                      (get-in deletion [:request :opts :json])))
               (is (= "Bearer inert-token" (get-in deletion [:request :opts :headers "Authorization"]))))
             (is (= 2 (count (fixture/calls-of world :http))))
             (is (= 1 (count (fixture/calls-of world :credential))))))))

(deftest ^:async chat-send-optional-reply-and-react-arguments-remain-exact
  (doseq [reply [nil "" "parent-message"]]
    (await (fixture/with-world! {}
             (^:async fn [world]
               (await (execute! world "bluesky.chat.send" {:convoId "c" :text " hello " :replyToMessageId reply}))
               (is (= [fixture/session "c"
                       (cond-> {"$type" "chat.bsky.convo.defs#messageInput" :text " hello "}
                         (not (str/blank? reply)) (assoc :replyTo {"$type" "chat.bsky.convo.defs#messageRef" :messageId reply}))]
                      (:args (first (fixture/calls-of world :chat-send)))))
               (is (= 1 (count (fixture/calls-of world :session)))))))))

(deftest ^:async genuine-protocol-rejection-has-positive-call-guard
  (await (fixture/with-world! {:method-error :posts}
           (^:async fn [world]
             (is (= {:ok false :message "fixture-posts-failed"}
                    (await (fixture/outcome! #(execute! world "bluesky.search" {:query "q"})))))
             (is (= 1 (count (fixture/calls-of world :posts))))
             (is (= 1 (count (fixture/calls-of world :session))))))))

(deftest ^:async thread-depth-clamps-and-depth-first-presentation-retains-vector-clipping
  (let [long-text (apply str (repeat 181 "x"))
        reply {:post {:uri "child" :author {:handle "c"} :record {:text long-text}}
               :replies [{:post {:record {:text "deep"}}}]}
        tree {:thread {:post {:uri "root" :author {:displayName "Root" :handle "r"}
                              :record {:text "body"}} :replies [reply {}]}}
        root-line (str "- Root (@r): " ["body" false] "\n  root")
        child-line (str "  - c (@c): " [(apply str (repeat 180 "x")) true] "\n    child")
        blank-line (str "  - : " ["" false])
        deep-line (str "    - : " ["deep" false])]
    (doseq [[input depth] [[nil 6] [0 1] [999 10]]]
      (await (fixture/with-world! {:responses {:thread tree}}
               (^:async fn [world]
                 (let [result (await (execute! world "bluesky.thread" (cond-> {:uri "requested"} input (assoc :depth input))))
                       lines (if (= depth 1) [root-line child-line blank-line] [root-line child-line deep-line blank-line])]
                   (is (= (wire-result (str "Bluesky thread\n" (str/join "\n" lines))
                                       {:root {:uri "root" :text "body" :handle "r"} :lines lines}) result)))
                 (is (= [nil "requested" depth] (:args (first (fixture/calls-of world :thread)))))
                 (is (empty? (fixture/calls-of world :credential)))))))))

(deftest ^:async populated-notification-and-chat-presentations-keep-current-clipped-vectors
  (doseq [[id op payload text]
          [["bluesky.notifications" :notifications
            {:notifications [{:reason "like" :author {:displayName "Name" :handle "h"} :post {:record {:text "body"}}}]}
            (str "Bluesky notifications (1)\n- [like] Name (@h): " ["body" false])]
           ["bluesky.chat.list" :chat-list
            {:convos [{:id "c" :members [{:handle "h"} {:displayName "Name"}] :lastMessage {:text "body"}}]}
            (str "Bluesky conversations (1)\n- c: h, Name — " ["body" false])]
           ["bluesky.chat.read" :chat-read
            {:messages [{:id "m" :sender {:displayName "Name" :handle "h"} :text "body"}]}
            (str "Bluesky DMs (1)\n- [m] Name (@h): " ["body" false])]]]
    (await (fixture/with-world! {:responses {op payload}}
             (^:async fn [world]
               (is (= (wire-result text payload) (await (execute! world id {:convoId "c"}))))
               (is (= 1 (count (fixture/calls-of world op)))))))))

(deftest ^:async populated-post-projection-and-format-keep-url-and-source-fields
  (let [row {:uri "at://did:author/app.bsky.feed.post/key" :author {:handle "h" :displayName "Name"}
             :record {:text "body" :createdAt "when"}}
        projected {:handle "h" :displayName "Name" :text "body" :createdAt "when"
                   :uri "at://did:author/app.bsky.feed.post/key" :url "https://bsky.app/profile/h/post/key"}]
    (await (fixture/with-world! {:responses {:posts {:posts [row]} :feed {:feed [{:post row}]}
                                            :timeline {:feed [{:post row}] :cursor "next"}
                                            :actors {:actors [{:handle "h" :displayName "Name" :description "bio" :did "did:h"}]}
                                            :profile {:handle "h" :displayName "Name" :description "bio" :did "did:h"
                                                      :followersCount 2 :followsCount 3 :postsCount 4}}}
             (^:async fn [world]
               (is (= (wire-result (str "Bluesky search (posts)\n- Name (@h): " ["body" false]
                                       "\n  https://bsky.app/profile/h/post/key")
                                   {:kind "posts" :results [projected]})
                      (await (execute! world "bluesky.search" {:query "q"}))))
               (is (= {:actor "a" :results [projected]} (await (fixture/feed! "a" 3))))
               (is (= {:cursor "next" :results [projected]} (await (fixture/timeline! 3 "previous"))))
               (is (= {:kind "actors" :results [{:handle "h" :displayName "Name" :description "bio" :did "did:h" :url "https://bsky.app/profile/h"}]}
                      (await (fixture/search! "q" "actors" 3))))
               (is (= {:handle "h" :displayName "Name" :description "bio" :did "did:h" :followersCount 2 :followsCount 3 :postsCount 4 :url "https://bsky.app/profile/h"}
                      (await (fixture/profile! "h"))))
               (is (= [fixture/session "q" 5] (:args (first (fixture/calls-of world :posts))))))))))

(deftest ^:async upload-rejection-is-observed-and-prevents-later-effects
  (await (fixture/with-world! {:method-error :upload}
           (^:async fn [world]
             (is (= {:ok false :message "fixture-upload-failed"}
                    (await (fixture/outcome! #(fixture/publish! "text" ["image"] [] "reply")))))
             (is (= 1 (count (fixture/calls-of world :upload))))
             (is (every? empty? (map #(fixture/calls-of world %) [:resolve :clock :create])))))))
