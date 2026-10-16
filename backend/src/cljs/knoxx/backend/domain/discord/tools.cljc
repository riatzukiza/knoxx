(ns knoxx.backend.domain.discord.tools
  "Portable Discord message and chunk decisions; native observations are supplied as data."
  (:require [clojure.string :as str]))

(defn discord-attachments
  "Project the existing Discord attachment fields."
  [message]
  (->> (or (:attachments message) [])
       (mapv (fn [attachment]
               {:id (or (:id attachment) "")
                :filename (or (:filename attachment) "")
                :contentType (or (:content_type attachment) (:contentType attachment))
                :size (or (:size attachment) 0)
                :url (or (:url attachment) "")}))))

(defn discord-embeds
  "Project the existing Discord embed fields."
  [message]
  (->> (or (:embeds message) [])
       (mapv (fn [embed]
               {:title (:title embed)
                :description (:description embed)
                :url (:url embed)}))))

(defn discord-message->map
  "Normalize one Discord message without native objects."
  [message]
  (let [author (or (:author message) {})]
    {:id (or (:id message) "")
     :channelId (or (:channel_id message) (:channelId message) "")
     :content (or (:content message) "")
     :authorId (or (:id author) "")
     :authorUsername (or (:username author) "unknown")
     :authorIsBot (boolean (:bot author))
     :timestamp (or (:timestamp message) "")
   :attachments (discord-attachments message)
     :embeds (discord-embeds message)}))

(defn discord-message-line
  "Render the existing message transcript line."
  [message]
  (let [content (str/trim (str (or (:content message) "")))
        attachments (:attachments message)
        attachment-text (when (seq attachments)
                          (str " attachments="
                               (str/join ", "
                                         (map (fn [attachment]
                                                (str (:filename attachment)
                                                     (when-let [url (:url attachment)]
                                                       (str " <" url ">"))))
                                              attachments))))
        embeds (:embeds message)
        embed-text (when (seq embeds)
                     (str " embeds="
                          (str/join ", "
                                    (map (fn [embed]
                                           (str (or (:title embed) "embed")
                                                (when-let [url (:url embed)]
                                                  (str " <" url ">"))))
                                         embeds))))]
    (str "<" (:authorUsername message) " (id:" (:authorId message) ")> "
         (if (str/blank? content) "[no text]" content)
         (or attachment-text "")
         (or embed-text ""))))

(defn discord-messages-text
  "Render the heading and existing empty/transcript result."
  [heading messages]
  (str heading
       (if (seq messages)
         (str "\n\n" (str/join "\n\n" (map discord-message-line messages)))
         "\n\nNo messages found.")))

(defn discord-record-id
  "Derive the current OpenPlanner message label identity."
  [message]
  (str "discord:message:" (:channelId message) ":" (:id message)))

(defn label-for-record-id
  "Read the existing string or keyword label identity."
  [labels record-id]
  (or (get labels record-id)
      (get labels (keyword record-id))
      {}))

(defn good-first-then-not-bad
  "Order observations by existing quality and parsed time, returning original messages."
  [observations]
  (let [non-bad (remove #(= "bad" (:quality %)) observations)
        chronological #(sort-by (fn [row] (or (:timestamp-ms row) 0)) %)]
    (->> (concat (chronological (filter #(= "good" (:quality %)) non-bad))
                 (chronological (remove #(= "good" (:quality %)) non-bad)))
         (mapv :message))))

(defn within-hours?
  "Preserve the inclusive lower time boundary and unknown timestamp visibility."
  [hours timestamp-ms observed-at-ms]
  (if (some? timestamp-ms)
    (>= timestamp-ms (- observed-at-ms (* hours 60 60 1000)))
    true))

(defn discord-search-result
  "Filter supplied observations and retain the existing result projection."
  [scope timeframe-hours user-id query limit result observations]
  (let [needle (some-> query str str/lower-case)
        author-id (some-> user-id str not-empty)
        filtered (->> observations
                      (filter #(within-hours? timeframe-hours (:timestamp-ms %) (:observed-at-ms %)))
                      (filter (fn [{:keys [message]}]
                                (and (or (str/blank? (str (or needle "")))
                                         (str/includes? (str/lower-case (str (:content message))) needle))
                                     (or (nil? author-id) (= author-id (:authorId message))))))
                      good-first-then-not-bad
                      (take (or limit 50))
                      vec)]
    {:messages filtered :count (count filtered) :scope scope
     :channelId (:channelId result) :dmChannelId (:dmChannelId result)
     :source "client_side_filter_openplanner_labels"
     :qualityOrder "good_chronological_then_not_bad_chronological"
     :sinceHours timeframe-hours}))

(defn infer-upload-filename
  "Choose the existing pathname basename or indexed binary fallback."
  [pathname idx]
  (or (some-> pathname (str/split #"/") last str/trim not-empty)
      (str "attachment-" idx ".bin")))

(def svg-code-block-pattern
#"(?is)```(?:svg|image/svg\+xml)\s*\n([\s\S]*?)\n```")

(defn extract-inline-svg-code-blocks
"Pull fenced ```svg code blocks out of message text so they can be rendered
   and attached as PNGs instead of being sent as raw code."
[text]
(let [raw-text (str (or text ""))
      matches (re-seq svg-code-block-pattern raw-text)
      svg-blocks (->> matches
                      (map second)
                      (map #(str/trim (str %)))
                      (filter #(str/includes? (str/lower-case %) "<svg"))
                      vec)
      cleaned-text (reduce (fn [acc [full-match _svg]]
                             (str/replace acc full-match "[image]"))
                           raw-text
                           matches)]
  {:text cleaned-text
   :svg-blocks svg-blocks}))

(defn discord-message-chunks
  "Preserve UTF16-unit chunking and the paragraph threshold."
  [normalized]
  (let [chunk-size 2000
        base-text (if (str/blank? normalized) "[attachment]" normalized)]
    (loop [remaining base-text
           acc []]
      (if (<= (count remaining) chunk-size)
        (conj acc remaining)
        (let [slice (or (str/last-index-of remaining "\n\n" chunk-size) -1)
              split-at (if (> slice (int (* chunk-size 0.5))) slice chunk-size)]
          (recur (str/trim (subs remaining split-at))
                 (conj acc (str/trim (subs remaining 0 split-at)))))))))

(defn discord-message-payload
  "Preserve the exact current messageId reply sentinel."
  [chunk reply-to state]
  (cond-> {:content chunk}
    (and reply-to (nil? (:messageId state)))
    (assoc :message_reference {:message_id reply-to})))

(defn text-channel-type?
  "Select the current text and thread channel types."
  [channel]
  (contains? #{0 5 11 12} (:type channel)))

(defn pget
  "Preserve the current primary-key or fallback-key precedence."
  ([params k]
   (get params k))
  ([params k fallback-k]
   (or (get params k) (get params fallback-k))))
