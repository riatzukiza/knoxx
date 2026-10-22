---
uuid: 891ff8be-40b0-4157-b734-72e76fe3e10b
title: Review the exact accepted Knoxx main synchronization into the personal fork
status: incoming
priority: P1
points: 2
labels: fork-sync, planning, review
---

## Outcome

Preserve personal fork main `b3903dfc4c9e9dd5e32ae658cf5d8abb88b8e792` and synchronize the nine accepted origin commits through exact origin main `3409977bca4ba35e09967f9a99d50867a679a73c` in a standalone personal-fork PR. Develop the separate local Axxium login migration on this branch, rather than publishing new development commits to origin.

## Scope

The verified source comparison has zero fork-only commits and nine origin-only commits. It changes three review/promotion workflows and appends two provenance ledgers, from merged origin PRs382 and383. Preserve the exact source bytes and both histories through an ordinary merge; append only this card and owned synchronization receipt/reflection.

## Non-goals

No merge, main update, automatic-merge activation, deployment label, workflow dispatch, DB operation, PM2/service restart, secret/configuration/protection changes, global tool mutation, or import of unmerged origin PR381 feature code. Historical origin review is provenance, not approval of this personal PR or its new head.

## Acceptance

- Reverify both fixed forty-character revisions, fork parent/default branch, and the zero/nine comparison before publication.
- Inspect every source-diff artifact; preserve source workflow bytes exactly and source/fork historical ledger byte prefixes.
- Confirm YAML and receipt EDN parse, changed workflow actionlint passes, and whitespace passes.
- Inspect inherited automatic merge and promotion triggers. Retain draft with auto-merge off while eager ready-triggered merge conflicts with canonical qualification. Do not rely on `allow_auto_merge:false` as reviewed enforcement.
- Current-head native reviews, all required checks, and review convergence qualify before any separately authorized merge. Known reviewer quota/cooldown and missing fork credentials remain explicit states.

## Dependency

The separate development PR carries origin381 source `40221a69f7fff675b46614d9ab318b4ef52f3786` as an ordinary history-preserving merge atop this synchronization branch. Its review diff must contain only the original eleven feature files plus identified migration planning/provenance. The eight original settled native threads remain linked; their states do not migrate as approvals or round credit.

## Verification

Use read-only Git ancestry/byte comparison, YAML/EDN parsing and actionlint. No application build or live verifier is run for this accepted workflow-history synchronization. Board readiness and status transitions remain Rheos-owned; this incoming Markdown is reviewed authoring input only.
