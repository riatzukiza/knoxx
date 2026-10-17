(ns knoxx.backend.law.translation-source-split
  "Deterministic server-owned paragraph partitioning for translation sources.

  Every returned member retains its original source characters. Blank-line
  separators trail the preceding paragraph; leading separators stay with the
  first nonblank paragraph and trailing whitespace stays with the last. Fenced
  and indented Markdown code, mapping front matter, and list-item subtrees are
  indivisible where an internal split would remove required syntax context."
  (:require [clojure.string :as str]
            [knoxx.backend.law.translation-source-blocks :as blocks]))

(def ^:private decimal-digits
  "Characters admitted in a Markdown ordered-list marker."
  #{"0" "1" "2" "3" "4" "5" "6" "7" "8" "9"})

(defn- decimal-digit?
  "Whether one source character is an ASCII decimal digit."
  [character]
  (contains? decimal-digits character))

(defn- ordered-marker-end
  "Return the end of a one-to-nine-digit ordered-list marker."
  [source marker-start content-end]
  (loop [cursor marker-start
         digits 0]
    (if (and (< cursor content-end)
             (< digits 9)
             (decimal-digit? (blocks/source-character source cursor)))
      (recur (inc cursor) (inc digits))
      (when (and (pos? digits)
                 (< cursor content-end)
                 (contains? #{"." ")"}
                            (blocks/source-character source cursor)))
        (inc cursor)))))

(defn- list-marker-end
  "Return the end of an unordered or ordered marker beginning at `start`."
  [source start content-end]
  (when (< start content-end)
    (let [character (blocks/source-character source start)]
      (if (contains? #{"-" "+" "*"} character)
        (inc start)
        (ordered-marker-end source start content-end)))))

(defn- marker-padding-info
  "Return visual indentation after the spaces or tabs following a marker."
  [source start content-end start-column]
  (loop [cursor start
         column start-column]
    (if (< cursor content-end)
      (case (blocks/source-character source cursor)
        " " (recur (inc cursor) (inc column))
        "\t" (recur (inc cursor) (blocks/next-tab-column column))
        {:cursor cursor :column column})
      {:cursor cursor :column column})))

(defn- marker-result-fields
  "Describe marker identity before the original branch computes content presence."
  [marker-indent content-indent unordered? ordered-digits]
  {:indent marker-indent
   :content-indent content-indent
   :kind (if unordered? :unordered :ordered)
   :starts-at-one? (boolean
                    (and ordered-digits
                         (re-matches #"0*1" ordered-digits)))})

(defn- list-marker
  "Describe a Markdown list marker and its required continuation column."
  [source line]
  (let [{marker-start :cursor marker-indent :column}
        (blocks/indentation-info source line)
        content-end (:content-end line)
        marker-character (when (< marker-start content-end)
                           (blocks/source-character source marker-start))]
    (when-let [marker-end (list-marker-end source marker-start content-end)]
      (let [marker-width (- marker-end marker-start)
            marker-column (+ marker-indent marker-width)
            unordered? (contains? #{"-" "+" "*"} marker-character)
            ordered-digits (when-not unordered?
                             (subs source marker-start (dec marker-end)))]
        (cond
          (= marker-end content-end)
          (assoc (marker-result-fields marker-indent (inc marker-column)
                                       unordered? ordered-digits)
                 :content? false)

          (contains? #{" " "\t"}
                     (blocks/source-character source marker-end))
          (let [padding (marker-padding-info source marker-end content-end
                                             marker-column)]
            (assoc (marker-result-fields marker-indent (:column padding)
                                         unordered? ordered-digits)
                   :content? (< (:cursor padding) content-end)))

          :else nil)))))

(defn- paragraph-interrupting-marker?
  "Whether a nonempty marker may interrupt an active CommonMark paragraph."
  [{:keys [kind starts-at-one? content?]}]
  (and content?
       (or (= :unordered kind)
           (and (= :ordered kind) starts-at-one?))))

(defn- root-list-marker-permitted?
  "Whether `marker` may begin an item at this exact block position."
  [source lines line-index marker]
  (let [previous-line (when (pos? line-index)
                        (nth lines (dec line-index)))]
    (or (nil? previous-line)
        (blocks/blank-line? source previous-line)
        (blocks/block-boundary-line? source previous-line)
        (paragraph-interrupting-marker? marker))))

(defn- list-continuation?
  "Whether a nonblank line remains inside one list-item subtree."
  [source root-marker line]
  (let [indent (:column (blocks/indentation-info source line))
        nested-marker (list-marker source line)
        required-indent (:content-indent root-marker)]
    (if nested-marker
      (>= (:indent nested-marker) required-indent)
      (>= indent required-indent))))

(defn- list-item-span
  "Return one item subtree span and the index of the first line after it."
  [source lines item-index root-marker]
  (let [item-line (nth lines item-index)]
    (loop [line-index (inc item-index)
           last-content-end (:content-end item-line)
           blank-since-content? false]
      (if (< line-index (count lines))
        (let [line (nth lines line-index)]
          (cond
            (blocks/blank-line? source line)
            (recur (inc line-index) last-content-end true)

            (list-continuation? source root-marker line)
            (recur (inc line-index) (:content-end line) false)

            (and (not blank-since-content?)
                 (nil? (list-marker source line))
                 (not (blocks/paragraph-interrupting-block-line? source line)))
            (recur (inc line-index) (:content-end line) false)

            :else
            [[(:start item-line) last-content-end] line-index]))
        [[(:start item-line) last-content-end] line-index]))))

(defn- list-item-spans
  "Return protected spans for root list items, excluding sibling boundaries."
  [source lines]
  (loop [line-index 0
         continuing-list? false
         spans []]
    (if (< line-index (count lines))
      (let [line (nth lines line-index)
            marker (list-marker source line)]
        (if (and marker
                 (<= (:indent marker) 3)
                 (or continuing-list?
                     (root-list-marker-permitted?
                      source lines line-index marker)))
          (let [[span next-index]
                (list-item-span source lines line-index marker)
                next-marker (when (< next-index (count lines))
                              (list-marker source (nth lines next-index)))]
            (recur next-index
                   (boolean (and next-marker
                                 (<= (:indent next-marker) 3)))
                   (conj spans span)))
          (recur (inc line-index) false spans)))
      spans)))

(defn- merge-protected-spans
  "Sort and union overlapping syntax spans without bridging source gaps."
  [spans]
  (reduce
   (fn [merged [start end]]
     (if-let [[previous-start previous-end] (peek merged)]
       (if (<= start previous-end)
         (conj (pop merged) [previous-start (max previous-end end)])
         (conj merged [start end]))
       [[start end]]))
   []
   (sort-by first spans)))

(defn- span-start-protected?
  "Whether a candidate begins inside an already authoritative syntax span."
  [protected-spans [candidate-start]]
  (some (fn [[protected-start protected-end]]
          (<= protected-start candidate-start (dec protected-end)))
        protected-spans))

(defn- exclude-nested-spans
  "Drop lower-precedence syntax guesses born inside stronger contexts."
  [protected-spans candidate-spans]
  (remove #(span-start-protected? protected-spans %) candidate-spans))

(defn- protected-source-spans
  "Return every syntax context whose internal blanks are not boundaries."
  [source]
  (let [lines (blocks/source-lines source)
        front-matter (keep identity [(blocks/front-matter-span source lines)])
        fences (exclude-nested-spans front-matter (blocks/fenced-code-spans source))
        fenced-spans (merge-protected-spans (concat front-matter fences))
        tables (exclude-nested-spans fenced-spans
                                     (blocks/gfm-table-spans source lines))
        primary-spans (merge-protected-spans (concat fenced-spans tables))
        indented-code (exclude-nested-spans
                       primary-spans
                       (blocks/indented-code-spans source lines (set (map second tables))))
        code-spans (merge-protected-spans
                    (concat primary-spans indented-code))
        list-items (exclude-nested-spans
                    code-spans (list-item-spans source lines))]
    (merge-protected-spans (concat code-spans list-items))))

(defn- blank-line-separator-end
  "Return the exclusive end of a blank-line separator starting at `index`.

  A separator contains at least two line breaks, with only horizontal blank
  characters between them. Additional blank lines belong to the same
  separator. Line-ending bytes are never normalized."
  [source index]
  (when-let [first-break-end (blocks/line-break-end source index)]
    (let [second-break-start
          (blocks/horizontal-whitespace-end source first-break-end)]
      (when-let [second-break-end (blocks/line-break-end source second-break-start)]
        (loop [separator-end second-break-end]
          (let [next-break-start
                (blocks/horizontal-whitespace-end source separator-end)]
            (if-let [next-break-end (blocks/line-break-end source next-break-start)]
              (recur next-break-end)
              separator-end)))))))

(declare partition-source)

(defn exact-source-parts?
  "Whether `parts` are the complete admissible partition of `source`.

  Translation manifests require a nonblank source and a nonempty vector of
  nonblank members. The members must also equal this server-owned canonical
  partition; accepting an arbitrary lossless partition would permit two
  authoritative manifests for the same source revision."
  [source parts]
  (and (string? source)
       (not (str/blank? source))
       (vector? parts)
       (seq parts)
       (every? string? parts)
       (= source (apply str parts))
       (every? (complement str/blank?) parts)
       (= parts (partition-source source))))

(defn- partition-source
  "Partition one nonblank source at complete blank-line separators."
  [source]
  (loop [part-start 0
         scan-index 0
         protected-spans (protected-source-spans source)
         parts []]
    (if (< scan-index (count source))
      (let [[protected-start protected-end] (first protected-spans)]
        (cond
          (= scan-index protected-start)
          (recur part-start protected-end (next protected-spans) parts)

          :else
          (if-let [separator-end
                   (blank-line-separator-end source scan-index)]
            (let [candidate (subs source part-start separator-end)]
              (if (not (str/blank? candidate))
                (recur separator-end separator-end protected-spans
                       (conj parts candidate))
                (recur part-start separator-end protected-spans parts)))
            (recur part-start (inc scan-index) protected-spans parts))))
      (let [remainder (subs source part-start)]
        (cond
          (empty? remainder) parts
          (not (str/blank? remainder)) (conj parts remainder)
          :else (update parts (dec (count parts)) str remainder))))))

(defn source-parts
  "Return deterministic, exact paragraph members for `source`.

  The server can pass this result directly to the translation split-manifest
  constructor. The UI does not need to rediscover paragraph boundaries. Blank
  sources are refused because they cannot produce an admissible manifest."
  [source]
  (when-not (string? source)
    (throw (ex-info "translation source text must be a string"
                    {:source source})))
  (when (str/blank? source)
    (throw (ex-info "translation source text must not be blank"
                    {:source source})))
  (let [parts (partition-source source)]
    (when-not (exact-source-parts? source parts)
      (throw (ex-info "translation source partition lost source text"
                      {:source source
                       :source-parts parts})))
    parts))
