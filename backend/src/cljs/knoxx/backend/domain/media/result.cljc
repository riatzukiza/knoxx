(ns knoxx.backend.domain.media.result
  "Portable projections of already-decoded media and generation result data.")

(defn content-part
  "Retain supplied content coordinates without reading an opaque buffer."
  [part-type data-url mime-type filename size]
  {:type part-type
   :data data-url
   :mimeType mime-type
   :filename filename
   :size size})

(defn attachment-label
  "Describe the existing part type, retaining the generic fallback."
  [part-type]
  (case part-type
    "image" "image"
    "audio" "audio file"
    "video" "video"
    "document" "document"
    "file"))

(defn- blaze-endpoint
  [modality]
  (case modality
    "image" "/v1/images/generations"
    "video" "/v1/videos/generations"
    "music" "/v1/music/generations"
    "tts" "/v1/audio/speech"
    "/v1/chat/completions"))

(defn blaze-context
  "Project decoded request coordinates for the owning workflow's first log."
  [tool-name tool-call-id modality explicit-model? models prompt-chars output-path base-url]
  {:tool tool-name
   :tool_call_id tool-call-id
   :modality modality
   :explicit_model explicit-model?
   :candidate_models models
   :prompt_chars prompt-chars
   :output_path output-path
   :endpoint (blaze-endpoint modality)
   :proxx_base_url base-url})

(defn blaze-completion
  "Extend the original trace after the owning workflow finishes its writes."
  [log-context model attempts saved response-artifact]
  (assoc log-context
         :model model
         :attempts attempts
         :saved_asset (boolean saved)
         :asset_workspace_path (:workspace-path saved)
         :response_artifact_workspace_path (:workspace-path response-artifact)))

(defn blaze-generation-result
  "Return the original message and details with actual outcome values held."
  [modality model attempts text saved response-artifact]
  [(str "Proxx Blaze " modality " response from " model
        (if saved
          (str ". Saved asset: " (:workspace-path saved) " (" (:bytes saved) " bytes). Use workspace_media.attach to embed it.")
          (str ". No media URL/data URL was found; saved JSON response: " (:workspace-path response-artifact) ".")))
   (cond-> {:provider "blaze"
            :via "proxx"
            :endpoint (blaze-endpoint modality)
            :modality modality
            :model model
            :attempts attempts
            :response_text text}
     saved (assoc :asset saved)
     response-artifact (assoc :response_artifact response-artifact))])
