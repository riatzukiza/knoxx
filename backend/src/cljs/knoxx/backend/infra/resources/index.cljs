(ns knoxx.backend.infra.resources.index
  "Owned resource index and debounced watcher lifecycle."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [knoxx.backend.domain.resources.loader :as resources]
            [knoxx.backend.extern.resource-files :as files]
            [knoxx.backend.extern.resources :as native]
            [knoxx.backend.infra.event-runtime :as event-runtime]))

(def ^:private resource-watch-debounce-ms 350)

(defonce ^:private resource-watchers* (atom []))

(defonce ^:private resource-watch-timer* (atom nil))

(defonce ^:private resource-watch-running?* (atom false))

(defonce ^:private resource-index* (atom #{}))

(defn- resource-id->index-key [resource-class resource-id]
  (str (resources/resource-class resource-class) "/" resource-id))

(defn ^:async sync-resource-index!
  "Sync resource EDN files → in-memory resource index set.

   The index is a fast in-process cache; disk is canonical. Invalid resource
   files are omitted by the loader and must not block backend startup or the
   repair UI."
  [config]
  (try
    (let [records (await (resources/load-all-resources! config))
          ids (->> records
                   (map (fn [record]
                          (resource-id->index-key (:resource/class record)
                                                  (:resource/id record))))
                   distinct
                   sort
                   vec)
          existing-set @resource-index*
          desired-set (set ids)
          to-add (vec (sort (set/difference desired-set existing-set)))
          to-remove (vec (sort (set/difference existing-set desired-set)))]
      (reset! resource-index* desired-set)
      (println "[resources] synced resource index; add=" (count to-add) "remove=" (count to-remove))
      {:ok true
       :added to-add
       :removed to-remove
       :count (count ids)})
    (catch :default err
      (println "[resources] sync-resource-index! failed; startup continuing:" (native/error-message err))
      {:ok false :error (native/error-message err)})))

(defn sync-contract-index!
  "Compatibility alias for old contract route callers."
  [config]
  (sync-resource-index! config))

(defn- clear-resource-watch-timer!
  []
  (when-let [timer @resource-watch-timer*]
    (files/clear-timer! timer)
    (reset! resource-watch-timer* nil)))

(defn stop-resource-watcher!
  "Close every owned watcher and reset the one shared lifecycle."
  []
  (clear-resource-watch-timer!)
  (doseq [watcher @resource-watchers*]
    (when watcher (files/close-watcher! watcher)))
  (reset! resource-watchers* [])
  (reset! resource-watch-running?* false)
  nil)

(defn stop-contract-watcher!
  "Compatibility alias for old contract route callers."
  []
  (stop-resource-watcher!))

(defn- watchable-resource-change?
  [filename]
  (or (nil? filename)
      (str/ends-with? (str/lower-case (str filename)) ".edn")))

(defn- ^:async resource-refresh!
  [config]
  (try
    (await (sync-resource-index! config))
    (event-runtime/debounced-reload!)
    (println "[resources] event runtime reload queued after resource change")
    (catch :default err
      (println "[resources] watcher refresh failed:" (native/error-message err)))))

(defn- schedule-resource-refresh!
  [config reason]
  (clear-resource-watch-timer!)
  (reset! resource-watch-timer*
          (files/set-timer!
           (fn []
             (reset! resource-watch-timer* nil)
             (println "[resources] watcher refresh triggered by" reason)
             (resource-refresh! config))
           resource-watch-debounce-ms)))

(defn- watch-root!
  [config root]
  (try
    (files/watch! root
                  (fn [event-type filename]
                    (when (watchable-resource-change? filename)
                      (schedule-resource-refresh!
                       config (str root " :: " event-type " :: " (or filename "<unknown>"))))))
    (catch :default err
      (println "[resources] failed to watch" root ":" (native/error-message err))
      nil)))

(defn start-resource-watcher!
  "Start once, preserving root order and successful-watcher-only activation."
  [config]
  (when-not @resource-watch-running?*
    (let [roots (->> (resources/resource-root-paths config)
                     (filter files/exists?) distinct vec)
          watchers (->> roots (map (partial watch-root! config)) (remove nil?) vec)]
      (when (seq watchers)
        (reset! resource-watchers* watchers)
        (reset! resource-watch-running?* true)
        (println "[resources] watching" (count watchers) "resource roots for live reload")))))

(defn start-contract-watcher!
  "Compatibility alias for old contract route callers."
  [config]
  (start-resource-watcher! config))
