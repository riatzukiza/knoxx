# Generator lifecycle planning for existing issue 160

This is a proposed refinement of [open-hax/knoxx issue 160](https://github.com/open-hax/knoxx/issues/160), existing task `knoxx-generator-runtime-create`, P2 / 3 points. It creates no replacement card. The original card, Ready status, estimate, UUID, comments and engine history remain unchanged. Native Ready readback establishes the existing task's current state; it does not approve the amendments below. No implementation, runtime activation, live request or new admission occurred in this planning change.

## Outcome and current evidence

Knoxx must activate its own configured generator resources through its event-runtime lifecycle, release their owned handles on stop, and expose truthful generator status through `GET /api/admin/config/events`. Cataloguing or logging a resource alone must not count as successful activation.

Accepted source `3409977bca4ba35e09967f9a99d50867a679a73c` still has no generator runtime module. `infra/event_runtime.cljs` starts triggers, schedules and sources, stops those runtimes, and reports their status, with no generator hooks. `domain/driver/registry.cljs` is a compatibility delegate to Katamorph, including `start-source!`; that name does not demonstrate a generic generator lifecycle contract. `backend/deps.edn` pins Katamorph at `b00316a64310190335e4cb1a31b08d601eff8bb0`. Inspect that exact API before implementation; do not copy child code, update its pin or invent an implementation in the child.

The HTTP seam needs explicit integration. `infra/routes/tools.cljs` builds `events-control-response` using `domain.event.dispatch/status-snapshot`, not `event-runtime/status`. That snapshot currently returns events and triggers without generators. Adding a field only to `event-runtime/status` cannot satisfy the HTTP criterion. Preserve the existing response envelope and fields; the proposed new key is `[:runtime :generators]`, containing the generator runtime's status map. Its inner `:generators` is a vector of shaped descriptors, and its `:running` reflects observed generator lifecycle state. Do not flatten that map into a top-level collection, discard existing event/trigger information, or silently reinterpret the legacy dispatcher `:running` field.

The preparation base is personal synchronization branch `codex/sync-knoxx-origin-main` at `5670338fcf1db7d51a2690f388e4e4c5c409c48a`, preserving accepted source history. Parent qualification remains required. The earlier captured intake contained 206 upstream open issues, 25 upstream open PRs and 3 personal open PRs. That count predates the root coordinator’s publication of personal PRs 4, 5 and 6. A later native read contains 6 personal open PRs; owned implementation PR 6 at `c46f571ba70d155fcd62080a9eec0831f5101f96` intersects event-runtime and tools-route files and must be guarded and serialized before generator implementation. Full exact-head path comparison of all 25 upstream PRs found no generator namespace, `infra/event_runtime.cljs` or `infra/routes/tools.cljs` overlap; this is a captured file-scope result, not absence of every possible conceptual dependency or foreign owner. Foreign work and later changes require a fresh guard before implementation.

## Original five definitions of done — retained

1. `domain/generator/runtime.cljs` exists with `start!`, `stop!`, `status` arities matching `domain/schedule/runtime`.
2. `infra/event_runtime.cljs` requires and calls generator-runtime in `start!`, `stop!`, and `status`.
3. `pnpm -C backend run typecheck` exits 0 (shadow-cljs compile server).
4. `pnpm -C backend lint` exits 0 (clj-kondo).
5. `GET /api/admin/config/events` response includes `:generators` key in status map.

The original Work also requires loading generator resource IDs, logging each without disclosing resource secrets, delegating to the driver registry where applicable, resetting owned state on stop, and returning `{:running ... :generators [...]}` from status. All those outcomes remain in scope.

## Explicit amendment requiring planning review

Literal criterion 1 and the proposed effectful `domain/schedule/runtime` copy conflict with current AGENTS: domain is pure; state, resource loading, logging and driver effects belong outside it. Proposed resolution:

- Portable `.cljc` law/shape/domain functions validate supplied descriptors and observations, compute activation/stop decisions and project status. A proposed `domain/generator/runtime.cljc` receives plain Clojure data and has no atoms, filesystem, registry mutation, timer, console, SDK or provider calls.
- `infra/generator/runtime.cljs` owns the public effectful `start! [config]`, `stop! []`, and `status [config]` lifecycle arities. It composes existing shaped resource APIs, explicit driver capabilities and pure decisions. Raw host handles are inspected only by a named extern adapter; ordinary infra treats them as opaque.
- `infra/event_runtime.cljs` wires that infra lifecycle seam after the source-start call, on stop, and in status. The existing disabled-process guard must still prevent generator acquisition.
- `infra/routes/tools.cljs` includes the observed generator status at `[:runtime :generators]` while preserving the existing envelope and authorization requirements.

This is a proposed placement amendment to criteria 1–2, preserving their runtime behavior and arities; it is not a claim that the literal old file-path criterion has been satisfied. Review must accept the amendment, or specify another architecture that satisfies the pure-domain rule. Do not implement a domain-to-infra facade, label an effectful callback invocation pure, or silently rename the original contract. No source file has been created here.

## Contracts and decisions to settle before red

Use the existing `GeneratorContract`, canonical resource identity and catalog rules at the boundary; do not invent a competing ID equivalence, normalize distinct resources together or require optional generator fields without a reviewed law. Pure lifecycle contracts must name validated descriptor data, supplied runtime observations, acquired/released/failed outcomes and a sanitized error shape. Driver/resource values remain untrusted until checked. Status contains allowlisted identity/kind/driver/emission and lifecycle information, not arbitrary resource configuration, credentials or opaque handles.

Review must pin the exact registered-driver selection and lifecycle semantics at the existing Katamorph boundary. It must define which supplied generator declarations have an applicable hook, how kind-only or emits-only declarations are handled, and how unknown/unsupported drivers are refused visibly. Do not report those cases active merely because they were catalogued. No new live model/provider/Discord driver is part of this task. If real activation cannot be completed through the verified existing boundary within this scope, record that prerequisite or propose a lawful breakdown; do not substitute a logging-only implementation for the outcome.

Review must also settle synchronous versus asynchronous return semantics. Keep the public lifecycle arities and compatibility obligations; audit every event-runtime start/stop/reload and HTTP/bootstrap caller before changing a return type. Never serialize an unresolved Promise as a status, claim `:started` before required acquisition completes, discard a rejection, or hide partial startup. Modern `^:async` belongs in the effect adapter when needed. A reviewed failure must leave generator status truthful and release only handles acquired by that owning generator run, once; it must not clear another context's state or mutate the unrelated trigger/schedule/source contract. If coordination changes are necessary to propagate failure faithfully, include their affected callers and tests in the reviewed implementation scope.

Repeated start, start while disabled, stop before start, repeated stop, restart and rejected startup need defined outcomes. Observation/handle ownership belongs to an explicit runtime context. Preserve production wrapper arities while allowing isolated constructed contexts for tests, rather than assuming one global atom can isolate two concurrent fixtures. Logs name resource identity and phase without copying configuration. No new immutable ledger authority, event-dispatch deduplication rule or common semantic promotion is introduced.

## Compatibility with the separately owned trusted capability work

Issue 161 is a separately owned, unqualified proposal. Its immutable preparation head `9faf559120aa345777a2c2ef5edc52e560a3d71c` (relevant capability/runtime/route bytes preserved in personal PR 6 head `c46f571ba70d155fcd62080a9eec0831f5101f96`) attaches a runtime-owned `:action/capabilities` context at the outer infra boundary, replacing caller-supplied slots; it also routes external HTTP dispatch through that boundary. This planning branch does not import that implementation or make it a hard card dependency. Before any generator implementation, require a fresh merge and path-overlap guard after the 161 qualification decision, and inspect the actual resulting accepted capability contract.

Generator integration must preserve that trusted outer composition across event-runtime config/start/stop/reload and tools-route edits. Generator/resource descriptors and HTTP request configuration must never replace or manufacture the trusted capability map, grant spawn authority through supplied data, erase a legitimately attached context, or serialize its callable handles into status. Retain source, schedule and trigger behavior, internal versus external provenance, and the captured Discord compatibility obligations; generator lifecycle changes do not authorize new provider or Discord actions. Keep the disabled-runtime and no-spawn controls intact.

Add explicit compatibility fixtures using fictional trusted contexts and supplied driver doubles: legitimate outer attachment remains effective through lifecycle and route composition; malicious generator/resource/HTTP capability slots cannot supersede runtime authority; internal/external provenance remains correct; disabled or unauthorized paths do not acquire a generator or invoke spawn. Verify the real owner boundary rather than accepting a map solely because it contains the expected key. No real agent session, Discord client or provider is invoked. The reviewed implementation must preserve the eventual accepted 161 seam without silently adopting its currently unqualified branch.

## Future acceptance and red/green verification

These are requirements for a later implementation, not executed checks in this PR:

1. Pure fixtures reject malformed descriptors/observations, preserve existing generator IDs and supplied order, and project stopped, starting, running and failed states without any host effect. Portable `.cljc` decisions run on JVM Clojure and the owning NBB version when supported; declare the target/command first and retain any portability limit. The actual compiled CLJS artifact remains mandatory for the Node runtime.
2. Constructed adapter contexts use fictional resources and deterministic driver doubles. Valid startup acquires each owned generator once; applicable driver hooks are actually invoked. Unsupported declarations and thrown/rejected acquisition produce explicit failure and no false active status. No backend/provider/network service is contacted by these fixtures.
3. Stop releases every acquired owned handle exactly once, clears generator state, and handles partial failure observably. Empty resources, disabled runtime, repeated start/stop, restart, late callbacks and async rejection are exercised. Paired contexts A/B prove that stopping/resetting A preserves B's handles/status. Restore fixtures even after failure; use only per-run private resource/temp directories.
4. The real event-runtime composition has generator start after source-start invocation, generator stop and nested status hooks. Test the disabled guard and acquisition/failure ordering with supplied doubles; retain meaningful existing source/schedule/trigger assertions. If source startup is asynchronous, review its completion ordering rather than treating call order as completion evidence.
5. The actual registered HTTP route, with an authorized fictional principal, returns the unchanged envelope plus `body.runtime.generators` status and its inner descriptor vector. Test stopped, running and failed cases, and unauthenticated/insufficient-authority denial. `availableGeneratorKinds` is not evidence that runtime generators are active. Use the owning extern/Fastify injection seam for offline tests; do not bypass session/permission gates.
6. Demonstrate red on absent generator lifecycle/status, wrong nested response shape, ignored rejection, cross-context cleanup and false success. Green must pass the relevant compiled test suite, full backend lint and server typecheck. A deliberate failing fixture must produce a nonzero official runner result; do not replace the production runner or filter away failures.
7. Ship the future runnable human verification script and guide described below for this user-reachable API. Capture its actual source identity, prerequisites, failures, observed output and cleanup. Mocked assertions do not replace the original API outcome or a required live verification artifact.

Construction order is law → shape → extern → domain → infra. Red laws/tests precede green adapters. Keep existing package-manager policy, pinned dependencies, configuration, workflows and meaningful tests; any required dependency or harness change belongs in the reviewed implementation, with its actual limit shown.

## Required future commands and current holds

```bash
pnpm -C backend exec shadow-cljs compile test
pnpm -C backend run typecheck
pnpm -C backend lint
pnpm -C backend boundary:check
pnpm -C backend error-boundaries:check
```

Use the configured official test script where needed to enforce failure counters/nonzero asynchronous rejection; retain its full relevant scope. A compile exit 0 with failed assertions or warnings is a failure. The original zero-warning lint/typecheck outcomes remain mandatory. Existing issues 181/182 own the broader lint/coverage/architecture gates and remain a hold, not a waiver or a hidden baseline adjustment. No implementation, dependency install, suite, service or live smoke ran for this documentation-only proposal. Future implementation requires qualified planning decisions, current native card authority, fresh source/overlap guards, red/green evidence, and every required review/check. Historical Ready/triage does not supply current PR approval or review rounds.

## Scope, non-goals and size review

The proposed implementation touches only the new generator law/shape/domain and named extern/infra seam, event-runtime integration, the existing events HTTP response seam, tests and human verification artifacts necessary for all five outcomes. Names and exact fixture/harness targets need planning review. Do not refactor all legacy schedule/trigger/source I/O, adopt the foreign PR305 split, edit another repository, provision credentials, change PM2/services/deployment/protection, or alter board status/history. Issue 161 implementation remains separately owned and excluded; the compatibility obligations above apply to shared runtime and route edits.

Retain the original 3-point estimate for review; this note does not re-estimate or manufacture child cards. Review must decide whether lifecycle/error ownership, route evidence and mandatory gates fit 3 points. If they do not, propose the complete breakdown through the existing planning/Rheos process without dropping any original definition of done. Implementation is held until that decision and the architecture amendment are qualified.

Publication must use the mapped personal fork and the verified synchronization parent. Accepted `.github/workflows/auto-merge.yml` has an eager non-draft same-repository caller, write permissions, pinned reusable workflow `0823119585244bc5dfeea3c2b4cd64b0b0aa7cef`, SQUASH and App secret forwarding. Existing issue 385 owns that readiness/trust hold. Root controls any publication; this lane neither triggers it nor changes workflows/settings. No origin development PR, merge, auto-merge or deployment is authorized by this proposal.
