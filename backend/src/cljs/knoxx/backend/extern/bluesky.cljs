(ns knoxx.backend.extern.bluesky
  "Native Bluesky tool ingress, wire observations and promise/array egress.")

(defn error "Construct the current workflow Error without changing its message." [message]
  (js/Error. message))

(defn tool-param
  "Read the same native parameter key; decode image arrays at this boundary."
  [params key]
  (let [value (aget params key)]
    (if (js/Array.isArray value) (vec (array-seq value)) value)))

(defn tool-array
  "Return the native tool array expected by the runtime."
  [tools]
  (clj->js (vec tools)))

(defn now-iso "Observe the current ISO timestamp at the existing record boundary." []
  (.toISOString (js/Date.)))

(defn- text->utf8-bytes [text]
  (js/Uint8Array.from (js/Array.from (.encode (js/TextEncoder.) text))))

(defn- match-observations
  [pattern text]
  (let [matcher (js/RegExp. pattern "g")]
    (loop [match (.exec matcher text) acc []]
      (if (nil? match)
        acc
        (let [full-match (aget match 0)
              prefix (.substring text 0 (.-index match))
              start-byte (.-length (text->utf8-bytes prefix))]
          (recur (.exec matcher text)
                 (conj acc {:match full-match :tag (aget match 1)
                            :byte-start start-byte
                            :byte-end (+ start-byte (.-length (text->utf8-bytes full-match)))})))))))

(defn hashtag-matches
  "Observe original ASCII hashtag matches and UTF8 offsets."
  [text]
  (match-observations "#(\\w+)" text))

(defn url-matches
  "Observe original URL matches, including their existing punctuation."
  [text]
  (match-observations "(https?://[^\\s]+)" text))

(defn ^:async promise-all-vector
  "Start the supplied image promises in order and preserve Promise.all result order."
  [promises]
  (let [results (await (js/Promise.all (clj->js promises)))]
    (vec (array-seq results))))
