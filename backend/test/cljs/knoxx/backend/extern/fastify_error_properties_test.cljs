(ns knoxx.backend.extern.fastify-error-properties-test
  "Characterize real HTTP exceptions through their existing Fastify boundary."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [goog.object :as object]
            [knoxx.backend.extern.fastify :as fastify]
            [knoxx.backend.infra.http :as http]
            [knoxx.backend.infra.routes.mcp.params :as params]))

(defn- capture-created-exception
  "Capture and return the genuine ex-info constructed by the public API."
  [construct]
  (let [original-ex-info ex-info
        created (atom nil)]
    (with-redefs [cljs.core/ex-info
                  (fn
                    ([message data]
                     (let [error (original-ex-info message data)]
                       (reset! created error)
                       error))
                    ([message data cause]
                     (let [error (original-ex-info message data cause)]
                       (reset! created error)
                       error)))]
      {:returned (construct) :created @created})))

(deftest ordinary-http-error-retains-the-genuine-exception-and-both-representations
  (let [{:keys [returned created]}
        (capture-created-exception #(http/http-error 403 "scope_denied" "Denied"))]
    (is (identical? created returned))
    (is (instance? js/Error returned))
    (is (= "403 Denied" (ex-message returned)))
    (is (= {:status 403 :code "scope_denied"} (ex-data returned)))
    (is (= 403 (object/get returned "statusCode")))
    (is (= "scope_denied" (object/get returned "code")))
    (is (= 403 (fastify/error-status returned 500)))
    (is (= "scope_denied" (fastify/error-code returned)))
    (is (= "403 Denied" (fastify/error-message returned)))))

(deftest mcp-http-error-retains-message-data-properties-and-public-arities
  (doseq [data [nil {:attempt 2 :nested {:signal "retained"}}]]
    (let [{:keys [returned created]}
          (capture-created-exception
           #(if data
              (params/http-error 400 "invalid_request" "Invalid" data)
              (params/http-error 400 "invalid_request" "Invalid")))]
      (is (identical? created returned))
      (is (instance? js/Error returned))
      (is (= "Invalid" (ex-message returned)))
      (is (= (merge {:status 400 :error "invalid_request" :detail "Invalid"} data)
             (ex-data returned)))
      (is (= 400 (object/get returned "statusCode")))
      (is (= "invalid_request" (object/get returned "code")))
      (is (= 400 (fastify/error-status returned 500)))
      (is (= "invalid_request" (fastify/error-code returned)))
      (is (= "Invalid" (fastify/error-message returned))))))

(deftest native-error-readers-preserve-property-priority-and-fallbacks
  (testing "a genuine native Error without HTTP metadata uses the caller's default"
    (let [error (js/Error. "plain")]
      (is (= 503 (fastify/error-status error 503)))
      (is (nil? (fastify/error-code error)))
      (is (= "plain" (fastify/error-message error)))))
  (testing "native statusCode has priority over status, including a falsey-looking zero"
    (let [error (js/Error. "prioritized")]
      (object/set error "status" 418)
      (object/set error "statusCode" 409)
      (object/set error "code" "native_conflict")
      (is (= 409 (fastify/error-status error 500)))
      (is (= "native_conflict" (fastify/error-code error)))
      (object/set error "statusCode" nil)
      (is (= 418 (fastify/error-status error 500)))
      (object/set error "statusCode" 0)
      (is (= 0 (fastify/error-status error 500)))))
  (testing "a missing message retains the existing string fallback"
    (let [handle #js {:status 422}]
      (is (= 422 (fastify/error-status handle 500)))
      (is (= (str handle) (fastify/error-message handle))))))

(deftest unclassified-exception-logging-preserves-the-native-error-and-key-only-data
  (let [error (ex-info "boom" {:mongo-uri "mongodb://user:pw@host"
                               :resource/file-path "/srv/private/secret.edn"})
        calls (atom [])
        original js/console.error]
    (set! js/console.error (fn [surface message keys] (swap! calls conj [surface message keys])))
    (try
      (fastify/log-unclassified-failure! "error-probe" error)
      (finally (set! js/console.error original)))
    (is (= 1 (count @calls)))
    (is (= "[error-probe] unclassified failure:" (ffirst @calls)))
    (is (= "boom" (second (first @calls))))
    (is (str/includes? (nth (first @calls) 2) ":mongo-uri"))
    (is (str/includes? (nth (first @calls) 2) ":resource/file-path"))
    (is (not (str/includes? (str @calls) "mongodb://")))
    (is (not (str/includes? (str @calls) "secret.edn")))
    (is (= {:mongo-uri "mongodb://user:pw@host"
            :resource/file-path "/srv/private/secret.edn"}
           (ex-data error)))))
