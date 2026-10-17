---
uuid: 4fea9977-a3b6-49c8-aa09-231209926181
title: Reuse the eight stable publication schemas without changing validation semantics
status: incoming
priority: P1
points: 3
category: tasks
labels: tasks,3sp,has-parent,cms,publication,contracts,performance
parent: knoxx-contract-owned-publication-pipeline
dependency: knoxx-publication-resource-contracts
created_at: 2026-10-17T00:00:00Z
---

# Reuse the eight stable publication schemas without changing validation semantics

## Context

The canonical parent is `knoxx-contract-owned-publication-pipeline`, currently
`breakdown`. The predecessor `knoxx-publication-resource-contracts` is `done`.
These are the exact explicit `uuid` values returned by Rheos, including their
historical slug spelling. Their status and old implementation reviews do not
provide readiness for this new performance slice.

The current coherent source is private `21bed7cdf1e5835bd985b0afd0d894448e80621d`.
Its publication source, locale contracts, existing publication tests, compiler
configuration, and dependency declaration are byte-identical to the qualified
`13c81a13e1d4fc7772629ea257bbe1d41d8ec05c` inputs. Actual pinned Malli 0.16.4
recompiles raw vector schemas when `m/validate`/`m/explain` is called repeatedly.

The actual 13c three-mutation pilot passed all three assertion kills but measured
174.7–196.5 seconds per full test child. Historical f19 coverage namespace arrival
intervals were 285.572 seconds for publication-migration-fold and 269.585 seconds
for the CMS publication route tests. Eleven selected source files are byte-identical
f19→13c. Those coverage arrival intervals are a lead, not measured attribution of
current plain-suite duration or proof of a speedup. A separate five-second Mongo
fixture attempt is not the claimed explanation for the long test child.

## Outcome

Reuse a finite prepared representation of the eight unchanged publication resource
schemas at the existing `publication/assert-valid!` boundary, while preserving the
raw schemas as semantic authority and all generic validation behavior. Measure the
actual complete suite after qualification; do not promise a campaign runtime fit.

## Scope

- Keep all existing raw public schemas, public signatures, contract IDs, successful
  value identity, and named errors/ex-data unchanged.
- Configure one narrow pure `publication_validation.cljc` selector once from the
  eight exact local raw schema objects: Document, Garden, PublicationIntentResource,
  PublicationIntent, PublicationDocumentView, PublicationListView,
  PublicationGardenView, and PublicationGardenListView.
- Bound prepared state to those eight delayed Schema objects. Compilation remains
  demand-driven; actual Malli supplies its Schema-local validator/explainer caches.
- Preserve the original raw generic path for unknown, redefined, external, or
  runtime-registry schemas and changed API/default-registry/type-resolution contexts.
  An unchanged default-registry object alone does not establish unchanged semantics.
- Add one auto-discovered law test namespace that calls the existing public boundary
  and actual Malli. Preserve the entire existing publication test file byte-for-byte.

The implementation proposal names only these three paths:

- `backend/src/cljs/knoxx/backend/law/publication.cljs`
- `backend/src/cljs/knoxx/backend/law/publication_validation.cljc`
- `backend/test/cljs/knoxx/backend/law/publication_validation_test.cljs`

## Non-goals

- No growing memoization map, unbounded cache, mutable semantic registry, or changes
  to other Malli consumers, schemas, provider contracts, publication effects, or DBs.
- No skipped tests, reduced assertions, changed mutation prefixes/classifiers/caps,
  forced exit, timeout growth, linter suppression, policy waiver, or fake SDK/provider.
- No compiler/libspec work, network fixture cleanup, distributed campaigns, model
  inference, production change, schema migration, writer fence, or new account.
- No readiness, adoption, release, mutation score, or runtime credit from source
  review, old approvals, semantic exclusions, or this planning card.

## Acceptance criteria

1. Each of the eight exact stable raw schemas reuses an actual prepared Schema and
   delegated validator across repeated public-boundary calls; all raw public values
   and forms remain unchanged. The finite bound cannot grow for unknown inputs.
2. Every lawful fixture returns the exact supplied value. Invalid fixtures retain
   the existing message and humanized `:contract`/`:errors` data. Unknown malformed
   schema errors retain the original Malli path.
3. Root/API redefinitions, registry replacement, mutable/dynamic resolution,
   same-object changed type resolution, unknown raw forms, external Schema objects,
   and runtime registry keywords retain their live generic behavior.
4. The new public-boundary controls genuinely fail on unchanged product for the
   claimed reuse behavior, then pass with only the two product paths. Loader,
   setup, dependency, or analysis errors are never causal RED evidence.
5. All old tests/assertions remain. The same complete registered backend suite
   reaches zero failures/errors with actual source, native, payload, and process
   closure evidence. Current coherent aggregate counts must be measured, not
   transferred from the older 13c pilot.
6. Retain actual registered lint output and diagnostic correspondence. The current
   global 169-warning/native-2 gate and inherited publication file-length warning
   remain failures; neither the story nor the source review waives them.
7. Report comparable full compile/test child timing and its limits. No speedup,
   six-hour campaign fit, or production capacity claim precedes measurement.
8. Before any later four-campaign execution, re-enumerate the exact final canonical
   candidate universe and original four logical 250 prefixes/equivalent basis.
   Adding or editing law sources must not silently change accepted selection.

## Verification

Planning review and lawful readiness through canonical `pr-flow` and Rheos are
prerequisites. This is a proposed initial incoming story, not a status transition.
All review and merge rules remain owned by `~/.agents/skills/pr-flow/SKILL.md`.

After readiness, make an explicit test-only commit first. The frozen proposal has
eight tests and 80 predicted assertions; 16 intended reuse failures are predictions
until the unchanged-product full hosted run proves them. Then apply the two exact
product postimages and run the identical full suite to GREEN with no old assertion
loss. Follow the registered backend compile/test/typecheck commands and retain the
unfiltered registered lint failure; source/checker configuration stays unchanged.

For timing, reuse the already prepared one-full-baseline composition at the actual
canonical mutation harness's compile/test boundary, preserving its commands,
240000ms child caps, full test runner, source/payload checks, terminal cleanup,
and zero campaign credit. Its byte-preserving stdout tap has three already-passed
inert controls; any hosted use needs reviewed source-tuple bindings. Raw stdout
is not the whole inherited-stderr capture, and arrival intervals are bounds.

Keep test-only, product, and canonical Receipt River/portable session-mycology
provenance commits distinct. No same-session spore promotion. Root owns the hosted
workflow/application/dispatch and any later release or campaign admission.

## Risks

A finite registry guard must not serve a stale compiled contract after relevant
resolution changes. Malli's default registry delegates through mutable indirection.
The proposal does not claim equivalence for arbitrary stateful custom IntoSchema
implementations. All actual selected keys and the supported public contexts need
source review and real compatibility controls.

Historical coverage timing may not predict the current plain suite. First-use
compilation, pipe buffering, failure output, and worker scheduling affect measured
results. Existing lint, migration/fence/recovery, review/adoption, staging, and
Spanish-publication obligations remain independent release blockers.
