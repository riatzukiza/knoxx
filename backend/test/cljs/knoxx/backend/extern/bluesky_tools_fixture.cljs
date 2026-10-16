(ns knoxx.backend.extern.bluesky-tools-fixture
  "Owned inert Bluesky boundary fixture; never reads credential files or sends HTTP."
  (:require [knoxx.backend.domain.bluesky.bluesky :as decisions]
            [knoxx.backend.domain.bluesky.client :as client]
            [knoxx.backend.domain.media :as media]
            [knoxx.backend.extern.fetch :as fetch]
            [knoxx.backend.infra.actor.credentials :as credentials]
            [knoxx.backend.infra.auth.authz :as authz]
            [knoxx.backend.infra.bluesky.api :as api]
            [knoxx.backend.infra.bluesky.tools :as factory]))

(def runtime :bluesky-fixture-runtime)
(def config {:fixture true})
(def instant "2026-01-02T03:04:05.000Z")
(def session {:did "did:fixture:self" :handle "self.test" :accessJwt "inert-token"})
(def post-ref {:uri "at://did:parent/app.bsky.feed.post/parent" :cid "parent-cid"})

(defn- note! [world row]
  (swap! (:calls world) conj row))

(defn- fail! [message]
  (throw (js/Error. message)))

(defn- result! [world op args fallback]
  (note! world {:op op :args args})
  (when (= op (get-in world [:options :method-error]))
    (fail! (str "fixture-" (name op) "-failed")))
  (get-in world [:options :responses op] fallback))

(defn- client-for [world]
  (reify client/IBlueskyClient
    (create-session! [_ credential] (result! world :session [credential] session))
    (upload-blob! [_ s buffer mime] (result! world :upload [s buffer mime] {:blob {:ref buffer}}))
    (create-record! [_ s collection record]
      (result! world :create [s collection record] {:uri "at://did:self/collection/new" :cid "new-cid"}))
    (delete-record! [_ s collection rkey] (result! world :delete [s collection rkey] {}))
    (resolve-post! [_ uri] (result! world :resolve [uri] post-ref))
    (profile! [_ actor] (result! world :profile [actor] {}))
    (search-posts! [_ s query limit] (result! world :posts [s query limit] {}))
    (search-actors! [_ s query limit] (result! world :actors [s query limit] {}))
    (actor-feed! [_ s actor limit] (result! world :feed [s actor limit] {}))
    (timeline! [_ s opts] (result! world :timeline [s opts] {}))
    (thread! [_ s uri depth] (result! world :thread [s uri depth] {}))
    (notifications! [_ s opts] (result! world :notifications [s opts] {}))
    (followers! [_ actor limit] (result! world :followers [actor limit] {}))
    (follows! [_ actor limit] (result! world :follows [actor limit] {}))
    (chat-list! [_ s opts] (result! world :chat-list [s opts] {}))
    (chat-read! [_ s convo opts] (result! world :chat-read [s convo opts] {}))
    (chat-send! [_ s convo payload] (result! world :chat-send [s convo payload] {:id "dm-id"}))
    (chat-react! [_ s convo message emoji] (result! world :chat-react [s convo message emoji] {}))))

(defn- client-factory [world original]
  (fn client-double
    ([] (client-double {}))
    ([_options]
     (note! world {:op :client})
     (if (get-in world [:options :transport?])
       ;; The original multi-arity wrapper dispatches through its Var too.
       ;; Restore it only for this synchronous inert constructor call.
       (with-redefs [client/client original]
         (original {:http-client :inert-http-client}))
       (client-for world)))))

(defn- credential-reader [world]
  (fn [actual-runtime provider]
    (note! world {:op :credential :runtime actual-runtime :provider provider})
    (when (get-in world [:options :credential-error?]) (fail! "fixture-credential-denied"))
    (get-in world [:options :credential]
            {:secretJson {:identifier "fixture.test" :appPassword "inert-password"}})))

(defn- permission-check [world]
  (fn [context id]
    (note! world {:op :permission :context context :id id})
    (contains? (get-in world [:options :allow] #{}) id)))

(defn- transport [world]
  (fn [http-client request]
    (note! world {:op :http :client http-client :request request})
    {:ok true :body (if (.endsWith (:url request) "createSession")
                      session (get-in world [:options :http-body] {}))}))

(defn- image-source [source]
  {:buffer source :mime-type "image/fixture"})

(defn- media-reader [world]
  (fn [actual-runtime actual-config source limit]
    (note! world {:op :media :runtime actual-runtime :config actual-config :source source :limit limit})
    (when (= source (get-in world [:options :media-error])) (fail! "fixture-image-refused"))
    (if (get-in world [:options :defer-images?])
      (js/Promise. (fn [resolve reject]
                     (swap! (:pending world) assoc source {:resolve resolve :reject reject})))
      (image-source source))))

(defn- ^:async run-boundaries! [world action]
  (let [original-client client/client]
    (with-redefs [client/client (client-factory world original-client)
                  credentials/get-credential! (credential-reader world)
                  media/load-media-source! (media-reader world)
                  fetch/json! (transport world)
                  authz/ctx-tool-allowed? (permission-check world)]
      (await (action world)))))

(defn ^:async with-world!
  "Restore the owned ISO clock hook after the entire awaited action settles."
  [options action]
  (let [world {:options options :calls (atom []) :pending (atom {})}
        prototype (.-prototype js/Date)
        original (.-toISOString prototype)]
    (set! (.-toISOString prototype)
          (fn [] (note! world {:op :clock}) instant))
    (try (await (run-boundaries! world action))
         (finally (set! (.-toISOString prototype) original)))))

(defn calls "Read observations from the current owned fixture." [world]
  @(:calls world))

(defn calls-of "Select observations for an exact positive invocation guard." [world op]
  (filterv #(= op (:op %)) (calls world)))

(defn tools "Construct the real original factory, including permission filtering." [context]
  (factory/create-bluesky-custom-tools runtime config context))

(defn descriptors "Decode real native tool metadata, excluding callable handles." [native-tools]
  (mapv #(dissoc (js->clj % :keywordize-keys true) :execute) (array-seq native-tools)))

(defn schemas "Return the thirteen actual public parameter schema values." []
  [decisions/publish-params decisions/profile-params decisions/search-params decisions/feed-params
   decisions/timeline-params decisions/post-uri-params decisions/actor-params decisions/thread-params
   decisions/notifications-params decisions/chat-send-params decisions/chat-list-params
   decisions/chat-read-params decisions/chat-react-params])

(defn- update-callback [world slot]
  (when slot
    (fn [payload]
      (note! world {:op :update :slot slot :payload (js->clj payload :keywordize-keys true)}))))

(defn ^:async execute!
  "Invoke a real factory callback using native parameters and legacy update slots."
  [world native-tools id params slots]
  (let [tool (first (filter #(= id (.-name %)) (array-seq native-tools)))
        [a b c] (mapv #(update-callback world %) slots)]
    (assert tool (str "Missing real tool " id))
    (js->clj (await ((.-execute tool) "fixture-call" (clj->js params) a b c))
             :keywordize-keys true)))

(defn ^:async outcome!
  "Report an exact failure message; tests must separately guard provider invocation."
  [action]
  (try {:ok true :value (await (action))}
       (catch :default error {:ok false :message (.-message error)})))

(defn ^:async auth! []
  (await (api/bluesky-auth-config! runtime)))

(defn ^:async publish! [text images alts reply]
  (await (api/bluesky-publish! runtime config text images alts reply)))

(defn ^:async profile! [actor]
  (await (api/bluesky-profile! runtime actor)))

(defn ^:async search! [query kind limit]
  (await (api/bluesky-search! runtime query kind limit)))

(defn ^:async feed! [actor limit]
  (await (api/bluesky-author-feed! actor limit)))

(defn ^:async timeline! [limit cursor]
  (await (api/bluesky-timeline! runtime limit cursor)))

(defn ^:async thread! [uri depth]
  (await (api/bluesky-thread! uri depth)))

(defn ^:async followers! [actor limit]
  (await (api/bluesky-followers! actor limit)))

(defn ^:async follows! [actor limit]
  (await (api/bluesky-follows! actor limit)))

(defn ^:async reply! [uri]
  (await (api/resolve-reply-refs! uri)))

(defn ^:async reverse-image-completion!
  "Resolve image b before a through real concurrent upload orchestration."
  [world]
  (let [result (api/load-and-upload-images! runtime config session ["a" "b"] ["alt-a" "alt-b"])]
    (assert (= #{"a" "b"} (set (keys @(:pending world)))) "Both media loads must start before either resolves")
    ((get-in @(:pending world) ["b" :resolve]) (image-source "b"))
    (await (js/Promise.resolve))
    ((get-in @(:pending world) ["a" :resolve]) (image-source "a"))
    (await result)))

