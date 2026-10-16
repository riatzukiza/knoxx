---
category: "tasks"
labels: "tasks, 3sp, has-parent, regression, translations, frontend, cms"
dependency: ["knoxx-translation-review-current-view-mutation-lifecycle", "ae9a3cb7-a630-4d8d-8d60-c69b825d008d"]
parent: "knoxx-translation-review-chat-panel"
type: "task"
write-id: "1792119292436-0.t8qism1o887jo6ykq9"
points: "3"
title: "Keep resource translation locales selectable without legacy statistics"
priority: "P0"
status: "ready"
uuid: "knoxx-translation-resource-locale-filter-options"
created_at: "2026-10-15T09:31:50.072Z"
---

# Keep resource translation locales selectable without legacy statistics

## Context

The private native CMS browser diagnostic on 2026-10-15 reproduced a user-visible
translation filter failure against source `19f8f2d7e6e7684c93df8b620ad83ffc0ec30b1c`.
Normal password login and authenticated `/api/auth/context` returned HTTP 200.
After the exact current-project manifest request completed with HTTP 200 and
document loading finished, the Target Lang control offered exactly `All` even
though the resource inventory contained one English → Español work item.
The existing All view opened that real item and its three pending split members;
this diagnostic read does not make the locale-filter tour or release pass.

The earlier tour also had a harness locator defect: an exact wrapping-label
match included descendant option text. The corrected unique combobox locator
sampled the actual All-only inventory. Preserve both failures rather than
mistaking an unsampled empty diagnostic variable for observed empty options.

The source cause is that `logic/available-langs` reads only legacy manifest
language keys whenever the manifest is nonnil. Resource-owned desired work is
independent of whether legacy segment statistics exist. The controller filters
resource reviews by the selected locale before installing document rows, so
using only those filtered rows for options would hide other real choices after
selecting one locale.

Mandatory predecessor: `ae9a3cb7-a630-4d8d-8d60-c69b825d008d`,
which depends on `knoxx-translation-review-current-view-mutation-lifecycle`.
Parent: `knoxx-translation-review-chat-panel`. Product anchor:
`knoxx-knowledge-ops-translation-document-review-v2`. This is a bounded child of
the existing accepted eight-point UI owner; it does not complete that owner.
The separate `knoxx-publication-locale-catalog` owns publication writer-target
admissibility, and `knoxx-openplanner-rest-retirement` owns compatibility removal.
Neither is an invented dependency for presenting existing review work locales.

## Outcome

A reviewer can choose any locale represented in the current server-derived
resource work inventory even when legacy language statistics are empty or
unavailable. Choosing Español does not remove another actual locale, such as
Français, from the same project's choices.

## Scope

- Project the unfiltered successful resource inventory's target locales into
  presentation state before target filtering, bound to the effective server
  project and the existing request-generation guards. Install that locale
  projection immediately after a successful current resource response, before
  awaiting legacy document enrichment; a hung legacy request must not withhold
  the real resource options.
- Extend the pure locale-option helper with an exact order: for a nonnil
  current-project manifest, normalize language keys with the existing `name`
  conversion and sort their locale codes lexically; for a nil/unavailable
  manifest, preserve the historical fallback vector order exactly. Append valid
  resource-only locale codes in lexical order, excluding duplicates already in
  the legacy/fallback prefix.
- Revalidate the selected target whenever the effective option set changes,
  including same-project nil/fallback→settled-empty-manifest and resource option
  updates. Preserve a still-represented selection; otherwise reset to All and
  invalidate its old filtered load synchronously, then load the actual new
  filter. Do not leave an invisible de filter or manufacture an unsupported
  option to conceal it. Account for this automatic reset separately from the
  settled-project manual filter request baseline.
- Pass that project-bound projection to the existing header; keep legacy
  statistics display independent from resource work availability.
- Keep legacy enrichment failure and superseded project/load responses from
  erasing or overwriting the current resource locale projection.
- At an effective project change, immediately hide this story's previously
  installed resource-locale projection and legacy manifest and reset Target Lang
  to All before the new document load. Generic historical fallback choices may
  remain; they are not evidence of the previous project's resource inventory.
  Consume the mandatory predecessor
  `ae9a3cb7-a630-4d8d-8d60-c69b825d008d` and its current-view
  lifecycle predecessor. The lifecycle slice owns synchronous exclusion of old
  rows/detail/actions, actual view/mounted completion and operation ownership;
  the draft/refresh slice owns segment/draft retention through reload/form-sync
  and coalesced post-snapshot freshness. Their six-runner, unmount, read-only
  export and controlled-browser obligations remain intact. This story cannot
  complete while either prerequisite is unqualified.
- Limit product/test changes to the existing translation page files below:

  - `frontend/src/cljs/knoxx/frontend/pages/translations/logic.cljs`
  - `frontend/src/cljs/knoxx/frontend/pages/translations/review_controller.cljs`
  - `frontend/src/cljs/knoxx/frontend/pages/translations/view.cljs`
  - `frontend/test/cljs/knoxx/frontend/pages/translations/logic_test.cljs`
  - `frontend/test/cljs/knoxx/frontend/pages/translations/review_controller_test.cljs`
  - `frontend/test/cljs/knoxx/frontend/pages/translations/page_interaction_test.cljs`

The bounded human-verification scope also includes:

- `scripts/lib/translation-split-review-fixture.sh`
- `scripts/verify-translation-split-review-tour.sh`
- `docs/verification/translation-split-review.md`

Add an opt-in locale scenario to that shared fixture helper: retain its default
18 Spanish work intents and one durable Spanish three-split candidate unchanged,
and in the opt-in scenario add one French and one Dutch resource intent in the
same run-owned namespace, with garden locales `[:en :es :fr :nl]`. The extra
intents need no generated candidates or model invocation. Keep other helper
consumers on the unchanged default. The opt-in tour must reconcile its expected
All inventory of 20 work items and filtered counts of 18 Spanish, one French and
one Dutch, while preserving the original default 18-row regression assertion.
For this opt-in scenario, make empty legacy statistics run-owned: before
translation navigation, use the existing browser driver's route/body facility
for only the exact current-server-project manifest URL from
`api/get-manifest` (`/api/translations/export/manifest?project=<encoded project>`),
returning the JSON `{"languages":{}}`. Require the page's matching GET to finish
with that controlled empty response and its render to settle before checking
options; fail if the driver cannot install or observe the control. Keep native
resource inventory and authentication live. Record the override explicitly as
UI integration evidence, with no upstream-manifest or backend-health credit.
Remove the exact route in the run-owned EXIT/INT/TERM cleanup and close its
private browser session; never clear a shared project's legacy records.
The opt-in locale mode ends after selection/detail checks and fixture cleanup,
before the existing default tour's live mutation workflow. The same human
artifact must also execute the predecessor's separately disclosed controlled
race mode with held synthetic responses, exact run-owned mutation payloads and
browser abort backstops; it cannot claim the races passed by taking this early
locale exit. The unchanged default mode retains its full existing workflow.
Preserve checkout preconditions, dedicated paths and EXIT/INT/TERM cleanup.
This planning change edits none of those implementation/verification files.

Estimate: retain three points for unfiltered locale presentation, its own
project-boundary projection, causal helper/controller/UI regressions and bounded
real-inventory locale helper/tour/documentation. The separately estimated
five-point current-view lifecycle slice owns boundary/mounted completion,
six-runner operation ownership, unmount and read-only export feedback with its
own unit/browser proof. The new five-point draft/refresh successor owns legacy,
resource-split and bulk dirty-draft retention through controller/form-sync plus
required post-snapshot catch-up, also with its own proof. The first split added
five points (3+5=8), and the second draft discovery added five more, giving the
prior core5 + draft/refresh5 + locale3 =13. Native4286589102 now separates
`knoxx-translation-review-legacy-fixture-auth-preflight` as an additional fixture/auth5
prerequisite of core: current repair total18, explicit new+5 discovery. This
locale3 already depends on core+draft and therefore consumes that prerequisite
transitively; no redundant cycle or extra locale implementation is introduced.
The broader accepted eight-point parent remains unchanged/open;
its case/receipt/chat/memory/publication obligations are not consumed, completed
or silently re-estimated by these repair estimates.

## Non-goals

- No new translation/review/publication law, locale catalog, identity, tenant
  authority, backend API, OpenPlanner change or compatibility retirement.
- No new endpoint or extra HTTP request beyond the existing successful-load
  baseline specified below. No model/provider invocation, content generation,
  dispatch, judgment, correction, approval, reconciliation or publication.
- No changes to Services admission, production topology, controller adoption,
  caller pins, existing failed containers, credentials or runtime security.
- No claim that the parent UI workflow, broader native tour or release is done.

## Acceptance criteria

1. With current-project resource work in `es` and `fr` and an empty legacy
   `:languages` map, the actual Target Lang combobox offers both locales.
2. Selecting `es` filters the displayed work while `fr` remains selectable;
   switching to `fr` and All still shows the appropriate real resource work.
3. Legacy-only choices remain available. A nonnil manifest's normalized locale
   codes are the lexically sorted prefix; nil/unavailable manifests retain the
   exact historical prefix `["es" "de" "ko" "fr" "ja" "zh" "it" "pt" "ru"]`.
   Append only resource codes absent from that prefix, lexically sorted. Thus
   manifest `es`/`fr` plus resource `es`/`de`/`nl` yields exactly
   `["es" "fr" "de" "nl"]`, regardless of input iteration order.
4. A successful current resource response installs its unfiltered locale
   options before legacy enrichment settles. Hold the matching-project legacy
   request pending/never-resolving and require es/fr/nl choices while it remains
   held, then select one without losing other real locales. Failure cannot erase
   that projection either. Catch/finally-only installation is insufficient.
5. On an effective project change, immediately exclude installed resource
   locale options and the previous legacy manifest, and reset Target Lang to All
   before the new document load. Pending, hung or failed replacement requests
   and late A responses cannot restore A options or filter B with A's locale.
   The mandatory lifecycle predecessor must additionally prove no A rows,
   selection/detail/segment/draft/actions in the first B render, whole-view
   completion/reload fencing and operation-owned saving. A's authorized server
   work and follow-ups remain original; current B load/UI ownership survives.
   Project changes synchronously invalidate old document/manifest/detail
   generations before replacement effects; resolve A in that interval, not only
   after B starts. Filter changes invalidate document/detail loads while keeping
   a valid project-only manifest alive without an extra manifest GET. The
   predecessor owns this shared boundary.
6. Locale projection preserves work/candidate identity, review state and
   authorization. In a successful settled-project fixture with no automatic
   project adoption, each existing `es` → `fr` → All filter change makes exactly
   one resource-inventory GET and one legacy-documents GET, and zero manifest
   GETs. The projection adds no duplicate fetch, endpoint or mutation call.
   Initial project discovery/adoption remains a separate existing generation and
   is accounted for before these counters are reset.
   Effective option changes also keep target selection valid: choose fallback-
   only de before a deferred same-project empty manifest resolves with resource
   es/fr/nl; afterwards de is absent and Target Lang must be All with no hidden de
   request/rows. Preserve a selected es that remains represented, and cover an
   analogous resource-projection option change. Treat the necessary All reload
   as a distinct initialization/options generation, not a duplicate manual fetch.
7. The ordinary UI browser path proves normal login/authenticated context,
   current-project load completion, visible/selectable Español, the exact
   Spanish work item and its split detail. Screenshots show those steps.
8. Existing failed tours, seven console errors, and observed private ancillary
   HTTP 502 responses remain explicit evidence limits. Passing this narrow
   locale check supplies no independent review, approval or release credit.


### Real locale-read authentication before controlled responses

For native inline Codex root4285031557, this three-point locale story also owns
two real credential-free GET assertions in the shared API verifier
`scripts/verify-translation-split-review.sh`, consumed by the tour and guide;
extend `scripts/test-cms-verifier-preconditions.py` for their causal orchestration
controls through its existing registered CI invocation.
Before any manifest route override or deferred fetch adapter is installed, bind
the actual effective server project, settle authenticated readiness for both
existing locale-loading routes, then use an independent empty-cookie client
without bearer/API/identity headers to probe their exact frontend-built URLs:
`api/get-manifest` builds `/api/translations/export/manifest?project=<encoded>`;
`api/list-documents` uses URLSearchParams for project and the current optional
`target_lang`. Do not substitute the resource inventory, SFT route or detail
endpoint. A real authenticated200 with contract-shaped manifest/documents data
establishes route readiness; an empty valid languages/documents result is ready.
The wrapper tests readiness before authentication, so503,404,400, missing fixture,
or an unavailable dependency cannot be relabeled as an authentication pass.

Require401 with no_session for each real anonymous GET. Unexpected readable
content is a failure without logging its body. Record real method/path/query,
status/error and the absence of auth headers/cookies, independently from the
later UI-only empty-manifest control. Prove the two probes occur before any
interceptor registration, exercise a real200 exposure and unready503 adverse
case, and fail if a probe is omitted or intercepted. Only then install this
card's exact controlled empty manifest and require the matching response/render.
Reuse core5's real auth-preflight mechanism and complete its temporary legacy
fixture teardown before navigation/counter reset. Keep these two GETs separately
accounted from SFT and the two legacy POSTs, the initial project-adoption reads,
and settled es→fr→All counts. Product code adds no endpoint or request; this
explicitly scoped verification preflight does not alter the existing successful
filter baseline or the controlled phase's zero forwarded mutation/provider calls.
No native auth probe or interceptor is executed by this planning amendment.

## Verification

RED first: add an interaction regression with actual resource `es`/`fr`/`nl`
work and a controlled deferred manifest promise. Resolve it to `{:languages {}}`
inside the test's render/act boundary, then await the resulting render before
asserting all three actual combobox options. The outside-fallback `nl` witness
and completed empty-map render prevent the initial nil-manifest es/fr fallback
from producing a false pass. Select `es`, require `fr` and `nl` still selectable,
and require zero mutation calls. The current code must fail the first settled
resource-locale assertion. This fixture exercises resource inventory rather
than manufacturing legacy segments to conceal the failure.

Add separate causal cases before changing the existing settled-empty RED:
(1) after a successful resource es/fr/nl response and settled empty manifest,
hold matching-project legacy list-documents indefinitely; options must already
be visible and selectable before the legacy promise ever resolves/rejects;
(2) select fallback-only de while the manifest is still nil, resolve that same-
project manifest to an empty languages map, and require selection reset to All
and old filtered-load invalidation before any new-context request. Keep a valid
es selection unchanged in a companion case; repeat selection validity when a
resource option disappears. These schedules need explicit deferred unit and
visibly controlled browser checkpoints/counters, with no live mutation/provider
calls. Preserve the original nl-outside-fallback settled-manifest RED as well.

Add pure helper cases asserting exact ordered vectors, rather than sets:
empty statistics; legacy-only codes sorted lexically; deduplication and sorted
resource-only suffix under permuted inputs; and the unchanged nil fallback.
For the causal unavailable-manifest case, include real resource locale `nl`,
which is absent from the fallback, and require fallback-plus-`nl`. Test fallback
compatibility alone separately, so fallback `es`/`fr` cannot conceal a missing
resource projection.

Add controller cases for filtered rows retaining the unfiltered inventory,
legacy failure, and stale generations arriving after the current response.
Add an interaction case that installs A resource locale nl and legacy-only sv,
selects nl, then switches effective project to B. Require old resource/manifest
options absent and All reset before B's response; hold/fail B's manifest and
resolve A responses last. Add a separate pre-passive race that resolves A
inventory/manifest after the project boundary but before any replacement effect
starts B; require synchronous invalidation to block A then. Preserve valid same-
project manifest work on filter changes. This story owns the new locale projection;
consume the predecessor's synchronous row/detail/action boundary rather than
reimplementing it. The first B resource GET must not carry nl.

The two mandatory five-point lifecycle predecessors jointly own the complete
transferred API-stub matrix. Current-view core owns installed A rows/detail/
actions -> first B render, deferred A mutation -> B held load, same-project
es -> fr and row A -> B stale closure/detail, all six runners' success/error/
finally, old A completion versus a new B saving owner and explicit unmount.
Draft/refresh owns legacy/resource-split/resource-bulk segment/draft edits through
actual reload/detail/form-sync, preserving current segment and draft revision.
The remaining shared requirements include
original payload/approval→reconcile preservation, and the existing unchanged
same-project refresh/selection test. Its refined boundary also excludes installed
feedback and old filtered/detail/segment/form state at the first filter/work
render. Its separate read-only SFT policy fences shared feedback by captured
project generation/filter while preserving the original request/download; six
mutation runners remain six. Its controlled browser race phase must
exercise those reviewed invariants through normal controls with held synthetic
responses and a live-mutation abort backstop. These requirements remain blocking
for the repair and are not replaced by locale-only or mocked unit evidence.
The draft/refresh successor owns required coalesced same-project freshness
after a pre-mutation snapshot settles; core conserves its original pending load.
Simply skipping that refresh cannot leave old rows installed indefinitely.
Old-project A still cannot refresh B; all catch-up reads revalidate current
context, and neither predecessor can claim the other's browser cases passed.

For request-count assertions, settle the initial fixture with the server's
project equal to the page project, then reset counters. After each successful
`es`, `fr` and All selection, require deltas of one resource-inventory GET, one
legacy-documents GET, zero manifest GETs and zero mutation calls. Do not assert
zero total requests: the current `[project target-lang]` effect already reloads
both inventories. Keep project-adoption generations explicit in their separate
boundary test. Async tests use the native `^:async`/`await` pattern.

After the minimal repair, run:

```bash
pnpm -C frontend run test:cljs
pnpm -C frontend run lint
```

Require actual zero failures/errors/warnings, not compiler exit status alone.
If an unrelated suite failure is encountered, preserve its real counters and
owner; do not call the repair complete or silently replace the suite.

The implementation verification artifact must validate the checkout served,
seed and tear down its dedicated resource fixture, exercise empty legacy
statistics through the explicitly controlled run-owned legacy response plus
actual Spanish/French/Dutch resource intents through the bounded opt-in helper
scenario, require the matching empty-manifest response/render before assertions,
assert the default and opt-in cardinalities above,
capture locale selection/detail steps, and fail on this card's invariant
violations. Keep fixture behavior
separate from provider-quality evidence and leave unrelated known gaps visible
as warnings. A later authorized private frontend build/browser check must bind
actual source, image, Unix input modes and running nonroot security profile.
No such build or runtime action is admitted by this planning commit.

Observed private evidence (hashes are custody references, not release approval):

- Corrected diagnostic V5 script SHA256:
  `70a718fe5904747faf006c65ee008dc0280da476c8270072d7a55f72242b38dc`.
- Actual browser observation SHA256:
  `3d96fdff8439cd4f791c60c81ffc925673df2a10e3bc4675251f747284e06fe8`.
- Options-before-selection observation SHA256:
  `ef7aa729960c87f399ba79e86c190492a8dbf1d73faf94883713705b7f2e4d8a`.
- Actual Spanish split-detail screenshot SHA256:
  `8c13c05755b15a658656f493341b1dc2960d4660ff056bd0d136f3a9361d897a`.

These belong to the retained private deployment evidence under
`CMS19f-native-resource-locale-filter-planning-v1` and the preceding native
browser diagnostic packet. The original locale-filter outcome remains FAIL.

## Risks

Deriving options from already filtered rows can make the selector self-narrowing.
Reusing old-project presentation state can advertise stale choices. Replacing
resource options with historical fallback locales can hide the actual failure.
Preserve unfiltered inventory provenance and the existing generation/project
boundary, while leaving review and publication truth in their current owners.

---
Planning refinement for native PR16 review 5531333989 on 7e7af709cac6b996f7df3a842cf949356eebb853: all six findings were verified against the existing frontend logic/controller/view and shared fixture helper. Refine only this 3-point breakdown card body and verification: explicitly own the shared helper's opt-in es/fr/nl scenario while preserving default 18 rows; define exact locale ordering; reset All and immediately hide installed old-project manifest/resource options; assert the existing successful filter-change GET baseline; use nl outside the historical fallback for causal unavailable-manifest coverage. No application source/test/fixture implementation, ready transition, runtime rebuild, model/content/review/publication effect or CI waiver is admitted by this planning refinement. Draft SHA256 a2288993471240d1a2c0dfd20301ea40b24b113ae455a2f8f0ea4590da33e2df. Existing source-family Sandbox SDK failure and observed private browser failures/502 limits remain visible.

Planning refinement for native current-head Codex review5531594405 on69235c23641c4e4cf1972bf5bd2cec7c16278412: accept three source-backed findings4281770565/4281770579/4281770589. Keep this same P0 three-point breakdown child and prior six Fixed histories. Add immediate clearing of selected work/detail/segment draft plus old-detail generation invalidation at project change; settle a controlled empty-map manifest render before causal RED using nl outside fallback; make the opt-in locale tour's empty manifest a private run-owned exact-current-project GET override with actual-response/render evidence and EXIT/INT/TERM unroute/session cleanup. Keep resource inventory/authentication real, default18/full tour unchanged and opt-in20/18/1/1 counts. Explicitly label the override causal UI integration only, with no genuine upstream/backend/provider/release health credit. Exact root-reviewed V2 delta SHA2562e71f3e001d5ef0ca99b7768af98edd2f6e1cb45bc763e1b7b69d3ef6ceda04b; full draft SHA256dec9e55e0417a5228349e290ac68bc7952f3a8d54f57639cec457c00926dfcd4. No application/runtime/model/content/production effect, no readiness/merge or CI waiver.

Planning refinement for native Codex review 5531746779 on cd4e45d6b049d69d45ec88cadd25e3ebbeff703c: accept P1 roots 4281890603 and 4281890616. Root reviewed exact 5,733-byte delta SHA256 c5f164933d723f3ca0cc9106ff513d147042ed3dac82cc6a77ed3594536d5c72. Refine only this same P0/3-point breakdown child: synchronously exclude old document rows at project change, and bind the six existing mutation runners' UI completion/reload callbacks to the current project generation. Preserve already issued A mutation payloads and required server follow-up sequence; no cancellation, retargeting or reissue. Add a separate deferred API-stub A-dispatch/B-pending regression, legacy detail completion and stale UI lifecycle callbacks; passive boundary/filter cases still have zero mutation calls. Preserve all nine prior Fixed histories, exact fixture cardinalities and controlled-manifest UI-only evidence boundaries. No app implementation/readiness, live mutation, runtime/model/content/production or merge effect. Commit the card/native-event source separately from canonical RR/SM provenance.

Planning split for native PR16 Codex review 5531973258 on c7b64025fdb0bb5c755e5edb13ebbb9f4f70bed8: accept genuine P1 roots 4282077748/4282077754/4282077767. Root read and admitted complete 21,384-byte source/scope proposal SHA256 07d31d1c1a6a948effbb876ba68f4c050a85ae92369c16c4c653200e74886a05. Fresh canonical parent-family dedup read all 265 PW tasks; shared K has 261 identical canonical-source cards and three distinct native reads, no equivalent sibling. Retain this P0/breakdown locale child at 3 points; native dependency now requires knoxx-translation-review-current-view-mutation-lifecycle, newly admitted incoming→accepted→breakdown P0/5. Explicit +5 discovery gives repair 3+5=8; broader accepted8 parent stays unchanged/open with remaining obligations. Transfer whole-view actual reload/detail closure, all six original server payload/follow-up sequences, operation-owned saving, current pending-load conservation, and visibly synthetic run-owned browser race mode with abort backstop and zero live mutation/provider/publication into that mandatory sibling without weakening acceptance. Preserve existing same-project refresh/selection regression, real-inventory locale phase/default18/opt-in20 cardinalities, all eleven Fixed histories, failed tours/502 and source-family Sandbox SDK failure. Body remains hand-authored first-class Markdown; all board metadata/status/dependency/events use native Rheos. No app implementation, runtime, model/content/production or merge/readiness effect.

Native PR16 body review5532308090 is handled in mandatory lifecycle predecessor knoxx-translation-review-current-view-mutation-lifecycle: first project/filter/work boundary excludes installed stale feedback/detail/segment/form with retained state distinguished; separate read-only SFT shared feedback fences captured project generation/filter and preserves original request/download. This locale story retains3points/P0/breakdown, same dependency and live-inventory locale/default18/optin20 scope. Lifecycle stays5; six mutation runners remain6. Root-approved exact proposal7c76aa884431f3f673bffea62fbdaf4e0d23e947b76bfae7eb030684146ddef8. Preserve native fourteen settled roots and source-family/browser limits; no implementation/readiness/runtime/provider/content/publication effect.

Native PR16 current8b187 Codex5532537847 and CodeRabbit5532590232 planning correction: install current successful unfiltered resource locales before awaiting legacy enrichment; revalidate the selected target on fallback-to-manifest/resource option changes, retaining a represented selection or resetting to All with synchronous stale filtered-load invalidation. Add deferred causal unit and visibly controlled browser schedules. Shared pre-passive generations, segment/draft guards, actual anonymous SFT boundary and pending-snapshot catch-up belong to existing mandatory lifecycle predecessor. Exact locale body19529B sha256:f70a2218000c04e1056f16eb0835de63b182efa4f1b85600caaef74974e0e019; full delta17244B sha256:1a51a6fd7c1784998020f7dcd304c7f02fd8c9b1b76d2ad369a4be39d8552da7. Keep breakdown/P0/3points, same lifecycle dependency, scalar labels and accepted8 parent unchanged/open. Preserve default18 and optin20/18/1/1, real auth/inventory versus synthetic race distinction, all14 prior native roots/body review5532308090 and original browser/502/Sandbox limits. No app implementation/readiness/merge/runtime/provider/content/publication effects.

Root-reviewed Codex5532975630 correction preserves locale3/P0/breakdown, ordering/unfiltered/early options/valid-target/native-auth-inventory/default18-optin20 scope. ONE SINGLE-KEY native dependency edit preserves core5 and adds UUIDv4 draft/refresh5 ae9a3cb7-a630-4d8d-8d60-c69b825d008d. Both predecessor proofs remain mandatory: boundary/operation/unmount/export in core, three-runner dirty draft/controller/form-sync and bounded freshness in successor. Exact UUID-bound body20223B SHAc96f3d60e2953d336ac02005ffacc9fb2dc58394d9742020d44affaf73ea2d2d. Total13 repair points is explicit+5 beyond prior8; broader accepted8 parent unchanged/open. Preserve20 settled native roots, all histories and original browser/502/Sandbox limitations. Local planning only; hold all public PR/body/reply/review/push/merge/readiness effects.

Native CMS16 Codex P1 root4285031557/threadPRRT_kwDOU4VeEc6s2Lri/review5535724920 on18125604b5cc499e6616ec65cbe768ae5521518c: root reviewed exact two-card patch e40ce45100723de196647e1b629455dd8f009fe9908bdaa90825ce22853010d2. Existing locale3 owns exact frontend-built manifest and legacy-document-list real credential-free GETs before any synthetic interception; establish authenticated200 route readiness and require401/no_session with an empty-cookie/header-free client. Exposure200/unready503/400/404 or intercepted/omitted probes cannot pass. Extend shared API verifier and existing registered process tests; retain UI-only empty-manifest evidence and distinguish probe/init/filter counts. Preserve default18/optin20/18/1/1, core5->draft5->locale3=13, all metadata/history and accepted8 parent unchanged/open. Core owns temporary legacy fixture teardown before navigation; no app implementation/auth probe/test/runtime/model/publication or readiness credit. Local-only planning source and separate provenance; public effects held.

Accepted root-reviewed estimate disclosure for native Codex5537788801/root4286589102 at7722bb23d7c2baa7006f7d2627ee2b4ec7cf338f. Locale remains3points with its real locale-read auth and locale UI/projection obligations and existing core+draft dependencies unchanged. New knoxx-translation-review-legacy-fixture-auth-preflight independently owns fixture/auth5 as core prerequisite; locale consumes it transitively. Current repair total18 (fixture5+core5+draft5+locale3), explicit new+5 discovery beyond previous13; accepted8 parent stays unchanged/open. Exact reviewed3BODYpatch20309B SHA10a2d22792ac07199e497debea1c200a23314baae53c2767bea85d0d18059696. Preserve all historical comments/metadata and failed evidence; this local planning correction executes no fixture/auth/test/browser/model/runtime operation and supplies no readiness, approval or release credit.

---