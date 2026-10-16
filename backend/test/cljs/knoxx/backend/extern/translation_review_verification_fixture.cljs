(ns knoxx.backend.extern.translation-review-verification-fixture
  "Inert native driver and CLI boundaries for owned verification regressions."
  (:require [knoxx.backend.extern.node-cli :as cli]
            [knoxx.backend.extern.openplanner-translation-mongo.verification-fixture :as native]
            [knoxx.backend.extern.translation-review-verification-client :as driver]
            [openplanner.translations.core :as translation]
            ["mongodb" :refer [ObjectId]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def source-commit (apply str (repeat 40 "a")))
(def uri "mongodb://fixture-user:fixture-password@inert.invalid")
(def source-text "Owned legacy source for authorization verification.")
(def translated-text "Traducción sintética de verificación de autorización.")

(defn object-id "A real driver identity for a native boundary control." [] (ObjectId.))
(defn id-string "Decode one real driver identity for an assertion." [id] (driver/id-string id))
(defn native-object "An opaque value that must never be admitted as request data." [] #js {:secret "opaque"})
(defn native-error "A native OS error with an intentionally unsafe message." [code]
  (doto (js/Error. "do-not-print-native-error-message") (aset "code" code)))
(defn throw-nil! "Throw a genuine null in the compiled unit fixture." [] (js* "(() => { throw null; })()"))

(defn ^:async with-world!
  "Run with one actual owned temporary record and an inert, recorded driver."
  ([operation] (await (with-world! {} operation)))
  ([faults operation]
   (let [root (.mkdtempSync fs (.join path (.tmpdir os) "knoxx-review-fixture-"))
         request {:action "legacy-seed"
                  :settings {:mongo-uri uri :mongo-db "owned-db" :source-commit source-commit
                             :ownership-file (.join path root "ownership.json")}
                  :scope {:run-id "owned-run" :org-id "owned-org" :project "owned-project"
                          :document-id "owned-legacy-document" :garden-id nil :target-locale :es}}
         world {:root root :request request :rows* (atom {}) :operations* (atom []) :faults faults}]
     (with-redefs [native/*client-factory* (partial driver/client world)]
       (try (await (operation world))
            (finally (.rmSync fs root #js {:recursive true :force true})))))))

(defn read-ownership "Decode the actual persisted ownership intent." [world]
  (js->clj (js/JSON.parse (.readFileSync fs (get-in world [:request :settings :ownership-file]) "utf8"))
           :keywordize-keys true))

(defn ownership-exists? "Observe whether intent survived a partial action." [world]
  (.existsSync fs (get-in world [:request :settings :ownership-file])))

(defn rewrite-ownership! "Fault-inject one owned record without exposing a raw handle." [world ownership]
  (.writeFileSync fs (get-in world [:request :settings :ownership-file])
                  (js/JSON.stringify (clj->js ownership))))

(defn add-row! "Add an explicit native test row, including deliberate foreign scope." [world name row]
  (swap! (:rows* world) update name (fnil conj []) row))

(defn update-segment! "Change the one owned row to exercise hidden-state observation." [world changes]
  (let [owned-id (:segment_id (read-ownership world))]
    (swap! (:rows* world) update "translation_segments"
           #(mapv (fn [row] (if (= owned-id (driver/scalar (:_id row))) (merge row changes) row)) %))))

(defn ^:async with-normalization-fault!
  "Exercise canonical output validation around the real public one-arity normalizer."
  [changes operation]
  (let [original translation/normalize-segment]
    (with-redefs [translation/normalize-segment (fn [input] (merge (original input) changes))]
      (await (operation)))))

(defn ^:async capture-cli!
  "Capture only owned console methods and exit status, restoring them after await."
  [operation]
  (let [out* (atom []) err* (atom []) original-out (.-log js/console)
        original-err (.-error js/console) previous-exit (.-exitCode js/process)]
    (set! (.-log js/console) (fn [text] (swap! out* conj text)))
    (set! (.-error js/console) (fn [text] (swap! err* conj text)))
    (set! (.-exitCode js/process) 0)
    (try (await (cli/run! operation))
         {:stdout (mapv #(js->clj (js/JSON.parse %) :keywordize-keys true) @out*)
          :stderr @err* :exit-code (.-exitCode js/process)}
         (finally (set! (.-log js/console) original-out)
                  (set! (.-error js/console) original-err)
                  (set! (.-exitCode js/process) previous-exit)))))
