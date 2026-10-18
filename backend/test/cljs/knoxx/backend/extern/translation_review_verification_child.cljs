(ns knoxx.backend.extern.translation-review-verification-child
  "Opt-in NBB process control for the real adapter with an owned inert store.

   Compiled unit helpers and their js* or async with-redefs bodies are excluded
   from this import graph. Only the shared inert native client is reused."
  (:require [knoxx.backend.extern.node-cli :as cli]
            [knoxx.backend.extern.openplanner-translation-mongo.verification-fixture :as native]
            [knoxx.backend.extern.translation-review-verification-client :as driver]
            [knoxx.backend.law.translation-review-verification-fixture :as law]
            ["mongodb" :refer [ObjectId]]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(defn- read-json [filename]
  (js->clj (js/JSON.parse (.readFileSync fs filename "utf8")) :keywordize-keys true))

(defn- durable-json! [filename value flags]
  (let [descriptor (.openSync fs filename flags 384)]
    (try (.writeFileSync fs descriptor (js/JSON.stringify (clj->js value)))
         (.fsyncSync fs descriptor)
         (finally (.closeSync fs descriptor)))))

(defn- store-rows [store]
  (when-not (and (= 1 (:version store))
                 (map? (:collections store))
                 (every? #{:translation_segments :translation_labels} (keys (:collections store))))
    (throw (ex-info "Invalid synthetic fixture store" {})))
  (into {} (map (fn [[name rows]]
                  [(cljs.core/name name)
                   (mapv #(update % :_id (fn [id] (ObjectId. (law/assert-valid! ::law/segment-id id)))) rows)]))
        (:collections store)))

(defn- persist-store! [world store-file]
  (durable-json!
   store-file
   {:version 1
    :collections (into {} (map (fn [[name rows]]
                                 [name (mapv #(update % :_id driver/id-string) rows)])) @(:rows* world))
    :operations @(:operations* world)}
   "w"))

(defn- ^:async pause-after-delete! [world store-file marker mode]
  (persist-store! world store-file)
  (when (= "pause-after-label-delete" mode)
    (durable-json! marker {:phase "after-owned-label-delete" :store_file store-file} "wx")
    (let [timer* (atom nil)]
      (try
        (await (js/Promise.
                (fn [_resolve reject]
                  (reset! timer* (js/setTimeout
                                 #(reject (ex-info "Synthetic child pause deadline elapsed" {}))
                                 15000)))))
        (finally (when @timer* (js/clearTimeout @timer*)))))))

(defn- ^:async child! [[request-file store-file marker mode :as arguments]]
  (when-not (and (= 4 (count arguments))
                 (every? #(.isAbsolute path %) [request-file store-file marker])
                 (contains? #{"pause-after-label-delete" "finish"} mode))
    (throw (ex-info "Invalid owned child fixture arguments" {})))
  (let [request (update-in (read-json request-file) [:scope :target-locale] keyword)
        _ (law/assert-request! request)
        _ (when-not (= "legacy-cleanup" (:action request))
            (throw (ex-info "Owned child fixture only runs legacy cleanup" {})))
        store (read-json store-file)
        world* (atom nil)
        world {:rows* (atom (store-rows store))
               :operations* (atom (vec (:operations store)))
               :faults {:after-delete-labels
                        (^:async fn [] (await (pause-after-delete! @world* store-file marker mode)))
                        :after-delete-segment #(persist-store! @world* store-file)}}]
    (reset! world* world)
    ;; execute! validates the request and ownership and captures this client
    ;; synchronously before its first await. Restore the factory immediately;
    ;; the installed NBB capability control proves this eager capture contract.
    (let [pending (with-redefs [native/*client-factory* (partial driver/client world)]
                    (native/execute! request))]
      (try (await pending)
           (finally (persist-store! world store-file))))))

;; This exact opt-in is absent from every normal test invocation. The driver
;; never calls a provider or a real database; the native construction seam is
;; bound before execute!, and all state is in the explicitly supplied files.
(when (= "__owned-verification-child__" (first *command-line-args*))
  (cli/run! #(child! (vec (rest *command-line-args*)))))
