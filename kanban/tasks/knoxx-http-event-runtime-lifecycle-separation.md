---
uuid: knoxx-http-event-runtime-lifecycle-separation
title: HTTP / Event Runtime Lifecycle Separation
status: ready
priority: P1
points: 5
labels:
  - tasks
  - publication
  - events
  - has-parent
---

# HTTP / Event Runtime Lifecycle Separation

> Parent epic: `knoxx-publication-runtime-follow-up`

## Purpose

Make backend HTTP startup independently usable for verification without connecting gateways, schedules, triggers, generators, or dispatching agent/event work.

## Work

- Identify the current boot seam where HTTP startup implicitly starts event runtimes.
- Split HTTP server lifecycle from event-runtime lifecycle with an explicit composition point.
- Define and test `start`, `stop`, `reset`, and `status` semantics, including repeated calls and disabled mode.
- Preserve fail-closed behavior: disabled/failed lifecycle mutations cannot return false success.
- Add an HTTP-only verification boot mode with zero Discord/gateway/schedule/trigger/generator/agent effects.
- Once authoritative, retire or narrow `KNOXX_DISABLE_EVENT_RUNTIMES` so there is one lifecycle model rather than two competing controls.

## Definition of Done

- HTTP server can start and serve required routes without starting event runtimes.
- Tests prove HTTP-only startup produces zero event effects.
- Lifecycle operations have explicit tested idempotency/error semantics.
- Disabled `start`/`reset` remain truthful failures rather than no-op success.
- Existing full-runtime startup still starts intended event runtimes exactly once.
- Backend test/lint/typecheck gates pass.


## Proposed current-source refinement — full HTTP/event lifecycle outcome

This is a planning proposal for the complete existing story, not implementation or a new admission decision. Original UUID, Ready/P1/5 metadata, parent reference and all six Definition of Done items above remain unchanged. Native Ready is lifecycle visibility; the captured history does not demonstrate eligible current-head planning qualification. No child card, estimate adjustment or operational transition is authored.

### Context and outcome

At accepted origin `3409977bca4ba35e09967f9a99d50867a679a73c`, carried through pending personal synchronization head `5670338fcf1db7d51a2690f388e4e4c5c409c48a`, HTTP composition still reaches event and agent work through multiple entry points. `infra.core/register-app-routes!` queues SDK prewarming and background startup; background startup includes the resource watcher and MCP discovery. `infra.core/start!` starts the watcher and resumes agent sessions after listen. Bootstrap also binds Discord reaction handling and installs session recovery/periodic flushing. Disabling the event shell alone is therefore insufficient evidence of zero HTTP-only event/agent effects. These are source observations, not an executed runtime reproduction.

The existing event flag centrally refuses disabled starts, and `reload!` refuses before stopping an existing runtime; the admin start/reset routes already surface disabled refusal. Retain those repairs. Complete separation must also account for asynchronous source startup, partial failures and delayed callbacks, rather than treating `running?` or a scheduled operation as successful completion.

### Scope and six acceptance obligations

1. Start the actual supported HTTP composition and serve required health, authenticated repair/admin and publication verification routes without implicit Discord/gateway, schedule, trigger, generator, agent dispatch/resume, SDK prewarm, MCP discovery or event-triggering watcher activity. Preserve mandatory policy and persistence admission; HTTP-only mode is not an authorization or Mongo readiness bypass. Review the exact required-route set and adapter dependencies before implementation.
2. Prove zero effects through both existing core composition paths, bootstrap/entrypoint, hot reload, watcher/debounce and delayed callbacks. Use the real route/composition functions with isolated host adapters, controlled time and effect counts, including negative controls that deliberately reintroduce a forbidden callback and make the test fail. Pure planner tests alone are insufficient.
3. Define and test explicit start/stop/reset/status semantics for repeated, concurrent, disabled and failing operations. Pending asynchronous work is not running. Partial start cleans up only owned successful effects, preserves the primary failure alongside cleanup failures, and cannot return success. Stale completions after stop/reset cannot resurrect work. Per-instance ownership prevents one isolated verification context from stopping another's timers, gateways or runtime.
4. Disabled start/reset return a truthful refusal at the decision, adapter and authenticated HTTP response boundaries; failed starts/resets return failure with actual status. Disabled reset keeps the existing refusal-before-stop property. Stop and status report observed owned state; no blanket `:ok true` or misleading already-running label may hide failure or work not attempted.
5. Full-runtime composition retains intended default behavior and starts intended event runtimes exactly once, including repeated boot, hot reload and overlapping callers. Preserve source/trigger/schedule/generator dispatch semantics and trusted actor/capability provenance. Once authoritative, narrow or retire `KNOXX_DISABLE_EVENT_RUNTIMES` through a reviewed compatibility mapping to the single lifecycle model; do not introduce two competing controls or change the default silently.
6. The relevant backend tests, strict lint and typecheck pass with zero failures/errors/warnings under owning policy. Production compile, boundary checks and a runnable human verification script/document are additional required integration evidence; a narrower passing fixture cannot replace the full gates. Historical failures and unavailable prerequisites stay visible.

### Architecture and ownership decisions for review

Prefer portable Clojure-shaped lifecycle state, command/result shapes and pure decision laws in `.cljc`; admission/validation stays in law/shape namespaces. Effects, asynchronous handles, clocks, scheduler registration, filesystem watchers, HTTP listen/close, Mongo and SDK/Discord/MCP calls remain named infra/extern adapters. The outer composition chooses HTTP-only or full capabilities and owns resource handles; untrusted resource/config/HTTP data cannot grant a runtime capability. Namespace/result ABI, outcome taxonomy, cancellation/rollback strategy, explicit mode/config compatibility and concurrency serialization require review, not acceptance by this document.

Parent issue [#246](https://github.com/open-hax/knoxx/issues/246) still owns all four follow-ups and publication closeout sequencing. This refinement covers only its entire HTTP child; it does not close the epic, finish lossless edits or Gardens decoupling. Generator [#160](https://github.com/open-hax/knoxx/issues/160)/personal PR7 and trusted spawn [#161](https://github.com/open-hax/knoxx/issues/161)/personal PR6 remain separately owned. Preserve the trusted outer capability through config/start/reload/tools-route composition; generator/resource/HTTP configuration must not replace it. Retain source/trigger/schedule internal/external provenance and captured Discord callback compatibility. Fresh qualification/merge guards are required before integrating overlapping changes; no foreign branch is adopted and no hard UUID dependency is invented.

Foreign origin [PR353](https://github.com/open-hax/knoxx/pull/353) overlaps bootstrap/startup/persistence, with its existing #324 reconciliation and #325 qualification holds. Its assembled test claims are not evidence for this plan or current accepted integration. The fresh captured inventory is personal 10/open origin 25 PRs; later publications require a new guard. Existing parent PR1 qualification, publisher visibility Foresight #134, eager-merge #385, strict lint #181/#182 and canonical informational-author-thread classification [.agents #22](https://github.com/riatzukiza/.agents/issues/22) remain holds. Author walkthrough comments must not be self-classified as Handled or converted into reviewer approval.

### Verification and red/green plan

After planning qualification and a lawful implementation handoff, first add failing portable laws and compiled-host tests against actual composition. Exercise full/HTTP-only/disabled modes, repeated and concurrent operations, delayed async success/rejection, failure after each acquired resource, cleanup failure, stop/reset before completion, stale timer callbacks and paired independent contexts. Test unauthenticated/unauthorized admin calls, truthful refused/failure responses, mandatory persistence refusal before readiness and all route semantics. A deliberately broken async mock and forbidden effect must make the official guarded runner exit nonzero.

Discover the new `*-test` namespaces through the actual Shadow selectors and retain existing disable/prewarm/bootstrap/route tests. Future full commands include `pnpm -C backend test`, `pnpm -C backend exec shadow-cljs compile test`, `pnpm -C backend lint`, `pnpm -C backend typecheck`, `pnpm -C backend exec shadow-cljs compile server`, boundary/error-boundary checks and explicit `test:e2e`/`test:routes:release` where the reviewed route/composition changes require them. Use fresh isolated stores, outputs, ports, timers and service fixtures; no shared PM2, providers or backend are used for this planning work.

Ship a script plus `docs/verification/` guide that verifies the served SHA/checkout, seeds and tears down owned data, walks allowed/refused/failing outcomes, reports PASS/WARN/FAIL and exits nonzero on required failures. Observe real isolated HTTP behavior and full-runtime adapter composition; do not claim live external provider behavior from mocks. Review prerequisite/provider availability before any such live proof. No UI change is proposed; a browser tour is required if implementation introduces a UI surface.

### Risks, non-goals and sizing

The whole five-point estimate is unchanged and must be reviewed against lifecycle/concurrency/host integration work. If it does not fit, propose a lawful complete breakdown before implementation, preserving all six outcomes; do not choose a smaller fixture substitute. No OpenPlanner companion implementation, new event ledger/Rheos engine, backend TypeScript expansion, unrelated lint repair, service restart, deployment/settings/credential change or provider request is authorized here. Body authoring and native read visibility are preparation, not implementation, CI qualification or Ready promotion.
