---
category: "tasks"
labels: "tasks, 5sp, has-parent, verification, translations, cms"
dependency: []
parent: "knoxx-translation-review-chat-panel"
type: "task"
write-id: "1792097673224-0.mt5bhq2bloaomsatdxw"
points: "5"
title: "Seed run-owned legacy review fixtures and prove auth preflight"
priority: "P0"
status: "breakdown"
uuid: "knoxx-translation-review-legacy-fixture-auth-preflight"
created_at: "2026-10-15T18:40:12.445Z"
---

# Seed run-owned legacy review fixtures and prove auth preflight

## Context

Native Codex review5537788801, root4286589102 on source7722bb23, identifies an
independently implementable verification slice hidden in the existing core5.
The core already owns all six mutation runners, project/filter/work/unmount
fences, saving ownership, read-only SFT feedback and controlled browser races.
Its later legacy fixture amendment additionally requires a named raw-Mongo
extern, seed/read/cleanup entry points, live authenticated state/counter reads,
two anonymous POST proofs, process tests and signal-safe exact cleanup.
Those mandatory mechanisms need their own estimate and prerequisite owner.

Parent: `knoxx-translation-review-chat-panel` (accepted8, unchanged/open).
Consumer: `knoxx-translation-review-current-view-mutation-lifecycle` (core5).
The existing draft5 and locale3 consume this prerequisite transitively through
core5; no cycle or new application/server feature is introduced.

## Outcome

Before frontend-controlled verification starts, a reviewer can create exactly
one safely owned legacy Spanish segment, prove both real legacy review POSTs
and the exact legacy document-detail GET refuse a credential-free caller without
any effective write, and remove exactly
that fixture with checked cleanup. The resulting artifact has exact source,
run/DB/tenant/document/segment identity, truthful failure evidence and zero
remaining owned labels/segments. It preserves the resource fixture cardinalities.

## Scope

Own only this legacy fixture/auth-preflight mechanism and its tests/artifact:

- Extend the existing backend verification entry and shared shell helper;
  add the named extern and automatically discovered backend regression test.
- Extend `scripts/verify-translation-split-review.sh`, shared tour integration,
  `scripts/test-cms-verifier-preconditions.py`, and
  `docs/verification/translation-split-review.md` for the named phase below.
- Reuse the existing source-built legacy routes and their actual frontend wire
  builders. No endpoint, production auth/review/tenant law, provider or product
  data model changes. The other three stories own all frontend behavior.
- Register the phase before navigation/interception/manual filter counters.
  Keep the existing resource bulk assertion accurately named and preserve
  the independent real SFT GET gate owned by core5. Locale3 still owns its
  real anonymous manifest/legacy-document GET checks and locale UI proof.

### Run-owned legacy fixture and exact cleanup contract

Extend the existing `backend/scripts/translation_split_review_fixture.cljs`
entry point and shell helper with explicit legacy-seed/read/cleanup actions.
Put the complete Mongo lifecycle of this touched entry behind the system-named
child `knoxx.backend.extern.openplanner-translation-mongo.verification-fixture`
(`backend/src/cljs/knoxx/backend/extern/openplanner_translation_mongo/verification_fixture.cljs`),
with the automatically discovered regression namespace in
`backend/test/cljs/knoxx/backend/extern/openplanner_translation_mongo/verification_fixture_test.cljs`.
The public boundary accepts Clojure-shaped action/settings/scope data and returns
Clojure-shaped results; it neither accepts a database handle constructed by the
script nor returns a native client/database/collection/cursor. The adapter owns
MongoClient construction, connect/database selection, collection/query/result
conversion, deletion and finally-close for the entry's existing resource actions
as well as the new legacy actions. Move the existing raw Mongo helpers/lifecycle
out of `backend/scripts/translation_split_review_fixture.cljs`, not just new calls.
Keep pure fixture construction and existing production store/domain contracts
intact; no second review, authorization, schema, tenant or cleanup law is added.
Document this system-named child in the existing boundary inventory where required;
no use-case-named generic extern or opaque raw-Mongo escape hatch is introduced.
Its already owned typed read action also supplies exact run-owned resource
dispatch/claim/batch/receipt snapshots to core5's separate dispatch auth assertion,
using the same request/result and read-only/tenant guards. It adds no fixture,
public API, dispatch/provider call or legacy probe count.

Add the pure named law `knoxx.backend.law.translation-review-verification-fixture`
in `backend/src/cljs/knoxx/backend/law/translation_review_verification_fixture.cljc`,
with `FixtureAction`, `FixtureSettings`, `FixtureScope`, `FixtureOwnership`,
`FixtureRequest` and action-dispatched `FixtureResult` Malli schemas. Expose
`assert-request!` and `assert-result!` with named `::request` / `::result` contract
IDs, called by every public adapter operation for its actual action. Cover all existing seed/cleanup/memory/
materialization and new legacy-seed/read/cleanup actions, required scoped IDs,
nonsecret run/source/DB identity and successful/partial cleanup results. Compose
existing translation/tenant/segment contracts at the extern boundary; do not copy
review, authorization or tenant policy into this fixture law. Every public adapter
entry validates its complete request before client construction/connection or any
read/write, and validates converted result data before returning. Native handles,
unknown actions, missing/malformed identity and scope, or malformed results refuse.
Validation errors retain the contract ID/field paths and original failure category
without logging URI/settings values, cookies, keys, content or native objects.
For any client already constructed, finally-close still runs when action or result
validation fails. A request rejected before construction performs zero client,
connect, query, mutation or close side effects. A secondary validation/cleanup
failure never replaces an already recorded action first cause. Add pure schema
regressions in `backend/test/cljs/knoxx/backend/law/translation_review_verification_fixture_test.cljs`
and adapter controls proving invalid requests perform zero client/query/mutation
effects, invalid results cannot return success, and close/original-failure custody.
These are input/output contracts for the same already-owned fixture boundary.

Before a seed attempt, register run ownership and cleanup intent in the existing
trap. Derive a distinct document ID from the helper's fresh run namespace, carry
the actual org/project, garden (including a real nil legacy coordinate) and es target,
and refuse an existing/colliding row. Persist exactly one valid pending segment
with run-owned fixed synthetic source/translation text and no labels. Retain its
actual Mongo ObjectId and full scoped identity even on a partial seed failure.
Never adopt an ambient record or treat an upsert of an existing record as a fresh
owned seed. Legacy review contracts require a nonblank project: if the actual
server scope cannot supply it, fail the precondition instead of inventing one
or sending a malformed payload and calling its response authentication proof. An authenticated source-built document/detail and segment read must
recover that exact ID and scope from the live backend before any anonymous probe;
this also detects a helper database different from the selected live client.
Use the exact frontend `api/get-document` encoded document/target-language path
and optional project/garden query (`api.cljs:19–36`). While this owned legacy
fixture still exists, first prove that authenticated detail GET is ready and
returns the owned segment/scope, then send exactly one independent empty-cookie,
no-Authorization/API/identity-header GET to that same URL before interception.
Require401/no_session; unexpected200/400/404/503, redirects, missing error code,
unknown identity or unavailable readiness fails without logging content bodies.
Snapshot and compare authenticated state/hidden counter across this probe too.
This detail-read check belongs here before exact fixture cleanup, not after the
fixture is gone or only behind the later synthetic browser adapter.
REST mode or unavailable/mismatched Mongo storage cannot silently pass or fall
back to somebody else's data.

Run this prerequisite as a distinct real auth-preflight phase before translation navigation,
synthetic interception, or manual filter request counters. Snapshot the owned
segment's status/labels and document review summary through authenticated reads,
and its stored label_version_counter through the exact owned helper read before
any anonymous detail GET or POST. The API's segment-view does not expose that counter; do not pretend an HTTP snapshot
covers a failed label attempt that changed only the counter. Use the frontend's actual encoded paths/scope and contract-valid reject
payloads (`overall:"reject"`, no corrected text; the segment label also carries
the normal adequacy/fluency/terminology/risk fields). Rejection is deliberate:
these two existing paths invoke graph-memory writes only for approved outcomes.
Send exactly one credential-free POST per route from an independent empty-cookie
client, require401/no_session, and compare the same authenticated state after
each probe. Keep the one anonymous detail GET and the two anonymous POST counts
separate; neither manifest/list/SFT readiness nor their401s substitute for detail. Capture only scope/IDs, response status/error code, counts/digests
and equality outcomes, never credentials or unexpected content.

Remove only labels belonging to the recorded segment ID and org/project, then
the exact owned segment with ID + complete doc/org/project/garden/locale identity.
Validate ownership before deletion and prove zero remaining owned labels/segments
plus absence of this document from an authenticated scoped list. Do not delete
source events, resource rows, shared legacy data, whole collections or any broad
namespace. The aggregated legacy document disappears when its sole segment is
removed. Complete and verify this cleanup before starting the ordinary resource
browser workflow, preserving default18 and locale20/18/1/1 cardinalities and all
existing durable candidate evidence. EXIT/INT/TERM also attempts the same exact
owned cleanup after setup, probe or interruption failure. A cleanup failure keeps
the original failure and makes an otherwise successful artifact nonzero. Retain
the exact run/source/DB/full-scope/segment ownership record outside disposable
scratch until absence is verified; cleanup interruption must not erase that
record. Explicit cleanup re-entry consumes that same record, validates its source,
DB and complete scope, and resumes only the exact recorded labels/segment. A
missing already-removed owned label is idempotent; changed or unknown ownership
refuses before further deletion. Preserve the original interruption/failure and
each partial cleanup result, even if later explicit recovery succeeds. No broad
sweep or ambient-row adoption is a recovery mechanism.

Extend the existing registered process-test surface
`scripts/test-cms-verifier-preconditions.py` for split-review auth-preflight
ordering/status/error/cookie isolation and original-failure/cleanup aggregation;
its existing CI invocation stays the owning harness gate. Boundary unit tests
cover a clean empty store seed/read/cleanup, collision refusal
without modifying the prior row, partial seed failure/retained ownership,
org/project/garden mismatch refusal, preserved foreign rows/labels, cleanup
failure, and interruption after seed. Add a distinct causal process schedule:
create an owned segment/label plus foreign controls in isolated storage, execute
the actual cleanup helper, and synchronize on completion of its exact label
removal while the segment still exists. Deliver TERM before segment deletion;
retain the real terminated/partial outcome and ownership record. Re-enter cleanup
in a fresh process using only that recorded identity, then prove zero owned
labels/segment and unchanged foreign rows/labels. This is neither a signal before
cleanup starts nor an ordinary thrown cleanup error. Boundary tests separately
assert connect/query/decode/close custody (including connect/action failure), no
native handle crossing, and missing-label re-entry versus mismatched ownership.
The auth harness must fail on 200,400,404,
503, missing no_session, changed state, wrong live database or missing readback.
Add the causal detail-open control: all other preflights return401 but this exact
anonymous detail GET returns200; the phase must fail, log no response content and
complete checked cleanup. Its ready authenticated response and independent
credential-free request must use the same encoded owned URL/scope before any
interceptor or fixture removal; no synthetic response supplies auth evidence;
no such response supplies auth credit. A deliberately failing cleanup must not
mask the original probe failure. The normal resource fixture remains18 before
and after this preflight and opt-in locale mode remains20/18/1/1. Register real
RED before the implementation, then require zero failures/errors/warnings from
the existing backend test build and typecheck (`pnpm -C backend exec shadow-cljs
compile test`, `pnpm -C backend typecheck`), explicitly registered full backend
lint `pnpm -C backend run lint`, the affected backend build where required,
shell syntax and the genuine live preflight with exact source custody.
The lint script (`backend/package.json:26`) runs clj-kondo over all `src/cljs`
and `test/cljs` with `--fail-level warning`; typecheck at line32 only compiles
server and cannot substitute. Retain complete lint output and actual native exit;
require zero errors and zero warnings without narrowing paths, suppressing
warnings or waiving a historical failure for this story's law/extern/test work.
Run `python3 scripts/test-cms-verifier-preconditions.py` as its registered gate.
These backend/helper gates belong to this separately estimated prerequisite.
The dependent frontend stories retain their own unit/browser gates; this
planning card executes none of them.

## Non-goals

No application feature, translation/review/publication semantic change, new
server API, OpenPlanner companion, provider/model call, judgment/approval,
source-event insertion, shared legacy cleanup or production rollout. This
fixture/auth phase is genuine integration evidence, separate from the
frontend stories' explicitly synthetic races. Its two anonymous POSTs and exact
detail GET are real requests expected to have no effect; synthetic race requests remain entirely
intercepted. Fixture creation/cleanup writes only the recorded run-owned rows.
A planning card, mock pass or unpublished model summary proves none of this.

## Acceptance criteria

1. In the existing real auth preflight, before any synthetic interceptor,
    send exactly one credential-free POST to each source-built legacy route:
    `/api/translations/segments/:id/labels` and
    `/api/translations/documents/:documentId/:targetLang/review`. Resolve the
    encoded run-owned IDs and valid scope/payload from the actual legacy fixture
    and frontend API, not literal placeholder IDs or the resource bulk endpoint.
    Independently establish enabled auth and compatibility/route readiness.
    An empty cookie jar and absent Authorization/API/identity headers are
    mandatory. Both must return401/no_session, and authenticated before/after
    reads must prove unchanged labels/review state and no effective write.
    A400/404/503, unavailable prerequisite, unknown state comparison or unexpected
    content fails this check without logging the body. Keep the existing resource
    bulk anonymous check as a distinct accurately named assertion. Add these
    named assertions within the existing auth step; preserve default18 and locale
    opt-in20/18/1/1 scenario counts and the separate SFT401 preflight.

2. In that same real preflight, while the recorded fixture is present, require
   authenticated readiness/identity at the exact frontend-built legacy detail
   GET, then one independent credential-free request to that exact URL returning
   401/no_session before interception. Check unchanged authenticated state and
   hidden counter, zero credential/redirect leakage and checked cleanup; a
   detail200 must fail even if every other preflight returns401. Keep exact counts
   one detail GET plus the two separate POSTs; manifest/list/SFT checks do not
   replace this affected read-surface proof.
3. Seed/register ownership before any possibly partial insert. Refuse collisions
   and ambiguous upserts without adopting or modifying prior rows. Retain exact
   IDs/full scope after partial failures, and verify the selected live backend
   actually reads that same database/segment before using any auth result.
4. Each anonymous probe compares authenticated review/label state and the
   actual stored label_version_counter before/after. No API-only comparison
   pretends the hidden counter is covered. Unknown comparison or readiness
   cannot qualify. Capture status/error codes and equality/digests, never keys,
   cookies, credentials or unexpected content bodies.
5. Checked cleanup removes only the exact recorded labels/segment with full
   ownership/scope guards, preserves foreign data, proves absence from stored
   rows and authenticated document listing, then finishes before the resource
   browser workflow. Default18 and locale20/18/1/1 remain unchanged.
6. Registered unit/process controls cover empty-store seed/read/cleanup,
   collision refusal, partial setup, scope/DB mismatch, foreign preservation,
   cleanup failure and interruption, including TERM after actual owned-label
   deletion but before segment deletion, then exact fresh-process recovery from
   retained ownership. Prove the partial state before re-entry and zero owned
   residue/unchanged foreign controls afterward. The auth phase fails for200/400/404/503,
   missing no_session, changed state/counter, missing readback or wrong live
   source. Cleanup failure preserves the original failure and causes nonzero
   status; retained ownership supports operator recovery without a broad sweep.
7. A genuine source-built live run passes both POST probes, the detail GET and exact cleanup
   after actual source/environment preconditions are established. No ambient
   fixture, mocked auth response, skipped route or production service is used
   as a substitute. The artifact names what was proved and its limits.

## Verification

Register meaningful causal RED before implementation using the existing entry
and process suite; no fabricated dist, skipped failure or new shadow validator.
Run the affected backend test build/typecheck and required build with zero
failures/errors/warnings, the registered Python process suite and shell syntax,
then the genuine owned live phase with complete exit/cleanup evidence, including its
one exact detail GET and both legacy POST auth checks. Preserve
all existing tests and default consumers. The integration artifact is runnable
through the existing scripts and documented verification page per AGENTS85–100.
A future source failure outside this bounded fixture mechanism remains visible;
new backend policy/API work must return for scope assessment rather than expand
this card silently.

## Estimate and custody

Five points for the system-named Mongo boundary and complete existing/new helper
lifecycle/fault tests, source-built live-DB correspondence, two real auth probes
with hidden-counter comparison plus the same-fixture detail GET preflight,
signal-safe shell/Python aggregation and actual cleanup qualification. One
bounded verification outcome reuses the existing entry/helper/routes; splitting
it into backend-only and probe-only cards would add a handoff across the same
ownership/teardown contract without reducing the required proof. The native
5538651862 refinements make this existing lifecycle and signal-safe teardown
contract explicit; they do not add a new product behavior, prerequisite owner or
estimate. The full resource-fixture behavior remains unchanged while its raw Mongo
interop moves behind the same system boundary.

This is explicit +5 discovery. Preserve core5 + draft5 + locale3 =13 as the
previous allocation; add this fixture/auth5 for a current repair total18.
Do not absorb it into core5, spend the broader accepted8 parent again, close a
parent, or grant implementation/release credit from planning. The standing
deployment authorization remains subject to source qualification and release
gates.

## Risks and prerequisites

Legacy compatibility/auth must be enabled and the selected backend/database
must be exact and readable by the already authorized verifier. REST-only mode,
missing project scope, unavailable storage or cleanup ambiguity refuses the
phase. No generated-content/provider dependency or new credential collection
is introduced. Shared helper/verifier paths are changed first here; core5,
draft5 and locale3 consume them in dependency order, never overlapping source
implementation blindly. Persistent product correctness remains owned by its
actual application contracts.

---
Root-reviewed local planning correction for native Codex5538651862 atb19f9fc8f2c7bcb9d345bddb3a6883f9c5166fb1, inline roots4287284040/4287284069. Put the complete existing/new touched-helper Mongo lifecycle (client construction/connect/database/query/conversion/delete/finally-close) inside system-named extern.openplanner-translation-mongo.verification-fixture with a CLJS-data action/settings/scope/result API and no script-created/native DB handle crossing. Preserve production stores/contracts and existing resource fixture semantics. Add genuine TERM after exact owned-label deletion before segment removal, retained exact ownership outside scratch, fresh-process exact cleanup reentry, zero owned residue/foreign preservation and original partial/failure evidence. This refines this existing5-point lifecycle/signal-safe teardown outcome; no new owner or estimate. Exact reviewed two-BODY patch12325B SHA712e15a1ca8457faa0d59017839b3c0c00d4836b0a213909442de49430328a7f. Preserve metadata/status/points/dependencies/parent/history and repair18total; no application implementation, fixture/auth probe, test/build/browser/model/runtime, readiness or release credit.

Root-reviewed local planning correction for native Codex5539062909/current0429, roots4287621731 and4287621771: explicitly name pure law.translation-review-verification-fixture action/settings/scope/ownership/request/action-dispatched-result schemas and assert-request!/assert-result! boundaries; invalid pre-construction request has zero client/connect/query/mutation/close effects, while an already constructed client closes on action/result refusal with original first cause preserved. Add the exact authenticated legacy document-detail GET readiness plus independent credential-free401/no_session on the same owned URL/scope before cleanup and any synthetic interception, with unchanged-state/count/failure evidence. These refine this existing fixture/auth5 outcome; preserve all metadata/dependencies/points/history and total18. No application implementation, fixture/auth/test/browser/model/runtime/readiness/release credit.

Root-reviewed local planning correction for Codex5539622567/currente9 root4288059113: explicitly require pnpm -C backend run lint, retaining the full src/cljs+test/cljs clj-kondo --fail-level warning pathset, complete actual output/exit and zero errors/warnings alongside test/server compiles; no narrowing, suppression or historical-red waiver. The already owned typed verification read may expose only exact run-owned resource/publication+tenant/source-bound dispatch/claim/batch/receipt snapshots for core5. Extend action-dispatched request/result schemas as needed, validate before DB access and return safe state/equality/count data only, never handles/content/credentials or a generic read escape. Samefixture5/status/dependencies/history/18total; no new fixture/API/model/writer, app/test/auth/runtime/readiness or public effect.

---