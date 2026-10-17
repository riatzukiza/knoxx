(ns knoxx.backend.extern.discord
  "Discord boundary adapters.

   Owns Discord-specific JS runtime shapes: SDK status objects, multipart
   FormData/Blob bodies, promise array aggregation, runtime tool arrays, and raw
   tool parameter ingress. Domain Discord code should call these functions with
   CLJS maps/vectors and avoid generic JS helpers."
  (:require [clojure.string :as str]))

(defn gateway-started?
  [manager]
  (when (some? manager)
    (let [status (.status manager)]
      (boolean (or (aget status "ready")
                   (aget status "started"))))))

(defn ^:async promise-all-vector
  "Resolve a CLJS collection of promises into a CLJS vector of JS/CLJS results."
  [promises]
  (let [results (await (js/Promise.all (clj->js (vec promises))))]
    (vec (array-seq results))))

(defn message-form-data
  "Build Discord multipart message FormData from a CLJS payload and files.

   Files are CLJS maps with :name, :mimeType, and :buffer."
  [{:keys [payload files]}]
  (let [form (js/FormData.)]
    (.append form "payload_json" (.stringify js/JSON (clj->js payload)))
    (doseq [[idx file] (map-indexed vector (or files []))]
      (.append form
               (str "files[" idx "]")
               (js/Blob. #js [(:buffer file)] #js {:type (:mimeType file)})
               (:name file)))
    form))

(defn normalize-tool-params
  "Convert raw runtime params to a CLJS keyword map."
  [params]
  (cond
    (nil? params) {}
    (map? params) params
    :else (js->clj params :keywordize-keys true)))

(defn tool-array
  "Convert a CLJS vector of runtime tool objects into the JS array expected by eta-mu."
  [tools]
  (clj->js (vec tools)))

(defn trim-path-delims
  [s]
  (-> (str (or s ""))
      (str/replace #"<\\|\"|\"[|>]" "")
      str/trim))

(defn error "Construct the same native Discord workflow error." [message]
  (js/Error. message))

(defn positive-hours
  "Preserve Number coercion and the existing positive-hours fallback."
  [value default-hours]
  (let [n (js/Number value)]
    (if (and (not (js/isNaN n)) (pos? n)) n default-hours)))

(defn timestamp-ms "Parse the existing Discord timestamp representation." [value]
  (let [ms (.parse js/Date (str (or value "")))]
    (when-not (js/isNaN ms) ms)))

(defn now-ms "Observe the current time at the caller's existing boundary." []
  (.now js/Date))

(defn upload-pathname
  "Decode a source URL with explicit fallback data, never a hidden parse error."
  [source]
  (try
    {:ok true :pathname (.-pathname (js/URL. (str source)))}
    (catch :default parse-error
      {:ok false :pathname "" :parse-error? (some? parse-error)})))

(defn file-url-path
  "Preserve URL/URI decoding and the original literal file-prefix fallback."
  [source]
  (try
    {:ok true :path (js/decodeURIComponent (.-pathname (js/URL. source)))}
    (catch :default parse-error
      {:ok false :path (subs source (count "file://")) :parse-error? (some? parse-error)})))

(defn buffer-text "Decode an opaque SVG buffer as UTF8." [buffer]
  (.toString buffer "utf8"))

(defn upload-buffer "Decode the current base64 or URI data-URL payload." [payload base64?]
  (if base64?
    (.from js/Buffer payload "base64")
    (.from js/Buffer (js/decodeURIComponent payload) "utf8")))

(defn body-buffer "Copy an attachment response body to the current Buffer representation." [body]
  (.from js/Buffer body))

(defn buffer-length "Observe the byte length used by the existing media size contract." [buffer]
  (.-length buffer))

(defn svg-data-url "Encode the same inline SVG attachment URL." [svg]
  (str "data:image/svg+xml;charset=utf-8," (js/encodeURIComponent svg)))

(defn warn-label-lookup! "Preserve the existing label warning before fail-closed fallback." [error]
  (.warn js/console "[discord-tools] OpenPlanner label lookup failed; failing closed to avoid surfacing crossed/bad messages" error))

(defn warn-svg-render! "Preserve the existing renderer warning before original attachment fallback." [error]
  (.warn js/console "[discord-tools] SVG render failed; uploading original SVG" error))
