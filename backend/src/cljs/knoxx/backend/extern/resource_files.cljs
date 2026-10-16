(ns knoxx.backend.extern.resource-files
  "Node resource filesystem, watcher and timer boundary."
  (:refer-clojure :exclude [exists?])
  (:require ["node:fs" :as fs]
            ["node:fs/promises" :as fsp]
            [knoxx.backend.domain.error-observatory :as observatory]))

(defn read-text! [path] (.readFile fsp path "utf8"))

(defn delete! [path] (.unlink fsp path))

(defn exists? [path] (.existsSync fs path))

(defn watch!
  "Return an opaque watcher and supply only observed event/filename strings."
  [path callback]
  (.watch fs path (clj->js {:recursive true})
          (fn [event-type filename] (callback event-type (some-> filename str)))))

(defn set-timer! [callback milliseconds] (js/setTimeout callback milliseconds))

(defn clear-timer! [timer] (js/clearTimeout timer))

(defn- observe-close-failure!
  [error-present?]
  (try
    (observatory/log-warning! "resource-watcher.close" {:operation :close :fallback :continue-cleanup :error-present? error-present?})
    (catch :default observer-error
      {:observed? false :observation-unavailable? true
       :boundary "resource-watcher.close" :observer-error? (some? observer-error)})))

(defn close-watcher!
  "Attempt an owned watcher close; preserve cleanup continuation even if observation fails."
  [watcher]
  (try
    (.close watcher)
    (catch :default close-error
      (observe-close-failure! (some? close-error))
      nil)))
