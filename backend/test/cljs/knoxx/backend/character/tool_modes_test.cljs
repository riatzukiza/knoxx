(ns knoxx.backend.character.tool-modes-test
  (:require [clojure.string :as str]
            [cljs.test :refer [deftest is testing]]
            [knoxx.backend.domain.character.tool-modes :as modes]))

(def catalog
  [{:id "tools.enter_mode" :name "tools_enter_mode"}
   {:id "character.context" :name "character_context"}
   {:id "discord.read" :name "discord_read"}
   {:id "discord.send" :name "discord_send"}
   {:id "bluesky.timeline" :name "bluesky_timeline"}
   {:id "memory_search" :name "memory_search"}])

(def configuration
  {:initial "home"
   :core ["tools.enter_mode" "character.context"]
   :modes {"home" {:description "Choose a focused activity" :tools []}
           "discord-read" {:description "Explore permitted Discord spaces" :tools ["discord.read"]}
           "discord-chat" {:description "Chat in permitted Discord spaces" :tools ["discord.read" "discord.send"]}
           "bluesky-read" {:description "Encounter outside feed material" :tools ["bluesky.timeline"]}
           "memory" {:description "Recall admitted experience" :tools ["memory_search"]}}})

(def initial-state
  {:conversation-id "continuing-conversation"
   :character {:identity "creative-character"
               :persona "A continuing creative role."
               :snapshot {:interests ["weather"] :attention "outside encounters"
                          :mood {:revision "existing-projection"}}
               :evidence-ids ["admitted-encounter"]
               :revision "persisted-projection"}})

(def all-allowed (set (map :id catalog)))

(deftest modes-change-only-tool-exposure
  (let [home (modes/enter-mode initial-state configuration catalog all-allowed "home")
        reading (modes/enter-mode (:state home) configuration catalog all-allowed "discord-read")
        memory (modes/enter-mode (:state reading) configuration catalog all-allowed "memory")]
    (is (= :accepted (:status reading)))
    (is (= ["tools_enter_mode" "character_context"] (get-in home [:state :visible-names])))
    (is (= ["tools_enter_mode" "character_context" "discord_read"]
           (get-in reading [:state :visible-names])))
    (is (= (:character initial-state) (get-in memory [:state :character])))
    (is (= (:conversation-id initial-state) (get-in memory [:state :conversation-id])))
    (is (str/includes? (modes/character-context-text (:state memory)) "A continuing creative role."))
    (is (str/includes? (modes/character-context-text (:state memory)) "admitted-encounter"))))

(deftest refusal-preserves-state-and-never-grants-capabilities
  (doseq [[mode registry allowed reason]
          [["invented" catalog all-allowed :unknown-mode]
           ["discord-chat" catalog (disj all-allowed "discord.send") :unauthorized-tool]
           ["memory" (vec (remove #(= "memory_search" (:id %)) catalog)) all-allowed :unknown-tool]
           ["home" (conj catalog {:id "discord.other" :name "discord_read"}) all-allowed :ambiguous-tool-name]]]
    (let [result (modes/enter-mode initial-state configuration registry allowed mode)]
      (is (= :refused (:status result)))
      (is (= reason (:reason result)))
      (is (= initial-state (:state result)))))
  (is (= #{"home" "discord-read" "bluesky-read" "memory"}
         (set (map :mode (modes/mode-menu configuration catalog (disj all-allowed "discord.send")))))))

(deftest invocation-rechecks-hidden-tools-and-revoked-permission
  (let [state (:state (modes/enter-mode initial-state configuration catalog all-allowed "discord-read"))]
    (testing "registered tools outside the active mode cannot execute"
      (is (= {:status :refused :reason :hidden-tool}
             (modes/authorize-invocation state catalog all-allowed "discord_send"))))
    (testing "current authority is checked again after a mode was selected"
      (is (= {:status :refused :reason :unauthorized-tool}
             (modes/authorize-invocation state catalog (disj all-allowed "discord.read") "discord_read"))))
    (testing "provider aliases resolve to the original canonical identity"
      (is (= {:status :accepted :tool-id "discord.read"}
             (modes/authorize-invocation state catalog all-allowed "discord_read"))))
    (is (= {:status :refused :reason :unknown-tool}
           (modes/authorize-invocation state catalog all-allowed "made_up")))))

(deftest malformed-catalog-refuses-before-traversal
  (doseq [malformed [42 nil "not-a-catalog" {:id "read"} [42]]]
    (let [selection (modes/enter-mode initial-state configuration malformed all-allowed "home")]
      (is (= :invalid-catalog (:reason selection)))
      (is (= initial-state (:state selection)))
      (is (= {:status :refused :reason :invalid-catalog}
             (modes/authorize-invocation initial-state malformed all-allowed "discord_read"))))))

(deftest default-persona-is-present-once-and-explicit-projection-survives
  (let [base (get-in initial-state [:character :persona])
        assembled (modes/system-context-text base initial-state)
        distinct-state (assoc-in initial-state [:character :persona] "Additional explicit character voice.")
        distinct (modes/system-context-text base distinct-state)]
    (is (= 1 (count (re-seq #"A continuing creative role\." assembled))))
    (is (str/includes? assembled "admitted-encounter"))
    (is (str/includes? assembled "existing-projection"))
    (is (= 1 (count (re-seq #"Additional explicit character voice\." distinct))))
    (is (str/includes? distinct base))
    (is (not (str/includes? assembled "Current mode:")))))
