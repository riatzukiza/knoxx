(ns knoxx.backend.extern.translation-review-verification-client
  "One inert native Mongo implementation shared by compiled and NBB controls."
  (:require ["mongodb" :refer [ObjectId]]))

(defn id-string
  "Decode the actual driver identity at the named inert native boundary."
  [id]
  (.toHexString id))

(defn scalar
  "Decode an ObjectId comparison scalar for the two owned test adapters."
  [value]
  (if (instance? ObjectId value) (id-string value) value))

(defn- matches? [row selector]
  (every? (fn [[field expected]]
            (if (and (map? expected) (contains? expected :$in))
              (contains? (set (map scalar (:$in expected))) (scalar (get row field)))
              (= (scalar (get row field)) (scalar expected)))) selector))

(defn- record! [world operation]
  (swap! (:operations* world) conj operation))

(defn- fault! [world phase]
  (when-let [operation (get (:faults world) phase)] (operation)))

(defn- query-rows [world collection raw-selector]
  (let [selector (js->clj raw-selector :keywordize-keys true)]
    (record! world [:query collection selector])
    (fault! world :query)
    (filterv #(matches? % selector) (get @(:rows* world) collection []))))

(defn- insert! [world collection raw-row]
  (let [row (js->clj raw-row :keywordize-keys true)]
    (record! world [:insert collection row])
    (fault! world :before-insert)
    (swap! (:rows* world) update collection (fnil conj []) row)
    (fault! world :after-insert)
    (js/Promise.resolve #js {:acknowledged true :insertedId (:_id row)})))

(defn- ^:async delete! [world collection raw-selector]
  (let [selector (js->clj raw-selector :keywordize-keys true)
        before (get @(:rows* world) collection [])
        removed (count (filter #(matches? % selector) before))
        label? (= collection "translation_labels")]
    (record! world [:delete collection selector])
    (await (fault! world (if label? :before-delete-labels :before-delete-segment)))
    (swap! (:rows* world) assoc collection (filterv #(not (matches? % selector)) before))
    (await (fault! world (if label? :after-delete-labels :after-delete-segment)))
    #js {:acknowledged true :deletedCount removed}))

(defn- collection [world name]
  #js {:findOne (fn [selector] (js/Promise.resolve (some-> (first (query-rows world name selector)) clj->js)))
       :find (fn [selector] #js {:toArray (fn [] (js/Promise.resolve (clj->js (query-rows world name selector))))})
       :countDocuments (fn [selector] (js/Promise.resolve (count (query-rows world name selector))))
       :insertOne (fn [row] (insert! world name row))
       :deleteMany (fn [selector] (delete! world name selector))})

(defn client
  "Construct only the recorded inert Mongo boundary for an explicit test world.

   The world owns its rows, operation log and faults. This native test authority
   is never request data and never opens a network or real database client."
  [world _uri _options]
  (record! world [:construct])
  (fault! world :construct)
  #js {:connect (fn [] (record! world [:connect]) (fault! world :connect) (js/Promise.resolve true))
       :db (fn [name] (record! world [:db name]) #js {:collection (fn [name] (collection world name))})
       :close (fn [] (record! world [:close]) (fault! world :close) (js/Promise.resolve true))})
