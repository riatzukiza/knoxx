(ns knoxx.backend.extern.memory-sessions-fixture
  "Owned native Promise/clock controls for memory-session characterization."
  (:require [knoxx.backend.extern.mongo-cache-fixture :as clock]
            [knoxx.backend.infra.routes.memory :as routes]
            [knoxx.backend.infra.stores.mongo-memory-sessions :as mongo]
            [knoxx.backend.infra.stores.mongo-session-store :as sessions]
            [knoxx.backend.infra.stores.session-titles :as titles]))

(defn resolved [value] (js/Promise.resolve value))
(defn rejected [] (js/Promise.reject (js/Error. "Owned memory fixture failure")))
(defn throw! [] (throw (js/Error. "Owned synchronous memory fixture failure")))
(defn throw-nil! [] (throw nil))
(defn extra [data] (.stringify js/JSON (clj->js data)))
(defn tick! [] (js/Promise. (fn [resolve _reject] (js/setTimeout resolve 0))))
(defn ^:async all! [promises]
  (vec (array-seq (await (.all js/Promise (clj->js promises))))))

(defn deferred
  []
  (let [resolve* (atom nil)
        reject* (atom nil)
        promise (js/Promise. (fn [resolve reject]
                               (reset! resolve* resolve)
                               (reset! reject* reject)))]
    {:promise promise
     :resolve! (fn [value] (@resolve* value))
     :reject! (fn [] (@reject* (js/Error. "Owned deferred memory fixture failure")))}))

(defn ^:async at!
  [at f]
  (await (clock/with-clock! at f)))

(defn ^:async with-cache!
  [f]
  (let [entries (atom {}) reads (atom []) writes (atom [])]
    (routes/clear-memory-sessions-cache!)
    (try
      (with-redefs [mongo/get-cache-entry!
                    (fn
                      ([key] (swap! reads conj key) (resolved (get @entries key)))
                      ([_db key] (swap! reads conj key) (resolved (get @entries key))))
                    mongo/set-cache-entry!
                    (fn
                      ([key entry]
                       (swap! writes conj [key entry])
                       (swap! entries assoc key entry)
                       (resolved entry))
                      ([_db key entry]
                       (swap! writes conj [key entry])
                       (swap! entries assoc key entry)
                       (resolved entry)))]
        (await (f {:entries entries :reads reads :writes writes})))
      (finally (routes/clear-memory-sessions-cache!)))))

(defn ^:async failure?
  [promise]
  (try (await promise) false (catch :default _ true)))

(defn ^:async with-active!
  [live conversation-ids f]
  (let [responses (atom []) failures (atom [])]
    (with-redefs [sessions/get-session (fn
                                         ([id] (resolved (get live id)))
                                         ([_db id] (resolved (get live id))))
                  sessions/get-conversation-active-session
                  (fn
                    ([id] (resolved (get conversation-ids id)))
                    ([_db id] (resolved (get conversation-ids id))))
                  titles/session-titles* (atom {})
                  titles/session-title-promises* (atom {})
                  routes/run-warm-title-cache! (fn [& _] (resolved nil))]
      (await
       (f {:config {} :runtime {} :reply nil
           :json-response! (fn [_ status payload]
                             (swap! responses conj {:status status :body payload}))
           :error-response! (fn [& error] (swap! failures conj error))}
          responses failures)))))

