---
uuid: "knoxx-knowledge-ops-adaptive-expand-policy-telemetry"
title: "Knowledge Ops — Adaptive Expand Policy Telemetry"
status: ready
priority: P2
labels: ["tasks", "2sp", "has-parent"]
created_at: "2026-04-05T00:00:00Z"
source: "specs/tasks/knowledge-ops-adaptive-expand-policy-telemetry.md"
points: 2
category: tasks
---

# Knowledge Ops — Adaptive Expand Policy Telemetry

> Source: `specs/tasks/knowledge-ops-adaptive-expand-policy-telemetry.md`
> Parent: `knowledge-ops-adaptive-expand-policy-hook.md`
> Points: 2

Date: 2026-04-05
Status: ready
Parent: `knowledge-ops-adaptive-expand-policy-hook.md`
Story points: 2

## Purpose

Add structured telemetry for bounded graph expansion so future adaptive policies can be compared against the baseline using evidence rather than intuition.

## Problem

Even with a policy seam, future adaptive traversal will be guesswork unless the system records which policy ran, what bounds were applied, and what result shape came back.

## Goals

1. Emit structured telemetry for expansion requests and outcomes.
2. Record enough context to compare default and future policies.
3. Keep telemetry out of the public agent-facing contract.

## Non-Goals

1. Real-time policy optimization.
2. Building a full observability dashboard.
3. Exposing internal scoring details directly to agents.

## Telemetry minimums

At minimum, record:

- operation type
- active policy name
- applied bounds / limits
- result counts or summary shape
- duration / failure class

## Affected files / surfaces

- `orgs/open-hax/knoxx/backend/src/cljs/knoxx/backend/core.cljs`
- any structured log / metric / receipt surface used by Knoxx graph operations
- adjacent docs/specs describing graph query behavior

## Verification

1. Expansion operations emit structured telemetry under the default policy.
2. Telemetry can distinguish policy choice, bounds, and outcome shape.
3. Agent-facing tool semantics remain unchanged.

## Definition of done

- Future adaptive traversal work has an evidence surface for judging policy quality without redesigning the public graph contract.

## Breakdown

Implementation is a single-file change in `knoxx/backend/core.cljs`: wrap the graph expansion call(s) with a structured log/receipt emit capturing operation type, active policy name, applied bounds, result shape, and duration or failure class. No new routes, no public contract changes, no new dependencies required. Verification is a manual smoke check confirming telemetry fields appear under the default policy without altering agent-facing tool semantics.

---

**Triage 2026-05-29 (incoming → accepted):** P2. Parent `knowledge-ops-adaptive-expand-policy-seam` is Done — seam exists. 2sp score confirmed: scoped to adding structured telemetry at the graph-op call site, no new routes or public contract changes. Accepted; deprioritised below P1 graph recovery tasks.

---

**Triage 2026-05-29 (accepted → ready):** All Ready gate criteria met. Score is 2sp. Blocking dependency `knowledge-ops-adaptive-expand-policy-seam` is Done — the policy seam call-site exists. DoD and telemetry minimums are unambiguously specified. Scope is bounded to `backend/core.cljs` and adjacent log surfaces with no public contract changes. Promoted to Ready.


## Proposed current-source refinement — issue 162

This appendix is an unqualified planning proposal. The entire original card,
including its Ready history, P2 priority, 2-point estimate, parent references,
five telemetry minimums and three verification outcomes, remains above unchanged.
Historical Ready does not qualify this new refinement or permit implementation.
See [design](../../docs/design/adaptive-expansion-telemetry-planning.md) and
[verification plan](../../docs/verification/adaptive-expansion-telemetry-planning.md).

### Context and outcome

Accepted source `3409977bca4ba35e09967f9a99d50867a679a73c` no longer contains
`backend/src/cljs/knoxx/backend/core.cljs`. Actual policy applications live in
`infra/openplanner/memory.cljs` (vector search and graph query) and
`infra/routes/memory.cljs` (search request bounds and optional session preview).
The native parent seam reads Done; the original hook filename and triage seam
reference are retained as historical evidence, without inventing a dependency.
Deliver structured, comparable requests and outcomes for these bounded graph
access operations while retaining the public tool, HTTP and permission contracts.

### Scope and acceptance criteria

1. Every executed policy-bound operation has a validated request/terminal pair
   with operation type, **resolved active policy name**, exact applied bounds,
   result counts or explicitly classified summary shape, and duration plus
   failure class. Default, fallback and future registered policies are
   distinguishable; unknown result shapes never manufacture zero counts.
2. Preserve all four existing policy application sites. Correlate route and
   adapter observations by stage so double search bounding is explicit, not
   counted as two SDK executions. Optional unbounded preview keeps its existing
   behavior and is explicitly distinguished. The unused writeback protocol
   method is not reported as an executed operation.
3. Pure payload selection, shape and admissibility laws use portable `.cljc`
   where practical. Clock, settlement observation and emission remain named
   host/infra boundaries. Reuse the existing structured diagnostic transport
   through a Knoxx-owned named extern contract; telemetry does not enter the
   durable run-event writer, create a ledger, or become authorization evidence.
4. Success, validation rejection, synchronous throw, asynchronous rejection and
   observed cancellation carry distinct safe outcomes. Clock/sink failure must
   not replace the primary value, rejection or cancellation, retry a graph call,
   add an extra SDK request, or change public responses. No invented cancellation
   interface, failure-message/stack capture or background policy optimization.
5. Default-policy smoke evidence must show fields, policy/bounds/outcome
   distinguishability, and unchanged agent-facing semantics. Meaningful pure
   and adapter red/green tests include paired independent contexts, fallback,
   unknown shape and failing clock/sink controls; retain the required baseline
   suites, warning and boundary gates. The future live verifier validates its
   served revision and owns seed/cleanup. Offline fixtures are not live proof.

### Review decisions and risks

Review must explicitly accept current-source placement instead of the original
single-file premise, the policy identity/sink contract, full operation coverage,
and whether all this still fits 2 points. If it does not, propose lawful
breakdown/re-estimation through Rheos before implementation; do not silently
reduce scope or metadata. No new card, estimate change, transition or acceptance
is performed here.

Foreign origin PR 339 owns the same adapter (captured head
`ac6ece1ca001075a7f9b19cf79751a4d30e023db`); original PR 305 is an immutable
superseded handoff. Issues 324/325 and the missing linked SDK/live prerequisites
remain distinct reconciliation/qualification holds. Coordinate current paths
and fresh merge/base/source guards after qualification; do not adopt their
branches or historical test claims. Personal parent PR 1, eager-merge issue 385,
lint/architecture issues 181/182, current-head planning review and lawful ready
admission remain prerequisites. No bot invitation, implementation or live
backend/provider execution occurred for this proposal.
