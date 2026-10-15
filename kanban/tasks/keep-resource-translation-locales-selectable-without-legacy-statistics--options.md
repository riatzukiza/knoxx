---
category: "tasks"
labels: "tasks, 3sp, has-parent, regression, translations, frontend, cms"
dependency: []
parent: "knoxx-translation-review-chat-panel"
type: "task"
write-id: "1792056748405-0.ttzh7sgpgqb6at75nv"
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
- Extend the pure locale-option helper to preserve existing legacy manifest or
  historical fallback choices, deduplicate resource locales, and append new
  resource choices in deterministic order.
- Pass that project-bound projection to the existing header; keep legacy
  statistics display independent from resource work availability.
- Keep legacy enrichment failure and superseded project/load responses from
  erasing or overwriting the current resource locale projection.
- Limit product/test changes to the existing translation page files below:

  - `frontend/src/cljs/knoxx/frontend/pages/translations/logic.cljs`
  - `frontend/src/cljs/knoxx/frontend/pages/translations/review_controller.cljs`
  - `frontend/src/cljs/knoxx/frontend/pages/translations/view.cljs`
  - `frontend/test/cljs/knoxx/frontend/pages/translations/logic_test.cljs`
  - `frontend/test/cljs/knoxx/frontend/pages/translations/review_controller_test.cljs`
  - `frontend/test/cljs/knoxx/frontend/pages/translations/page_interaction_test.cljs`

The existing human verification pair is
`scripts/verify-translation-split-review-tour.sh` and
`docs/verification/translation-split-review.md`. Extend its locale scenario only
as needed to make this UI repair runnable and visible; preserve its own fixture,
checkout preconditions and signal-safe cleanup. This planning change edits none
of those implementation or verification files.

Estimate: three points for presentation plumbing through existing generation
controls plus causal helper/controller/UI regression tests. It does not include
translation semantics, new endpoints or deployment rollout.

## Non-goals

- No new translation/review/publication law, locale catalog, identity, tenant
  authority, backend API, OpenPlanner change or compatibility retirement.
- No new HTTP request, model/provider invocation, content generation, dispatch,
  judgment, correction, approval, reconciliation or publication in this repair.
- No changes to Services admission, production topology, controller adoption,
  caller pins, existing failed containers, credentials or runtime security.
- No claim that the parent UI workflow, broader native tour or release is done.

## Acceptance criteria

1. With current-project resource work in `es` and `fr` and an empty legacy
   `:languages` map, the actual Target Lang combobox offers both locales.
2. Selecting `es` filters the displayed work while `fr` remains selectable;
   switching to `fr` and All still shows the appropriate real resource work.
3. Legacy-only locale choices remain available; a locale appearing in both
   sources occurs once, new resource choices have deterministic order, and the
   historical nil-manifest fallback remains compatible.
4. Failed legacy document enrichment cannot remove locale options from a
   successful current resource inventory.
5. A superseded load or old project response cannot overwrite the new project's
   locale projection. The header never advertises another project's stale work
   locales as current inventory.
6. Locale projection does not change work/candidate identity, review state or
   authorization and causes no additional requests or mutation calls.
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

Add pure helper cases for empty statistics, legacy-only options, shared locale
deduplication, deterministic added choices and nil fallback. Add controller
cases for filtered rows retaining the unfiltered locale inventory, legacy
failure, and a stale-generation/old-project response arriving after the current
one. Async tests use the repository's native `^:async`/`await` pattern.

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
statistics plus multiple actual locales, capture the locale selection/detail
steps, and fail on this card's invariant violations. Keep fixture behavior
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