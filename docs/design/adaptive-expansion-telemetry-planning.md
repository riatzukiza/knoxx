# Adaptive expansion telemetry — proposed current-source plan

Refs [Knoxx issue 162](https://github.com/open-hax/knoxx/issues/162).
Existing UUID `knoxx-knowledge-ops-adaptive-expand-policy-telemetry`, native
Ready / P2 / 2 points; parent `knoxx-knowledge-ops-adaptive-expand-policy-seam`
natively reads Done. This document proposes a refinement; neither historical
Ready nor the parent’s earlier green claims supplies current planning approval.
Base is personal synchronization `5670338fcf1db7d51a2690f388e4e4c5c409c48a`,
containing accepted source `3409977bca4ba35e09967f9a99d50867a679a73c`.

## Original outcome and complete obligations

Future adaptive traversal must have an evidence surface for comparing policy
quality without redesigning the public graph contract. Preserve all five
minimums: operation type; active policy name; applied bounds/limits; result
counts or summary shape; duration/failure class. Preserve all three verification
outcomes: default-policy operations emit telemetry; policy choice, bounds and
outcome shape are distinguishable; agent-facing tool semantics remain unchanged.
Requests and outcomes both need structured evidence. This is not optimization,
a dashboard or disclosure of internal scoring to agents.

## Accepted seams and placement decision

The original `backend/src/cljs/knoxx/backend/core.cljs` path is absent. Current
`infra/openplanner/memory.cljs` applies `bounded-search-params` in
`openplanner-memory-search!` and `bounded-expand-params` in
`openplanner-graph-query!`. `infra/routes/memory.cljs` applies search bounds
again at request decoding and applies preview bounds only when a positive limit
is supplied. `bounded-writeback-params` is declared by the protocol but has no
accepted production call site. Preserve this entire inventory; do not add
policy applications or narrow the story to only one query wrapper.

The registry returns a policy object with fallback; `get-policy` does not return
its resolved ID. Today all four sites use its zero-argument default lookup.
A requested unknown ID is not an active policy name. Review a minimal internal
resolution result pairing the actual object with its resolved registered ID
and fallback classification from one registry snapshot. Preserve the existing
lookup API and default behavior. Do not derive identity from a JS class name,
re-read mutable state after execution, or invent a future request selector.
Do not turn the existing stateful registry into a new pure authority; any
required resolution/registry effect stays outside new portable payload laws.

`infra/core.cljs` configures Fastify’s stderr logger, but its logger helpers are
private raw-JS calls. `domain/error_observatory.cljs` is a legacy structured
console diagnostic boundary that includes raw messages/stack. Neither declares
a validated graph telemetry payload. `record-retrieval-sample!` only records
mode and rolling timing, so cannot satisfy the five minimums. `run_state.cljs`
explicitly distinguishes its application durable-event admission hook from
optional stream telemetry; `infra/run_events.cljs` installs the selected
provider’s durable writer. Preserve this ownership and queue behavior.

Preferred reviewed emission route: use Knoxx’s existing local structured
logging transport through a **named extern telemetry boundary**, receiving only
a validated CLJS payload. Do not copy raw logger interop into pure/ordinary
infra namespaces, install graph telemetry as the run-event sink, synthesize run
IDs, or introduce Clio events, event types, projections, immutable ledgers or
an OpenPlanner event writer. Implementation review must settle the exact logger
attachment/availability contract; absence of an available sink is an explicit
observation failure, not durable admission fallback or operational qualification.
No new dependency or OpenPlanner companion implementation is proposed.

## Proposed data and contracts

`shape` describes a versioned internal request/terminal record. Portable
`domain` selection and `law` validation live in `.cljc` where practical; they
receive plain supplied data and have no clock, logger, JS object, filesystem or
SDK access. A named extern owns timestamp/duration clock and native emission;
infra owns policy resolution, actual operation settlement and attachment.
The existing `extern/clock.cljs` supplies ISO timestamps only; it does not prove
a monotonic duration API. Review and test the named duration boundary rather
than assuming subtracting wall time is safe.

Allowlist a bounded request correlation token, stage, operation, resolved policy
ID/fallback classification, applied numeric bounds, outcome class, known result
counts or summary class, duration and measurement/observation status. Never
include query text, documents, embeddings, credentials, raw exceptions/stacks,
provider URLs, config dumps, arbitrary map keys or tenant-sensitive identifiers.
Safe correlation belongs to the call context, not a process-global mutable sink
or policy override. Telemetry cannot change tenant/project scopes or permissions.

Applied bounds are what actually reach the operation: search `k` and `fetch-k`,
graph `limit`/`edge-limit` and derived `max-cost` when present, optional preview
`limit`. Distinguish requested and applied values if both are included; only
allowlisted numeric requests may be retained. Preserve the SDK request body,
source/project/session scoping, default clamps, mode, hit order and result
values. Search’s route bound and adapter bound are distinct stages of one call;
request-stage rejection is not reported as an SDK call. Graph summary extraction
must validate the actual selected client’s returned CLJS shape before counting;
unknown/unavailable shape is explicit, never a zero-result success. Search hits
and preview rows can be counted without retaining their contents. Unbounded
preview and unused writeback are explicitly not policy-bound executions.

## Settlement, failure and isolation laws

Emit a request observation before each actual bounded execution and at most one
terminal observation after it settles, correlated by stage/call. Record duration
and safe failure class for success, validation rejection, synchronous throw,
async rejection and cancellation **only when existing cancellation is observed**.
Do not invent new cancellation semantics, swallow a rejection, normalize away
error identity, wait indefinitely on a sink, or change sync-vs-async contracts.
Invalid/backward/non-finite clock readings produce an explicit unavailable
measurement classification instead of fabricated duration; primary outcome is
preserved. A sink’s throw/rejection/unavailability is distinct from a failed
operation. It must not cause retry, fallback provider calls, duplicate terminal
records, changed responses or replacement of the original failure. Record the
observation limitation through the same bounded local diagnostic contract when
available; failure to report remains a visible verifier failure, not a pass.

Attach emitter/clock/context at outer composition. Two independently configured
requests/runtimes must not replace one another’s sink, policy snapshot or timing
state. Late completion belongs only to its initiating context. Reset/teardown
cannot remove another owner’s attachment. No global settings/process mutation
or new event authority is needed. Policy comparison is evidence, not a learned
policy update or authorization grant.

## Review and sequencing

Review the stale placement amendment, default/fallback identity result, exact
sink/clock contract, four-site coverage and observation failure law explicitly.
Review whether the complete outcome fits the existing 2-point story: retain
its estimate until review, and propose lawful breakdown/re-estimation if needed.
No silent scope reduction or invented child UUIDs/dependencies is permitted.
The original hook filename parent remains prose; native Done seam readback is
separate evidence, not a hand-authored relationship update.

At the captured intake, origin PR 305 head
`1d3b207ab2e5bfc6b2fa149836b855d458a9c997` is a superseded immutable handoff;
foreign PR 339 head `ac6ece1ca001075a7f9b19cf79751a4d30e023db` owns the same
memory adapter and local generation/embedding integrations. Their full source,
SDK, tenant/vector verification and issues 324/325 holds remain separate. This
plan neither adopts those branches nor treats their historical assembled-suite
claims as accepted current-main proof. Fresh native path/base/head and merge
compatibility guards are required after their qualification before touching
shared paths. Current native personal/upstream inventories are time-qualified
captures, not claims that no one else owns adjacent work.

Personal synchronization PR 1 must qualify; eager automation issue 385 must be
honored with draft/auto-off publication if still unsafe. Issues 181/182 remain
lint/architecture holds, not waivers. This refinement needs canonical planning
review and a lawful Rheos admission decision before red/green implementation.
Unknown or exhausted native capacity is a hold; no local approval or full round
is inferred. No software, board lifecycle, provider, host or shared service ran
for this planning candidate. A later origin release/deploy is separately gated.
