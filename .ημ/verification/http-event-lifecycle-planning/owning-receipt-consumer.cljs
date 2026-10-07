(ns http-lifecycle-planning-receipts
 (:require [clojure.string :as s] [eta-mu.receipt-river.api :as api] [eta-mu.receipt-river.domain.receipt :as receipt] [eta-mu.receipt-river.shape.edn :as edn] ["node:fs" :as fs] ["node:child_process" :as cp]))
(let [[mode target inherited-count] *command-line-args*]
 (if (= mode "append")
  (let [now (.toISOString(js/Date.)) repo "open-hax/knoxx"
    payload(receipt/build-payload {:kind :decision :owner "root/issues" :origin "existing Ready HTTP5 whole-card planning refinement"
     :dod "Preserve all six original outcomes, Ready/P1/5/UUID/parent/history; actual HTTP-only zero effects, truthful async lifecycle, isolated owned cleanup and full-mode exactly-once plus full backend gates and human proof"
     :pi "canonical pr-flow planning; actual Rheos read-only visibility"
     :host "independent complete persistent Git store/private Node22.20.0 NBB1.3.204 and BB planning tools"
     :manifest "existing HTTP lifecycle card; docs/notes/design/http-event-lifecycle-planning.md; .ημ/verification/http-event-lifecycle-planning"
     :refs "base5670338;accepted3409977;parent246;generator160-PR7-10626687;spawn161-PR6-c46f571b;foreign353-5bf0831b;RR154440"
     :tests "Actual native before/after Ready/P1/5 fullbody read and empty events; fixture exact/no extras; all15 owningRR source bytes; current declared addition validation and fulltip historical refusal accounting; portable sm-log/sm-list; diff/prefix/complete objects checks. Frontend/backend compiler/test/lint/typecheck/browser/manual live verification NOT executed or qualified."
     :note "Native Ready observed, no eligible complete planning provenance demonstrated, not illegal-state inference. Whole five-point sizing, lifecycle ABI/default/compatibility and host integration remain proposed decisions. Both real core paths have deferred agent/event effects; disabled reload repair preserved, enabled partial async startup requires future proof. Foreign353/160161 overlap and parent/134385181182/Agents22 holds retained. No code/foreign adoption/state/remote/provider/settings/service changes."} repo now :decision)
    line(edn/format-line(api/build-event {:event-id(str(random-uuid)) :recorded-at now :component-manifest{:eta-mu/version "1.1.1"} :command "HTTP lifecycle planning" :producer{:actor "root/issues"} :subject{:repo repo}}payload))]
   (when-not(:ok(api/validate-line line 1))(throw(ex-info "actual owning API refused new declared decision"{})))(.appendFileSync fs target(str line "\n")))
  (let [text(if (= mode "git")(.toString(.execFileSync cp "git" #js["show"(str target ":.ημ/receipts.edn")]))(.readFileSync fs target "utf8"))
        lines(s/split-lines text) results(mapv(fn[i line](select-keys(api/validate-line line(inc i))[:ok :line-number :source/schema :errors]))(range)lines)
        n(js/parseInt inherited-count 10) owned(subvec results n)]
   (println(js/JSON.stringify(clj->js{:count(count lines) :historical-count n :historical-accepted(count(filter :ok(take n results))) :historical-refused(count(remove :ok(take n results))) :owned owned :results results})))
   (when-not(and (= 1(count owned))(every? :ok owned)(every? #(= :declared(get-in %[:source/schema :status]))owned))(set!(.-exitCode js/process)1)))))