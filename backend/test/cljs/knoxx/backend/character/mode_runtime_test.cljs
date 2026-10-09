(ns knoxx.backend.character.mode-runtime-test
  (:require [cljs.test :refer [deftest is async]]
            [knoxx.backend.extern.tools :as tools]
            [knoxx.backend.extern.tool-validation :as validation]
            [knoxx.backend.infra.character.mode-runtime :as runtime]
            [knoxx.backend.infra.agent.session :as sessions]
            [knoxx.backend.infra.actor.acting :as acting]
            [knoxx.backend.character.tool-modes-test :as fixture]))

(def configuration
  {:initial "home" :core []
   :modes {"home" {:description "Attend and choose" :tools []}
           "discord" {:description "Read permitted Discord spaces" :tools ["discord.read"]}
           "making" {:description "Make in the owned workspace" :tools ["read" "write" "edit" "bash"]}
           "memory" {:description "Recall scoped experience" :tools ["memory_search"]}}})

(def parameters
  [:map {:closed true} [:channel :string] [:limit [:int {:min 1 :max 5}]]
   [:reaction {:optional true} [:enum "yes" "no"]]])

(defn- catalog [calls*]
  (mapv (fn [id]
          {:id id :name (if (= id "discord.read") "discord_read" id)
           :description (str "Existing " id)
           :parameters-schema parameters :parameters {:type "object"}
           :execute (fn [_id args _signal _update]
                      (swap! calls* conj {:tool id :arguments args})
                      {:content [{:type :text :text "Scoped fixture result"}]
                       :details {:tool id}})})
        ["discord.read" "read" "write" "edit" "bash" "memory_search"]))

(defn- execute [controller name]
  (:execute (first (filter #(= name (:name %)) (:tools controller)))))

(defn- ^:async refused-arguments! [invoke! calls* arguments]
  (let [before @calls*]
    (try
      (await (invoke! "call" {:tool "discord_read" :arguments arguments} nil nil))
      (is false "Invalid input reached execution")
      (catch :default error
        (is (= :invalid-arguments (:reason (ex-data error))))))
    (is (= before @calls*))))

(defn- ^:async validator-controller! [calls* validator]
  (let [tool (-> (first (catalog calls*))
                 (dissoc :parameters-schema)
                 (assoc :validate-arguments validator))
        controller (await (runtime/make-controller! configuration fixture/initial-state
                                                    (fn [] {:catalog [tool] :allowed-ids #{(:id tool)}})))]
    (is (= :accepted (get-in (await ((execute controller "capabilities") "menu" {:mode "discord"} nil nil)) [:details :status])))
    controller))

(deftest ^:async non-boolean-tool-validator-results-refuse-before-capability-execution
  (doseq [diagnostic [{:errors [{:path [:channel] :message "Expected a string"}]}
                      ["Invalid channel"] "Invalid channel" {} [] "" :invalid 0 1]]
    (let [calls* (atom []) checked* (atom []) arguments {:channel 42 :limit 1}
          controller (await (validator-controller! calls* (fn [input] (swap! checked* conj input) diagnostic)))]
      (await (refused-arguments! (execute controller "invoke") calls* arguments))
      (is (= [arguments] @checked*) "The actual trusted validator was called with the invalid arguments")
      (is (= [] @calls*) "A truthy diagnostic must not authorize the effectful capability"))))

(deftest ^:async exact-true-tool-validator-result-preserves-authorized-capability-execution
  (let [calls* (atom []) checked* (atom []) arguments {:channel "home" :limit 1}
        controller (await (validator-controller! calls* (fn [input] (swap! checked* conj input) true)))
        result (await ((execute controller "invoke") "call" {:tool "discord_read" :arguments arguments} nil nil))]
    (is (= [arguments] @checked*))
    (is (= [{:tool "discord.read" :arguments arguments}] @calls*))
    (is (= "discord.read" (get-in result [:details :tool])))))

(deftest ^:async false-and-nil-tool-validator-results-remain-refused
  (doseq [refusal [false nil]]
    (let [calls* (atom []) controller (await (validator-controller! calls* (fn [_arguments] refusal)))]
      (await (refused-arguments! (execute controller "invoke") calls* {:channel 42 :limit 1})))))

(deftest ^:async malli-tool-argument-schema-retains-precedence-over-the-callback-validator
  (let [calls* (atom []) callback-calls* (atom 0)
        tool (assoc (first (catalog calls*)) :validate-arguments
                    (fn [_arguments] (swap! callback-calls* inc) false))
        controller (await (runtime/make-controller! configuration fixture/initial-state
                                                    (fn [] {:catalog [tool] :allowed-ids #{(:id tool)}})))
        arguments {:channel "home" :limit 1}]
    (await ((execute controller "capabilities") "menu" {:mode "discord"} nil nil))
    (await ((execute controller "invoke") "call" {:tool "discord_read" :arguments arguments} nil nil))
    (is (= [{:tool "discord.read" :arguments arguments}] @calls*))
    (await (refused-arguments! (execute controller "invoke") calls* {:channel 42 :limit 1}))
    (is (zero? @callback-calls*) "The existing Malli branch remains the authoritative schema branch")))

(deftest progressive-registry-preserves-character-and-creative-tools
  (async done
    ((^:async fn []
       (try
         (let [calls* (atom []) registry (catalog calls*)
               allowed* (atom (set (map :id registry)))
               controller (await (runtime/make-controller! configuration fixture/initial-state
                                                           #(identity {:catalog registry :allowed-ids @allowed*})))
               menu! (execute controller "capabilities") invoke! (execute controller "invoke")
               initial @(:state* controller)]
           (is (= ["capabilities" "invoke"] (mapv :name (:tools controller))))
           (is (empty? (get-in (await (menu! "menu" {} nil nil)) [:details :capabilities])))
           (is (= :hidden-tool (get-in (await (invoke! "hidden" {:tool "discord_read" :arguments {:channel "home" :limit 1}} nil nil)) [:details :reason])))
           (let [making (await (menu! "menu" {:mode "making"} nil nil))]
             (is (= #{"read" "write" "edit" "bash"} (set (map :name (get-in making [:details :capabilities]))))))
           (await (invoke! "make" {:tool "write" :arguments {:channel "fixture" :limit 1}} nil nil))
           (is (= "write" (:tool (last @calls*))))
           (await (menu! "menu" {:mode "discord"} nil nil))
           (await (invoke! "read" {:tool "discord_read" :arguments {:channel "home" :limit 1}} nil nil))
           (doseq [arguments [{} {:channel 42 :limit 1} {:channel "home" :limit 1.5}
                              {:channel "home" :limit 6} {:channel "home" :limit 1 :reaction "maybe"}
                              {:channel "home" :limit 1 :extra "forbidden"} nil [] "text"]]
             (await (refused-arguments! invoke! calls* arguments)))
           (swap! allowed* disj "discord.read")
           (let [before @calls* prior @(:state* controller)]
             (is (= :unauthorized-tool (get-in (await (invoke! "revoked" {:tool "discord_read" :arguments {:channel "home" :limit 1}} nil nil)) [:details :reason])))
             (is (= before @calls*))
             (is (= :refused (get-in (await (menu! "bad" {:mode "invented"} nil nil)) [:details :status])))
             (is (= prior @(:state* controller))))
           (is (= (:character initial) (:character @(:state* controller))))
           (is (= (:conversation-id initial) (:conversation-id @(:state* controller)))))
         (catch :default error (is false (str error)))
         (finally (done)))))))

(deftest protocol-json-schema-is-validated-without-coercion
  (let [schema {:type "object" :required ["count" "nested"] :additionalProperties false
                :properties {:count {:type "integer" :minimum 1 :maximum 3}
                             :nested {:type "object" :required ["kind"] :additionalProperties false
                                      :properties {:kind {:enum ["outside" "home"]}}}}}
        validate (validation/trusted-json-validator schema)]
    (is (fn? validate))
    (is (validate {:count 2 :nested {:kind "outside"}}))
    (doseq [input [{} {:count "2" :nested {:kind "home"}}
                   {:count 2 :nested {:kind "invented"}}
                   {:count 2 :nested {:kind "home" :extra true}}
                   {:count 2 :nested {:kind "home"} :extra true}
                   {:count 2.5 :nested {:kind "home"}} nil [] "text"]]
      (is (false? (validate input))))
    (is (nil? (validation/trusted-json-validator (assoc schema :$schema "https://json-schema.org/draft/2020-12/schema"))))
    (is (nil? (validation/trusted-json-validator {:type "object" :required "count"})))
    (is (nil? (validation/trusted-json-validator {:type "object" :unknownValidationKeyword true})))
    (is (nil? (validation/trusted-json-validator {:type "object" :unevaluatedProperties false})))
    (is (nil? (validation/trusted-json-validator {:type "object" :properties {:bad {:type "invented"}}})))))

(deftest engine-codec-keeps-registered-alias-and-trusted-schema
  (let [definition (tools/tool-definition {:name "discord.read" :label "Read" :description "Existing read"
                                           :parameters-schema parameters
                                           :execute (fn [& _] nil) :runtime {} :config {}})
        registered (tools/registered-tool (tools/sanitize-custom-tool-name definition))]
    (is (= "discord.read" (:id registered)))
    (is (= "discord_read" (:name registered)))
    (is (= parameters (:parameters-schema registered)))))

(deftest scoped-dispatch-retains-the-existing-fixed-domain-execute-arity
  (async done
    ((^:async fn []
       (try
         (let [seen* (atom nil) updates* (atom [])
               definition (tools/tool-definition
                           {:name "social.read" :parameters-schema parameters :runtime :owned-runtime :config :owned-config
                            :execute (fn [runtime config id args signal update! context]
                                       (reset! seen* [runtime config id (js->clj args :keywordize-keys true) signal context])
                                       (update! #js {:details #js {:status "read"}})
                                       #js {:details #js {:source "fixed-domain-closure"}})})
               registered (tools/registered-tool definition)
               result (await ((:execute registered) "call" {:channel "fixture" :limit 1} :signal #(swap! updates* conj %)))]
           (is (= [:owned-runtime :owned-config "call" {:channel "fixture" :limit 1} :signal nil] @seen*))
           (is (= [{:details {:status "read"}}] @updates*))
           (is (= {:details {:source "fixed-domain-closure"}} result)))
         (catch :default error (is false (str error)))
         (finally (done)))))))

(deftest ^:async focused-closures-use-the-fresh-exact-credential-owner-scope
  (let [seen* (atom nil)
        tool (tools/tool-definition
              {:name "social.read" :parameters-schema parameters
               :execute (^:async fn [_runtime _config _id _args _signal _update _context]
                          (await (js/Promise.resolve nil))
                          (reset! seen* (assoc (acting/current-lookup-scope) :actor-id (acting/current-actor-id)))
                          #js {:details #js {:status "read"}})})
        registered (#'sessions/registered-focused-tool
                    {:actorId "fresh-actor" :org {:id "fresh-org"} :membership {:id "fresh-member"}} tool)]
    (await (acting/run-as! {:actor-id "unrelated-actor" :org-id "unrelated-org" :membership-id "unrelated-member"}
                          #((:execute registered) "call" {:channel "fixture" :limit 1} nil nil)))
    (is (= {:actor-id "fresh-actor" :org-id "fresh-org" :membership-id "fresh-member"} @seen*))
    (is (false? (acting/in-scope?)))))
