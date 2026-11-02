# Adaptive expansion telemetry — future verification contract

Planning only for [issue 162](https://github.com/open-hax/knoxx/issues/162) and
UUID `knoxx-knowledge-ops-adaptive-expand-policy-telemetry`; Ready/P2/2 metadata
and original historical body remain unchanged. No implementation command,
backend compiler, provider, SDK, database or live smoke has run in this lane.
These are required future red/green and human acceptance steps, not passes.

## Red before green

1. Add pure `.cljc` shape/law/selection tests that initially reject missing or
   ambiguous active policy identity; unknown requested ID/fallback; omitted
   operation/stage/bounds/outcome; fabricated counts; non-finite or backward
   duration; and leakage of raw contents/config/errors/URLs. A future registered
   policy with different bounds must be distinguishable from default, including
   lookup fallback. Supply times/data; do not call native clocks in pure tests.
2. Add real-call-site adapter fixtures for both memory SDK calls and the search
   request/optional-preview route stages. Stub only the named client/clock/sink
   boundary, never bypass the policy or authorizer. Assert exact baseline SDK
   request bodies, public values/response shapes, hit order, scopes, permissions,
   status codes and throw/rejection identity. Default policy must emit all five
   minimums; stage correlation proves route bounding is not a second SDK call.
3. Exercise success (including empty known results), validation rejection before
   client invocation, synchronous throw, async rejection, and any existing
   observable cancellation. Unknown graph response produces a summary class,
   not invented counts. Absent preview limit retains its current unbounded
   behavior; unused writeback does not manufacture telemetry execution.
4. Include clock and sink throws/rejections/unavailability; operation failures
   must retain their original result and no extra client call. Pair two
   independent contexts with reversed completion order, separate sinks/policies,
   reset/teardown and late completion; verify neither overwrites or deletes the
   other’s state. A deliberately failing async assertion must make the official
   configured runner fail nonzero; compiler exit 0 alone is insufficient.
5. Preserve and extend meaningful `core_memory_test`, `memory_routes_test`,
   `extern_openplanner_sdk_test`, `run_state_test` and existing client/ownership
   tests as relevant. Never delete historical failing cases to get green.
   Green implements portable decisions/contracts first, then thin effects.

## Required future gates

Run from the isolated implementation checkout with its owning package policy:

```sh
pnpm -C backend exec shadow-cljs compile test
pnpm -C backend test
pnpm -C backend typecheck
pnpm -C backend exec shadow-cljs compile server
pnpm -C backend lint
pnpm -C backend boundary:check
pnpm -C backend error-boundaries:check
```

Each relevant suite must finish with zero failures/errors and zero introduced
or touched warnings. Keep required existing CI gates and mutation/regression
contracts; add a meaningful mutation control that removing terminal emission or
misreporting policy/bounds fails the new tests. Issues 181/182 historical lint
and architecture failures are blocking evidence, not accepted warning baselines.
Missing linked `@open-hax/openplanner-sdk` at `../../openplanner/packages/` is a
prerequisite, not permission to change package policy, fetch foreign work or
expand into an OpenPlanner companion implementation. Isolate writable Maven,
Gitlibs, npm/PNPM, compiler, cache and temp roots; no shared ports/watchers.
These commands have not been executed for a documentation proposal.

## Human acceptance artifact required in the later implementation

Ship `scripts/verify-adaptive-expansion-telemetry.sh` and this guide’s completed
instructions. It must first verify the live backend’s exact served revision and
selected client, scope and sink contract. Use an explicitly authorized isolated
runtime; never borrow an unrelated PM2 service or restart one. Seed fictional
owned graph/memory fixtures in its own scope, trap cleanup, fail nonzero on any
failed criterion, and print what each comparison proves. No dashboard or new
public telemetry fields are needed; a UI tour is required if implementation
actually changes a UI, which this story does not propose.

Walk default search and graph expansion plus route search/preview stages. Show
all five request/terminal fields; compare a registered test policy and default
fallback with different applied bounds/outcome summaries. Compare public tool
and HTTP behavior against the baseline for success, empty results, invalid
query/limit, unauthorized requests, provider rejection and any supported
cancellation. Confirm no secrets/query contents/stack and no durable run-event
writer or extra SDK request is used for telemetry. Repeat paired independent
contexts and prove cleanup affects only seeded data. Live failure controls must
be contained and authorized; if unavailable, mark unverified rather than silently
skip. Offline mocks prove adapter logic, not live SDK/model/storage admission.

All three original outcomes are independently reported: default emission;
policy/bounds/outcome distinguishability; unchanged agent-facing semantics.
Missing SDK, provider/cache configuration, owned runtime, sink attachment or
served-revision proof keeps live acceptance unsatisfied. Retain issues 324/325
and foreign 339 qualifications; the verifier cannot replace them. Historical
Ready, parent Done, this plan or local receipt validity is not permission to
implement, merge or deploy. Root coordinates review requests and publication.
