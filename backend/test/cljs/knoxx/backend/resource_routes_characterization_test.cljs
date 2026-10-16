(ns knoxx.backend.resource-routes-characterization-test
  (:require [clojure.string :as str]
            [cljs.test :refer [deftest is]]
            [knoxx.backend.extern.resource-routes-fixture :as fixture]
            [knoxx.backend.infra.routes.contracts :as contracts]
            [knoxx.backend.infra.routes.resources :as routes]))

(def config {:contracts-dir "/fixture" :session-project-name "fixture-project"})
(def context {:orgId "fixture-org" :membershipId "fixture-member"
              :permissions ["org.translations.manage" "org.publications.manage"]})
(def agent-edn
  "{:contract/id \"agent\" :contract/kind :agent :enabled true :trigger-kind :manual
    :agent {:role :role/developer :model \"test-model\"} :prompts {:task \"Fixture only\"}}")

(def expected-routes
  [["GET" "/api/contracts/ui-actions"] ["GET" "/api/agent/contracts"]
   ["POST" "/api/agent/contracts/validate"] ["GET" "/api/agent/contracts/:contractId"]
   ["PUT" "/api/agent/contracts/:contractId"] ["GET" "/api/admin/resources"]
   ["GET" "/api/admin/resources/:resourceId"] ["PUT" "/api/admin/resources/:resourceId"]
   ["POST" "/api/admin/resources/validate"] ["POST" "/api/admin/resources/:resourceId/copy"]
   ["GET" "/api/admin/contracts"] ["GET" "/api/admin/contracts/:contractId"]
   ["PUT" "/api/admin/contracts/:contractId"] ["POST" "/api/admin/contracts/validate"]
   ["POST" "/api/admin/contracts/:contractId/copy"]])

(defn- registration
  ([] (registration #{}))
  ([denied]
   (let [value (fixture/registration context denied)]
     (routes/register-resource-routes! :app :runtime config (:helpers value)) value)))

(defn- capture []
  (let [result (atom nil)] {:result result :send! (fn [status body] (reset! result [status body]))}))

(defn- ^:async caught [task]
  (try (await (task)) {:failed? false}
       (catch :default error {:failed? true :error error})))

(deftest original-registration-surface-and-both-aliases
  (doseq [register! [routes/register-resource-routes! routes/register-contracts-routes!
                    contracts/register-resource-routes! contracts/register-contracts-routes!]]
    (let [r (fixture/registration context #{})]
      (is (nil? (register! :app :runtime config (:helpers r))))
      (is (= expected-routes (mapv #(subvec % 0 2) @(:table r))))
      (is (empty? @(:effects r))))))

(deftest validation-aliases-retain-empty-malformed-and-ordered-warnings
  (doseq [validate! [routes/validate-resource-edn routes/validate-contract-edn contracts/validate-contract-edn]]
    (is (= {:ok false :contract nil :errors [{:path [] :message "EDN text is empty"}] :warnings []}
           (validate! "agents" " ")))
    (is (false? (:ok (validate! "agents" "{")))))
  (let [text (str "{:contract/id \"agent\" :contract/kind :agent :trigger-kind :manual "
                  ":source {} :capabilities [] :agent {:role :bad_role} "
                  ":prompts {:task \"read :data/world_state\"} "
                  ":data {:filter {} :source {:max-messages \"101suffix\"}}}")
        one (routes/validate-resource-edn "agent" text)
        two (routes/validate-contract-edn "agents" text)]
    (is (= one two))
    (is (= [["data" "filter"] ["source"] ["capabilities"] ["data" "source" "max-messages"]
            ["agent" "role"] ["agent" "role"] ["prompts"]]
           (mapv :path (:warnings one))))))

(deftest native-number-prefix-and-other-class-warning-boundaries
  (doseq [[value expected] [["100suffix" false] ["101suffix" true] ["1.5" false] ["n/a" false]]]
    (let [text (str "{:contract/id \"agent\" :contract/kind :agent :agent {:role :role/developer} "
                    ":data {:source {:max-messages " (pr-str value) "}}}")
          result (routes/validate-resource-edn "agents" text)]
      (is (= expected (boolean (some #(= ["data" "source" "max-messages"] (:path %)) (:warnings result)))))))
  (is (empty? (:warnings (routes/validate-resource-edn "roles" "{:role/id :role/test :role/name \"Test\"}")))))

(deftest identity-rewrites-preserve-every-existing-class-and-default
  (doseq [[kind before after] [["agents" "{:contract/id \"old\"}" "{:contract/id \"new_id\"}"]
                               ["policies" "{:contract/id \"old\"}" "{:contract/id \"new_id\"}"]
                               ["sources" "{:contract/id \"old\" :source/id :source/old}" "{:contract/id \"new_id\" :source/id :source/new-id}"]
                               ["actors" "{:actor/id \"old\"}" "{:actor/id \"new_id\"}"]
                               ["roles" "{:role/id :role/old}" "{:role/id :role/new-id}"]
                               ["capabilities" "{:cap/id :cap/old}" "{:cap/id :cap/new-id}"]
                               ["model_families" "{:model-family/id \"old\"}" "{:model-family/id \"new_id\"}"]
                               ["models" "{:model/id \"old\"}" "{:model/id \"new:id\"}"]
                               ["documents" "{:document/id :docs/old}" "{:document/id :docs/new-id}"]
                               ["gardens" "{:garden/id :gardens/old}" "{:garden/id :gardens/new-id}"]
                               ["publications" "{:publication/id :publications/old}" "{:publication/id :publications/new-id}"]
                               ["actions" "{:contract/id \"old\"}" "{:contract/id \"old\"}"]]]
    (is (= after (routes/update-resource-id-in-edn-text kind before "new_id"))))
  (is (= ":contract/id \"new\"\n{}" (routes/update-resource-id-in-edn-text "agents" "{}" "new")))
  (is (= "{:cap/id :cap/new-id}" (routes/update-resource-id-in-edn-text "capabilities" "{:cap/id :cap/old}" "cap_new_id"))))

(deftest ^:async list-order-filter-and-legacy-wire-shapes
  (let [w (fixture/world {:records [{:resource/id "z" :resource/class "roles" :resource/kind :role}
                                    {:resource/id "b" :resource/class "agents" :resource/kind :agent}
                                    {:resource/id "a" :resource/class "agents" :resource/kind :agent}]})]
    (await (fixture/with-world! w
             (^:async fn []
               (let [one (capture) two (capture)]
                 (await (routes/handle-list-resources (:send! one) config nil))
                 (await (contracts/handle-list-contracts (:send! two) config "agents"))
                 (is (= ["a" "b" "z"] (mapv :id (:resources (second @(:result one))))))
                 (is (= ["a" "b"] (mapv :id (:contracts (second @(:result two))))))
                 (is (every? #(= "agents" (:contractClass %)) (:contracts (second @(:result two)))))
               (let [failed (capture)]
                 (fixture/update-state! w assoc :load-error (fixture/native-error "load-failed" "EIO"))
                 (await (routes/handle-list-resources (:send! failed) config nil))
                 (is (= [500 {:detail "Failed to list resources: load-failed"}] @(:result failed))))))))))

(deftest ^:async index-sync-aliases-are-promise-status-and-preserve-failure
  (let [w (fixture/world {:records [{:resource/id "a" :resource/class "agents"}]})]
    (await (fixture/with-world! w
             (^:async fn []
               (is (map? (await (routes/sync-resource-index! config))))
               (is (= {:ok true :added [] :removed [] :count 1} (await (routes/sync-contract-index! config))))
               (is (= {:ok true :added [] :removed [] :count 1} (await (contracts/sync-contract-index! config))))
               (fixture/update-state! w assoc :load-error (fixture/native-error "load-failed" "EIO"))
               (is (= {:ok false :error "load-failed"} (await (routes/sync-resource-index! config)))))))))

(deftest ^:async routes-use-null-prototype-params-and-preserve-compatibility-text
  (let [w (fixture/world {:files {"/fixture/agents/agent.edn" agent-edn}})]
    (await (fixture/with-world! w
             (^:async fn []
               (let [r (registration)
                     json (await (fixture/invoke-route! r "GET" "/api/admin/resources/:resourceId"
                                                       {:params {:resourceId "agent"} :query {:kind "agents"}}))
                     text (await (fixture/invoke-route! r "GET" "/api/agent/contracts/:contractId"
                                                       {:params {:contractId "agent"}}))]
                 (is (= 200 (:status json)))
                 (is (= "agent" (get-in json [:body :resource/id])))
                 (is (= agent-edn (get-in json [:body :ednText])))
                 (is (= 200 (:status text)))
                 (is (= agent-edn (:body text)))
                 (is (:receiver? text))
                 (is (:method-receiver? text))
                 (is (= {"Content-Type" "text/plain; charset=utf-8"} (:headers text)))))))))

(deftest ^:async denied-admin-write-stops-before-native-effects
  (let [w (fixture/world)]
    (await (fixture/with-world! w
             (^:async fn []
               (let [r (registration #{"platform.org.create"})
                     response (await (fixture/invoke-route! r "PUT" "/api/admin/resources/:resourceId"
                                                           {:params {:resourceId "agent"} :body {:ednText agent-edn}}))]
                 (is (= 403 (:status response)))
                 (is (= [[:context] [:permission "platform.org.create"]] @(:effects r)))
                 (is (empty? (fixture/calls w)))))))))

(deftest ^:async invalid-save-and-kind-precedence-refuse-before-write
  (let [w (fixture/world)]
    (await (fixture/with-world! w
             (^:async fn []
               (let [r (registration)
                     response (await (fixture/invoke-route! r "PUT" "/api/admin/resources/:resourceId"
                                                           {:params {:resourceId "different"} :query {:kind "roles"}
                                                            :body {:kind "agents" :edn_text agent-edn}}))]
                 (is (= 400 (:status response)))
                 (is (= "different" (get-in response [:body :routeResourceId])))
                 (is (= "agent" (get-in response [:body :ednResourceId])))
                 (is (empty? (fixture/calls w)))))))))

(deftest ^:async agent-compatibility-writes-only-agent-class-in-both-arities
  (let [w (fixture/world)]
    (await (fixture/with-world! w
             (^:async fn []
               (let [one (capture) two (capture) denied (capture)]
                 (await (routes/handle-agent-put-contract-edn (:send! one) config "agents" "agent" agent-edn))
                 (await (routes/handle-agent-put-contract-edn (:send! two) config "agents" "agent" agent-edn context nil))
                 (await (routes/handle-agent-put-contract-edn (:send! denied) config "documents" "doc" "{}"))
                 (is (= 200 (first @(:result one))))
                 (is (= @(:result one) @(:result two)))
                 (is (= 400 (first @(:result denied))))
                 (is (= agent-edn (get-in (fixture/state w) [:files "/fixture/agents/agent.edn"])))))))))

(deftest ^:async resource-save-five-seven-arities-and-validation-mismatch
  (let [w (fixture/world)]
    (await (fixture/with-world! w
             (^:async fn []
               (let [one (capture) two (capture) bad (capture)]
                 (await (routes/handle-save-resource (:send! one) config "agents" "agent" agent-edn))
                 (await (routes/handle-save-resource (:send! two) config "agents" "agent" agent-edn context nil))
                 (await (routes/handle-save-resource (:send! bad) config "agents" "different" agent-edn))
                 (is (= 200 (first @(:result one))))
                 (is (= @(:result one) @(:result two)))
                 (is (= 400 (first @(:result bad))))
                 (is (= "agent" (:ednResourceId (second @(:result bad)))))
                 (is (= #{"/fixture/agents/agent.edn"} (set (keys (:files (fixture/state w))))))))))))

(deftest ^:async registered-document-save-enters-index-before-exact-admission
  (let [w (fixture/world)
        edn "{:document/id :docs/a :document/title \"A\" :document/source-locale :en :document/source {:path \"a.md\"}}"]
    (await (fixture/with-world! w
             (^:async fn []
               (let [r (registration)
                     result (await (fixture/invoke-route! r "PUT" "/api/admin/resources/:resourceId"
                                                         {:params {:resourceId "a"} :body {:kind "documents" :ednText edn}}))]
                 (is (= 200 (:status result)))
                 (is (= edn (get-in result [:body :ednText])))
                 (is (= {"ok" true "admitted" 1 "failed" 0} (get-in result [:body :admission])))
                 (is (= [:read :write :load :admit] (mapv first (fixture/calls w))))
                 (is (= [:admit {:org-id "fixture-org" :membership-id "fixture-member" :project "fixture-project"}
                         {:document :docs/a}] (last (fixture/calls w))))))))))

(deftest ^:async compatibility-registration-keeps-its-json-and-raw-text-forms
  (let [w (fixture/world)]
    (await (fixture/with-world! w
             (^:async fn []
               (let [r (registration)
                     json (await (fixture/invoke-route! r "PUT" "/api/admin/contracts/:contractId"
                                                       {:params {:contractId "agent"} :body {:contract_class "agents" :ednText agent-edn}}))
                     text (await (fixture/invoke-route! r "PUT" "/api/agent/contracts/:contractId"
                                                       {:params {:contractId "agent"} :body agent-edn}))]
                 (is (= 200 (:status json)))
                 (is (= "agents" (get-in json [:body :contractClass])))
                 (is (not (contains? (:body json) :resourceClass)))
                 (is (= 200 (:status text)))
                 (is (str/includes? (:body text) ":contract/id \"agent\""))))))))

(deftest ^:async admission-scope-permissions-source-and-result-contracts
  (let [seen (atom []) admit (fn [scope selection] (swap! seen conj [scope selection]) (fixture/resolved {:ok true :failed 0 :admitted 1}))]
    (is (= {:org-id "fixture-org" :membership-id "fixture-member" :project "fixture-project"}
           (routes/admission-scope config context)))
    (is (= :docs/a (routes/admission-document-id "documents" {:document/id :docs/a})))
    (is (= :docs/a (routes/admission-document-id "publications" {:publication/document :docs/a})))
    (await (routes/admit-saved-publication-resource! config context "documents" {:document/id :docs/a} admit))
    (is (= 1 (count @seen)))
    (doseq [ctx [(dissoc context :membershipId) (assoc context :permissions [])]]
      (is (:failed? (await (caught (fn [] (routes/admit-saved-publication-resource! config ctx "documents" {:document/id :docs/a} admit)))))))
    (is (= 1 (count @seen)))
    (is (:failed? (await (caught (fn [] (routes/admit-saved-publication-resource! config context "documents" {:document/id :docs/a}
                                                                             (fn [_ _] (fixture/resolved {:ok false :admitted 1 :failed 1}))))))))))

(deftest ^:async write-read-refusal-has-no-later-effects
  (let [failure (ex-info "read-primary" {}) w (fixture/world {:read-error failure})]
    (await (fixture/with-world! w
             (^:async fn []
               (let [outcome (await (caught (fn [] (routes/write-resource-and-admit! config "/doc" "new"
                                                                                   (fn [] (throw (ex-info "must-not-admit" {})))
                                                                                   (fixture/write-deps w)))))]
                 (is (:failed? outcome)) (is (identical? failure (:error outcome)))
                 (is (= [[:read "/doc"]] (fixture/calls w)))))))))

(deftest ^:async forward-sync-refusal-restores-existing-or-removes-new
  (doseq [old [nil "old"]]
    (let [events (atom []) failure (atom 0) files (atom (if old {"/doc" old} {}))
          deps {:read-file! (fn [_] (if old (fixture/resolved old) (fixture/rejected (fixture/native-error "absent" "ENOENT"))))
                :write-file! (fn [path text] (swap! events conj [:write text]) (swap! files assoc path text) (fixture/resolved nil))
                :delete-file! (fn [path] (swap! events conj [:delete]) (swap! files dissoc path) (fixture/resolved nil))
                :sync-index! (fn [_] (swap! events conj [:sync]) (fixture/resolved {:ok (> (swap! failure inc) 1)}))}
          outcome (await (caught (fn [] (routes/write-resource-and-admit! config "/doc" "new" nil deps))))]
      (is (:failed? outcome))
      (is (= "resource_index_sync_failed" (:code (ex-data (:error outcome)))))
      (is (= (if old {"/doc" "old"} {}) @files))
      (is (= (if old [[:write "new"] [:sync] [:write "old"] [:sync]] [[:write "new"] [:sync] [:delete] [:sync]]) @events)))))

(deftest ^:async rollback-failure-preserves-both-first-causes
  (let [first-error (ex-info "forward-primary" {}) rollback-error (ex-info "rollback-secondary" {})
        writes (atom 0)
        deps {:read-file! (fn [_] (fixture/resolved "old"))
              :write-file! (fn [_ _] (if (= 1 (swap! writes inc)) (fixture/rejected first-error) (fixture/rejected rollback-error)))
              :sync-index! (fn [_] (fixture/resolved {:ok true}))}
        result (await (caught (fn [] (routes/write-resource-and-admit! config "/doc" "new" nil deps))))]
    (is (= 2 @writes))
    (is (= "resource_write_rollback_failed" (:code (ex-data (:error result)))))
    (is (= "forward-primary" (:write/error (ex-data (:error result)))))
    (is (= "rollback-secondary" (:rollback/error (ex-data (:error result)))))))

(deftest ^:async irreversible-admission-failure-retains-entered-source-and-four-arity-defaults
  (let [w (fixture/world {:files {"/doc" "old"}}) primary (ex-info "admission-primary" {})]
    (await (fixture/with-world! w
             (^:async fn []
               (let [result (await (caught (fn [] (routes/write-resource-and-admit! config "/doc" "entered"
                                                                                   (fn [] (fixture/rejected primary))))))]
                 (is (identical? primary (:error result)))
                 (is (= "entered" (get-in (fixture/state w) [:files "/doc"])))
                 (is (= [[:read "/doc"] [:write "/doc" "entered"] [:load]] (fixture/calls w)))))))))

(deftest ^:async same-path-coalescing-is-serialization-and-other-path-progresses
  (let [w (fixture/world {:files {"/same" "old"}}) gate (fixture/deferred) first-error (ex-info "first-admission" {})]
    (await (fixture/with-world! w
             (^:async fn []
               (let [one (caught (fn [] (routes/write-resource-and-admit! config "/same" "one" (fn [] (:promise gate)) (fixture/write-deps w))))
                     two (routes/write-resource-and-admit! config "/same" "two" nil (fixture/write-deps w))
                     other (routes/write-resource-and-admit! config "/other" "separate" nil (fixture/write-deps w))]
                 (await other) (await (fixture/flush!))
                 (is (= "one" (get-in (fixture/state w) [:files "/same"])))
                 (is (= "separate" (get-in (fixture/state w) [:files "/other"])))
                 ((:reject! gate) first-error)
                 (is (identical? first-error (:error (await one))))
                 (await two) (await (fixture/flush!))
                 (is (= "two" (get-in (fixture/state w) [:files "/same"])))
                 (is (= [[:write "/same" "one"] [:write "/same" "two"]]
                        (filterv #(and (= :write (first %)) (= "/same" (second %))) (fixture/calls w))))))))))

(deftest ^:async watcher-aliases-start-once-debounce-and-attempt-every-owned-close
  (let [failure (ex-info "watcher-close" {}) w (fixture/world {:close-errors {"/one" failure}})]
    (await (fixture/with-world! w
             (^:async fn []
               (routes/start-resource-watcher! config) (contracts/start-contract-watcher! config)
               (is (= [[:watch "/one" {:recursive true}] [:watch "/two" {:recursive true}]]
                      (filterv #(= :watch (first %)) (fixture/calls w))))
               (fixture/emit! w "/one" "change" "ignored.txt")
               (is (empty? @(:timers w)))
               (fixture/emit! w "/one" "change" "A.EDN")
               (fixture/emit! w "/two" "rename" nil)
               (is (= 1 (count @(:timers w))))
               (is (= [[:timer 350] [:timer 350]] (filterv #(= :timer (first %)) (fixture/calls w))))
               (await (fixture/fire-timer! w))
               (is (= [[:load] [:reload]] (filterv #(contains? #{:load :reload} (first %)) (fixture/calls w))))
               (is (nil? (contracts/stop-contract-watcher!)))
               (is (= ["/one" "/two"] @(:close-attempts w))))))))

(deftest ^:async completed-predecessor-cannot-retire-a-replacement-path-tail
  (let [w (fixture/world {:files {"/same" "old"}}) one-gate (fixture/deferred) two-gate (fixture/deferred)]
    (await (fixture/with-world! w
             (^:async fn []
               (let [one (routes/write-resource-and-admit! config "/same" "one" (fn [] (:promise one-gate)) (fixture/write-deps w))
                     two (routes/write-resource-and-admit! config "/same" "two" (fn [] (:promise two-gate)) (fixture/write-deps w))]
                 (await (fixture/flush!)) ((:resolve! one-gate) :first) (await one) (await (fixture/flush!))
                 (let [three (routes/write-resource-and-admit! config "/same" "three" nil (fixture/write-deps w))]
                   (await (fixture/flush!))
                   (is (= "two" (get-in (fixture/state w) [:files "/same"])))
                   ((:resolve! two-gate) :second) (await two) (await three)
                   (is (= "three" (get-in (fixture/state w) [:files "/same"]))))))))))

(deftest ^:async watcher-partial-start-retains-only-successful-owned-handles
  (let [w (fixture/world {:watch-errors {"/one" (fixture/native-error "watch-refused" "ENOSYS")}})]
    (await (fixture/with-world! w
             (^:async fn []
               (routes/start-contract-watcher! config)
               (is (= ["/two"] (mapv :root @(:watchers w))))
               (routes/start-resource-watcher! config)
               (is (= 2 (count (filter #(= :watch (first %)) (fixture/calls w)))))
               (routes/stop-resource-watcher!)
               (is (= ["/two"] @(:close-attempts w))))))))

(deftest ^:async malformed-native-query-scalar-refuses-at-the-original-route-boundary
  (doseq [variant [:null-prototype :throwing-to-string] key ["kind" "class"]]
    (let [w (fixture/world)]
      (await (fixture/with-world! w
               (^:async fn []
                 (let [r (registration) request {:native-query {key variant}}
                       json (await (fixture/invoke-route! r "GET" "/api/admin/resources" request))
                       text (await (fixture/invoke-route! r "GET" "/api/agent/contracts" request))]
                   (is (= 400 (:status json)))
                   (is (= "Invalid resource kind" (get-in json [:body :detail])))
                   (is (= 400 (:status text)))
                   (is (str/starts-with? (:body text) ";; Invalid contract class: "))
                   (is (empty? (fixture/calls w))))))))))

(deftest ^:async selected-query-kind-does-not-coerce-the-unused-malformed-class
  (let [w (fixture/world)]
    (await (fixture/with-world! w
             (^:async fn []
               (let [r (registration)
                     response (await (fixture/invoke-route! r "GET" "/api/admin/resources"
                                                           {:query {:kind "agents"}
                                                            :native-query {"class" :throwing-to-string}}))]
                 (is (= 200 (:status response)))
                 (is (= [] (get-in response [:body :resources])))
                 (is (= [[:load]] (fixture/calls w)))))))))
