(ns knoxx.backend.extern.openplanner-sdk-query-fixture
  "Owned native cursor seam for the genuine pinned SDK query fallback."
  (:require ["@open-hax/openplanner-sdk" :as sdk-mod]
            [knoxx.backend.extern.openplanner-sdk :as xsdk]))

(defn- native-query-cursor
  "Keep actual cursor chaining and separately controlled native/fallback reads."
  [rows failure]
  (let [cursor #js {:toArray (^:async fn []
                              (when failure (throw failure))
                              (clj->js rows))}]
    (aset cursor "sort" (fn [& _] cursor))
    (aset cursor "limit" (fn [& _] cursor))
    cursor))

(defn ^:async native-query-outcome
  "Invoke unchanged Knoxx query through the genuine SDK's native fallback core."
  [outcome! native-rows native-error fallback-rows]
  (let [sdk (await (sdk-mod/createOpenPlannerSdk))
        db (.-db (.-mongo sdk))
        original-collection (.-collection db)
        reads (atom [])
        collection #js {:collectionName "owned-native-fallback"
                        :aggregate (fn [_]
                                     (swap! reads conj :aggregate)
                                     (native-query-cursor native-rows native-error))
                        :find (fn [_]
                                (swap! reads conj :fallback)
                                (native-query-cursor fallback-rows nil))}]
    (sdk-mod/__setNativeQueryCore true)
    (sdk-mod/__setPartitions
     (clj->js [{:tier "hot" :model "qwen3-embedding:8b" :dimensions 1024
               :collectionName "owned-native-fallback" :searchIndexName "owned-index"
               :searchIndexStatus "ready"}]))
    (try
      (aset db "collection" (fn [& _] collection))
      {:result (await (outcome! #(xsdk/vector-search!
                                         {:q "owned full query" :tier "hot" :quality "any"})))
       :reads @reads}
      (finally
        (aset db "collection" original-collection)
        (sdk-mod/__setNativeQueryCore false)))))
