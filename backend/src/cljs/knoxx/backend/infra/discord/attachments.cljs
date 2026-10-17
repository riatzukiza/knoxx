(ns knoxx.backend.infra.discord.attachments
  "Discord attachment acquisition and SVG rendering through existing media boundaries."
  (:require [clojure.string :as str]
            [knoxx.backend.domain.discord.rest-client :as discord-rest]
            [knoxx.backend.domain.discord.tools :as decisions]
            [knoxx.backend.domain.media :as media]
            [knoxx.backend.domain.text :as text]
            [knoxx.backend.extern.discord :as xdiscord]
            [knoxx.backend.infra.svg-render :as svg-render]))

(defn- svg-buffer->png-buffer!
"Render an SVG buffer to PNG using headless Chromium. Returns a promise."
[svg-buffer]
(let [svg-str (text/sanitize-svg-content (xdiscord/buffer-text svg-buffer))]
  (svg-render/svg->png svg-str {:width 600 :height 300})))

(defn ^:async maybe-render-svg!
"If the resolved attachment is an SVG, render it to PNG transparently.
   On render failure, returns original attachment."
  [{:keys [name mimeType buffer] :as attachment}]
  (if (or (= mimeType "image/svg+xml")
          (some-> name str/lower-case (str/ends-with? ".svg")))
    (try
      (let [png-buf (await (svg-buffer->png-buffer! buffer))]
        {:name (if (some-> name str/lower-case (str/ends-with? ".svg"))
                 (str/replace name #"(?i)\.svg$" ".png")
                 (str (or name "attachment") ".png"))
         :mimeType "image/png"
         :buffer png-buf})
      (catch :default error
        (xdiscord/warn-svg-render! error)
        attachment))
    attachment))

(defn- data-url-upload-attachment [source idx]
  (let [data-start (or (str/index-of source ",") -1)
        metadata (when (>= data-start 0) (subs source 5 data-start))
        payload (when (>= data-start 0) (subs source (inc data-start)))]
    (when (or (nil? metadata) (nil? payload))
      (throw (xdiscord/error "Invalid data URL attachment")))
    (let [metadata-parts (str/split metadata #";")
          mime-type (media/sanitize-mime-type (first metadata-parts) "application/octet-stream")
          base64? (boolean (some #{"base64"} (rest metadata-parts)))
          buffer (xdiscord/upload-buffer payload base64?)]
      (media/ensure-source-size! (xdiscord/buffer-length buffer) media/workspace-media-max-bytes "Discord attachment")
      {:name (str "attachment-" idx (media/mime-type->extension mime-type))
       :mimeType mime-type
       :buffer buffer})))

(defn ^:async http-upload-attachment! [source idx]
  (let [{:keys [ok status headers body]} (await (discord-rest/fetch-attachment! (discord-rest/client nil) source))]
    (if ok
      (let [buffer (xdiscord/body-buffer body)
            mime-type (media/sanitize-mime-type (get headers "content-type")
                                                (media/workspace-media-mime-type source))]
        (media/ensure-source-size! (xdiscord/buffer-length buffer) media/workspace-media-max-bytes "Discord attachment")
        {:name (decisions/infer-upload-filename (:pathname (xdiscord/upload-pathname source)) idx)
         :mimeType mime-type
         :buffer buffer})
      (throw (xdiscord/error (str "Attachment fetch failed " status))))))

(defn ^:async local-upload-attachment! [runtime config source idx]
  ;; Local file path — resolve through shared workspace media rules so
  ;; @-prefixed, workspace-relative, and allowed absolute paths work, while
  ;; paths outside allowed media roots are rejected before Discord sees them.
  (let [raw-source (if (media/source-file-url? source)
                     (:path (xdiscord/file-url-path source))
                     source)
        loaded (await (media/load-media-source! runtime config raw-source media/workspace-media-max-bytes))]
    {:name (or (:filename loaded)
               (str "attachment-" idx (media/mime-type->extension (:mime-type loaded))))
     :mimeType (media/sanitize-mime-type (:mime-type loaded) "application/octet-stream")
     :buffer (:buffer loaded)}))

(defn ^:async fetch-discord-upload-attachment!
  "Fetch an attachment from a URL, data URL, or local file path.
   Returns a promise resolving to {:name :mimeType :buffer}.
   SVG files are automatically rendered to PNG before upload."
  [runtime config url idx]
  (let [source (str (or url ""))]
    (cond
      (str/blank? source) (throw (xdiscord/error "Empty attachment source"))
      (media/source-data-url? source) (data-url-upload-attachment source idx)
      (media/source-http-url? source) (await (http-upload-attachment! source idx))
      :else (await (local-upload-attachment! runtime config source idx)))))
