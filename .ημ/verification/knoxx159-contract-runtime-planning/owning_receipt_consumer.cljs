(ns owning-receipt-consumer
  (:require [clojure.string :as s]
            [eta-mu.receipt-river.api :as api]
            [eta-mu.receipt-river.domain.receipt :as receipt]
            [eta-mu.receipt-river.shape.edn :as edn]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]))

(defn inherited-index
  "Reject missing, nonnumeric, fractional or negative counts before reading input."
  [raw]
  (when-not (and (string? raw) (re-matches #"[0-9]+" raw))
    (throw (ex-info "inherited-count must be a nonnegative integer" {})))
  (let [n (js/Number raw)]
    (when-not (js/Number.isSafeInteger n)
      (throw (ex-info "inherited-count must be a safe integer" {})))
    n))

(defn physical-lines
  "Retain every physical record, including blanks, without counting the EOF newline."
  [text]
  (if (empty? text)
    []
    (s/split (if (s/ends-with? text "\n") (subs text 0 (dec (count text))) text)
             #"\r?\n" -1)))

(let [[mode target inherited-count & extra] *command-line-args*]
  (when-not (and (#{"append" "file" "git"} mode)
                 (string? target) (not (s/blank? target)) (empty? extra))
    (throw (ex-info "usage: append TARGET | file TARGET COUNT | git SHA COUNT" {})))
  (when (and (= mode "append") inherited-count)
    (throw (ex-info "append accepts only a target" {})))
  (if (= mode "append")
  (let [now (.toISOString(js/Date.)) repo "open-hax/knoxx"
    payload(receipt/build-payload {:kind :decision :owner "root/foresight_prs" :origin "existing Ready Knoxx159 whole-epic planning refinement"
     :dod "Preserve all nine original steps, seven retirement rows, dependency/interpreter/file authority intent, Ready/P2/unsized identity and complete future gates"
     :pi "canonical pr-flow planning; actual Rheos read-only visibility"
     :host "independent complete persistent Git store/private Node22.20.0 NBB1.4.207 and BB planning tools"
     :manifest "existing issue159 card; full current design and verification; .ημ/verification/knoxx159-contract-runtime-planning"
     :refs "base5670338;accepted3409977;issue159;c02d4ac;2b457af;recovery477b9fe;foreign305slices;RR154440"
     :tests "Actual owner native base/refined full-card reads0 at neutral readonly mount, Ready/P2/unsized; unchanged config and fixture inputs except intentional body append. Exact historical archives, accepted source identities, fulltip new declared suffix/historical refusals, strict transport, canonical reflection and complete-store/hygiene proof. Backend/frontend runtime suites/gates/human verification NOT executed."
     :note "Historical acceptance is provenance, not current authority. Nine dispositions and seven deletion proofs remain future review; files/Mongo/Redis cache authority distinguished, no old SQL/agent-field resurrection. Unsized full epic requires reviewed breakdown/admission. Parent1/foreign305/private spawn161/generator160/lifecycle/publisher134/eager385/baseline181182/Agents22 holds retained. Original prefix remains exact. Neutral-mount setup failure and explicitly derived initial warning display preserve raw hashes. No source implementation, native state, provider, service, settings or remote changes."} repo now :decision)
    line(edn/format-line(api/build-event {:event-id(str(random-uuid)) :recorded-at now :component-manifest{:eta-mu/version "1.1.1"} :command "whole159 planning" :producer{:actor "root/foresight_prs"} :subject{:repo repo}}payload))]
   (when-not(:ok(api/validate-line line 1))(throw(ex-info "actual owning API refused new declared decision"{})))(.appendFileSync fs target(str line "\n")))
    (let [n (inherited-index inherited-count)
          text (if (= mode "git")
                 (.toString (.execFileSync cp "git" #js ["show" (str target ":.ημ/receipts.edn")]))
                 (.readFileSync fs target "utf8"))
          lines (physical-lines text)
          _ (when (> n (count lines))
              (throw (ex-info "inherited-count exceeds physical record count" {})))
          results (mapv (fn [i line]
                          (let [result (api/validate-line line (inc i))]
                            {:ok (:ok result) :line-number (inc i)
                             :schema (:source/schema result) :errors (:errors result)}))
                        (range) lines)
          owned (subvec results n)]
      (println (js/JSON.stringify
                (clj->js {:scope :actual-api-all-physical-records
                         :count (count lines) :historical-count n
                         :historical-accepted (count (filter :ok (take n results)))
                         :historical-refused (count (remove :ok (take n results)))
                         :owned owned :results results})))
      (when-not (and (seq owned) (every? :ok owned)
                     (every? #(= :declared (get-in % [:schema :status])) owned))
        (set! (.-exitCode js/process) 1)))))
