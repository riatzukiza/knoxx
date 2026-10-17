(ns knoxx.backend.extern.discord-gateway-voice
  "Discord voice/prism/Node stream, PCM, native timing and cleanup boundary."
  (:require [knoxx.backend.domain.discord.gateway :as decisions]
            [knoxx.backend.extern.discord-gateway-events :as events]
            ["@discordjs/voice" :as voice]
            ["prism-media" :as prism]
            ["node:module" :refer [createRequire]]
            ["node:stream" :refer [Readable]]))

;; Preserve the original CommonJS libsodium workaround and module-load timing.
(defonce libsodium-wrappers-loaded?
  (let [req (createRequire (str (.cwd js/process) "/"))]
    (req "libsodium-wrappers")
    true))

(defn pcm16le->wav-buffer
  "Wrap raw PCM16LE bytes in a WAV container so ffmpeg (and thus STT) can decode it.

   pcm: Node Buffer of signed 16-bit little-endian samples.
   rate: sample rate in Hz (Discord voice is typically 48000)
   channels: 1 or 2 (Discord voice is typically 2)

   Returns a Node Buffer containing a complete .wav file."
  [pcm rate channels]
  (let [rate (max 1 (long (or rate 48000)))
        channels (max 1 (long (or channels 2)))
        data-size (.-length pcm)
        byte-rate (* rate channels 2)
        block-align (* channels 2)
        wav (js/Buffer.alloc (+ 44 data-size))]
    (.write wav "RIFF" 0)
    (.writeUInt32LE wav (+ 36 data-size) 4)
    (.write wav "WAVE" 8)
    (.write wav "fmt " 12)
    (.writeUInt32LE wav 16 16)
    (.writeUInt16LE wav 1 20)
    (.writeUInt16LE wav channels 22)
    (.writeUInt32LE wav rate 24)
    (.writeUInt32LE wav byte-rate 28)
    (.writeUInt16LE wav block-align 32)
    (.writeUInt16LE wav 16 34)
    (.write wav "data" 36)
    (.writeUInt32LE wav data-size 40)
    (.copy pcm wav 44)
    wav))

(defn voice-connection-guild-id
  [conn]
  (or (.-__guildId conn)
      (some-> conn (.-joinConfig) (.-guildId))
      (.-guildId conn)))

(defn join-voice-connection!
  [channel-id channel guild-id]
  (let [conn (voice/joinVoiceChannel
              #js {:channelId channel-id
                   :guildId guild-id
                   :adapterCreator (.-voiceAdapterCreator (.-guild channel))
                   :selfDeaf false
                   :selfMute false})]
    (aset conn "__guildId" guild-id)
    conn))

(defn ^:async wait-for-voice-ready!
  [conn]
  (try
    (await (voice/entersState conn (.-Ready voice/VoiceConnectionStatus) 15000))
    (js/console.log "[voice:gw] voice connection ready for guild:" (voice-connection-guild-id conn))
    conn
    (catch js/Error err
      (js/console.error "[voice:gw] voice connection failed to ready:" (.-message err))
      (throw err))))

(defn ^:async gw-join-voice
  "Join a voice channel. Returns a VoiceConnection."
  [ensure-client channel-id]
  (js/console.log "[voice:gw] joining channel:" channel-id)
  (let [active-client (await (ensure-client))
        channel (await (.fetch (.. active-client -channels) channel-id))]
    (when-not channel
      (js/console.error "[voice:gw] channel not found:" channel-id)
      (throw (js/Error. (str "Channel not found: " channel-id))))
    (let [guild-id (.-guildId channel)
          conn (join-voice-connection! channel-id channel guild-id)]
      (js/console.log "[voice:gw] channel found:" channel-id "guild:" guild-id "selfDeaf:false")
      (js/console.log "[voice:gw] joinVoiceChannel returned, waiting for ready state…")
      (await (wait-for-voice-ready! conn)))))

(defn gw-leave-voice
  "Leave a voice channel for a guild."
  [connections guild-id]
  (when-let [conn (.get connections guild-id)]
    (.destroy conn)
    (.delete connections guild-id))
  (js/Promise.resolve true))

(defn gw-play-audio
  "Play an audio buffer (PCM s16le 48kHz stereo or any ffmpeg-decodable) in the voice connection."
  [connections guild-id audio-buffer]
  (let [conn (.get connections guild-id)]
    (if-not conn
      (js/Promise.reject (js/Error. (str "No voice connection for guild: " guild-id)))
      (let [player (or (.-__audioPlayer conn)
                       (let [p (voice/createAudioPlayer)]
                         (aset conn "__audioPlayer" p)
                         (.subscribe conn p)
                         p))
            stream (new Readable #js {:read (fn [])})
            _ (do (.push stream audio-buffer) (.push stream nil))
            resource (voice/createAudioResource
                      stream
                      #js {:inputType (.-Arbitrary (aget voice "StreamType"))})]
        (.play player resource)
        (js/Promise.resolve true)))))

(defn gw-subscribe-voice
  "Subscribe to audio from a specific user. Returns an unsubscribe function."
  [connections guild-id user-id callback]
  (let [conn (.get connections guild-id)]
    (if-not conn
      (js/Promise.reject (js/Error. (str "No voice connection for guild: " guild-id)))
      (let [receiver (.-receiver conn)
            opus-stream (.subscribe receiver user-id #js {:mode "opus"})]
        (.on opus-stream "data" (fn [chunk] (callback user-id chunk)))
        (js/Promise.resolve
         (fn [] (.destroy opus-stream)))))))

(defn voice-member-entry
  [member]
  (let [user (.-user member)]
    #js {:userId (.-id user)
         :username (.-username user)
         :displayName (or (.-displayName member) (.-username user))
         :isBot (boolean (.-bot user))
         :isMuted (boolean (.-mute member))
         :isDeaf (boolean (.-deaf member))
         :isSpeaking false}))

(defn ^:async fetch-guild!
  [active-client guild-id]
  (let [guild (await (.fetch (.. active-client -guilds) guild-id))]
    (when-not guild
      (throw (js/Error. (str "Guild not found: " guild-id))))
    guild))

(defn ^:async fetch-guild-channel!
  [guild channel-id]
  (let [channel (await (.fetch (.. guild -channels) channel-id))]
    (when-not channel
      (throw (js/Error. (str "Channel not found: " channel-id))))
    channel))

(defn ^:async gw-list-voice-members
  "List members in a voice channel."
  [ensure-client guild-id channel-id]
  (let [active-client (await (ensure-client))
        guild (await (fetch-guild! active-client guild-id))
        channel (await (fetch-guild-channel! guild channel-id))]
    (into-array (for [[_ member] (.-members channel)]
                  (voice-member-entry member)))))

(defn voice-listener-create-decoder
  []
  (let [OpusDecoder (some-> prism (aget "opus") (aget "Decoder"))]
    (when-not (fn? OpusDecoder)
      (throw (js/Error. "prism-media Opus decoder unavailable")))
    (new OpusDecoder #js {:rate 48000 :channels 2 :frameSize 960})))

(defn voice-listener-flush-audio!
  [pcm-buffers silence-timers on-audio uid]
  (when-let [buf (get @pcm-buffers uid)]
    (let [pcm (js/Buffer.concat (js/Array.from buf))
          duration-s (/ (.-length pcm)
                        decisions/voice-listener-sample-rate
                        decisions/voice-listener-bytes-per-sample
                        decisions/voice-listener-channels)
          wav (pcm16le->wav-buffer pcm decisions/voice-listener-sample-rate decisions/voice-listener-channels)]
      (swap! pcm-buffers dissoc uid)
      (swap! silence-timers dissoc uid)
      (if (< duration-s decisions/voice-listener-min-duration-s)
        (js/console.log "[voice:listener] skipping very short audio for" uid "duration:" duration-s "s")
        (do
          (js/console.log "[voice:listener] calling on-audio for" uid "wav bytes:" (.-length wav) "duration:" duration-s "s")
          (on-audio uid wav))))))

(defn voice-listener-chunk-and-flush!
  [pcm-buffers on-audio uid]
  (when-let [buf (get @pcm-buffers uid)]
    (let [total-pcm (js/Buffer.concat (js/Array.from buf))
          total-len (.-length total-pcm)]
      (when (> total-len decisions/voice-listener-chunk-overlap-bytes)
        (let [flush-len (- total-len decisions/voice-listener-chunk-overlap-bytes)
              flush-pcm (.slice total-pcm 0 flush-len)
              keep-pcm (.slice total-pcm flush-len)
              duration-s (/ flush-len decisions/voice-listener-sample-rate decisions/voice-listener-bytes-per-sample decisions/voice-listener-channels)
              overlap-s (/ decisions/voice-listener-chunk-overlap-bytes decisions/voice-listener-sample-rate decisions/voice-listener-bytes-per-sample decisions/voice-listener-channels)
              wav (pcm16le->wav-buffer flush-pcm decisions/voice-listener-sample-rate decisions/voice-listener-channels)]
          (swap! pcm-buffers assoc uid #js [keep-pcm])
          (js/console.log "[voice:listener] chunk-flush for" uid
                          "flushed:" duration-s "s"
                          "overlap-kept:" overlap-s "s")
          (on-audio uid wav))))))

(defn voice-listener-destroy-user-streams!
  [streams decoders uid]
  (when-let [audio-stream (get @streams uid)]
    (try (.destroy audio-stream)
           (catch js/Error boundary-error
             (events/degraded! :voice-user-stream-destroy :continue-cleanup boundary-error)))
    (swap! streams dissoc uid))
  (when-let [decoder (get @decoders uid)]
    (try (.destroy decoder)
           (catch js/Error boundary-error
             (events/degraded! :voice-user-decoder-destroy :continue-cleanup boundary-error)))
    (swap! decoders dissoc uid)))

(defn voice-listener-on-start-speaking
  [receiver pcm-buffers streams decoders active-users silence-timers on-start on-audio]
  (fn [user-id]
    (let [uid (str user-id)]
      (when-let [t (get @silence-timers uid)]
        (js/clearTimeout t)
        (swap! silence-timers dissoc uid))
      (when-not (contains? @active-users uid)
        (js/console.log "[voice:listener] >>> SPEAKING START:" uid)
        (swap! active-users conj uid)
        (when on-start (on-start uid))
        (when-not (get @pcm-buffers uid)
          (swap! pcm-buffers assoc uid #js []))
        (let [audio-stream (.subscribe receiver uid)
              decoder (voice-listener-create-decoder)]
          (.pipe audio-stream decoder)
          (.on decoder "data"
               (fn [pcm-chunk]
                 (when-let [buf (get @pcm-buffers uid)]
                   (.push buf pcm-chunk)
                   (let [current-size (reduce (fn [acc b] (+ acc (.-length b))) 0 buf)]
                     (when (> current-size decisions/voice-listener-chunk-threshold-bytes)
                       (voice-listener-chunk-and-flush! pcm-buffers on-audio uid))))))
          (.on decoder "error" #(js/console.error "[voice:listener] decoder error for" uid ":" (.-message %)))
          (.on audio-stream "error" #(js/console.error "[voice:listener] audio stream error for" uid ":" (.-message %)))
          (.on audio-stream "end" #(js/console.log "[voice:listener] audio stream ended for" uid))
          (swap! streams assoc uid audio-stream)
          (swap! decoders assoc uid decoder))))))

(defn voice-listener-on-end-speaking
  [streams decoders active-users silence-timers flush-audio!]
  (fn [user-id]
    (let [uid (str user-id)]
      (js/console.log "[voice:listener] >>> SPEAKING END:" uid)
      (swap! active-users disj uid)
      (voice-listener-destroy-user-streams! streams decoders uid)
      (let [t (js/setTimeout #(flush-audio! uid) decisions/voice-listener-silence-debounce-ms)]
        (swap! silence-timers assoc uid t)))))

(defn voice-listener-stop!
  [guild-id speaking-map on-start-speaking on-end-speaking pcm-buffers streams decoders active-users silence-timers flush-audio!]
  (fn []
    (js/console.log "[voice:listener] stopping for guild:" guild-id)
    (.removeListener speaking-map "start" on-start-speaking)
    (.removeListener speaking-map "end" on-end-speaking)
    (doseq [[_ s] @streams]
      (try (.destroy s)
           (catch js/Error boundary-error
             (events/degraded! :voice-stop-stream-destroy :continue-cleanup boundary-error))))
    (doseq [[_ d] @decoders]
      (try (.destroy d)
           (catch js/Error boundary-error
             (events/degraded! :voice-stop-decoder-destroy :continue-cleanup boundary-error))))
    (doseq [[uid t] @silence-timers]
      (js/clearTimeout t)
      (flush-audio! uid))
    (reset! pcm-buffers {})
    (reset! streams {})
    (reset! decoders {})
    (reset! active-users #{})
    (reset! silence-timers {})))

(defn gw-start-voice-listener
  "Start voice capture and return a Promise of a stop function."
  [connections guild-id on-start on-audio]
  (js/console.log "[voice:listener] starting for guild:" guild-id "connections:" (.-size connections))
  (let [conn (.get connections guild-id)]
    (if-not conn
      (do
        (js/console.error "[voice:listener] no connection for guild:" guild-id)
        (js/Promise.reject (js/Error. (str "No voice connection for guild: " guild-id))))
      (let [receiver (.-receiver conn)
            speaking-map (.-speaking receiver)
            pcm-buffers (atom {})
            streams (atom {})
            decoders (atom {})
            active-users (atom #{})
            silence-timers (atom {})
            flush-audio! #(voice-listener-flush-audio! pcm-buffers silence-timers on-audio %)
            on-start-speaking (voice-listener-on-start-speaking receiver pcm-buffers streams decoders active-users silence-timers on-start on-audio)
            on-end-speaking (voice-listener-on-end-speaking streams decoders active-users silence-timers flush-audio!)]
        (js/console.log "[voice:listener] attaching listeners")
        (.on speaking-map "start" on-start-speaking)
        (.on speaking-map "end" on-end-speaking)
        (js/Promise.resolve
         (voice-listener-stop! guild-id speaking-map on-start-speaking on-end-speaking
                               pcm-buffers streams decoders active-users silence-timers flush-audio!))))))

(defn destroy-voice-connections!
  [voice-connections]
  (.forEach voice-connections (fn [conn _key] (try (.destroy conn) (catch js/Error boundary-error
                                                              (events/degraded! :voice-connection-destroy :continue-cleanup boundary-error)))))
  (.clear voice-connections))

(defn ^:async gateway-join-voice!
  [ensure-client voice-connections channel-id]
  (let [conn (await (gw-join-voice ensure-client channel-id))
        guild-id (voice-connection-guild-id conn)]
    (.set voice-connections guild-id conn)
    #js {:guildId guild-id :channelId channel-id :joined true}))

(defn gateway-active-voice-connection
  [voice-connections guild-id]
  (if guild-id
    (.get voice-connections guild-id)
    (when (> (.-size voice-connections) 0)
      (let [entries (.entries voice-connections)]
        (.-value (.next entries))))))
