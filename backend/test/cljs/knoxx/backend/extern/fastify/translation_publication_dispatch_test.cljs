(ns knoxx.backend.extern.fastify.translation-publication-dispatch-test
  "Publication selectors through the registered native Fastify route.

  These integration contracts preserve authorization, facade dependencies,
  source-revision narrowing and the one-publication response."
  (:require [cljs.test :refer [deftest is testing]]
            [knoxx.backend.extern.fastify.translation-dispatch :as adapter]
            [knoxx.backend.infra.translation-evidence-store :as evidence-store]))

(defn- request
  "A request whose body is `body`. Only `.body` is read by `decode-request`."
  [body]
  (js-obj "body" (clj->js body)))

(deftest ^:async a-registered-publication-command-reaches-the-facade-exactly
  (let [routes (atom [])
        app (js-obj "route" (fn [options]
                              (swap! routes conj options)))
        response (atom {})
        reply (js-obj)
        checks (atom [])
        facade-call (atom nil)
        ctx {:org-id "org-1" :membership-id "member-1"}
        handlers
        {:with-request-context! (fn [_runtime _request _reply operation]
                                  (operation ctx))
         :ensure-permission! (fn [actual-ctx permission]
                               (swap! checks conj [actual-ctx permission]))}
        candidate-event-emitter (fn [_] (js/Promise.resolve {:ok true}))
        config {:session-project-name "review-stage"
                :publication-content-root "/translation-content"
                :openplanner-client-mode "rest"}
        dependencies
        {:evidence-store :knoxx.backend.extern.fastify.translation-dispatch-test/evidence-store
         :split-store :knoxx.backend.extern.fastify.translation-dispatch-test/split-store
         :client :knoxx.backend.extern.fastify.translation-dispatch-test/client
         :observe-source-revision (constantly (js/Promise.resolve nil))
         :emit! (constantly (js/Promise.resolve nil))
         :emit-candidate-events! candidate-event-emitter
         :resolve-agent-contract
         (fn [_config _agent-id]
           {:model "gemma4:31b"
            :thinking-level :medium
            :system-prompt "Translate admitted splits."
            :tool-ids ["save_translation"]})
         :dispatch-translations!
         (fn [actual-config deps scope selection]
           (reset! facade-call
                   {:config actual-config
                    :deps deps
                    :scope scope
                    :selection selection})
           (js/Promise.resolve
            {:considered 1 :admissible 1 :dispatched []}))}]
    (aset reply "code" (fn [status]
                          (swap! response assoc :status status)
                          reply))
    (aset reply "type" (fn [_content-type] reply))
    (aset reply "send" (fn [body]
                          (swap! response assoc :body body)
                          reply))
    (aset reply "sent" false)
    (adapter/register-translation-dispatch-routes!
     app {} config handlers dependencies)
    (let [route (first @routes)]
      (await
       ((aget route "handler")
        (request {:publication "knoxx.publications/probe-es"})
        reply))

      (testing "the real registered handler decodes and preserves publication identity"
        ;; Regressing to `(:document decoded)` hands nil to the facade here,
        ;; which means the corpus-wide form and makes this assertion fail.
        (is (= {:publication :knoxx.publications/probe-es}
               (:selection @facade-call)))
        (is (= {:org-id "org-1"
                :membership-id "member-1"
                :project "review-stage"}
               (:scope @facade-call)))
        (is (= :knoxx.backend.extern.fastify.translation-dispatch-test/split-store (get-in @facade-call [:deps :split-store])))
        (is (= "/translation-content"
               (get-in @facade-call [:deps :content-root])))
        (is (identical? candidate-event-emitter
                        (get-in @facade-call [:deps :emit-candidate-events!]))))

      (testing "authorization still precedes dispatch and the response succeeds"
        (is (= [[ctx adapter/dispatch-permission]] @checks))
        (is (= 200 (:status @response)))))))

(deftest ^:async a-registered-publication-command-narrows-the-real-facade
  (let [document {:document/id :knoxx.docs/probe
                  :document/title "Probe"
                  :document/source-locale :en
                  :document/visibility :public
                  :document/source {:path "docs/probe.md"}}
        garden (fn [id]
                 {:garden/id id
                  :garden/title (name id)
                  :garden/status :active
                  :garden/locales [:en :es]})
        intent (fn [publication garden-id]
                 {:publication/id publication
                  :publication/document :knoxx.docs/probe
                  :publication/garden garden-id
                  :publication/locale :es
                  :publication/revision :source/current
                  :publication/state :published
                  :publication/path (str "/" (name publication))
                  :translation/review :required})
        publication-a :knoxx.publications/probe-es-a
        publication-b :knoxx.publications/probe-es-b
        garden-a :knoxx.gardens/a
        garden-b :knoxx.gardens/b
        resource-record (fn [kind definition]
                          {:ok? true
                           :resource/kind kind
                           :resource/file-path
                           (str "/contracts/" (name kind) ".edn")
                           :resource/definition definition})
        records [(resource-record :document document)
                 (resource-record :garden (garden garden-a))
                 (resource-record :garden (garden garden-b))
                 (resource-record :publication (intent publication-a garden-a))
                 (resource-record :publication (intent publication-b garden-b))]
        routes (atom [])
        app (js-obj "route" (fn [options] (swap! routes conj options)))
        response (atom {})
        reply (js-obj)
        revision-input (atom nil)
        dispatched-intents (atom nil)
        ctx {:org-id "org-1" :membership-id "member-1"}
        evidence (evidence-store/memory-store)
        handlers
        {:with-request-context! (fn [_runtime _request _reply operation]
                                  (operation ctx))
         :ensure-permission! (fn [_ctx _permission] nil)}
        config {:session-project-name "review-stage"
                :translation-runner "agent"
                :openplanner-client-mode "rest"}
        dependencies
        {:evidence-store evidence
         :split-store :knoxx.backend.extern.fastify.translation-dispatch-test/split-store
         :client :knoxx.backend.extern.fastify.translation-dispatch-test/client
         :emit-candidate-events! (fn [_completion]
                                   (js/Promise.resolve {:ok true}))
         :observe-source-revision (constantly (js/Promise.resolve nil))
         :emit! (constantly (js/Promise.resolve nil))
         :resolve-agent-contract
         (fn [_config _agent-id]
           {:model "gemma4:31b"
            :thinking-level :medium
            :system-prompt "Translate admitted splits."
            :tool-ids ["save_translation"]})
         :resource-records! (fn [_config] (js/Promise.resolve records))
         :source-revisions!
         (fn [_config documents _roots]
           (reset! revision-input documents)
           (js/Promise.resolve
            {:knoxx.docs/probe "sha256-aaa111bbb222"}))
         :ensure-contract-receipts! (fn [& _] (js/Promise.resolve []))
         :dispatch-agent-intents!
         (fn [_deps _index intents _facts _scope _roots]
           (reset! dispatched-intents intents)
           (js/Promise.resolve
            (mapv (fn [selected]
                    {:publication/id (:publication/id selected)
                     :dispatch/outcome :dispatch/accepted})
                  intents)))}]
    (aset reply "code" (fn [status]
                          (swap! response assoc :status status)
                          reply))
    (aset reply "type" (fn [_content-type] reply))
    (aset reply "send" (fn [body]
                          (swap! response assoc :body
                                 (js->clj body :keywordize-keys true))
                          reply))
    (aset reply "sent" false)
    (adapter/register-translation-dispatch-routes!
     app {} config handlers dependencies)
    (let [route (first @routes)]
      (await
       ((aget route "handler")
        (request {:publication "knoxx.publications/probe-es-b"})
        reply))

      (testing "the transport selector narrows before revision derivation"
        (is (= [document] @revision-input))
        (is (= [publication-b]
               (mapv :publication/id @dispatched-intents)))
        (is (not-any? #(= publication-a (:publication/id %))
                      @dispatched-intents)))

      (testing "the real facade reports one considered and dispatched relation"
        (is (= 200 (:status @response)))
        (is (= 1 (get-in @response [:body :considered])))
        (is (= 1 (get-in @response [:body :admissible])))
        (is (= ["knoxx.publications/probe-es-b"]
               (mapv :id
                     (get-in @response [:body :dispatched]))))))))
