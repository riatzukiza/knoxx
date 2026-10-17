(ns knoxx.backend.text-test
  (:require [cljs.test :refer [deftest is testing]]
            [knoxx.backend.domain.text :as text]))

(deftest sanitize-svg-content-repairs-duplicated-open-tags
  (testing "repairs the model-corruption pattern that duplicates tag names after <<"
    (let [broken "<<svgsvg><<defsdefs><<linearlinearGradient id=\"g\"><<stopstop offset=\"0%\" /></linearGradient></defs></svg>"
          fixed (text/sanitize-svg-content broken)]
      (is (= "<svg><defs><linearGradient id=\"g\"><stop offset=\"0%\" /></linearGradient></defs></svg>"
             fixed))
      (is (not (re-find #"<<[a-z]" fixed))))))

(deftest assistant-message-text-collapses-duplicate-and-cumulative-parts
  (testing "assistant text prefers the longest cumulative segment instead of repeating mirrored parts"
    (let [message #js {:content #js [#js {:type "text" :text "Not"}
                                     #js {:type "output_text" :text "Not much — I’m here."}
                                     #js {:type "text" :text "Not much — I’m here."}] }]
      (is (= "Not much — I’m here."
             (text/assistant-message-text message))))))

(deftest assistant-message-reasoning-text-collapses-overlapping-parts
  (testing "reasoning text preserves independent fragments but removes cumulative overlap"
    (let [message #js {:content #js [#js {:type "reasoning" :text "Think"}
                                     #js {:type "reasoning_text" :text "Thinking step 1"}
                                     #js {:type "thinking" :thinking "Thinking step 1\nThinking step 2"}] }]
      (is (= "Thinking step 1\nThinking step 2"
             (text/assistant-message-reasoning-text message))))))

(defn- empty-preview-fields
  []
  (zipmap (concat text/preview-preferred-keys text/preview-collection-keys)
          (repeat "")))

(deftest public-preview-preserves-field-priority-and-existing-false-nil-behavior
  (testing "field selection stays ordered across keyword and string keys"
    (is (= "first" (text/value->preview-text {:content "first" :text "second"})))
    (is (= "second" (text/value->preview-text {:content " " :text "second"})))
    (is (= "string key" (text/value->preview-text {"content" "string key"})))
    (is (= "string fallback" (text/value->preview-text {:content false "content" "string fallback"})))
    (is (= "0" (text/value->preview-text {:content 0 :text "second"}))))
  (testing "the extraction preserves the current nil/false fallback, including absent content"
    (is (= "null" (text/value->preview-text {:content nil :text "later"})))
    (is (= "null" (text/value->preview-text {:content false})))
    (is (= "null" (text/value->preview-text {:text "later"})))
    (is (= "false" (text/value->preview-text false))))
  (testing "public field vectors remain the caller-owned lookup order"
    (with-redefs [text/preview-preferred-keys [:output]
                  text/preview-collection-keys [:rows]]
      (is (= "chosen" (text/value->preview-text {:content "ignored" :output "chosen"}))))))

(deftest public-preview-keeps-collection-and-eight-item-content-boundaries
  (testing "nonblank preferred text wins before the collection, then rows precede hits"
    (is (= "preferred" (text/value->preview-text {:content "preferred" :rows [1 2]})))
    (is (= "- 1\n- 2"
           (text/value->preview-text (assoc (empty-preview-fields) :rows [1 2] :hits [3])))))
  (testing "plain lists and text content parts retain the first eight items"
    (is (= "- 0\n- 1\n- 2\n- 3\n- 4\n- 5\n- 6\n- 7"
           (text/value->preview-text (range 12))))
    (is (= "part-0\n\npart-1\n\npart-2\n\npart-3\n\npart-4\n\npart-5\n\npart-6\n\npart-7"
           (text/value->preview-text (mapv (fn [i] {:type "text" :text (str "part-" i)}) (range 12)))))
    (is (= "first\n\nsecond"
           (text/value->preview-text [{:type "text" :text " first "}
                                     {"type" "output_text" "text" " second "}
                                     {:text "  "}])))))

(deftest public-preview-keeps-depth-and-twenty-item-markdown-boundaries
  (testing "depth four refuses the nested summary and keeps the public printable fallback"
    (is (= "- - - leaf" (text/value->preview-text [[["leaf"]]])))
    (is (= "[[[[\"leaf\"]]]]" (text/value->preview-text [[[["leaf"]]]]))))
  (testing "blank early items expose the real Markdown fallback and its twenty-item limit"
    (is (= "- 1\n- 2\n- 3\n- 4\n- 5\n- 6\n- 7\n- 8\n- 9\n- 10\n- 11\n- 12"
           (text/value->preview-text (vec (concat (repeat 8 "") (range 1 14)))))))
  (testing "map Markdown retains the first twenty ordered fields"
    (let [fields (into (sorted-map)
                       (concat (empty-preview-fields)
                               (map (fn [i]
                                      [(keyword (str "a" (when (< i 10) "0") i))
                                       (str "entry-" i)])
                                    (range 21))))]
      (is (= (str "- a00: entry-0\n- a01: entry-1\n- a02: entry-2\n- a03: entry-3\n"
                  "- a04: entry-4\n- a05: entry-5\n- a06: entry-6\n- a07: entry-7\n"
                  "- a08: entry-8\n- a09: entry-9\n- a10: entry-10\n- a11: entry-11\n"
                  "- a12: entry-12\n- a13: entry-13\n- a14: entry-14\n- a15: entry-15\n"
                  "- a16: entry-16\n- a17: entry-17\n- a18: entry-18\n- a19: entry-19")
             (text/value->preview-text fields))))))

(deftest public-preview-retains-runtime-json-no-content-and-truncation-adapters
  (testing "runtime values and JSON strings enter through the public adapter"
    (is (= "native" (text/value->preview-text #js {:content "native"})))
    (is (= "parsed" (text/value->preview-text "{\"content\":\"parsed\",\"rows\":[1]}")))
    (is (= "fragment" (text/value->preview-text "{\"text\":\"fragment")))
    (is (= "- JSON (truncated/invalid preview) keys: count"
           (text/value->preview-text "{\"count\":7")))
    (is (= "[]" (text/value->preview-text []))))
  (testing "no-content and blank scalar inputs stay absent"
    (doseq [value [nil js/undefined "" " \n\t " "null" "UNDEFINED"]]
      (is (nil? (text/value->preview-text value)))))
  (testing "clipping stays at the existing public adapter boundary"
    (is (= "plain" (text/value->preview-text " \nplain\t ")))
    (is (= "abc…" (text/value->preview-text " abcdef " 3)))
    (is (= "abc…" (text/value->preview-text {:content "abcdef"} 3)))))
