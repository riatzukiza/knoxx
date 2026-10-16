(ns knoxx.backend.infra.discord-tools-characterization-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [knoxx.backend.extern.discord-tools-fixture :as fixture]))

(def expected-ids
  ["discord.publish" "discord.send" "discord.react" "discord.thread.create"
   "discord.read" "discord.channel.messages" "discord.channel.scroll"
   "discord.dm.messages" "discord.search" "discord.guilds" "discord.list.servers"
   "discord.channels" "discord.list.channels"])

(defn- message [id timestamp & [extra]]
  (merge {:id id :channel_id "channel" :content "needle" :timestamp timestamp
          :author {:id "user" :username "Author"}} extra))

(deftest ^:async real-factory-preserves-wire-identities-order-and-schema
  (await (fixture/with-world! {}
           (^:async fn [world]
             (let [descriptors (fixture/descriptors (fixture/tools nil))]
               (is (= expected-ids (mapv :name descriptors)))
               (is (= [] (fixture/calls world)) "Construction performs no authorization/provider effect")
               (is (= "object" (get-in descriptors [0 :parameters :type])))
               (is (= "string" (get-in descriptors [1 :parameters :properties :channel_id :type])))
               (is (= 100 (get-in descriptors [5 :parameters :properties :limit :maximum]))))))))

(deftest ^:async aliases-have-individual-permission-identities
  (await (fixture/with-world! {:allow #{"discord.read" "discord.guilds"}}
           (^:async fn [world]
             (is (= ["discord.read" "discord.guilds"]
                    (mapv :name (fixture/descriptors (fixture/tools {:owner "fixture"})))))
             (is (= expected-ids (mapv :id (fixture/calls-of world :permission))))
             (is (every? #(= {:owner "fixture"} (:context %)) (fixture/calls-of world :permission)))
             (is (empty? (fixture/calls-of world :credential))))))
  (await (fixture/with-world! {:allow #{}}
           (^:async fn [world]
             (is (empty? (fixture/descriptors (fixture/tools {}))))
             (is (= 13 (count (fixture/calls-of world :permission))))))))

(deftest ^:async public-aliases-dispatch-real-boundaries
  (doseq [[id params op]
          [["discord.publish" {:channel_id "channel" :content "hello"} :post]
           ["discord.read" {:channel_id "channel" :limit 3} :messages]
           ["discord.guilds" {} :guilds]
           ["discord.channels" {:guildId "guild"} :channels]]]
    (await (fixture/with-world! {}
             (^:async fn [world]
               (let [result (await (fixture/execute! world (fixture/tools nil) id params [nil nil nil]))]
                 (is (= 1 (count (fixture/calls-of world op))) id)
                 (is (= 1 (count (fixture/calls-of world :credential))) id)
                 (is (= "text" (get-in result [:content 0 :type])) id)))))))

(deftest ^:async snake-keys-win-and-first-callable-update-slot-is-used
  (await (fixture/with-world! {}
           (^:async fn [world]
             (let [result (await (fixture/execute! world (fixture/tools nil) "discord.send"
                                                  {:channel_id "snake" :channelId "camel"
                                                   :text "preferred" :content "other"}
                                                  [:a :b :c]))
                   [channel row] (:args (first (fixture/calls-of world :post)))]
               (is (= "snake" channel))
               (is (= "preferred" (get-in row [:payload :content])))
               (is (= "snake" (get-in result [:details :channelId])))
               (is (= [:a] (mapv :slot (fixture/calls-of world :update))))))))
  (doseq [slots [[nil :b :c] [nil nil :c]]]
    (await (fixture/with-world! {}
             (^:async fn [world]
               (await (fixture/execute! world (fixture/tools nil) "discord.react"
                                       {:channelId "channel" :messageId "message" :emoji "ok"} slots))
               (is (= [(first (remove nil? slots))]
                      (mapv :slot (fixture/calls-of world :update)))))))))

(deftest ^:async required-fields-refuse-before-credential-or-network
  (doseq [[action expected]
          [[#(fixture/send! "" "hello" nil []) "channel_id is required"]
           [#(fixture/send! "channel" " " nil []) "text or attachment_urls is required"]
           [#(fixture/fetch! "" {}) "channel_id is required"]
           [#(fixture/react! "channel" "" "ok") "message_id is required"]
           [#(fixture/thread! "channel" nil "" nil) "name is required"]]]
    (await (fixture/with-world! {}
             (^:async fn [world]
               (is (= {:ok false :message expected} (await (fixture/outcome! action))))
               (is (empty? (fixture/calls-of world :credential)))
               (is (empty? (fixture/calls-of world :rest-client))))))))

(deftest ^:async message-normalization-retains-defaults-and-exact-cursors
  (await (fixture/with-world! {:messages [{:id "one" :channelId "camel"}
                                         {:id "two" :channel_id "snake"
                                          :attachments [{:filename "f" :contentType "image/png"}]
                                          :embeds [{:title "embed"}]}]}
           (^:async fn [world]
             (let [result (await (fixture/fetch! "channel" {:limit 9 :before "b" :after "a" :around "r"}))]
               (is (= 2 (:count result)))
               (is (= ["camel" "snake"] (mapv :channelId (:messages result))))
               (is (= {:id "one" :channelId "camel" :content "" :authorId ""
                       :authorUsername "unknown" :authorIsBot false :timestamp ""
                       :attachments [] :embeds []} (first (:messages result))))
               (is (= "image/png" (get-in result [:messages 1 :attachments 0 :contentType])))
               (is (= ["channel" {:limit 9 :before "b" :after "a" :around "r"}]
                      (:args (first (fixture/calls-of world :messages))))))))))

(deftest ^:async quality-labels-retain-bad-dominance-and-chronological-good-first
  (await (fixture/with-world!
           {:labels {"discord:message:channel:bad" {:quality "bad" :labels ["good"]}
                     :discord:message:channel:good {:quality "good"}}}
           (^:async fn [world]
             (let [rows [{:id "neutral" :channelId "channel" :timestamp (fixture/iso -1)}
                         {:id "bad" :channelId "channel" :timestamp (fixture/iso -3)}
                         {:id "good" :channelId "channel" :timestamp (fixture/iso -2)}]
                   result (await (fixture/labels! rows))]
               (is (= ["good" "neutral"] (mapv :id result)))
               (is (= ["discord:message:channel:neutral" "discord:message:channel:bad"
                       "discord:message:channel:good"]
                      (:ids (first (fixture/calls-of world :labels)))))
               (is (= {:quality "good"} (:openplannerLabels (first result)))))))))

(deftest ^:async label-failure-warns-once-and-never-surfaces-messages
  (await (fixture/with-world! {:labels-error? true}
           (^:async fn [world]
             (is (= [] (await (fixture/labels! [{:id "one" :channelId "channel"}]))))
             (is (= 1 (count (fixture/calls-of world :labels))))
             (is (= [{:op :warning
                      :message "[discord-tools] OpenPlanner label lookup failed; failing closed to avoid surfacing crossed/bad messages"
                      :error-message "fixture-labels-failed"}]
                    (fixture/calls-of world :warning))))))
  (await (fixture/with-world! {:planner-enabled? false}
           (^:async fn [world]
             (is (= [{:id "one"}] (await (fixture/labels! [{:id "one"}]))))
             (is (empty? (fixture/calls-of world :labels)))))))

(deftest ^:async search-keeps-inclusive-time-boundary-invalid-time-and-query-author-filter
  (let [hour 3600000
        rows [(message "equal" (fixture/iso (- hour)))
              (message "too-old" (fixture/iso (- (- hour) 1)))
              (message "unknown" "not-a-timestamp")
              (message "wrong-author" (fixture/iso 0) {:author {:id "other"}})
              (message "wrong-query" (fixture/iso 0) {:content "else"})]]
    (await (fixture/with-world! {:messages rows :planner-enabled? false}
             (^:async fn [world]
               (let [result (await (fixture/search! "CHANNEL"
                                                    {:channel-id "channel" :user-id "user"
                                                     :query "NEEDLE" :since-hours 1 :limit 2
                                                     :before "b" :after "a"}))]
                 (is (= ["unknown" "equal"] (mapv :id (:messages result))))
                 (is (= 1 (:sinceHours result)))
                 (is (= "client_side_filter_openplanner_labels" (:source result)))
                 (is (= ["channel" {:limit 100 :before "b" :after "a" :around nil}]
                        (:args (first (fixture/calls-of world :messages)))))))))))

(deftest ^:async hours-number-coercion-and-dm-dispatch-remain-actual
  (doseq [[value expected] [[nil 168] [0 168] [-1 168] ["invalid" 168] ["2" 2]]]
    (await (fixture/with-world! {:planner-enabled? false}
             (^:async fn [world]
               (let [result (await (fixture/search! "dm" {:user-id "user" :before "b" :since-hours value}))]
                 (is (= expected (:sinceHours result)))
                 (is (= ["user"] (:args (first (fixture/calls-of world :dm)))))
                 (is (= ["dm-channel" {:limit 100 :before "b" :after nil :around nil}]
                        (:args (first (fixture/calls-of world :messages)))))))))))

(deftest ^:async repeated-files-and-native-id-reply-sentinel-are-characterized
  (await (fixture/with-world! {}
           (^:async fn [world]
             (let [result (await (fixture/send! "channel" (apply str (repeat 2001 "x"))
                                                "reply" ["data:application/octet-stream;base64,QQ=="]))
                   posts (mapv #(second (:args %)) (fixture/calls-of world :post))]
               (is (= [2000 1] (mapv #(count (get-in % [:payload :content])) posts)))
               (is (= ["reply" "reply"] (mapv #(get-in % [:payload :message_reference :message_id]) posts)))
               (is (= [1 1] (mapv #(count (:files %)) posts)))
               (is (= (mapv :files posts) (repeat 2 (:files (first posts)))))
               (is (= {:channelId "channel" :messageId "sent-2" :sent true
                       :timestamp "fixture-time" :chunkCount 2 :attachmentCount 1} result))
               (is (= 1 (count (fixture/calls-of world :size)))))))))

(deftest ^:async explicit-message-id-sentinel-suppresses-only-later-reference
  (await (fixture/with-world! {:message-id-sentinel? true}
           (^:async fn [world]
             (await (fixture/send! "channel" (apply str (repeat 2001 "x")) "reply" []))
             (is (= ["reply" nil]
                    (mapv #(get-in % [:args 1 :payload :message_reference :message_id])
                          (fixture/calls-of world :post))))))))

(deftest ^:async paragraph-chunk-split-threshold-and-blank-attachment-message-remain
  (await (fixture/with-world! {}
           (^:async fn [world]
             (await (fixture/send! "channel" (str (apply str (repeat 1200 "a")) "\n\n"
                                                  (apply str (repeat 1000 "b"))) nil []))
             (is (= [1200 1000]
                    (mapv #(count (get-in % [:args 1 :payload :content])) (fixture/calls-of world :post)))))))
  (await (fixture/with-world! {}
           (^:async fn [world]
             (await (fixture/send! "channel" "" nil ["data:application/octet-stream,plain"]))
             (is (= "[attachment]" (get-in (first (fixture/calls-of world :post)) [:args 1 :payload :content])))))))

(deftest ^:async inline-svg-is-rendered-before-sending-and-keeps-shape
  (await (fixture/with-world! {}
           (^:async fn [world]
             (let [result (await (fixture/send! "channel" (fixture/svg-fence) nil []))
                   render (first (fixture/calls-of world :render))
                   post (get-in (first (fixture/calls-of world :post)) [:args 1])]
               (is (= "<svg/>" (:source render)))
               (is (= {:width 600 :height 300} (:options render)))
               (is (= "[image]" (get-in post [:payload :content])))
               (is (= "attachment-0.png" (get-in post [:files 0 :name])))
               (is (= 1 (:attachmentCount result))))))))

(deftest ^:async attachments-use-real-size-contract-and-propagate-inert-provider-refusal
  (await (fixture/with-world! {}
           (^:async fn [world]
             (is (= {:name "attachment-0.bin" :mime-type "application/octet-stream"
                     :bytes [65] :text "A"} (await (fixture/attachment! "data:application/octet-stream;base64,QQ==" 0))))
             (is (= [1] (mapv :size (fixture/calls-of world :size)))))))
  (await (fixture/with-world! {:max-bytes 2}
           (^:async fn [world]
             (let [result (await (fixture/outcome! #(fixture/attachment! "data:application/octet-stream,abc" 0)))]
               (is (false? (:ok result)))
               (is (str/includes? (:message result) "exceeds 2 bytes"))
               (is (= [3] (mapv :size (fixture/calls-of world :size))))
               (is (empty? (fixture/calls-of world :credential)))))))
  (await (fixture/with-world! {:local-error? true}
           (^:async fn [world]
             (is (= {:ok false :message "fixture-local-refused"}
                    (await (fixture/outcome! #(fixture/attachment! "file:///fixture/owned.svg" 0)))))
             (is (= "/fixture/owned.svg" (:source (first (fixture/calls-of world :local)))))))))

(deftest ^:async http-filename-mime-and-failure-keep-actual-transport-calls
  (await (fixture/with-world! {:http-type "image/png; charset=binary"}
           (^:async fn [world]
             (is (= {:name "photo.png" :mime-type "image/png" :bytes [65 66] :text "AB"}
                    (await (fixture/attachment! "https://fixture.invalid/photo.png" 7))))
             (is (= ["https://fixture.invalid/photo.png"] (:args (first (fixture/calls-of world :attachment)))))
             (is (= [nil] (mapv :token (fixture/calls-of world :rest-client)))))))
  (await (fixture/with-world! {:http-ok? false :http-status 503}
           (^:async fn [world]
             (is (= {:ok false :message "Attachment fetch failed 503"}
                    (await (fixture/outcome! #(fixture/attachment! "https://fixture.invalid/a" 0)))))
             (is (= 1 (count (fixture/calls-of world :attachment))))
             (is (empty? (fixture/calls-of world :size)))))))

(deftest ^:async svg-render-failure-warns-then-preserves-original-owned-attachment
  (await (fixture/with-world! {:render-error? true}
           (^:async fn [world]
             (is (= {:same? true :name "local.svg" :mime-type "image/svg+xml"
                     :bytes [60 115 118 103 47 62]} (await (fixture/render-local!))))
             (is (= 1 (count (fixture/calls-of world :local))))
             (is (= 1 (count (fixture/calls-of world :render))))
             (is (= ["[discord-tools] SVG render failed; uploading original SVG"]
                    (mapv :message (fixture/calls-of world :warning))))
             (is (= [:local :render :warning]
                    (mapv :op (filter #(contains? #{:local :render :warning} (:op %))
                                     (fixture/calls world)))))))))

(deftest ^:async reaction-thread-and-channel-discovery-preserve-native-api-arguments
  (await (fixture/with-world! {:guilds [{:id "g1" :name "one"} {:id "g2" :name "two"}]
                              :channels {"g1" [{:id "text" :type 0} {:id "voice" :type 2}]
                                         "g2" [{:id "thread" :type 11}]}}
           (^:async fn [world]
             (is (= {:channelId "c" :messageId "m" :emoji "ok" :reacted true}
                    (await (fixture/react! "c" "m" "ok"))))
             (is (= {:threadId "thread-id" :channelId "c" :messageId ""
                     :name "name" :created true} (await (fixture/thread! "c" nil "name" nil))))
             (is (= ["c" nil {:name "name" :auto_archive_duration 1440 :type 11}]
                    (:args (first (fixture/calls-of world :thread)))))
             (let [result (await (fixture/channels! nil))]
               (is (= ["text" "thread"] (mapv :id (:channels result))))
               (is (= ["g1" "g2"] (mapv #(first (:args %)) (fixture/calls-of world :channels))))
             (is (= ["c" "m" "ok"] (:args (first (fixture/calls-of world :reaction))))))))))

(deftest ^:async genuine-client-rejection-is-not-a-fixture-arity-fallback
  (await (fixture/with-world! {:method-error :messages}
           (^:async fn [world]
             (is (= {:ok false :message "fixture-messages-failed"}
                    (await (fixture/outcome! #(fixture/fetch! "channel" {})))))
             (is (= 1 (count (fixture/calls-of world :messages))))
             (is (= 1 (count (fixture/calls-of world :credential))))))))
