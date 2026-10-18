(ns knoxx.backend.extern.discord-gateway-audio-fixture
  "Actual local FFmpeg/codec characterization. No fake availability or shared graph edits."
  (:require [knoxx.backend.extern.discord-gateway-fixture :as fixture]
            ["@discordjs/voice" :as voice]
            ["node:child_process" :as child]
            ["node:events" :refer [EventEmitter]]))

(defn- wav-input []
  (let [data-size (* 48000 2 2) wav (js/Buffer.alloc (+ 44 data-size))]
    (.write wav "RIFF" 0) (.writeUInt32LE wav (+ 36 data-size) 4)
    (.write wav "WAVE" 8) (.write wav "fmt " 12) (.writeUInt32LE wav 16 16)
    (.writeUInt16LE wav 1 20) (.writeUInt16LE wav 2 22) (.writeUInt32LE wav 48000 24)
    (.writeUInt32LE wav 192000 28) (.writeUInt16LE wav 4 32) (.writeUInt16LE wav 16 34)
    (.write wav "data" 36) (.writeUInt32LE wav data-size 40) wav))
(defn- property [object field]
  (let [d (js/Object.getOwnPropertyDescriptor object field)]
    (when-not (and d (.-configurable d) (.-writable d) (fn? (.-value d)))
      (fixture/fail! (str "Unsupported native audio property: " field))) d))
(defn- install! [object field descriptor value]
  (js/Object.defineProperty object field #js {:value value :writable (.-writable descriptor)
                                             :enumerable (.-enumerable descriptor) :configurable (.-configurable descriptor)}))
(defn- same-descriptor? [a b]
  (every? #(identical? (aget a %) (aget b %)) ["value" "writable" "configurable" "enumerable" "get" "set"]))
(defn- probe [world original]
  (fn [command args options]
    (when-not (= ["-h"] (vec args)) (fixture/fail! "Audio fixture refuses a non-help synchronous child"))
    (fixture/note! world :actual-ffmpeg-probe command)
    (.call original child command args (js/Object.assign #js {} options #js {:timeout 4000 :maxBuffer 8388608}))))
(defn- remember-error! [world phase error]
  (if @(:cleaning? world)
    (swap! (:cleanup-errors world) conj {:phase phase :error error})
    (when-not @(:primary-error world)
      (reset! (:primary-error world) {:phase phase :error error}))))
(defn- watch-stream! [world stream kind]
  (when (and stream (not (some #(identical? stream (:stream %)) @(:streams world))))
    (let [closed (js/Promise. (fn [resolve _]
                               (.once stream "close" #(resolve {:closed true}))
                               (when (true? (.-closed stream)) (resolve {:closed true}))))]
      (.on stream "error" #(remember-error! world kind %))
      (swap! (:streams world) conj {:stream stream :kind kind :closed closed}))))
(defn- spawn [world original]
  (fn [command args options]
    (let [arguments (vec args)]
      (when-not (and (false? (.-shell options)) (= "pipe:1" (peek arguments))
                     (= "-" (nth arguments (inc (.indexOf args "-i")) nil)))
        (fixture/fail! "Audio fixture refuses a shell or non-stdin input"))
      (fixture/note! world :actual-ffmpeg-spawn command arguments)
      (let [process (.call original child command args options)
            closed (js/Promise. (fn [resolve _]
                                  (.once process "close" (fn [code signal]
                                                           (resolve {:closed true :code code :signal signal})))))]
        (.on process "error" #(remember-error! world :child-process %))
        (swap! (:owned world) conj {:process process :closed closed})
        (watch-stream! world (.-stdin process) :child-stdin)
        (watch-stream! world (.-stdout process) :child-stdout)
        (watch-stream! world (.-stderr process) :child-stderr)
        process))))
(defn- ^:async wait-bounded! [promise delay]
  (let [timer (atom nil)]
    (try
      (await (js/Promise.race #js [promise (js/Promise. (fn [_ reject]
                        (reset! timer (js/setTimeout #(reject (js/Error. "Bounded local audio fixture timed out")) delay))))]))
      (finally (when @timer (js/clearTimeout @timer))))))

(defn- player [world resources packets ended]
  #js {:play (fn [resource]
               (fixture/note! world :actual-player-play resource)
               (swap! resources conj resource)
                (watch-stream! world (.-playStream resource) :play-stream)
               (reset! ended (js/Promise. (fn [resolve _]
                               (.once (.-playStream resource) "end" #(resolve {:ended true}))
                               (.once (.-playStream resource) "error" #(resolve {:error %})))))
               (.on (.-playStream resource) "data" #(swap! packets conj (.-length %)))
               (.resume (.-playStream resource)))})
(defn- audio-state [world]
  (let [groups (voice/getGroups) prior-group (.get groups "default") group (or prior-group (js/Map.))
        resources (atom []) packets (atom []) ended (atom nil)
        state (assoc world :groups groups :prior-group prior-group :group group
                     :had? (.has group fixture/guild-id) :prior (.get group fixture/guild-id)
                     :original-spawn (property child "spawn") :original-probe (property child "spawnSync")
                     :resources resources :owned (atom []) :streams (atom []) :cleaning? (atom false)
                     :primary-error (atom nil) :cleanup-errors (atom []) :packets packets :ended ended)]
    (assoc state :connection #js {:state #js {:status (.-Ready voice/VoiceConnectionStatus)
                                             :adapter #js {:sendPayload (fn [_] true)}}
                                 :receiver #js {:speaking (EventEmitter.)}
                                 :destroy (fn [] (fixture/note! world :audio-connection-destroy))
                                 :__audioPlayer (player state resources packets ended)})))
(defn- install-audio! [{:keys [groups prior-group group connection original-spawn original-probe] :as world}]
  (when-not prior-group (.set groups "default" group))
  (.set group fixture/guild-id connection)
  (install! child "spawn" original-spawn (spawn world (.-value original-spawn)))
  (install! child "spawnSync" original-probe (probe world (.-value original-probe))))
(defn- cleanup-error! [world phase error]
  (swap! (:cleanup-errors world) conj {:phase phase :error error}))
(defn- attempt-cleanup! [world phase action]
  (try (action) (catch :default error (cleanup-error! world phase error))))
(defn- restore-audio! [{:keys [groups prior-group group had? prior original-spawn original-probe] :as world}]
  (attempt-cleanup! world :spawn-descriptor #(js/Object.defineProperty child "spawn" original-spawn))
  (attempt-cleanup! world :probe-descriptor #(js/Object.defineProperty child "spawnSync" original-probe))
  (attempt-cleanup! world :voice-registry-entry
                    #(if had? (.set group fixture/guild-id prior) (.delete group fixture/guild-id)))
  (attempt-cleanup! world :voice-registry-group
                    #(when-not prior-group
                       (when (pos? (.-size group)) (fixture/fail! "Unexpected foreign audio registry entry"))
                       (.delete groups "default")))
  (attempt-cleanup! world :restoration-verification
                    #(when-not (and (same-descriptor? original-spawn (js/Object.getOwnPropertyDescriptor child "spawn"))
                                    (same-descriptor? original-probe (js/Object.getOwnPropertyDescriptor child "spawnSync"))
                                    (identical? prior-group (.get groups "default"))
                                    (= had? (.has group fixture/guild-id))
                                    (or (not had?) (identical? prior (.get group fixture/guild-id))))
                       (fixture/fail! "Actual audio fixture restoration failed"))))
(defn- ^:async await-close! [world phase closed]
  (try
    (when-not (:closed (await (wait-bounded! closed 4000)))
      (fixture/fail! "Owned audio object did not report terminal close"))
    (catch :default error (cleanup-error! world phase error))))
(defn- ^:async cleanup-audio! [{:keys [streams owned] :as world}]
  (reset! (:cleaning? world) true)
  (try
    ;; Initiate every owned teardown before waiting; one failure cannot skip the rest.
    (doseq [{:keys [stream kind]} (reverse @streams)]
      (when-not (true? (.-destroyed stream))
        (attempt-cleanup! world kind #(.destroy stream))))
    (doseq [{:keys [process]} @owned]
      (when (and (nil? (.-exitCode process)) (nil? (.-signalCode process))
                 (not (true? (.-killed process))))
        (attempt-cleanup! world :child-kill #(.kill process "SIGKILL"))))
    (doseq [{:keys [kind closed]} @streams]
      (await (await-close! world kind closed)))
    (doseq [{:keys [closed]} @owned]
      (await (await-close! world :child-close closed)))
    (finally
      (restore-audio! world))))
(defn- final-outcome! [world result]
  (let [primary @(:primary-error world) cleanup @(:cleanup-errors world)]
    (cond
      (seq cleanup) (throw (ex-info "Local audio fixture cleanup failed"
                                   {:primary-present? (some? primary)
                                    :primary-phase (:phase primary) :primary-error (:error primary)
                                    :cleanup-errors cleanup} (:error primary)))
      primary (throw (:error primary))
      :else result)))
(defn- ^:async play-and-observe! [{:keys [resources owned packets ended] :as world}]
  (await (fixture/invoke! (fixture/manager world) "joinVoice" fixture/channel-id))
  (let [result (await (fixture/invoke! (fixture/manager world) "playAudio" fixture/guild-id (wav-input)))]
    (when-not (and (true? result) (= 1 (count @resources)) (pos? (count @owned)))
      (fixture/fail! "Successful playAudio did not exercise actual FFmpeg/player"))
    (let [outcome (await (wait-bounded! @ended 8000))]
      (when-let [error (:error outcome)] (throw error))
      (when-not (:ended outcome) (fixture/fail! "Actual audio stream did not end")))
    (when-not (seq @packets) (fixture/fail! "Actual native audio produced no packets"))
    {:result result :player-calls (count (fixture/calls world :actual-player-play))
     :spawn-count (count @owned) :packet-count (count @packets) :packet-bytes (reduce + 0 @packets)}))
(defn ^:async actual-audio! [world]
  (await (fixture/start! world))
  (let [world (audio-state world) result (atom nil)]
    (try
      (install-audio! world)
      (reset! result (await (play-and-observe! world)))
      (catch :default error (remember-error! world :play error))
      (finally (await (cleanup-audio! world))))
    (final-outcome! world @result)))
