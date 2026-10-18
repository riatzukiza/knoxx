(ns knoxx.backend.law.translation-source-blocks
  "Exact source-line coordinates and root Markdown block spans.

  Pure string/line-map operations retain source offsets and line endings.
  Canonical partition validation, list-item precedence and source admission
  remain owned by translation-source-split."
  (:require [clojure.string :as str]))

(defn source-character
  "Return the one-character string at `index`."
  [source index]
  (subs source index (inc index)))

(defn line-break-end
  "Return the exclusive end of the line break at `index`, when present."
  [source index]
  (when (< index (count source))
    (case (source-character source index)
      "\n" (inc index)
      "\r" (if (and (< (inc index) (count source))
                      (= "\n" (source-character source (inc index))))
               (+ index 2)
               (inc index))
      nil)))

(defn horizontal-whitespace-end
  "Skip blank characters other than line breaks from `index`."
  [source index]
  (loop [cursor index]
    (if (< cursor (count source))
      (let [character (source-character source cursor)]
        (if (and (not= "\r" character)
                 (not= "\n" character)
                 (str/blank? character))
          (recur (inc cursor))
          cursor))
      cursor)))

(defn- line-content-end
  "Return the exclusive end of the current line, before its line break."
  [source line-start]
  (loop [cursor line-start]
    (if (or (= cursor (count source))
            (line-break-end source cursor))
      cursor
      (recur (inc cursor)))))

(defn- next-line-start
  "Return the next line start, or the source end for a final unterminated line."
  [source content-end]
  (or (line-break-end source content-end) content-end))

(defn source-lines
  "Describe source lines with exact half-open content and line spans."
  [source]
  (loop [line-start 0
         lines []]
    (if (< line-start (count source))
      (let [content-end (line-content-end source line-start)
            line-end (next-line-start source content-end)]
        (recur line-end
               (conj lines {:start line-start
                            :content-end content-end
                            :end line-end})))
      lines)))

(defn- line-text
  "Return one line's content without normalizing or including its line break."
  [source {:keys [start content-end]}]
  (subs source start content-end))

(defn blank-line?
  "Whether a source line contains only blank characters."
  [source line]
  (str/blank? (line-text source line)))

(defn next-tab-column
  "Advance a Markdown indentation column to the next four-column tab stop."
  [column]
  (+ column (- 4 (mod column 4))))

(defn indentation-info
  "Return the cursor and visual column after one line's leading indentation."
  [source {:keys [start content-end]}]
  (loop [cursor start
         column 0]
    (if (< cursor content-end)
      (case (source-character source cursor)
        " " (recur (inc cursor) (inc column))
        "\t" (recur (inc cursor) (next-tab-column column))
        {:cursor cursor :column column})
      {:cursor cursor :column column})))

(defn- leading-bom-free
  "Remove the optional Unicode byte-order marker from the first line only."
  [text]
  (if (str/starts-with? text "\uFEFF")
    (subs text 1)
    text))

(defn- front-matter-opening
  "Return a recognized front-matter marker at the exact source beginning."
  [source lines]
  (when-let [first-line (first lines)]
    (when (zero? (:start first-line))
      (let [opening (-> (line-text source first-line)
                        leading-bom-free
                        str/trimr)]
        (when (contains? #{"---" "+++"} opening)
          opening)))))

(defn- front-matter-entry?
  "Whether a line supplies mapping evidence for the selected front matter."
  [marker text]
  (case marker
    "---" (boolean
           (or (= "{}" (str/trim text))
               (re-find #"^[A-Za-z0-9_.-]+[ \t]*:(?:[ \t]+.*)?$" text)
               (re-find #"^[\"'][^\"']+[\"'][ \t]*:(?:[ \t]+.*)?$"
                        text)))
    "+++" (boolean
           (or (re-find #"^[A-Za-z0-9_.-]+[ \t]*=" text)
               (re-find #"^\[[^\]]+\][ \t]*$" text)))
    false))

(defn- front-matter-closing?
  "Whether one exact column-zero line closes `marker`."
  [marker text]
  (let [closing (str/trimr text)]
    (if (= "---" marker)
      (contains? #{"---" "..."} closing)
      (= "+++" closing))))

(defn front-matter-span
  "Return a closed mapping front-matter span, refusing ambiguous rule prose."
  [source lines]
  (when-let [marker (front-matter-opening source lines)]
    (loop [remaining (next lines)
           mapping-entry? false]
      (when-let [line (first remaining)]
        (let [text (line-text source line)]
          (if (front-matter-closing? marker text)
            (when mapping-entry?
              [0 (:content-end line)])
            (recur (next remaining)
                   (or mapping-entry?
                       (front-matter-entry? marker text)))))))))

(defn- indent-end
  "Skip the at-most-three literal spaces Markdown permits before a fence."
  [source line-start content-end]
  (loop [cursor line-start
         spaces 0]
    (if (and (< cursor content-end)
             (< spaces 3)
             (= " " (source-character source cursor)))
      (recur (inc cursor) (inc spaces))
      cursor)))

(defn- marker-run-end
  "Return the end of a repeated fence marker run."
  [source start content-end marker]
  (loop [cursor start]
    (if (and (< cursor content-end)
             (= marker (source-character source cursor)))
      (recur (inc cursor))
      cursor)))

(defn- opening-fence
  "Describe a CommonMark-style backtick or tilde opening fence on one line."
  [source line-start content-end]
  (let [marker-start (indent-end source line-start content-end)]
    (when (< marker-start content-end)
      (let [marker (source-character source marker-start)]
        (when (or (= "`" marker) (= "~" marker))
          (let [marker-end (marker-run-end source marker-start content-end marker)
                length (- marker-end marker-start)
                info (subs source marker-end content-end)]
            (when (and (>= length 3)
                       (or (= "~" marker) (not (str/includes? info "`"))))
              {:marker marker :length length})))))))

(defn- closing-fence?
  "Whether one line closes `fence` without interpreting its contents."
  [source line-start content-end {:keys [marker length]}]
  (let [marker-start (indent-end source line-start content-end)
        marker-end (marker-run-end source marker-start content-end marker)]
    (and (>= (- marker-end marker-start) length)
         (str/blank? (subs source marker-end content-end)))))

(defn fenced-code-spans
  "Return half-open spans whose internal blank lines cannot split paragraphs.

  A closed span stops before the closing line's line break so blank lines after
  the fence can still separate the fenced block from following prose. An
  unclosed fence protects the remainder of the source, matching Markdown's
  interpretation rather than guessing where code ended."
  [source]
  (loop [line-start 0
         active nil
         spans []]
    (if (< line-start (count source))
      (let [content-end (line-content-end source line-start)
            following-line (next-line-start source content-end)]
        (cond
          (and active (closing-fence? source line-start content-end active))
          (recur following-line nil
                 (conj spans [(:start active) content-end]))

          active
          (recur following-line active spans)

          :else
          (if-let [fence (opening-fence source line-start content-end)]
            (recur following-line (assoc fence :start line-start) spans)
            (recur following-line nil spans))))
      (cond-> spans
        active (conj [(:start active) (count source)])))))

(defn- atx-heading-line?
  "Whether one line is a complete ATX heading block."
  [text]
  (boolean (re-find #"^ {0,3}#{1,6}(?:[ \t]+.*)?$" text)))

(defn- setext-underline-line?
  "Whether one line can finish a Setext heading."
  [text]
  (boolean (re-find #"^ {0,3}(?:=+|-+)[ \t]*$" text)))

(defn- thematic-break-line?
  "Whether one line is a complete CommonMark thematic break."
  [text]
  (boolean
   (re-find #"^ {0,3}(?:(?:\*[ \t]*){3,}|(?:_[ \t]*){3,}|(?:-[ \t]*){3,})$"
            text)))

(defn- blockquote-opening-line?
  "Whether one line begins a root CommonMark block quote."
  [text]
  (boolean (re-find #"^ {0,3}>" text)))

(defn block-boundary-line?
  "Whether a line ends syntax after which indented code may start directly."
  [source {:keys [start content-end] :as line}]
  (let [text (line-text source line)]
    (or (atx-heading-line? text)
        (setext-underline-line? text)
        (thematic-break-line? text)
        (opening-fence source start content-end))))

(defn paragraph-interrupting-block-line?
  "Whether a line cannot be a lazy continuation of a list-item paragraph."
  [source line]
  (let [text (line-text source line)]
    (or (block-boundary-line? source line)
        (blockquote-opening-line? text))))

(defn- pipe-cells
  "Return simple pipe-delimited cells without interpreting their source bytes."
  [text]
  (let [trimmed (str/trim text)]
    (when (str/includes? trimmed "|")
      (let [without-leading (if (str/starts-with? trimmed "|")
                              (subs trimmed 1)
                              trimmed)
            body (if (str/ends-with? without-leading "|")
                   (subs without-leading 0 (dec (count without-leading)))
                   without-leading)]
        (str/split body #"\|" -1)))))

(defn- delimiter-cell?
  "Whether one GFM table delimiter cell has at least three hyphens."
  [cell]
  (boolean (re-matches #":?-{3,}:?" (str/trim cell))))

(defn- gfm-table-span-at
  "Return one validated, contiguous GFM pipe-table span at `line-index`."
  [source lines line-index]
  (when (< (inc line-index) (count lines))
    (let [header-line (nth lines line-index)
          delimiter-line (nth lines (inc line-index))
          header-cells (pipe-cells (line-text source header-line))
          delimiter-cells (pipe-cells (line-text source delimiter-line))]
      (when (and (seq header-cells)
                 (= (count header-cells) (count delimiter-cells))
                 (every? delimiter-cell? delimiter-cells)
                 (<= (:column (indentation-info source header-line)) 3)
                 (<= (:column (indentation-info source delimiter-line)) 3))
        (loop [row-index (+ line-index 2)
               table-end (:content-end delimiter-line)]
          (if (< row-index (count lines))
            (let [line (nth lines row-index)]
              (if (and (not (blank-line? source line))
                       (<= (:column (indentation-info source line)) 3)
                       (seq (pipe-cells (line-text source line))))
                (recur (inc row-index) (:content-end line))
                [[(:start header-line) table-end] row-index]))
            [[(:start header-line) table-end] row-index]))))))

(defn gfm-table-spans
  "Return validated table spans; blank lines always terminate a table."
  [source lines]
  (loop [line-index 0
         spans []]
    (if (< line-index (count lines))
      (if-let [[span next-index] (gfm-table-span-at source lines line-index)]
        (recur next-index (conj spans span))
        (recur (inc line-index) spans))
      spans)))

(defn- indented-code-line?
  "Whether a nonblank line begins at Markdown's four-column code indent."
  [source line]
  (and (not (blank-line? source line))
       (>= (:column (indentation-info source line)) 4)))

(defn- indented-code-action
  "Classify one root-code line without advancing its input sequence."
  [source line previous-line active-start table-end-offsets]
  (let [blank? (blank-line? source line)
        code? (indented-code-line? source line)]
    (cond
      (and active-start blank?) :retain
      (and active-start code?) :extend
      active-start :close
      (and code?
           (or (nil? previous-line)
               (blank-line? source previous-line)
               (block-boundary-line? source previous-line)
               (contains? table-end-offsets
                          (:content-end previous-line)))) :start
      :else :outside)))

(defn- indented-code-state
  "Advance the code run after its input sequence has advanced."
  [action line active-start last-code-end spans]
  (case action
    :retain [active-start last-code-end spans]
    :extend [active-start (:content-end line) spans]
    :close [nil nil (conj spans [active-start last-code-end])]
    :start [(:start line) (:content-end line) spans]
    :outside [nil nil spans]))

(defn indented-code-spans
  "Return spans that retain blank lines inside root indented code blocks.

  Indentation may start a block only at the source beginning or after a blank
  line. This prevents an indented continuation of ordinary prose from
  swallowing a later paragraph boundary. Trailing blank lines stay outside
  the protected span."
  [source lines table-end-offsets]
  (loop [remaining lines
         previous-line nil
         active-start nil
         last-code-end nil
         spans []]
    (if-let [line (first remaining)]
      (let [action (indented-code-action source line previous-line
                                         active-start table-end-offsets)
            following (next remaining)
            [next-start next-end next-spans]
            (indented-code-state action line active-start last-code-end spans)]
        (recur following line next-start next-end next-spans))
      (cond-> spans
        active-start (conj [active-start last-code-end])))))
