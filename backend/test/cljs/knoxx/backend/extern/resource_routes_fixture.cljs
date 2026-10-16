(ns knoxx.backend.extern.resource-routes-fixture
  "Inert native resource route characterization; no disk, server or provider activity."
  (:require ["node:fs" :as node-fs]
            ["node:fs/promises" :as fsp]
            [knoxx.backend.domain.error-observatory :as observatory]
            [knoxx.backend.domain.resources.loader :as resources]
            [knoxx.backend.infra.event-runtime :as event-runtime]
            [knoxx.backend.infra.publication-admission-hook :as admission-hook]
            [knoxx.backend.infra.routes.resources :as routes]))

(defonce ^:private active-world* (atom nil))

(defn resolved [value] (js/Promise.resolve value))

(defn rejected [error] (js/Promise.reject error))

(defn native-error [message code]
  (let [error (js/Error. message)] (aset error "code" code) error))

(defn deferred []
  (let [resolve* (atom nil) reject* (atom nil)
        promise (js/Promise. (fn [resolve reject] (reset! resolve* resolve) (reset! reject* reject)))]
    {:promise promise :resolve! (fn [value] (@resolve* value)) :reject! (fn [error] (@reject* error))}))

(defn ^:async flush! []
  (dotimes [_ 12] (await (resolved nil))))

(defn world
  "Fresh fixture authority; every mutation is confined to these atoms."
  ([] (world {}))
  ([options]
   {:state (atom (merge {:files {} :records [] :roots ["/one" "/one" "/missing" "/two"]
                         :existing-roots #{"/one" "/two"} :close-errors {}} options))
    :calls (atom []) :watchers (atom []) :close-attempts (atom []) :timers (atom {})
    :timer-id (atom 0) :observations (atom []) :cleanup (atom [])}))

(defn calls [world] @(:calls world))

(defn state [world] @(:state world))

(defn update-state! [world f & args] (apply swap! (:state world) f args))

(defn- record! [world value] (swap! (:calls world) conj value))

(defn- read-file! [world path]
  (record! world [:read path])
  (let [data (state world)]
    (cond (:read-error data) (rejected (:read-error data))
          (contains? (:files data) path) (resolved (get-in data [:files path]))
          :else (rejected (native-error "fixture missing" "ENOENT")))))

(defn- write-file! [world path text]
  (record! world [:write path text])
  (if-let [error (:write-error (state world))]
    (rejected error)
    (do (update-state! world assoc-in [:files path] text) (resolved nil))))

(defn- delete-file! [world path]
  (record! world [:delete path])
  (if-let [error (:delete-error (state world))]
    (rejected error)
    (do (update-state! world update :files dissoc path) (resolved nil))))

(defn write-deps
  "The product's existing optional dependency boundary, with ordered effect traces."
  [world]
  {:read-file! (partial read-file! world)
   :write-file! (partial write-file! world)
   :delete-file! (partial delete-file! world)
   :sync-index! (fn [_config]
                  (record! world [:sync])
                  (if-let [result (:sync-result (state world))] (resolved result) (resolved {:ok true})))})

(defn- fixture-path
  ([config id] (fixture-path config :agent id))
  ([_config kind id] (str "/fixture/" (resources/resource-class kind) "/" id ".edn")))

(defn- load-records! [world _config]
  (record! world [:load])
  (if-let [error (:load-error (state world))]
    (rejected error)
    (resolved (:records (state world)))))

(defn- replace-var! [get-current set-current! replacement]
  (let [prior (get-current)]
    (set-current! replacement)
    (fn []
      (when-not (identical? replacement (get-current))
        (throw (ex-info "Resource fixture public-var ownership drift" {})))
      (set-current! prior))))

(defn- install-loader! [world own!]
  (own! (replace-var! (fn [] resources/load-all-resources!)
                       (fn [value] (set! resources/load-all-resources! value)) (partial load-records! world)))
  (own! (replace-var! (fn [] resources/resource-root-paths)
                       (fn [value] (set! resources/resource-root-paths value)) (fn [_] (:roots (state world)))))
  (own! (replace-var! (fn [] resources/resource-file-path)
                       (fn [value] (set! resources/resource-file-path value)) fixture-path))
  (own! (replace-var! (fn [] resources/write-edn-file!)
                       (fn [value] (set! resources/write-edn-file! value)) (partial write-file! world)))
  (own! (replace-var! (fn [] event-runtime/debounced-reload!)
                       (fn [value] (set! event-runtime/debounced-reload! value)) (fn [] (record! world [:reload])))))

(defn- replace-property! [target name replacement]
  (let [descriptor (.getOwnPropertyDescriptor js/Object target name)]
    (when-not (and descriptor (aget descriptor "configurable") (fn? (aget descriptor "value")))
      (throw (ex-info "Unsupported fixture public-property descriptor" {:property name})))
    (.defineProperty js/Object target name (js/Object.assign #js {} descriptor #js {:value replacement}))
    (fn []
      (when-not (identical? replacement (aget target name))
        (throw (ex-info "Fixture property ownership drift" {:property name})))
      (.defineProperty js/Object target name descriptor))))

(defn- watcher [world root callback]
  (let [self (atom nil)
        handle (js-obj "close"
                       (fn []
                         (this-as receiver
                           (when-not (identical? receiver @self)
                             (throw (ex-info "Watcher receiver changed" {}))))
                         (swap! (:close-attempts world) conj root)
                         (let [errors (:close-errors (state world))]
                           (when (contains? errors root) (throw (get errors root))))))]
    (reset! self handle)
    (swap! (:watchers world) conj {:root root :callback callback :handle handle})
    handle))

(defn emit! [world root event filename]
  (let [entry (first (filter #(= root (:root %)) @(:watchers world)))]
    (when-not entry (throw (ex-info "No owned watcher for event" {:root root})))
    ((:callback entry) event filename)))

(defn ^:async fire-timer! [world]
  (let [[id callback] (first (sort-by key @(:timers world)))]
    (when-not callback (throw (ex-info "No owned pending resource timer" {})))
    (swap! (:timers world) dissoc id)
    (await (callback))))

(defn- install-watch! [world own!]
  (own! (replace-property! node-fs "existsSync" (fn [root] (contains? (:existing-roots (state world)) root))))
  (own! (replace-property! node-fs "watch"
                          (fn [root options callback]
                            (record! world [:watch root (js->clj options :keywordize-keys true)])
                            (let [errors (:watch-errors (state world))]
                              (when (contains? errors root) (throw (get errors root)))
                              (watcher world root callback)))))
  (own! (replace-property! js/globalThis "setTimeout"
                          (fn [callback milliseconds]
                            (let [id (swap! (:timer-id world) inc)]
                              (record! world [:timer milliseconds])
                              (swap! (:timers world) assoc id callback) id))))
  (own! (replace-property! js/globalThis "clearTimeout"
                          (fn [id] (record! world [:clear-timer id]) (swap! (:timers world) dissoc id)))))

(defn- install-files! [world own!]
  (own! (replace-property! fsp "readFile"
                          (fn ([path] (read-file! world path))
                            ([path _encoding] (read-file! world path)))))
  (own! (replace-property! fsp "unlink" (fn [path] (delete-file! world path)))))

(defn- install-admission! [world]
  (replace-var! (fn [] admission-hook/admit!)
                (fn [value] (set! admission-hook/admit! value))
                (fn [scope selection]
                  (record! world [:admit scope selection])
                  (if-let [error (:admit-error (state world))]
                    (rejected error)
                    (resolved (or (:admit-result (state world)) {:ok true :admitted 1 :failed 0}))))))

(defn- install-observer! [world]
  (replace-var! (fn [] observatory/log-warning!)
                (fn [value] (set! observatory/log-warning! value))
                (fn [boundary context]
                  (swap! (:observations world) conj [boundary context])
                  (let [data (state world)]
                    (when (contains? data :observer-error) (throw (:observer-error data))))
                  {:ok false :failed false :boundary boundary :context context})))

(defn- attempt-cleanup! [world f]
  (try (f)
       (catch :default error
         (swap! (:cleanup world) conj {:failed? true :error error}))))

(defn- cleanup! [world restorers]
  (when (seq @(:watchers world)) (attempt-cleanup! world routes/stop-resource-watcher!))
  (doseq [restore (reverse @restorers)] (attempt-cleanup! world restore))
  (when (seq @(:timers world))
    (swap! (:cleanup world) conj {:failed? true :error :fixture-resource-timer-leaked})))

(defn ^:async with-world!
  "Hold only named test replacements through await; restore all, preserving primary nil/error."
  [world task]
  (when-not (compare-and-set! active-world* nil world)
    (throw (ex-info "Resource fixture overlap or retained cleanup refusal" {})))
  (let [restorers (atom []) own! #(swap! restorers conj %)
        outcome (try
                  (install-loader! world own!)
                  (install-files! world own!)
                  (install-watch! world own!)
                  (own! (install-observer! world))
                  (own! (install-admission! world))
                  {:failed? false :value (await (task))}
                  (catch :default error {:failed? true :error error}))]
    (await (flush!))
    (cleanup! world restorers)
    (when (and (empty? @(:cleanup world)) (not (compare-and-set! active-world* world nil)))
      (swap! (:cleanup world) conj {:failed? true :error :fixture-owner-drift}))
    (when (:failed? outcome) (throw (:error outcome)))
    (when (seq @(:cleanup world)) (throw (ex-info "Resource fixture cleanup failed" {:cleanup @(:cleanup world)})))
    (:value outcome)))

(defn- malformed-query-value
  [variant]
  (case variant
    :null-prototype (.create js/Object nil)
    :throwing-to-string (js-obj "toString" (fn [] (throw (js/Error. "fixture coercion refused"))))
    (throw (ex-info "Unknown malformed native query fixture" {:variant variant}))))

(defn request
  "Real native values, including explicit adverse query scalars, without a server."
  [{:keys [query body params native-query]}]
  (let [value (js-obj "query" (clj->js (or query {})) "body" (clj->js body)
                      "params" (js/Object.assign (.create js/Object nil) (clj->js (or params {}))))]
    (doseq [[key variant] native-query]
      (aset (aget value "query") key (malformed-query-value variant)))
    value))

(defn- reply [captured]
  (let [self (atom nil)
        value (js-obj "status" (fn [status] (swap! captured assoc :status status) @self)
                      "end" (fn [receiver text headers]
                              (this-as self-receiver
                                (swap! captured assoc :method-receiver? (identical? self-receiver @self)))
                              (swap! captured assoc :receiver? (identical? receiver @self)
                                     :body text :headers (js->clj headers)) @self))]
    (reset! self value) value))

(defn registration
  "Own a registration table/context and record real helper invocation order."
  [ctx denied]
  (let [table (atom []) effects (atom [])]
    {:table table :effects effects
     :helpers {:route! (fn [_app method path handler] (swap! table conj [method path handler]))
               :json-response! (fn [reply status body]
                                 (swap! (aget reply "fixtureCapture") assoc :status status :body body))
               :error-response! (fn [reply error]
                                  (swap! (aget reply "fixtureCapture") assoc :status (:status (ex-data error)) :error error))
               :with-request-context! (fn [_runtime _request _reply callback]
                                        (swap! effects conj [:context]) (callback ctx))
               :ensure-permission! (fn [_ctx permission]
                                     (swap! effects conj [:permission permission])
                                     (when (contains? denied permission)
                                       (throw (ex-info "fixture permission denied" {:status 403 :permission permission}))))}}))

(defn ^:async invoke-route!
  "Invoke actual closures with a native reply; keep request/reply conversion at this edge."
  [registration method path request-data]
  (let [entry (first (filter #(= [method path] (subvec % 0 2)) @(:table registration)))
        captured (atom {}) native-reply (reply captured)]
    (when-not entry (throw (ex-info "Route missing from actual registration" {:method method :path path})))
    (aset native-reply "fixtureCapture" captured)
    (await ((nth entry 2) (request request-data) native-reply))
    @captured))
