(ns lossless-planning-receipts
 (:require [clojure.string :as s] [eta-mu.receipt-river.api :as api] [eta-mu.receipt-river.domain.receipt :as receipt] [eta-mu.receipt-river.shape.edn :as edn] ["node:fs" :as fs] ["node:child_process" :as cp]))
(let [[mode target inherited-count] *command-line-args*]
 (if (= mode "append")
  (let [now (.toISOString(js/Date.)) repo "open-hax/knoxx"
    payload(receipt/build-payload {:kind :decision :owner "root/issues" :origin "existing Ready lossless publication card planning refinement"
     :dod "Preserve all five original outcomes and Ready/P1/3/history; full lossless semantic edits in both CMS write branches with fail-closed parsing, exact identity and meaningful full future verification"
     :pi "canonical pr-flow planning; actual Rheos read-only visibility"
     :host "independent complete persistent Git store/private Node22.20.0 NBB1.3.204 and BB planning tools"
     :manifest "existing lossless card; docs/notes/design/lossless-publication-state-planning.md; .ημ/verification/lossless-publication-state-planning"
     :refs "base5670338;accepted3409977;parent246;foreign3431826/3051d3b;introduceddd0457;rewrite-clj1.1.49b5f8d06;RR154440"
     :tests "Actual native before/after Ready/P1/3 fullbody read and empty events; fixture exact/no extras; all15 owningRR source bytes; current declared addition validation and fulltip historical refusal accounting; portable sm-log/sm-list; diff/prefix/complete objects checks. Backend compiler/test/lint/typecheck/manual live verification NOT executed or qualified."
     :note "Existing Ready has no recovered event/eligible planning approval; full three-point sizing and actual compiled rewrite-clj/CLJS/NBB compatibility remain review decisions. Full246 sequence and343305 overlapping route/source holds retained; no code/foreign adoption/state/remote/provider/settings/service changes."} repo now :decision)
    line(edn/format-line(api/build-event {:event-id(str(random-uuid)) :recorded-at now :component-manifest{:eta-mu/version "1.1.1"} :command "lossless publication planning" :producer{:actor "root/issues"} :subject{:repo repo}}payload))]
   (when-not(:ok(api/validate-line line 1))(throw(ex-info "actual owning API refused new declared decision"{})))(.appendFileSync fs target(str line "\n")))
  (let [text(if (= mode "git")(.toString(.execFileSync cp "git" #js["show"(str target ":.ημ/receipts.edn")]))(.readFileSync fs target "utf8"))
        lines(s/split-lines text) results(mapv(fn[i line](select-keys(api/validate-line line(inc i))[:ok :line-number :source/schema :errors]))(range)lines)
        n(js/parseInt inherited-count 10) owned(subvec results n)]
   (println(js/JSON.stringify(clj->js{:count(count lines) :historical-count n :historical-accepted(count(filter :ok(take n results))) :historical-refused(count(remove :ok(take n results))) :owned owned :results results})))
   (when-not(and (= 1(count owned))(every? :ok owned)(every? #(= :declared(get-in %[:source/schema :status]))owned))(set!(.-exitCode js/process)1)))))