(ns knoxx.backend.domain.discord.gateway
  "Portable Discord gateway constants and decisions from explicit observations."
  (:require [clojure.string :as str]))

(def voice-listener-sample-rate 48000)

(def voice-listener-channels 2)

(def voice-listener-bytes-per-sample 2)

(def voice-listener-min-duration-s 0.8)

(def voice-listener-silence-debounce-ms 900)

(def voice-listener-chunk-threshold-s 25)

(def voice-listener-chunk-overlap-s 5)

(def voice-listener-chunk-threshold-bytes
  (* voice-listener-sample-rate
     voice-listener-channels
     voice-listener-bytes-per-sample
     voice-listener-chunk-threshold-s))

(def voice-listener-chunk-overlap-bytes
  (* voice-listener-sample-rate
     voice-listener-channels
     voice-listener-bytes-per-sample
     voice-listener-chunk-overlap-s))

(defn preferred-split-index [paragraph line space]
  (cond (> paragraph 1000) paragraph (> line 1000) line (> space 1000) space :else 2000))

(defn bounded-message-limit [value default-limit]
  (max 1 (min 100 (or value default-limit))))

(defn message-content-text [text attachments?]
  (let [base-text (str (or text ""))]
    (if (and (str/blank? base-text) attachments?) "[attachment]" base-text)))

(defn voice-state-action [old-channel-id new-channel-id]
  (cond (and (nil? old-channel-id) new-channel-id) "join"
        (and old-channel-id (nil? new-channel-id)) "leave"
        (and old-channel-id new-channel-id (not= old-channel-id new-channel-id)) "move"
        :else nil))

(defn normalize-actor-id [actor-id]
  (some-> actor-id str str/trim not-empty))

(defn degraded-context [operation fallback error?]
  {:operation operation :fallback fallback :error? error?})
