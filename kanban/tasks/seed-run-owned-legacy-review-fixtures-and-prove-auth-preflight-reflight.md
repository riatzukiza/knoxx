---
category: "tasks"
labels: "tasks, 5sp, has-parent, verification, translations, cms"
dependency: []
parent: "knoxx-translation-review-chat-panel"
type: "task"
write-id: "1792089635020-0.83ou78fe01y7bmv6rd7"
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
one safely owned legacy Spanish segment, prove both real legacy review routes
refuse a credential-free caller without any effective write, and remove exactly
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
Keep new raw Mongo interop in the named Knoxx verification boundary
`backend/src/cljs/knoxx/backend/extern/translation_review_verification_fixture.cljs`,
with its automatically discovered regression namespace
`backend/test/cljs/knoxx/backend/extern/translation_review_verification_fixture_test.cljs`.
The boundary accepts the existing owned database handle and Clojure-shaped scope,
reuses the existing translation request contracts/segment representation, and
adds no independent review, authorization, schema or tenant policy. Document this
named extern boundary where the existing boundary inventory requires it.

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
recover that exact ID and scope from the live backend before any anonymous POST;
this also detects a helper database different from the selected live client.
REST mode or unavailable/mismatched Mongo storage cannot silently pass or fall
back to somebody else's data.

Run this prerequisite as a distinct real auth-preflight phase before translation navigation,
synthetic interception, or manual filter request counters. Snapshot the owned
segment's status/labels and document review summary through authenticated reads,
and its stored label_version_counter through the exact owned helper read. The
API's segment-view does not expose that counter; do not pretend an HTTP snapshot
covers a failed label attempt that changed only the counter. Use the frontend's actual encoded paths/scope and contract-valid reject
payloads (`overall:"reject"`, no corrected text; the segment label also carries
the normal adequacy/fluency/terminology/risk fields). Rejection is deliberate:
these two existing paths invoke graph-memory writes only for approved outcomes.
Send exactly one credential-free POST per route from an independent empty-cookie
client, require401/no_session, and compare the same authenticated state after
each probe. Capture only scope/IDs, response status/error code, counts/digests
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
the original failure and makes an otherwise successful artifact nonzero; retain
owned IDs/partial outcome for operator recovery rather than claim teardown.

Extend the existing registered process-test surface
`scripts/test-cms-verifier-preconditions.py` for split-review auth-preflight
ordering/status/error/cookie isolation and original-failure/cleanup aggregation;
its existing CI invocation stays the owning harness gate. Boundary unit tests
cover a clean empty store seed/read/cleanup, collision refusal
without modifying the prior row, partial seed failure/retained ownership,
org/project/garden mismatch refusal, preserved foreign rows/labels, cleanup
failure, and interruption after seed. The auth harness must fail on 200,400,404,
503, missing no_session, changed state, wrong live database or missing readback;
no such response supplies auth credit. A deliberately failing cleanup must not
mask the original probe failure. The normal resource fixture remains18 before
and after this preflight and opt-in locale mode remains20/18/1/1. Register real
RED before the implementation, then require zero failures/errors/warnings from
the existing backend test build and typecheck (`pnpm -C backend exec shadow-cljs
compile test`, `pnpm -C backend typecheck`), the affected backend build where
required, shell syntax and the genuine live preflight with exact source custody.
Run `python3 scripts/test-cms-verifier-preconditions.py` as its registered gate.
These backend/helper gates belong to this separately estimated prerequisite.
The dependent frontend stories retain their own unit/browser gates; this
planning card executes none of them.

## Non-goals

No application feature, translation/review/publication semantic change, new
server API, OpenPlanner companion, provider/model call, judgment/approval,
source-event insertion, shared legacy cleanup or production rollout. This
fixture/auth phase is genuine integration evidence, separate from the
frontend stories' explicitly synthetic races. Its two anonymous POSTs are real
requests expected to have no effect; synthetic race requests remain entirely
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

2. Seed/register ownership before any possibly partial insert. Refuse collisions
   and ambiguous upserts without adopting or modifying prior rows. Retain exact
   IDs/full scope after partial failures, and verify the selected live backend
   actually reads that same database/segment before using any auth result.
3. Each anonymous probe compares authenticated review/label state and the
   actual stored label_version_counter before/after. No API-only comparison
   pretends the hidden counter is covered. Unknown comparison or readiness
   cannot qualify. Capture status/error codes and equality/digests, never keys,
   cookies, credentials or unexpected content bodies.
4. Checked cleanup removes only the exact recorded labels/segment with full
   ownership/scope guards, preserves foreign data, proves absence from stored
   rows and authenticated document listing, then finishes before the resource
   browser workflow. Default18 and locale20/18/1/1 remain unchanged.
5. Registered unit/process controls cover empty-store seed/read/cleanup,
   collision refusal, partial setup, scope/DB mismatch, foreign preservation,
   cleanup failure and interruption. The auth phase fails for200/400/404/503,
   missing no_session, changed state/counter, missing readback or wrong live
   source. Cleanup failure preserves the original failure and causes nonzero
   status; retained ownership supports operator recovery without a broad sweep.
6. A genuine source-built live run passes the two route probes and exact cleanup
   after actual source/environment preconditions are established. No ambient
   fixture, mocked auth response, skipped route or production service is used
   as a substitute. The artifact names what was proved and its limits.

## Verification

Register meaningful causal RED before implementation using the existing entry
and process suite; no fabricated dist, skipped failure or new shadow validator.
Run the affected backend test build/typecheck and required build with zero
failures/errors/warnings, the registered Python process suite and shell syntax,
then the genuine owned live phase with complete exit/cleanup evidence. Preserve
all existing tests and default consumers. The integration artifact is runnable
through the existing scripts and documented verification page per AGENTS85–100.
A future source failure outside this bounded fixture mechanism remains visible;
new backend policy/API work must return for scope assessment rather than expand
this card silently.

## Estimate and custody

Five points for the named Mongo boundary and lifecycle/fault tests, source-built
live-DB correspondence, two real auth probes with hidden-counter comparison,
signal-safe shell/Python aggregation and actual cleanup qualification. One
bounded verification outcome reuses the existing entry/helper/routes; splitting
it into backend-only and probe-only cards would add a handoff across the same
ownership/teardown contract without reducing the required proof.

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