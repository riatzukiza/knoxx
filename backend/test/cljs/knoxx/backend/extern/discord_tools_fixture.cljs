(ns knoxx.backend.extern.discord-tools-fixture
  "Owned inert Discord API fixture; never reads credentials or uses network/files."
  (:require [knoxx.backend.domain.discord.rest-client :as rest]
            [knoxx.backend.domain.discord.tools :as discord]
            [knoxx.backend.domain.media :as media]
            [knoxx.backend.infra.actor.credentials :as credentials]
            [knoxx.backend.infra.auth.authz :as authz]
            [knoxx.backend.infra.clients.openplanner :as planner]
            [knoxx.backend.infra.svg-render :as svg]))

(def runtime :discord-fixture-runtime)
(def config {:fixture true})
(def clock-ms 1760000000000)

(defn- note! [world row]
  (swap! (:calls world) conj row))

(defn- buffer [values]
  (.from js/Buffer (clj->js values)))

(defn- fail! [message]
  (throw (js/Error. message)))

(defn- method-result [world op args fallback]
  (note! world {:op op :args args})
  (if (= op (get-in world [:options :method-error]))
    (fail! (str "fixture-" (name op) "-failed"))
    fallback))

(defn- form-row [form]
  {:payload (js->clj (js/JSON.parse (.get form "payload_json")) :keywordize-keys true)
   :files (->> (js/Array.from (.entries form))
               array-seq
               (remove #(= "payload_json" (aget % 0)))
               (mapv (fn [entry]
                       (let [file (aget entry 1)]
                         {:field (aget entry 0) :name (.-name file)
                          :mime-type (.-type file) :size (.-size file)}))))})

(defn- post-form! [world channel form]
  (let [index (inc (count (filter #(= :post (:op %)) @(:calls world))))
        response (cond-> {:id (str "sent-" index) :timestamp "fixture-time"}
                   (get-in world [:options :message-id-sentinel?]) (assoc :messageId "sentinel"))]
    (method-result world :post [channel (form-row form)] response)))

(defn- attachment-response [world url]
  (method-result world :attachment [url]
                 {:ok (get-in world [:options :http-ok?] true)
                  :status (get-in world [:options :http-status] 200)
                  :headers {"content-type" (get-in world [:options :http-type] "image/png")}
                  :body (.-buffer (js/Uint8Array.from
                                   (clj->js (get-in world [:options :http-bytes] [65 66]))))}))

(defn- client-for [world]
  (reify rest/IDiscordRestClient
    (channel-messages! [_ channel opts] (method-result world :messages [channel opts] (get-in world [:options :messages] [])))
    (current-user-guilds! [_] (method-result world :guilds [] (get-in world [:options :guilds] [])))
    (guild-channels! [_ guild] (method-result world :channels [guild] (get-in world [:options :channels guild] [])))
    (open-dm-channel! [_ user] (method-result world :dm [user] {:id "dm-channel"}))
    (create-channel-message! [_ channel payload] (method-result world :json-post [channel payload] {:id "json"}))
    (create-channel-message-form! [_ channel form] (post-form! world channel form))
    (add-reaction! [_ channel message emoji] (method-result world :reaction [channel message emoji] {}))
    (create-thread! [_ channel message payload] (method-result world :thread [channel message payload] {:id "thread-id"}))
    (fetch-attachment! [_ url] (attachment-response world url))))

(defn- rest-factory [world]
  (fn
    ([token] (note! world {:op :rest-client :token token}) (client-for world))
    ([token options] (note! world {:op :rest-client :token token :options options}) (client-for world))))

(defn- credential-reader [world]
  (fn [actual-runtime provider]
    (note! world {:op :credential :runtime actual-runtime :provider provider})
    (get-in world [:options :credential] {:secretJson {:botToken "inert-fixture-token"}})))

(defn- planner-factory [world]
  (fn
    ([options] (note! world {:op :planner-client :config options}) :inert-planner)
    ([options transport] (note! world {:op :planner-client :config options :transport transport}) :inert-planner)))

(defn- labels-reader [world]
  (fn [client ids]
    (note! world {:op :labels :client client :ids ids})
    (if (get-in world [:options :labels-error?])
      (fail! "fixture-labels-failed")
      {:labels (get-in world [:options :labels] {})})))

(defn- media-reader [world]
  (fn [actual-runtime actual-config source limit]
    (note! world {:op :local :runtime actual-runtime :config actual-config :source source :limit limit})
    (when (get-in world [:options :local-error?]) (fail! "fixture-local-refused"))
    {:filename (get-in world [:options :local-name] "local.svg")
     :mime-type (get-in world [:options :local-type] "image/svg+xml")
     :buffer (buffer (get-in world [:options :local-bytes] [60 115 118 103 47 62]))}))

(defn- renderer [world]
  (fn [source options]
    (note! world {:op :render :source source :options options})
    (when (get-in world [:options :render-error?]) (fail! "fixture-render-failed"))
    (buffer [80 78 71])))

(defn- size-contract [world original]
  (fn [size limit label]
    (note! world {:op :size :size size :limit limit :label label})
    (original size limit label)))

(defn- permission-check [world]
  (fn [context id]
    (note! world {:op :permission :context context :id id})
    (contains? (set (get-in world [:options :allow])) id)))

(defn- ^:async run-with-boundaries! [world action]
  (with-redefs [credentials/get-credential! (credential-reader world)
                rest/client (rest-factory world)
                planner/client (planner-factory world)
                planner/enabled? (fn [_] (get-in world [:options :planner-enabled?] true))
                planner/record-labels! (labels-reader world)
                media/load-media-source! (media-reader world)
                media/ensure-source-size! (size-contract world media/ensure-source-size!)
                media/workspace-media-max-bytes (get-in world [:options :max-bytes] media/workspace-media-max-bytes)
                svg/svg->png (renderer world)
                authz/ctx-tool-allowed? (permission-check world)]
    (await (action world))))

(defn ^:async with-world!
  "Restore native time/warning hooks even after an awaited action rejects."
  [options action]
  (let [world {:calls (atom []) :options options}
        original-now (.-now js/Date)
        original-warn (.-warn js/console)]
    (set! (.-now js/Date) (fn [] (get options :now clock-ms)))
    (set! (.-warn js/console)
          (fn [message error]
            (note! world {:op :warning :message message :error-message (.-message error)})))
    (try
      (await (run-with-boundaries! world action))
      (finally
        (set! (.-now js/Date) original-now)
        (set! (.-warn js/console) original-warn)))))

(defn calls "Read only the owned fixture call observations." [world]
  @(:calls world))

(defn calls-of "Read owned observations for one exact operation." [world op]
  (filterv #(= op (:op %)) (calls world)))

(defn tools "Construct the real current public runtime factory." [context]
  (discord/create-discord-custom-tools runtime config context))

(defn descriptors "Project real native tool metadata without execute handles." [native-tools]
  (mapv #(dissoc (js->clj % :keywordize-keys true) :execute) (array-seq native-tools)))

(defn- update-callback [world slot]
  (when slot
    (fn [payload]
      (note! world {:op :update :slot slot :payload (js->clj payload :keywordize-keys true)}))))

(defn ^:async execute!
  "Invoke the real native execute handle with all three legacy callback slots."
  [world native-tools id params slots]
  (let [tool (first (filter #(= id (.-name %)) (array-seq native-tools)))
        [a b c] (mapv #(update-callback world %) slots)]
    (assert tool (str "Missing real tool " id))
    (js->clj (await ((.-execute tool) "fixture-call" (clj->js params) a b c))
             :keywordize-keys true)))

(defn ^:async outcome!
  "Preserve explicit success/failure without suppressing an unobserved fixture call."
  [action]
  (try {:ok true :value (await (action))}
       (catch :default error {:ok false :message (.-message error)})))

(defn ^:async fetch! [channel options]
  (await (discord/discord-fetch-channel-messages! runtime config channel options)))

(defn ^:async labels! [messages]
  (await (discord/attach-openplanner-labels! config messages)))

(defn ^:async search! [scope options]
  (await (discord/discord-search-messages! runtime config scope options)))

(defn ^:async send! [channel text reply urls]
  (await (discord/discord-send-message! runtime config channel text reply urls)))

(defn ^:async attachment! [source index]
  (let [row (await (discord/fetch-discord-upload-attachment! runtime config source index))]
    {:name (:name row) :mime-type (:mimeType row)
     :bytes (vec (array-seq (:buffer row))) :text (.toString (:buffer row) "utf8")}))

(defn ^:async render-local! []
  (let [original (await (discord/fetch-discord-upload-attachment! runtime config "file:///fixture/local.svg" 0))
        result (await (discord/maybe-render-svg! original))]
    {:same? (identical? original result) :name (:name result) :mime-type (:mimeType result)
     :bytes (vec (array-seq (:buffer result)))}))

(defn ^:async react! [channel message emoji]
  (await (discord/discord-react! runtime channel message emoji)))

(defn ^:async thread! [channel message name archive]
  (await (discord/discord-thread-create! runtime channel message name archive)))

(defn ^:async channels! [guild]
  (await (discord/discord-list-channels! runtime guild)))

(defn iso "Build a deterministic timestamp from the owned fixed clock." [delta]
  (.toISOString (js/Date. (+ clock-ms delta))))

(defn svg-fence "Supply a literal fenced SVG without native objects crossing the test API." []
  (str (apply str (repeat 3 (char 96))) "svg\n<svg/>\n" (apply str (repeat 3 (char 96)))))
