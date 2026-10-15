---
category: "tasks"
labels: "tasks, 3sp, has-parent, regression, translations, frontend, cms"
dependency: []
parent: "knoxx-translation-review-chat-panel"
type: "task"
write-id: "1792059088816-0.nj0z2j6gyviu8xq1q7b"
points: "3"
title: "Keep resource translation locales selectable without legacy statistics"
priority: "P0"
status: "breakdown"
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
  project and the existing request-generation guards.
- Extend the pure locale-option helper with an exact order: for a nonnil
  current-project manifest, normalize language keys with the existing `name`
  conversion and sort their locale codes lexically; for a nil/unavailable
  manifest, preserve the historical fallback vector order exactly. Append valid
  resource-only locale codes in lexical order, excluding duplicates already in
  the legacy/fallback prefix.
- Pass that project-bound projection to the existing header; keep legacy
  statistics display independent from resource work availability.
- Keep legacy enrichment failure and superseded project/load responses from
  erasing or overwriting the current resource locale projection.
- At an effective project change, immediately hide the previously installed
  project's resource-locale projection and legacy manifest, and reset Target
  Lang to All before starting the new project's document load. Pending, hung or
  failed replacement requests must not keep the previous project's state visible.
  Generic historical fallback choices may remain; they are not evidence of the
  previous project's inventory.
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
Preserve checkout preconditions, dedicated fixture paths and signal-safe cleanup.
This planning change edits none of those implementation or verification files.

Estimate: three points includes the presentation plumbing, project-boundary and
causal helper/controller/UI regressions, and this bounded shared-helper/tour/doc
extension. It excludes translation semantics, a general fixture framework, new
endpoints and deployment rollout.

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
4. Failed legacy document enrichment cannot remove locale options from a
   successful current resource inventory.
5. On an effective project change, immediately exclude both previously
   installed resource options and the previous legacy manifest and reset Target
   Lang to All before the new document load. This holds while new resource or
   manifest responses are pending, hung or failed; it also prevents an old
   selected locale from filtering away the new project's work. Late superseded
   responses cannot reinstall old state.
6. Locale projection preserves work/candidate identity, review state and
   authorization. In a successful settled-project fixture with no automatic
   project adoption, each existing `es` → `fr` → All filter change makes exactly
   one resource-inventory GET and one legacy-documents GET, and zero manifest
   GETs. The projection adds no duplicate fetch, endpoint or mutation call.
   Initial project discovery/adoption remains a separate existing generation and
   is accounted for before these counters are reset.
7. The ordinary UI browser path proves normal login/authenticated context,
   current-project load completion, visible/selectable Español, the exact
   Spanish work item and its split detail. Screenshots show those steps.
8. Existing failed tours, seven console errors, and observed private ancillary
   HTTP 502 responses remain explicit evidence limits. Passing this narrow
   locale check supplies no independent review, approval or release credit.

## Verification

RED first: add an interaction regression to the existing page test with resource
`es` plus `fr` work and `{:languages {}}`. Assert both combobox options before
selection, select `es`, then assert `fr` is still available and no mutation API
was called. The current code must fail the first actual locale-option assertion.
This fixture exercises resource inventory rather than manufacturing legacy
segments to conceal the failure.

Add pure helper cases asserting exact ordered vectors, rather than sets:
empty statistics; legacy-only codes sorted lexically; deduplication and sorted
resource-only suffix under permuted inputs; and the unchanged nil fallback.
For the causal unavailable-manifest case, include real resource locale `nl`,
which is absent from the fallback, and require fallback-plus-`nl`. Test fallback
compatibility alone separately, so fallback `es`/`fr` cannot conceal a missing
resource projection.

Add controller cases for filtered rows retaining the unfiltered inventory,
legacy failure, and stale generations arriving after the current response.
Add an interaction case that first installs project A's resource locale `nl`
and legacy-only `sv`, selects `nl`, then changes effective project to B. Assert
immediately, before B resolves, that A's `nl`/`sv` options are absent, All is
selected and B's first documents request does not carry `nl`. Hold B's manifest
pending (including a never-resolving response), then exercise manifest failure
while B's resource work is available; neither may restore A's installed state.
Resolve an old A response last and require B's state to remain current. This
covers already installed old state as well as future stale responses.

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
statistics plus actual Spanish/French/Dutch resource intents through the bounded
opt-in helper scenario, assert the default and opt-in cardinalities above,
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

---