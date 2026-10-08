(ns knoxx.backend.character.social-encounters-test
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [knoxx.backend.character.encounter-test :as fixture]
            [knoxx.backend.domain.character.social-encounters :as social]
            [knoxx.backend.domain.discord.source :as discord-source]
            [knoxx.backend.infra.character.encounter-admission :as admission]
            [knoxx.backend.infra.character.social-encounters :as pull]
            [knoxx.backend.law.character.encounter :as law]
            [knoxx.backend.shape.character.encounter :as shape]))

(def ^:private bluesky-source
  {:kind "bluesky" :account-id "did:plc:creator" :scope-id "timeline" :visibility :public})

(def ^:private bluesky-spec
  {:source bluesky-source :tool-id "bluesky.timeline" :parameters {} :limit 3})

(def ^:private discord-spec
  {:source (assoc fixture/source :visibility :private)
   :tool-id "discord.channel.messages"
   :parameters {:channel_id (:scope-id fixture/source)} :limit 3})

(defn- post
  [id text]
  {:uri (str "at://did:plc:other/app.bsky.feed.post/" id)
   :authorId "did:plc:other" :createdAt fixture/at :text text})

(defn- message
  [id text]
  {:id id :channelId (:scope-id fixture/source) :authorId "discord-other"
   :timestamp fixture/at :content text})

(defn- result
  [spec rows]
  {:details (merge {:rawCount (count rows)}
                   (if (= "bluesky" (get-in spec [:source :kind]))
                     {:results rows :accountId (:account-id bluesky-source)}
                     {:messages rows :channelId (:scope-id fixture/source)}))})

(defn- pull-ports
  [read!]
  {:read! read! :authorize! fixture/authority :normalize-instant identity})

(defn- matching-event?
  [owner stream event]
  (and (= owner (or (get-in event [:extra :encounter :owner])
                    (get-in event [:extra :encounter_cursor :owner])))
       (= stream (get-in event [:extra :encounter_stream_id]))))

(defn- storage-ports
  "Dedicated in-memory existing event-port fixture; no credentials or production IO."
  [rows*]
  {:find-event! (fn [owner id]
                  (first (filter #(and (= id (:id %))
                                       (= owner (or (get-in % [:extra :encounter :owner])
                                                    (get-in % [:extra :encounter_cursor :owner]))))
                                 @rows*)))
   :append-event! (fn [row] (swap! rows* conj row) {:ok true})
   :latest-checkpoint! (fn [owner stream]
                         (->> @rows*
                              (filter #(and (matching-event? owner stream %)
                                            (= "character.encounter-cursor" (:kind %))))
                              (map #(shape/wire->checkpoint (get-in % [:extra :encounter_cursor])))
                              (sort-by :sequence) last))
   :recent-encounters! (fn [owner stream limit]
                         (->> @rows*
                              (filter #(and (matching-event? owner stream %)
                                            (= "character.encounter" (:kind %))))
                              (sort-by :ts) reverse (take limit) vec))
   :with-exclusive! (fn [task!] (task!))})

(defn- ^:async pull-and-admit!
  [ports stored spec checkpoint at]
  (let [pulled (await (pull/pull-source! ports fixture/owner spec checkpoint at))
        admitted (await (admission/admit-page! stored fixture/digest fixture/owner
                                              (:page pulled) fixture/authority))]
    (assoc pulled :admission admitted)))

(defn- ^:async failure-code!
  [task!]
  (try
    (await (task!)) nil
    (catch :default error (:code (ex-data error)))))

(deftest exact-current-source-binding-and-current-tool-grant-are-both-required
  (let [source (:source discord-spec)
        resource {:source/filters {:encounter-source source}
                  :source/tools [:discord.channel.messages]}
        allowed #{"discord.channel.messages"}]
    (is (social/source-scope-admitted? discord-spec [resource] allowed))
    (is (not (social/source-scope-admitted? discord-spec [] allowed)))
    (is (not (social/source-scope-admitted? discord-spec [resource] #{})))
    (is (not (social/source-scope-admitted? discord-spec
                                          [(assoc resource :enabled false)] allowed)))
    (is (not (social/source-scope-admitted? discord-spec
                                          [(assoc resource :source/enabled? false)] allowed)))
    (is (not (social/source-scope-admitted? discord-spec
                                          [(assoc resource :source/tools [])] allowed)))
    (is (not (social/source-scope-admitted? discord-spec
                                          [(assoc resource :source/tools nil)] allowed)))
    (is (not (social/source-scope-admitted? discord-spec
                                          [(assoc-in resource [:source/filters :encounter-source :account-id]
                                                     "other-account")] allowed)))
    (is (not (social/source-scope-admitted? discord-spec
                                          [(assoc-in resource [:source/filters :encounter-source :visibility]
                                                     :public)] allowed)))
    (let [public-spec (assoc-in discord-spec [:source :visibility] :public)]
      (is (not (social/source-scope-admitted? public-spec [resource] allowed)))
      (is (social/source-scope-admitted?
           public-spec [(assoc-in resource [:source/filters :encounter-source] (:source public-spec))]
           allowed)))))

(deftest ^:async denied-or-wrong-account-authority-never-reads
  (let [reads* (atom 0)
        ports (pull-ports (fn [_tool _params] (swap! reads* inc) (result discord-spec [])))]
    (doseq [authorize! [(fn [_owner _source] {:allowed? false})
                       (fn [owner source]
                         (fixture/authority owner (assoc source :account-id "wrong-account")))]]
      (let [pulled (await (pull/pull-source! (assoc ports :authorize! authorize!)
                                           fixture/owner discord-spec nil fixture/at))]
        (is (= :denied (:status pulled)))
        (is (nil? (:page pulled)))))
    (is (zero? @reads*))))

(deftest account-metadata-maps-only-through-an-exact-current-actor-bound-resource
  (let [discord-account "450177073990860801"
        spec (assoc-in discord-spec [:source :account-id] discord-account)
        resource {:source/actor "creator-actor"
                  :source/filters {:encounter-source (:source spec)
                                   :account-identifier "configured-discord-account"}}
        bsky-resource {:source/protocol {:credentials-owner "creator-actor"}
                       :source/filters {:encounter-source bluesky-source
                                        :account-identifier "creator.example"}}]
    (is (social/source-account-admitted? spec [resource] "creator-actor" discord-account))
    (is (social/source-account-admitted? spec [resource] "creator-actor" "configured-discord-account"))
    (is (social/source-account-admitted? bluesky-spec [bsky-resource] "creator-actor" "creator.example"))
    (is (not (social/source-account-admitted? spec [resource] "other-actor" "configured-discord-account")))
    (is (not (social/source-account-admitted? spec [resource] "creator-actor" nil)))
    (is (not (social/source-account-admitted? spec [resource] "creator-actor" "wrong-account")))
    (is (not (social/source-account-admitted? spec [] "creator-actor" discord-account)))
    (is (not (social/source-account-admitted? spec [(assoc resource :enabled false)]
                                             "creator-actor" "configured-discord-account")))
    (is (not (social/source-account-admitted?
              spec [(assoc-in resource [:source/filters :encounter-source :scope-id] "other-space")]
              "creator-actor" "configured-discord-account")))))

(deftest ^:async later-newest-bluesky-poll-enters-next-decision-context-after-restart
  (let [rows* (atom [])
        stored (storage-ports rows*)
        current* (atom [(post "old" "first outside encounter")])
        requests* (atom [])
        ports (pull-ports (fn [tool params]
                            (swap! requests* conj {:tool tool :params params})
                            (assoc-in (result bluesky-spec @current*) [:details :cursor] "older-page-token")))
        first-pull (await (pull-and-admit! ports stored bluesky-spec nil fixture/at))
        stream (law/stream-id fixture/digest fixture/owner bluesky-source)
        first-cursor (await ((:latest-checkpoint! stored) fixture/owner stream))
        discord-pull (await (pull-and-admit!
                            (pull-ports (fn [_tool _params]
                                          (result discord-spec [(message "90071992547409931"
                                                                        "different permitted source encounter")])))
                            stored discord-spec nil fixture/at))]
    (is (= :provider-pagination (get-in first-pull [:coverage :reason])))
    (is (= "older-page-token" (get-in first-cursor [:coverage :provider-cursor])))
    (is (false? (get-in first-cursor [:coverage :complete?])))
    (is (nil? (:cursor-after first-cursor)))
    (is (= :admitted (get-in discord-pull [:admission :status])))
    (reset! current* [(post "new" "fresh outside information")
                     (post "old" "first outside encounter")])
    (let [second-pull (await (pull-and-admit! ports (storage-ports rows*) bluesky-spec first-cursor
                                            "2026-10-07T10:01:00.000Z"))
          second-cursor (await ((:latest-checkpoint! stored) fixture/owner stream))
          context (await (admission/load-context! (storage-ports rows*) fixture/digest fixture/owner
                                                 [bluesky-source (:source discord-spec)] fixture/authority {}))
          before-retry (count @rows*)
          retry (await (pull-and-admit! ports (storage-ports rows*) bluesky-spec second-cursor
                                       "2026-10-07T10:02:00.000Z"))]
      (is (= :admitted (get-in second-pull [:admission :status])))
      (is (= :replayed (get-in retry [:admission :status])))
      (is (= before-retry (count @rows*)))
      (is (str/includes? (:prompt-context context) "fresh outside information"))
      (is (str/includes? (:prompt-context context) "different permitted source encounter"))
      (is (= 3 (count (:encounters context))))
      (is (every? #(not (contains? (:params %) :cursor)) @requests*))
      (is (every? #(= "bluesky.timeline" (:tool %)) @requests*))
      (reset! current* [(post "new" "changed outside information")])
      (await (pull-and-admit! ports stored bluesky-spec second-cursor "2026-10-07T10:03:00.000Z"))
      (let [changed (await (admission/load-context! (storage-ports rows*) fixture/digest fixture/owner
                                                   [bluesky-source] fixture/authority {}))]
        (is (str/includes? (:prompt-context changed) "changed outside information"))
        (is (not (str/includes? (:prompt-context changed) "fresh outside information")))
        (is (= 2 (count (:encounters changed))))))))

(deftest after-cursor-advances-only-for-proved-unfiltered-short-page
  (let [spec (assoc discord-spec :poll-mode :after :limit 2)
        older (message "90071992547409931" "earlier")
        newer (message "90071992547409932" "later")
        bounded (social/project-page spec nil fixture/at (:details (result spec [newer older])) identity)
        proved (social/project-page spec nil fixture/at (:details (result spec [newer])) identity)
        filtered (social/project-page spec nil fixture/at
                                      (assoc (:details (result spec [newer])) :rawCount 2) identity)
        unknown (social/project-page spec nil fixture/at
                                     (dissoc (:details (result spec [newer])) :rawCount) identity)]
    (is (= :overflow (get-in bounded [:coverage :reason])))
    (is (nil? (get-in bounded [:page :cursor-after])))
    (is (= "90071992547409932" (get-in proved [:page :cursor-after])))
    (is (= "90071992547409931" (get-in bounded [:coverage :oldest-id])))
    (is (= :unknown (get-in filtered [:coverage :reason])))
    (is (nil? (get-in filtered [:page :cursor-after])))
    (is (nil? (get-in unknown [:page :cursor-after])))
    (is (= {:channel_id (:scope-id fixture/source) :limit 2 :after "90071992547409932"}
           (:parameters (social/read-request spec {:cursor-after "90071992547409932"}))))))

(deftest media-only-stickers-reactions-and-self-identity-remain-source-evidence
  (let [discord-row (assoc (message "90071992547409931" "")
                           :authorId (get-in discord-spec [:source :account-id])
                           :attachments [{:id "asset-1" :filename "image.png" :contentType "image/png"
                                          :url "https://cdn.example/image.png"}]
                           :stickers [{:id "sticker-1" :name "source sticker"}]
                           :reactions [{:emoji {:id "emoji-1" :name "source emoji"} :count 2}])
        bsky-row (assoc (post "media" "") :embed {:images [{:fullsize "https://cdn.example/picture"
                                                            :alt "source alt"}]}
                        :reactionCounts {:like 3 :reply 1})
        discord-item (first (get-in (social/project-page discord-spec nil fixture/at
                                                       (:details (result discord-spec [discord-row])) identity)
                                   [:page :items]))
        bluesky-item (first (get-in (social/project-page bluesky-spec nil fixture/at
                                                       (:details (result bluesky-spec [bsky-row])) identity)
                                   [:page :items]))]
    (is (= "" (:text discord-item)))
    (is (:self-output? discord-item))
    (is (not (:self-output? bluesky-item)))
    (is (= [{:kind :image :reference "discord:attachment:asset-1" :label "image.png"}
            {:kind :emote :reference "discord:sticker:sticker-1" :label "source sticker"}]
           (:media discord-item)))
    (is (= [{:label "source emoji" :reference "discord:emoji:emoji-1" :count 2}]
           (:reactions discord-item)))
    (is (= [{:kind :image :reference "https://cdn.example/picture" :label "source alt"}]
           (:media bluesky-item)))
    (is (= [{:label "likes" :count 3} {:label "replies" :count 1}]
           (:reactions bluesky-item)))))

(deftest rest-source-projection-does-not-drop-native-sticker-or-reaction-identities
  (let [raw {:id "90071992547409931" :channel_id (:scope-id fixture/source)
             :author {:id "discord-other"} :timestamp fixture/at :content ""
             :sticker_items [{:id "native-sticker" :name "original label"}]
             :reactions [{:emoji {:id "native-emoji" :name "original reaction"} :count 1}]
             :embeds [{:image {:url "https://cdn.example/native"}}]}
        mapped (discord-source/map-message raw)
        item (first (get-in (social/project-page discord-spec nil fixture/at
                                                (:details (result discord-spec [mapped])) identity)
                           [:page :items]))]
    (is (= "discord-other" (:author-id item)))
    (is (= "discord:emoji:native-emoji" (get-in item [:reactions 0 :reference])))
    (is (= #{"discord:sticker:native-sticker" "https://cdn.example/native"}
           (set (map :reference (:media item)))))))

(deftest long-optional-media-label-is-omitted-with-exact-reference-retained
  (let [long-alt (apply str (repeat 129 "a"))
        row (assoc (post "long-alt" "")
                   :embed {:images [{:fullsize "https://cdn.example/long-alt" :alt long-alt}]})
        projected (social/project-page bluesky-spec nil fixture/at
                                       (:details (result bluesky-spec [row])) identity)
        item (first (get-in projected [:page :items]))]
    (is (= "" (:text item)))
    (is (= [{:kind :image :reference "https://cdn.example/long-alt"}] (:media item)))
    (is (= (:uri row) (:external-id item)))
    (is (= 1 (get-in projected [:coverage :admitted-count])))))

(deftest truly-empty-system-row-is-skipped-without-advancing-through-the-gap
  (let [spec (assoc discord-spec :poll-mode :after)
        empty-row (message "90071992547409931" "")
        newer (message "90071992547409932" "valid newer experience")
        projected (social/project-page spec nil fixture/at
                                       (:details (result spec [empty-row newer])) identity)]
    (is (= ["90071992547409932"] (mapv :external-id (get-in projected [:page :items]))))
    (is (= 2 (get-in projected [:coverage :fetched-count])))
    (is (= 1 (get-in projected [:coverage :admitted-count])))
    (is (= :unknown (get-in projected [:coverage :reason])))
    (is (nil? (get-in projected [:page :cursor-after])))
    (is (= :encounter/invalid-shape
           (fixture/error-code #(social/project-page spec nil fixture/at
                                                     (:details (result spec [(dissoc empty-row :authorId) newer]))
                                                     identity))))
    (is (= :encounter/empty-content
           (fixture/error-code #(law/assert-item! (fixture/item "system-row" "")))))))

(deftest ^:async malformed-projections-and-configured-pagination-refuse-before-effects
  (let [reads* (atom 0)
        ports (pull-ports (fn [_tool _params] (swap! reads* inc) (result bluesky-spec [])))
        configured-pagination (assoc-in bluesky-spec [:parameters :cursor] "older-page-token")
        pagination-error (await (failure-code! #(pull/pull-source! ports fixture/owner
                                                                 configured-pagination nil fixture/at)))]
    (is (= :encounter/invalid-shape pagination-error))
    (is (zero? @reads*))
    (is (= :encounter/invalid-shape
           (fixture/error-code #(social/project-page bluesky-spec nil fixture/at
                                                     (:details (result bluesky-spec [(dissoc (post "x" "text") :authorId)]))
                                                     identity))))
    (let [empty-page (social/project-page bluesky-spec nil fixture/at
                                          (:details (result bluesky-spec [(post "empty" "")])) identity)]
      (is (empty? (get-in empty-page [:page :items])))
      (is (= :unknown (get-in empty-page [:coverage :reason]))))
    (is (= :encounter/invalid-social-result
           (fixture/error-code #(social/project-page bluesky-spec nil fixture/at
                                                     (assoc (:details (result bluesky-spec []))
                                                            :accountId "did:plc:wrong") identity))))
    (is (= :encounter/social-read-failed
           (await (failure-code! #(pull/pull-source!
                                   (pull-ports (fn [_tool _params] {:isError true}))
                                   fixture/owner bluesky-spec nil fixture/at)))))))
