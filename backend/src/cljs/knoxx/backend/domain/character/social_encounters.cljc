(ns knoxx.backend.domain.character.social-encounters
  "Pure bounded projections from existing authorized social read results."
  (:require [clojure.string :as str]
            [knoxx.backend.law.character.encounter :as law]
            [knoxx.backend.shape.character.encounter :as shape]))

(def SourceConfig
  "Trusted source selection; credentials and grants never come from source content."
  [:map {:closed true}
   [:source shape/Source]
   [:tool-id [:enum "discord.channel.messages" "bluesky.timeline"
              "bluesky.author.feed" "bluesky.search"]]
   [:parameters :map]
   [:poll-mode {:optional true} [:enum :newest :after]]
   [:limit {:optional true} [:int {:min 1 :max 24}]]])

(def ^:private parameter-schemas
  {"discord.channel.messages"
   [:map {:closed true} [:channel_id shape/NonBlankString]
    [:limit {:optional true} [:int {:min 1 :max 24}]]]
   "bluesky.timeline"
   [:map {:closed true} [:limit {:optional true} [:int {:min 1 :max 24}]]]
   "bluesky.author.feed"
   [:map {:closed true} [:actor shape/NonBlankString]
    [:limit {:optional true} [:int {:min 1 :max 24}]]]
   "bluesky.search"
   [:map {:closed true} [:query shape/NonBlankString]
    [:kind {:optional true} [:= "posts"]]
    [:limit {:optional true} [:int {:min 1 :max 24}]]]})

(defn assert-source-config!
  "Reject mismatched sources, cursors and unsupported tools before an effect."
  [spec]
  (law/assert-shape! SourceConfig spec :social-source-config)
  (let [{:keys [source tool-id parameters poll-mode limit]} spec
        discord? (= "discord.channel.messages" tool-id)
        expected-kind (if discord? "discord" "bluesky")]
    (law/assert-shape! (get parameter-schemas tool-id) parameters :social-read-parameters)
    (when-not (and (= expected-kind (:kind source))
                   (or (not discord?) (= (:scope-id source) (:channel_id parameters)))
                   (or (not= tool-id "bluesky.author.feed")
                       (= (:scope-id source) (:actor parameters)))
                   (or (not= tool-id "bluesky.search")
                       (= (:scope-id source) (:query parameters)))
                   (or (not= :after poll-mode) discord?)
                   (or (nil? limit) (nil? (:limit parameters))
                       (= limit (:limit parameters))))
      (throw (ex-info "Social read configuration does not match its exact source"
                      {:code :encounter/source-config-conflict}))))
  spec)

(defn source-limit
  "Bound every read to a single admission page."
  [spec]
  (assert-source-config! spec)
  (or (:limit spec) (get-in spec [:parameters :limit]) 24))

(defn- canonical-tool-id
  [value]
  (cond (string? value) value
        (keyword? value) (if-let [scope (namespace value)]
                           (str scope "/" (name value)) (name value))
        :else nil))

(defn source-scope-admitted?
  "Intersect exact existing source resources with current actor tool grants.

  Visibility is not a grant. The caller resolves actual current source contracts
  before supplying resources; missing refs are not authority. A private source
  requires its own exact current binding, including account, space and visibility."
  [spec source-resources allowed-tool-ids]
  (assert-source-config! spec)
  (let [source (:source spec)
        tool-id (:tool-id spec)
        grants (set (keep canonical-tool-id allowed-tool-ids))]
    (boolean
     (and (contains? grants tool-id)
          (some (fn [resource]
                  (let [declared? (contains? resource :source/tools)
                        ceiling (:source/tools resource)]
                    (and (not (false? (:enabled resource)))
                         (not (false? (:source/enabled? resource)))
                         (= source (get-in resource [:source/filters :encounter-source]))
                         (or (not declared?)
                             (and (or (sequential? ceiling) (set? ceiling))
                                  (contains? (set (keep canonical-tool-id ceiling)) tool-id))))))
                source-resources)))))

(defn- snowflake?
  [value]
  (and (string? value) (boolean (re-matches #"[1-9][0-9]{0,23}" value))))

(defn source-account-admitted?
  "Require an actor-bound canonical source and exact active account metadata.

  A declared account-identifier is a trusted handle-to-stable-identity mapping
  only inside the exact source resource. Source content supplies no mapping."
  [spec source-resources actor-id account-identifier]
  (assert-source-config! spec)
  (boolean
   (and (string? actor-id) (not (str/blank? actor-id))
        (string? account-identifier) (not (str/blank? account-identifier))
        (some (fn [resource]
                (and (not (false? (:enabled resource)))
                     (not (false? (:source/enabled? resource)))
                     (= (:source spec) (get-in resource [:source/filters :encounter-source]))
                     (or (= actor-id (:source/actor resource))
                         (= actor-id (get-in resource [:source/protocol :credentials-owner])))
                     (or (= account-identifier (get-in spec [:source :account-id]))
                         (= account-identifier (get-in resource [:source/filters :account-identifier])))))
              source-resources))))

(defn read-request
  "Newest polls never reuse provider pagination; after is an explicit Discord mode."
  [spec checkpoint]
  (assert-source-config! spec)
  (let [cursor (:cursor-after checkpoint)
        after? (= :after (:poll-mode spec))]
    (when (and after? cursor (not (snowflake? cursor)))
      (throw (ex-info "Discord observation cursor is not a source message identity"
                      {:code :encounter/invalid-social-cursor})))
    {:tool-id (:tool-id spec)
     :parameters (cond-> (assoc (:parameters spec) :limit (source-limit spec))
                   (and after? cursor) (assoc :after cursor))}))

(defn- nonblank
  [value]
  (when (and (string? value) (not (str/blank? value))) value))

(defn- reference
  [kind value label]
  (when-let [value (some-> (nonblank value) (#(when (<= (count %) 512) %)))]
    (cond-> {:kind kind :reference value}
      ;; Optional long source descriptions are omitted rather than misquoted as complete.
      (and (nonblank label) (<= (count label) 128)) (assoc :label label))))

(defn- blob-reference
  [blob]
  (when-let [cid (nonblank (get-in blob [:ref :$link]))]
    (str "atproto:blob:" cid)))

(defn- bluesky-embed-media
  [embed]
  (concat
   (keep #(reference :image (or (:fullsize %) (:thumb %) (blob-reference (:image %)))
                     (:alt %)) (:images embed))
   (when-let [video (reference :video (or (:playlist embed) (blob-reference (:video embed)))
                              (:alt embed))]
     [video])
   (when-let [external (:external embed)]
     (keep identity [(reference :link (:uri external) (:title external))
                     (reference :image (or (when (string? (:thumb external)) (:thumb external))
                                           (blob-reference (:thumb external))) nil)]))
   (when-let [record (reference :link (or (get-in embed [:record :uri])
                                         (get-in embed [:record :record :uri])) nil)]
     [record])
   (when-let [media (:media embed)]
     (bluesky-embed-media media))))

(defn- discord-media
  [row]
  (concat
   (keep (fn [attachment]
           (reference (let [content-type (or (:contentType attachment) "")]
                        (cond (str/starts-with? content-type "image/") :image
                              (str/starts-with? content-type "video/") :video
                              (str/starts-with? content-type "audio/") :audio
                              :else :file))
                      (or (when-let [id (nonblank (:id attachment))]
                            (str "discord:attachment:" id))
                          (:url attachment)) (:filename attachment)))
         (:attachments row))
   (mapcat (fn [embed]
             (keep identity [(reference :link (:url embed) (:title embed))
                             (reference :image (get-in embed [:image :url]) nil)
                             (reference :image (get-in embed [:thumbnail :url]) nil)
                             (reference :video (get-in embed [:video :url]) nil)]))
           (:embeds row))
   (keep (fn [sticker]
           (when-let [id (nonblank (:id sticker))]
             (reference :emote (str "discord:sticker:" id) (:name sticker))))
         (:stickers row))))

(defn- discord-reactions
  [row]
  (mapv (fn [{:keys [emoji count]}]
          (let [label (or (nonblank (:name emoji)) (nonblank (:id emoji)))]
            (cond-> {:label label :count count}
              (nonblank (:id emoji)) (assoc :reference (str "discord:emoji:" (:id emoji))))))
        (take 12 (:reactions row))))

(defn- bluesky-reactions
  [row]
  (->> [[:like "likes"] [:repost "reposts"] [:reply "replies"] [:quote "quotes"]]
       (keep (fn [[key label]]
               (when-let [count (get (:reactionCounts row) key)]
                 {:label label :count count})))
       vec))

(defn- normalized-item
  [spec row normalize-instant]
  (let [discord? (= "discord" (get-in spec [:source :kind]))
        external-id (if discord? (:id row) (:uri row))
        author-id (:authorId row)
        item {:external-id external-id :author-id author-id
              :occurred-at (normalize-instant (if discord? (:timestamp row) (:createdAt row)))
              :text (or (if discord? (:content row) (:text row)) "")
              :self-output? (= author-id (get-in spec [:source :account-id]))
              :reactions (if discord? (discord-reactions row) (bluesky-reactions row))
              :media (->> (if discord? (discord-media row)
                             (bluesky-embed-media (:embed row)))
                          distinct (take 6) vec)}]
    ;; Even an empty system row must prove its source identity/author/time.
    (law/assert-shape! shape/Item item :social-item)
    (when (or (not (str/blank? (:text item))) (seq (:media item)) (seq (:reactions item)))
      (law/assert-item! item))))

(defn- source-rows
  [spec details]
  (let [discord? (= "discord" (get-in spec [:source :kind]))
        rows (if discord? (:messages details) (:results details))]
    (when-not (and (map? details) (vector? rows) (<= (count rows) (source-limit spec))
                   (or (not (contains? details :accountId))
                       (= (get-in spec [:source :account-id]) (:accountId details)))
                   (or (not discord?) (= (get-in spec [:source :scope-id]) (:channelId details)))
                   (or (not= "bluesky.search" (:tool-id spec)) (= "posts" (:kind details))))
      (throw (ex-info "Social tool result is not the requested bounded source page"
                      {:code :encounter/invalid-social-result})))
    (when (and discord?
               (some #(not= (get-in spec [:source :scope-id]) (:channelId %)) rows))
      (throw (ex-info "Discord result contains a different channel"
                      {:code :encounter/source-result-conflict})))
    rows))

(defn- snowflake-extrema
  [ids]
  (when (every? snowflake? ids)
    (let [ordered (sort-by (juxt count identity) ids)]
      {:oldest-id (first ordered) :newest-id (last ordered)})))

(defn- read-identities
  [spec details rows raw-known? raw-count]
  (when (= "discord" (get-in spec [:source :kind]))
    (let [supplied? (contains? details :rawIds)
          row-ids (mapv :id rows)
          ids (if supplied? (:rawIds details) row-ids)
          valid? (and (vector? ids) (<= (count ids) (source-limit spec))
                      (every? snowflake? ids) (= (count ids) (count (set ids)))
                      (every? (set ids) row-ids)
                      (or (not supplied?) (not raw-known?) (= raw-count (count ids))))]
      (when (and (or supplied? (= :after (:poll-mode spec))) (not valid?))
        (throw (ex-info "Discord result has inconsistent raw source identity evidence"
                        {:code :encounter/invalid-social-result})))
      ;; Without a separate unfiltered ID vector, visible rows prove the frontier
      ;; only when the source count says no rows were removed. Item admission is
      ;; independent: an empty system row still has a read identity.
      {:observed-ids ids
       :raw-ids (when (and raw-known? valid? (= raw-count (count ids))) ids)})))

(defn- assert-read-count!
  [details returned limit]
  (when (and (contains? details :rawCount)
             (not (and (integer? (:rawCount details))
                       (<= returned (:rawCount details) limit))))
    (throw (ex-info "Social result has inconsistent source count evidence"
                    {:code :encounter/invalid-social-result}))))

(defn- read-evidence
  [spec details rows]
  (let [returned (count rows)
        limit (source-limit spec)
        _count-evidence (assert-read-count! details returned limit)
        raw-count (:rawCount details)
        raw-known? (and (integer? raw-count) (<= 0 raw-count limit))
        fetched (if raw-known? raw-count returned)
        provider-cursor (nonblank (:cursor details))]
    (merge {:raw-count raw-count :raw-known? raw-known? :limit limit
            :fetched fetched :provider-cursor provider-cursor
            :overflow? (or (= fetched limit) (boolean provider-cursor))}
           (read-identities spec details rows raw-known? raw-count))))

(defn- assert-after-result!
  [spec checkpoint ids]
  (when (and (= :after (:poll-mode spec)) (:cursor-after checkpoint)
             (some #(not (pos? (compare [(count %) %]
                                       [(count (:cursor-after checkpoint)) (:cursor-after checkpoint)]))) ids))
    (throw (ex-info "Discord after result includes an older source identity"
                    {:code :encounter/invalid-social-result}))))

(defn- coverage-report
  [spec evidence items extrema]
  (let [{:keys [raw-count raw-known? fetched provider-cursor overflow?]} evidence
        discord? (= "discord" (get-in spec [:source :kind]))]
    (merge {:mode (or (:poll-mode spec) :newest) :complete? false
            :reason (cond (< (count items) fetched) :unknown
                          (and discord? (or (not raw-known?)
                                           (not= raw-count (count items)))) :unknown
                          provider-cursor :provider-pagination
                          overflow? :overflow
                          :else :bounded-poll)
            :fetched-count fetched :admitted-count (count items)
            :overflow? overflow? :provider-cursor provider-cursor}
           extrema)))

(defn- next-cursor
  [spec checkpoint evidence extrema]
  (let [advance? (and (= :after (:poll-mode spec)) (seq (:raw-ids evidence))
                      (:newest-id extrema))]
    (if advance? (:newest-id extrema) (:cursor-after checkpoint))))

(defn project-page
  "Project a truthful bounded page; only proved Discord reads advance an after cursor."
  [spec checkpoint observed-at details normalize-instant]
  (assert-source-config! spec)
  (law/assert-shape! shape/Instant observed-at :social-observed-at)
  (when-not (fn? normalize-instant)
    (throw (ex-info "Social adapter requires a trusted timestamp normalizer"
                    {:code :encounter/missing-instant-adapter})))
  (let [rows (source-rows spec details)
        items (->> rows (keep #(normalized-item spec % normalize-instant)) vec)
        evidence (read-evidence spec details rows)
        _after-evidence (assert-after-result! spec checkpoint (:observed-ids evidence))
        extrema (if (= "discord" (get-in spec [:source :kind]))
                    (snowflake-extrema (:observed-ids evidence)) {:oldest-id nil :newest-id nil})
        coverage (coverage-report spec evidence items extrema)
        page {:source (:source spec) :items items :cursor-before (:cursor-after checkpoint)
              :cursor-after (next-cursor spec checkpoint evidence extrema)
              :observed-at observed-at :coverage coverage}]
    (law/assert-shape! shape/Page page :social-page)
    {:status :observed :page page :coverage coverage}))
