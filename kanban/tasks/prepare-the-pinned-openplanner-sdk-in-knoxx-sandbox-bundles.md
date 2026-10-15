---
category: "tasks"
labels: "knoxx, sandbox, ci, sdk-bootstrap, source-custody, 3sp"
dependency: []
type: "task"
write-id: "1792057570859-0.gsw8vvz8nouor5rj5i5"
points: "3"
title: "Prepare the pinned OpenPlanner SDK in Knoxx Sandbox bundles"
priority: "P1"
status: "breakdown"
uuid: "prepare-the-pinned-openplanner-sdk-in-knoxx-sandbox-bundles"
created_at: "2026-10-15T09:44:54.782Z"
---

# Prepare the pinned OpenPlanner SDK in Knoxx Sandbox bundles

## Context

Knoxx15 Sandbox job116336083846 checked out OpenPlanner07085d6557b75834ce6f50e6c54b8ca47e1c7c08 at2026-10-15T08:56:10Z and successfully linked the SDK during backend installation. At08:58:18Z the real guarded test process failed because the SDK's dist/search-core.js did not exist. The Sandbox workflow materializes the sibling source but never installs/builds the SDK. The genuine search-core import in backend/test/js/openplanner_sdk_test_stub.mjs:5 is intentional and must remain.

The gap dates to Sandbox introductiond939b0030ce7391ba21ae05cd5df26a2fccdc67f; test-only RED0f0d551625200ab1cf8ee3c96649aba041db5dca exposes it through real SDK code. Ordinary ci.yml already runs the upstream-owned filtered frozen SDK install/build using pnpm10.20.0. Its mutable main selector is not immutable custody.

## Outcome

A cold, owned Knoxx Sandbox preparation builds the exact genuine SDK dependency before backend use and retains enough source, identity, logs and restore instructions to reproduce that dependency from the bundle. Any source/preparation failure remains a failed job with inspectable partial evidence. This standalone three-point CI-bootstrap repair does not implement SDK staging graph migration.

## Scope

- Bind the existing Sandbox checkout to exact SDK07085d6557b75834ce6f50e6c54b8ca47e1c7c08. Use pnpm10.20.0, Java21 and Node24; retain frozen lockfiles and actual tool versions. Run the existing upstream commands, in order: `pnpm -C <sdk-root> --filter @open-hax/openplanner-sdk... install --frozen-lockfile`, then the same filter's `run build`.
- Prepare and retain the actual source closure SDK→packages/stores/document-hydration→packages/stores/cache, their real build inputs and root package/workspace/lock metadata. Record exact revision/tree, selected source/archive hashes, package-manager version and generated export hashes. A new source dependency must be made explicit; no neighbor's installed or generated products may fill a gap.
- Explicitly exclude tracked root .env and all credential/config material not required by this public build closure. Do not read credential values or import user .npmrc, provider keys, auth stores or shared installed trees. Extract only the declared immutable build-source closure; do not package opaque Git history that silently reintroduces excluded .env bytes. Retain the exclusion policy with the source inventory.
- Restore the retained source into an owned sibling path and run the same filtered frozen install/build before backend validation. Record the restore ordering in the existing bundle manifest setup_commands; keep network_required_after_restore:true. Do not claim offline recovery.
- Add SDK preparation to the existing manifest outcomes and final failing aggregation, preserving partial logs and independent frontend/ingestion evidence. An absent, skipped or failed prerequisite cannot become success; a failed install stops the SDK build, and a later successful step cannot erase a prior failure.

Expected implementation surface: .github/workflows/sandbox-bundle.yml; a minimal existing-shell-boundary scripts/build-sandbox-openplanner-sdk.sh so the same build command is executable by a registered test; backend/test/js/sandbox-openplanner-sdk-bootstrap.test.mjs; and a short docs/verification/sandbox-sdk-bootstrap.md. Workflow owns source pin/custody; the helper only orchestrates the two existing native build commands. This is not a generic dependency framework.

## Non-goals

No OpenPlanner/SDK application or semantic changes, copied SDK algorithms, fabricated dist, expanded test aliases, removed assertions or relaxed fatal guards. Preserve the original genuine SDK import and all backend coverage/error handling. No graph/source-adoption design, embedding/model qualification, application service execution, credentials/settings changes, native-review rerun, caller adoption, infrastructure or production deployment. SDK staging-design card6cc1e1ca-c8dd-4cf9-9cd1-24675a7ee0c0 stays untouched and is not a dependency or implementation grant for this card.

## Acceptance criteria

- [ ] The actual Sandbox source preparation binds exact07085, pnpm10.20.0 and the real filtered frozen install/build before backend dependency use. A wrong/unavailable source or incompatible package manager refuses; no mutable-main or neighboring-dist fallback supplies success.
- [ ] A committed registered cold-source RED precedes production edits. It materializes exact genuine SDK source without dist, executes the current authored sibling-preparation stanza, then fails on the real missing search-core export. It does not fail merely because a future helper/step is absent and does not substitute a fake SDK.
- [ ] GREEN executes the new authored SDK preparation stanza against the same immutable source and unchanged genuine package-import assertion. The actual generated search-core export resolves through the owned Knoxx link. Exact inputs and output hashes, native exits/counts and cleanup are retained.
- [ ] The retained source/archive inventory covers SDK, hydration, store-cache and actual build prerequisites, excludes tracked .env/credentials without reading values, and survives checksum/source-identity validation. Cold restoration without the original sibling or old dist reproduces the genuine dependency using the manifest's declared commands; network requirements remain explicit.
- [ ] Missing source/export and failed install/build controls remain nonzero. Failed or skipped preparation is represented honestly in metadata and final job outcome; existing independent evidence and partial logs remain recoverable. Transport-only call-order controls are labelled and grant no SDK-semantic success credit.
- [ ] The actual SDK source, existing search-core import, backend test aliases, fatal-error guard and coverage failure propagation remain unchanged. No model/database/application operation is needed to prove bootstrap.
- [ ] Relevant registered backend tests and applicable zero-warning checks complete after independent source review. Known inherited failures stay visible. Planning review/readiness supplies no passing CI, merge/release qualification or exception for the existing Sandbox RED.

## Verification

The future registered Node fixture runs actual authored SDK preparation shell stanzas against a fresh run-owned immutable-source fixture with its own process/directory cleanup. Before GREEN the new build stanza is absent, so the unchanged real import exposes the missing export. After GREEN the same fixture executes that stanza/helper and resolves the real built module. Exercise absent/wrong source, missing generated output and native preparation failure; use transport-only subprocess controls solely for call order and aggregation. Run the full affected registered backend owner and source/tooling checks, preserving stdout, inner/outer exit, counts, warnings and cleanup. No test execution or implementation is claimed by this planning card.

## Risks

SDK build depends on its actual CLJS hydration/cache closure as well as TypeScript; a lone dist placeholder or SDK-only source directory is insufficient. pnpm9 may report a frozen-lock mismatch for the pinned workspace, so use the already-established10.20.0 recipe without rewriting lockfiles. Registry/Maven unavailability remains a failure, not proof of a code defect or permission to skip validation. The existing private sibling has generated products and a tracked .env deletion; preserve it and create a new owned cold fixture. If a broader SDK/source migration is needed, split and review it instead of expanding this three-point repair. Card remains in planning until independently reviewed and lawfully made ready; implementation and release require separate authorization.