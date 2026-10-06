---
uuid: ae848c93-31f6-4b91-9bc4-80253192eea2
title: Review the existing local Axxium identity feature through the personal Knoxx fork
status: incoming
priority: P1
points: 3
labels: auth, migration, review
dependency: 891ff8be-40b0-4157-b734-72e76fe3e10b
---

## Outcome

Carry the already implemented and audited origin PR381 into a personal-fork development review without changing its runtime behavior. Preserve exact source commit `40221a69f7fff675b46614d9ab318b4ef52f3786` and all eight original source commits as ancestors through an ordinary merge onto the separate personal synchronization branch.

## Scope

The original eleven feature files provide loopback HTTP Axxium development, provider-specific login UI, anonymous-context handling, transient-error/failed-logout state preservation, and repeatable verifier/browser-tour artifacts. Carry those source artifacts unchanged; add only this migration card and identified provenance/handoff evidence. Retain accepted main documentation and append-only ledgers; do not copy the source branch's older whole tree.

## Dependency

The personal accepted-main synchronization card `891ff8be-40b0-4157-b734-72e76fe3e10b` and its separate PR must qualify independently. This PR is stacked on `codex/sync-knoxx-origin-main`; it does not update main or move the dependent card operationally.

## Non-goals

No new feature implementation, history rewrite, old-origin thread mutation, origin PR closure, transfer of native approval/round credit, local audit-only receipt import, DB/PM2/services, live verifier or tour execution, global tools, secrets/settings/protection changes, eager merge workaround, deployment label/dispatch, or origin release/deploy publication.

## Acceptance

- Reverify the original remote head and preserve source/main ancestry through ordinary merge.
- The original eleven feature artifacts match the source bytes exactly; the stacked feature diff adds only those paths plus identified migration planning/provenance.
- Link all eight original resolved native threads with their source comment IDs and final disposition. Reviewer withdrawal at comment4163000619 remains distinct from a code repair and explicitly supplies no approval/full-review credit.
- Preserve both origin and personal historical ledgers, parsed receipt envelopes and whitespace.
- Static source/JS/shell checks report their actual outcome; fresh backend/frontend compiler/test, live verifier and browser-tour results must be produced before claiming complete feature verification. Historical source tests are attributed, never reported as fresh personal-head results.
- Remain draft with auto-merge off while eager readiness automation in issue385 is unresolved and while the synchronization prerequisite is unqualified. Respect current personal FairUsage reset and pending-request deduplication.
- Native current-head review, required deterministic checks, lawful Rheos readiness and review convergence qualify before any separately authorized merge/release/deployment action.

## Verification

Read-only Git ancestry, byte/blob/source-path comparison, JavaScript/shell syntax and EDN parsing can run locally without a service. The published runtime scripts require an isolated test service and approved account fixtures; they are artifacts for later verification, not permission to operate shared infrastructure. No application test result is inferred from a clean merge.
