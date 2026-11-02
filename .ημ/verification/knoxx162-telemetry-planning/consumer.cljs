(ns telemetry-planning-receipt-consumer
  (:require [clojure.string :as str]
            [eta-mu.receipt-river.api :as api]
            [eta-mu.receipt-river.domain.receipt :as receipt]
            [eta-mu.receipt-river.shape.edn :as edn]
            ["node:fs" :as fs]
            ["node:child_process" :as child]))
(let [[mode target] *command-line-args*]
  (if (= mode "append")
    (let [now (.toISOString (js/Date.))
          payload (receipt/build-payload
                   {:kind :decision :owner "root/issues"
                    :origin "open-hax/knoxx issue162 planning refinement"
                    :dod "Preserve five telemetry minimums and three original verification outcomes with current placement, resolved policy identity, pure/host separation and unchanged public semantics"
                    :pi "pr-sprint-planning+receipt-river" :host "independent-private-local-worktree"
                    :manifest "kanban/tasks/knowledge-ops-adaptive-expand-policy-telemetry.md docs/design/adaptive-expansion-telemetry-planning.md docs/verification/adaptive-expansion-telemetry-planning.md .ημ/verification/knoxx162-telemetry-planning"
                    :refs "issue:open-hax/knoxx162 base:5670338fcf1db7d51a2690f388e4e4c5c409c48a accepted:3409977bca4ba35e09967f9a99d50867a679a73c RR:154440f3c997aa9208194bba59b5edbef3654f78"
                    :tests "Actual owning Rheos reads existing full card Ready/P2/2 and parent seam Done on exact accepted fixture. Complete independent Git fsck and source guards pass. No compiler, backend suite, live SDK/provider or board transition executed. Owning API validates only this declared new decision; all sixteen inherited legacy refusals remain visible."
                    :note "LOCAL proposal only, original body/frontmatter/events/source immutable. Review must approve current four-site placement, resolved identity and structured sink independent of durable admission, failure/clock laws and whether full scope fits two points. Foreign339 adapter and324/325 holds not adopted. Parent sync1/eager385/lint181182/native review and lawful ready admission required."
                    :decisions "Portable CLJC payload/schema/laws; named extern clock/emission and infra settlement. Preserve all default/public behavior, primary errors and independent contexts. No new ledger, model optimization or dependency/policy change."}
                   "open-hax/knoxx" now :decision)
          record (api/build-event {:event-id (str (random-uuid)) :recorded-at now
                                   :component-manifest {:eta-mu/version "1.1.1"}
                                   :command "local planning refinement" :producer {:actor "root/issues"}
                                   :subject {:repo "open-hax/knoxx"}} payload)
          line (edn/format-line record) result (api/validate-line line 17)]
      (when-not (:ok result) (throw (ex-info "own record refused" (dissoc result :event :line))))
      (.appendFileSync fs target (str line "\n"))
      (prn (select-keys result [:ok :line-number :source/schema :errors])))
    (let [text (if (= mode "git")
                 (.execFileSync child "git" #js ["show" (str target ":.ημ/receipts.edn")] #js {:encoding "utf8"})
                 (.readFileSync fs target "utf8"))
          lines (str/split-lines text)
          results (mapv (fn [idx line] (select-keys (api/validate-line line (inc idx)) [:ok :line-number :source/schema :errors])) (range) lines)
          own (last results)]
      (prn {:total-lines (count lines) :own-addition own
            :historical-not-qualification {:count (dec (count lines)) :refusals (filterv (comp not :ok) (butlast results))}})
      (when-not (and (= 17 (count lines)) (:ok own) (= :declared (get-in own [:source/schema :status])))
        (set! (.-exitCode js/process) 1)))))
