(ns knoxx.backend.extern.discord-gateway-voice-fixture
  "Owned public voice registry/decoder and timer custody; no Discord connection."
  (:require [knoxx.backend.extern.discord-gateway-fixture :as fixture]
            ["@discordjs/voice" :as voice]
            ["prism-media" :as prism]
            ["node:events" :refer [EventEmitter]]))

(defn- note! [world op & args] (apply fixture/note! world op args))
(defn- descriptor [object field]
  (let [d (js/Object.getOwnPropertyDescriptor object field)]
    (when-not (and d (.-configurable d) (.-writable d)) (fixture/fail! (str "Unsupported voice fixture seam: " field))) d))
(defn- replace! [object field d value]
  (js/Object.defineProperty object field #js {:value value :writable (.-writable d) :enumerable (.-enumerable d) :configurable (.-configurable d)}))
(defn- descriptor-equal? [a b]
  (every? #(identical? (aget a %) (aget b %)) ["value" "writable" "enumerable" "configurable" "get" "set"]))
(defn- stream [world kind uid]
  (let [s (EventEmitter.)]
    (aset s "pipe" (fn [target] (note! world :pipe uid target) target))
    (aset s "destroy" (fn [] (note! world kind uid)
                           (when (:voice-destroy-error? @(:options world)) (fixture/fail! "inert-voice-destroy-error")) s))
    s))
(defn- decoder [world]
  (fn [options]
    (note! world :decoder-create (fixture/snapshot options))
    (let [d (stream world :decoder-destroy "decoded-user")]
      (swap! (:decoders world) conj d) d)))
(defn- connection [world]
  #js {:state #js {:status (.-Ready voice/VoiceConnectionStatus)
                  :adapter #js {:sendPayload (fn [payload] (note! world :voice-payload (fixture/snapshot payload)) true)}}
       :joinConfig #js {:guildId fixture/guild-id}
       :receiver #js {:speaking (:speaking world)
                      :subscribe (fn [uid opts]
                                   (note! world :subscribe uid (fixture/snapshot opts))
                                   (let [s (stream world :audio-stream-destroy uid)] (swap! (:streams world) conj s) s))}
       :destroy (fn [] (note! world :connection-destroy)
                     (when (:connection-destroy-error? @(:options world)) (fixture/fail! "inert-connection-destroy-error")))})
(defn emit-speaking! [world kind uid] (.emit (:speaking world) kind uid))
(defn emit-pcm! [world bytes]
  (let [buffer (js/Buffer.alloc bytes 1)]
    (.emit (peek @(:decoders world)) "data" buffer) buffer))
(defn emit-opus! [world bytes] (.emit (peek @(:streams world)) "data" (js/Buffer.from bytes)))
(defn timer-count [world] (count @(:timers world)))
(defn fire-timers! [world]
  (doseq [[timer callback] @(:timers world)]
    (swap! (:timers world) dissoc timer) (callback)))
(defn wav-summary [wav]
  {:riff (.toString wav "ascii" 0 4) :wave (.toString wav "ascii" 8 12)
   :data-size (.readUInt32LE wav 40) :rate (.readUInt32LE wav 24)
   :channels (.readUInt16LE wav 22) :length (.-length wav)})

(defn- voice-state [world]
  (let [groups (voice/getGroups) prior-group (.get groups "default")
        group (or prior-group (js/Map.))
        world (assoc world :speaking (EventEmitter.) :streams (atom []) :decoders (atom []) :timers (atom {}))]
    (assoc world :groups groups :prior-group prior-group :group group
           :had? (.has group fixture/guild-id) :prior (.get group fixture/guild-id)
           :opus (aget prism "opus") :decoder-d (descriptor (aget prism "opus") "Decoder")
           :timeout-d (descriptor js/globalThis "setTimeout") :clear-d (descriptor js/globalThis "clearTimeout")
           :connection (connection world))))
(defn- timeout [world]
  (fn [callback delay & args]
    (let [timer #js {}]
      (note! world :set-timer delay)
      (swap! (:timers world) assoc timer (fn [] (.apply callback nil (to-array args)))) timer)))
(defn- clear-timeout [world]
  (fn [timer]
    (if (contains? @(:timers world) timer)
      (do (note! world :clear-timer) (swap! (:timers world) dissoc timer))
      (.call (.-value (:clear-d world)) js/globalThis timer))))
(defn- install-voice! [{:keys [groups prior-group group opus decoder-d timeout-d clear-d connection] :as world}]
  (when-not prior-group (.set groups "default" group))
  (.set group fixture/guild-id connection)
  (replace! opus "Decoder" decoder-d (decoder world))
  (replace! js/globalThis "setTimeout" timeout-d (timeout world))
  (replace! js/globalThis "clearTimeout" clear-d (clear-timeout world)))
(defn- restore-voice! [{:keys [groups prior-group group had? prior opus decoder-d timeout-d clear-d] :as world}]
  (.removeAllListeners (:speaking world))
  (reset! (:timers world) {})
  (js/Object.defineProperty opus "Decoder" decoder-d)
  (js/Object.defineProperty js/globalThis "setTimeout" timeout-d)
  (js/Object.defineProperty js/globalThis "clearTimeout" clear-d)
  (if had? (.set group fixture/guild-id prior) (.delete group fixture/guild-id))
  (when-not prior-group
    (when (pos? (.-size group)) (fixture/fail! "Unexpected foreign voice group entry"))
    (.delete groups "default"))
  (when-not (and (descriptor-equal? decoder-d (js/Object.getOwnPropertyDescriptor opus "Decoder"))
                 (descriptor-equal? timeout-d (js/Object.getOwnPropertyDescriptor js/globalThis "setTimeout"))
                 (descriptor-equal? clear-d (js/Object.getOwnPropertyDescriptor js/globalThis "clearTimeout"))
                 (identical? prior-group (.get groups "default"))
                 (= had? (.has group fixture/guild-id))
                 (or (not had?) (identical? prior (.get group fixture/guild-id))))
    (fixture/fail! "Voice fixture restoration failed")))
(defn ^:async with-voice
  "Use actual voice.joinVoiceChannel/entersState against one owned ready registry entry."
  [world action]
  (let [world (voice-state world)]
    (try
      (install-voice! world)
      (await (fixture/invoke! (fixture/manager world) "joinVoice" fixture/channel-id))
      (when-not (and (seq (fixture/calls world :voice-payload))
                     (identical? (:connection world) (fixture/invoke! (fixture/manager world) "getVoiceConnection" fixture/guild-id))
                     (empty? (fixture/calls world :forbidden-adapter)))
        (fixture/fail! "Actual public voice registry join seam was not exercised"))
      (await (action world))
      (finally (restore-voice! world)))))

(defn ^:async run-capture! [world]
  (let [audio (atom []) starts (atom []) m (fixture/manager world)
        stop (await (.startVoiceListener m fixture/guild-id #(swap! starts conj %) (fn [uid wav] (swap! audio conj [uid (wav-summary wav)]))))]
    (emit-speaking! world "start" "speaker")
    (emit-speaking! world "start" "speaker")
    (emit-pcm! world (* 26 48000 2 2))
    (emit-speaking! world "end" "speaker")
    (let [pending (timer-count world)]
      (fire-timers! world)
      (stop)
      {:starts @starts :audio @audio :pending pending
       :subscribes (count (fixture/calls world :subscribe))
       :decoder-options (mapv #(first (second %)) (fixture/calls world :decoder-create))
       :remaining-timers (timer-count world)
       :listener-counts [(.listenerCount (:speaking world) "start") (.listenerCount (:speaking world) "end")]
       :no-guild-entry (let [entry (.getVoiceConnection m nil)]
                         [(aget entry 0) (identical? (aget entry 1) (:connection world))])})))

(defn ^:async run-stop-cleanup! [world]
  (let [m (fixture/manager world) stop (await (.startVoiceListener m fixture/guild-id nil (fn [& args] (note! world :audio-callback (count args)))))]
    (emit-speaking! world "start" "remaining-user")
    (stop)
    {:stream-destroys (count (fixture/calls world :audio-stream-destroy))
     :decoder-destroys (count (fixture/calls world :decoder-destroy))
     :listener-counts [(.listenerCount (:speaking world) "start") (.listenerCount (:speaking world) "end")]}))

(defn ^:async run-subscribe! [world]
  (let [received (atom []) unsubscribe (await (fixture/invoke! (fixture/manager world) "subscribeVoice" fixture/guild-id "opus-user"
                                                             (fn [uid bytes] (swap! received conj [uid (vec bytes)]))))]
    (emit-opus! world #js [1 2 3]) (unsubscribe)
    {:received @received :subscribe-options (mapv second (fixture/calls world :subscribe))
     :stream-destroys (count (fixture/calls world :audio-stream-destroy))}))
