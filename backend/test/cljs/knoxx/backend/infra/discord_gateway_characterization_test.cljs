(ns knoxx.backend.infra.discord-gateway-characterization-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [knoxx.backend.extern.discord-gateway-fixture :as fixture]
            [knoxx.backend.extern.discord-gateway-voice-fixture :as voice]
            [knoxx.backend.extern.discord-gateway-audio-fixture :as audio]
            [knoxx.backend.extern.discord-gateway-observation-fixture :as boundaries]))

(defn- ^:async finish! [done promise]
  (try (await promise)
    (catch :default error (is false (str "Gateway test failed: " error)))
    (finally (done))))
(def stopped {:started false :ready false :userId nil :userTag nil :guildCount 0})

(deftest original-lifecycle-trimming-reuse-restart-and-explicit-false-default-quirk
  (async done
    (finish! done (fixture/with-world {}
      (^:async fn [world]
        (let [result (await (fixture/run-lifecycle! world))]
          (is (true? (:false-still-default result)))
          (is (true? (:same-client result)))
          (is (true? (:restart-new result)))
          (is (= [fixture/token "replacement-inert-token"] (:login-tokens result)))
          (is (= 2 (:destroy-count result)))
          (is (= {:started true :ready true :userId "fixture-self" :userTag "fixture#0001" :guildCount 1} (:before result)))
          (is (= stopped (:after result)))))))))

(deftest published-default-wrapper-arities-receivers-arguments-and-identical-returns
  (async done
    (finish! done (fixture/with-world {}
      (fn [_world]
        (let [{:keys [results calls nil-returns started-false ready-nil]} (fixture/forwarding-results!)]
          (is (= 21 (count results)))
          (is (every? :same-return results))
          (is (= (mapv (fn [{:keys [method args]}] [method true args]) results) calls))
          (is nil-returns) (is started-false) (is ready-nil)))))))

(deftest original-listings-mapping-pagination-dm-and-search
  (async done
    (finish! done (fixture/with-world {}
      (^:async fn [world]
        (let [result (await (fixture/run-listings-and-messages! world))]
          (is (= [{:id fixture/guild-id :name "Fixture Guild" :memberCount 7}] (:servers result)))
          (is (= (:channels result) (:specific result)))
          (is (= [{:id fixture/channel-id :name "fixture-text" :guildId fixture/guild-id :type "0"}] (:channels result)))
          (is (= ["new" "old"] (mapv :id (:messages result))))
          (is (= ["role-a"] (:authorRoleIds (first (:messages result)))))
          (is (= fixture/instant (:timestamp (first (:messages result)))))
          (is (= [] (:attachments (first (:messages result)))))
          (is (= [] (:embeds (first (:messages result)))))
          (is (= "fixture-dm" (get-in result [:dm :dmChannelId])))
          (is (= ["new"] (mapv :id (get-in result [:search :messages]))))
          (is (= 1 (get-in result [:search :count])))
          (is (= "gateway-cache" (get-in result [:search :source])))
          (is (= "fixture-dm" (get-in result [:dm-search :dmChannelId])))
          (is (= [{:limit 100 :before "before" :after "after" :around "around"}
                  {:limit 1 :before "dm-before"}
                  {:limit 100 :before nil :after nil :around nil}
                  {:limit 100 :before nil}]
                 (:fetch-options result)))))))))

(deftest original-event-identity-partial-fetch-order-unsubscribe-and-native-username
  (async done
    (finish! done (fixture/with-world {}
      (^:async fn [world]
        (let [{:keys [seen partial-order]} (await (fixture/run-message-events! world))]
          (is (= [:message :reaction :voice] (mapv first seen)))
          (is (= [:reaction-fetch :reaction-message-fetch] partial-order))
          (is (= "message-a" (get-in seen [0 1 :id])))
          (is (true? (get-in seen [0 2])))
          (is (= "reactor" (get-in seen [1 2])))
          (is (= "join" (get-in seen [2 1 :action])))
          (is (= "native-user-value" (get-in seen [2 1 :username])))))))))

(deftest original-paragraph-line-word-and-hard-utf16-message-splits
  (async done
    (finish! done (fixture/with-world {}
      (^:async fn [world]
        (doseq [[text expected] [[(str (str/join (repeat 1200 "a")) "\n\n" (str/join (repeat 1000 "b"))) [1200 1000]]
                               [(str (str/join (repeat 1200 "a")) "\n" (str/join (repeat 1000 "b"))) [1200 1000]]
                               [(str (str/join (repeat 1200 "a")) " " (str/join (repeat 1000 "b"))) [1200 1000]]
                               [(str/join (repeat 2001 "x")) [2000 1]]
                               [(str (str/join (repeat 1999 "x")) "😀") [2000 1]]]]
          (let [before (count (fixture/calls world :send))
                result (await (fixture/run-send! world text nil))
                sent (subvec (:sent result) before)]
            (is (= expected (mapv #(count (:content %)) sent)))
            (is (= {:messageReference "reply-id"} (:reply (first sent))))
            (is (every? #(nil? (:reply %)) (rest sent)))
            (is (= "" (get-in result [:result :messageId])))
            (is (= 2 (get-in result [:result :chunkCount]))))))))))

(deftest original-attachment-only-payload-and-first-chunk-file-order
  (async done
    (finish! done (fixture/with-world {}
      (^:async fn [world]
        (let [result (await (fixture/run-send! world "  " [{:attachment [1 2] :filename "one.bin"} {:buffer [3] :name "two.bin"}]))]
          (is (= "[attachment]" (get-in result [:sent 0 :content])))
          (is (= ["one.bin" "two.bin"] (mapv :name (get-in result [:sent 0 :files]))))
          (is (= 2 (get-in result [:result :attachmentCount])))
          (is (= 1 (get-in result [:result :chunkCount])))))))))

(deftest original-actor-identity-validation-reuse-and-mandatory-id
  (async done
    (finish! done (fixture/with-world {}
      (fn [_world]
        (let [first (fixture/ensure-actor! " actor-one ") second (fixture/ensure-actor! "actor-one")]
          (is (fixture/identical-value? first second))
          (is (fixture/identical-value? first (fixture/public-actor " actor-one ")))
          (is (fixture/identical-value? first (fixture/public-default)))
          (is (= #{"actor-one"} (set (keys (fixture/actor-map)))))
        (is (thrown-with-msg? js/Error #"actor id is required" (fixture/ensure-actor! "  ")))
        (is (thrown-with-msg? js/Error #"actor id is required" (fixture/set-actor! nil nil))))))))

(deftest original-duplicate-credentials-start-before-inactive-stop-and-ordered-results
  (async done
    (finish! done (fixture/with-world {}
      (^:async fn [world]
        (let [order (atom [])
              make-manager (fn [actor] (fixture/actor-object (fn [token] (swap! order conj [:start actor token]) nil)
                                      (fn [] (swap! order conj [:stop actor])) {:actor actor}))]
          (fixture/set-actor! "one" (make-manager "one"))
          (fixture/set-actor! "two" (make-manager "two"))
          (fixture/set-actor! "inactive" (make-manager "inactive"))
          (let [result (await (fixture/start-actors! [{:actorId "one" :secretJson {:botToken "first"}}
                                                    {:actor_id "two" :secretJson {:token "second"}}
                                                    {:actor-id "one" :secretJson {:bot-token "third"}}
                                                    {:actorId "bad" :secretJson {}}]))]
            (is (= [[:start "one" "first"] [:start "two" "second"] [:start "one" "third"] [:stop "inactive"]] @order))
            (is (= ["one" "two" "one"] (mapv :actorId result)))
            (is (= [{:actor "one"} {:actor "two"} {:actor "one"}] (mapv :status result)))
            (is (nil? (fixture/public-actor "inactive"))))))))))

(deftest original-degraded-event-status-and-logger-fallbacks
  (async done
    (finish! done (fixture/with-world {}
      (^:async fn [world]
        (let [{:keys [mapped status positive-throws logger-calls]} (await (boundaries/event-sites! world))]
          (is (= [] (:authorRoleIds mapped)))
          (is (string? (:timestamp mapped)))
          (is (= (assoc stopped :started true) status))
          (is (= [:throw-member-role :throw-message-date :throw-user :throw-user :throw-guilds] positive-throws))
          (is (= [[:logger [:info true 1]]] logger-calls)))))))))

(deftest original-login-keeps-original-error-if-synchronous-destroy-also-fails
  (async done
    (let [error (js/Error. "original-inert-login-error")]
      (finish! done (fixture/with-world {:login-error error :destroy-error? true}
        (^:async fn [world]
          (let [result (await (boundaries/login-site! world error))]
            (is (:same-original-error result)) (is (= stopped (:status result)))
            (is (= 1 (:destroy-count result))))))))))

(deftest original-stop-clears-client-after-destroy-failure
  (async done
    (finish! done (fixture/with-world {:destroy-error? true}
      (^:async fn [world]
        (let [result (await (boundaries/stop-site! world))]
          (is (= stopped (:status result))) (is (= 1 (:destroy-count result)))))))))

(deftest original-voice-chunk-overlap-wav-debounce-and-entry-pair-quirk
  (async done
    (finish! done (fixture/with-world {}
      (^:async fn [world]
        (await (fixture/start! world))
        (await (voice/with-voice world
          (^:async fn [world]
            (let [result (await (voice/run-capture! world))]
              (is (= ["speaker"] (:starts result))) (is (= 1 (:subscribes result)))
              (is (= [{:rate 48000 :channels 2 :frameSize 960}] (:decoder-options result)))
              (is (= [(* 21 48000 2 2) (* 5 48000 2 2)] (mapv #(get-in % [1 :data-size]) (:audio result))))
              (is (every? #(= {:riff "RIFF" :wave "WAVE" :rate 48000 :channels 2}
                              (select-keys (second %) [:riff :wave :rate :channels])) (:audio result)))
              (is (= 1 (:pending result))) (is (= 0 (:remaining-timers result)))
              (is (= [0 0] (:listener-counts result)))
              (is (= [fixture/guild-id true] (:no-guild-entry result))))))))))))

(deftest original-voice-subscription-raw-opus-callback-and-unsubscribe
  (async done
    (finish! done (fixture/with-world {}
      (^:async fn [world]
        (await (fixture/start! world))
        (await (voice/with-voice world
          (^:async fn [world]
            (let [result (await (voice/run-subscribe! world))]
              (is (= [["opus-user" [1 2 3]]] (:received result)))
              (is (= [["opus-user" {:mode "opus"}]] (:subscribe-options result)))
              (is (= 1 (:stream-destroys result))))))))))))

(deftest original-five-voice-cleanup-fallbacks-and-inactive-registry-finally
  (async done
    (finish! done (fixture/with-world {}
      (^:async fn [world]
        (let [voice-result (await (boundaries/voice-sites! world))
              inactive (await (boundaries/inactive-site! world))]
          (is (= 2 (:stream-destroys voice-result))) (is (= 2 (:decoder-destroys voice-result)))
          (is (= 1 (:connection-destroys voice-result))) (is (:connection-removed voice-result))
          (is (= stopped (:status voice-result)))
          (is (= [] (:result inactive))) (is (:removed inactive)) (is (= 1 (:stop-count inactive)))))))))

(deftest original-play-audio-uses-actual-local-ffmpeg-resource-and-cleanup
  (async done
    (finish! done (fixture/with-world {}
      (^:async fn [world]
        (let [result (await (audio/actual-audio! world))]
          (is (true? (:result result))) (is (= 1 (:player-calls result)))
          (is (pos? (:spawn-count result))) (is (pos? (:packet-count result)))
          (is (pos? (:packet-bytes result)))))))))

(deftest original-voice-members-native-fields-and-listener-errors-continue
  (async done
    (finish! done (fixture/with-world {}
      (^:async fn [world]
        (is (= [{:userId "member-a" :username "member-name" :displayName "Member Display"
                 :isBot true :isMuted true :isDeaf false :isSpeaking false}]
               (await (fixture/run-voice-members! world))))
        (is (= {:seen [:message :reaction :voice] :error-log-count 3}
               (await (fixture/run-listener-failures! world)))))))))

(deftest original-voice-leave-and-no-connection-errors
  (async done
    (finish! done (fixture/with-world {}
      (^:async fn [world]
        (await (fixture/start! world))
        (await (voice/with-voice world
          (^:async fn [world]
            (let [m (fixture/manager world)]
              (is (= {:guildId fixture/guild-id :left true} (fixture/snapshot (fixture/invoke! m "leaveVoice" fixture/guild-id))))
              (is (= 1 (count (fixture/calls world :connection-destroy))))
              (is (nil? (fixture/invoke! m "getVoiceConnection" fixture/guild-id)))
              (is (:rejected (await (fixture/rejected (fixture/invoke! m "playAudio" fixture/guild-id nil)))))
              (is (:rejected (await (fixture/rejected (fixture/invoke! m "subscribeVoice" fixture/guild-id "user" (fn [& _] nil))))))
              (is (:rejected (await (fixture/rejected (fixture/invoke! m "startVoiceListener" fixture/guild-id nil nil))))))))))))))

(deftest original-short-audio-is-skipped-and-resuming-speaking-cancels-owned-debounce
  (async done
    (finish! done (fixture/with-world {}
      (^:async fn [world]
        (await (fixture/start! world))
        (await (voice/with-voice world
          (^:async fn [world]
            (let [starts (atom []) audio (atom []) stop (fixture/invoke! (fixture/manager world) "startVoiceListener" fixture/guild-id
                                                                                 #(swap! starts conj %) (fn [& args] (swap! audio conj args)))
                  stop (await stop)]
              (voice/emit-speaking! world "start" "short") (voice/emit-pcm! world 1000)
              (voice/emit-speaking! world "end" "short")
              (is (= 1 (voice/timer-count world)))
              (voice/emit-speaking! world "start" "short")
              (is (= 0 (voice/timer-count world)))
              (is (= 1 (count (fixture/calls world :clear-timer))))
              (voice/emit-speaking! world "end" "short") (voice/fire-timers! world)
              (stop)
              (is (= ["short" "short"] @starts)) (is (= [] @audio)))))))))))
