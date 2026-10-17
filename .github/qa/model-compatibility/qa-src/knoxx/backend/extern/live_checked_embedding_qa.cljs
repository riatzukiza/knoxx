(ns knoxx.backend.extern.live-checked-embedding-qa
  "QA-only use of genuine SDK scope resolvers and Knoxx's checked provider."
  (:require ["@open-hax/openplanner-sdk/config" :as sdk-config]
            ["@open-hax/openplanner-sdk/embedding-runtime" :as sdk-embedding]
            [knoxx.backend.extern.embedding :as embedding]
            [knoxx.backend.extern.json :as xjson]
            [knoxx.backend.law.embedding :as law]))

(defn ^:async run
  "Perform one actual hot or compact embedding; the owning process cap is 15s. No SDK provider factory, Mongo client, test seam or mock is invoked."
  [tier-name]
  (when-not (contains? #{"hot" "compact"} tier-name)
    (throw (ex-info "QA tier must be hot or compact" {:code "qa_tier_invalid"})))
  (let [configuration (embedding/configuration!)
        ^js native (sdk-embedding/createEmbeddingRuntime (sdk-config/loadConfig))
        scope #js {:source "owned-live-embedding-qa"
                   :kind "checked-embedding" :project "owned-live-embedding-qa"}
        ^js native-hot (.-hot native)
        native-hot-model (law/assert-model! (.getModel native-hot scope))
        ^js native-compact (.-compact native)
        native-compact-model (law/assert-model! (.getModel native-compact))
        checked (embedding/runtime native configuration)
        ^js checked-handle (:handle checked)
        ^js tier (if (= tier-name "hot") (.-hot checked-handle) (.-compact checked-handle))
        selected (if (= tier-name "hot") (.getModel tier scope) (.getModel tier))
        ^js provider (if (= tier-name "hot")
                   (.getBackgroundEmbeddingFunction tier scope)
                   (.getBackgroundEmbeddingFunction tier))
        vectors (xjson/to-cljs
                 (await (.generate provider #js ["knoxx deployment embedding probe"])))]
    (clj->js
     (embedding/assert-complete!
      checked
      {:tier tier-name :selected-model selected
       :actual-sdk-hot-model native-hot-model
       :actual-sdk-compact-model native-compact-model
       :vector-count (count vectors) :widths (mapv count vectors)
       :vectors vectors}))))
