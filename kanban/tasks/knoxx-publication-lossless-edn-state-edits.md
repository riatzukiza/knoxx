---
uuid: knoxx-publication-lossless-edn-state-edits
title: Publication — Lossless EDN State Edits
status: ready
priority: P1
points: 3
labels:
  - tasks
  - publication
  - has-parent
---

# Publication — Lossless EDN State Edits

> Parent epic: `knoxx-publication-runtime-follow-up`

## Purpose

Replace value-level whole-manifest rewrites with syntax-preserving targeted edits for human-maintained publication/resource EDN.

## Work

- Locate publication/CMS state mutation paths that parse EDN and rewrite with `pr-str` or equivalent whole-value serialization.
- Introduce a syntax-preserving edit seam using `rewrite-clj` or an equivalent zipper representation.
- Preserve comments, unrelated resources, ordering, and surrounding formatting where practical.
- Fail closed on malformed/unreadable state; never reconstruct a partial manifest from a failed read.
- Add fixture and property/round-trip tests covering comments, sibling resources, documents, gardens, and targeted state transitions.

## Definition of Done

- A targeted publication state mutation changes only the intended semantic node.
- Comments and unrelated resource syntax survive the edit.
- Malformed state produces no write.
- Existing publication identity/manifest-preservation tests remain green.
- Backend test/lint/typecheck gates for touched code pass.


## Proposed current-source refinement — planning input only

The complete original card above remains a byte-exact prefix, including UUID, Ready/P1/three points and all five completion criteria. This BODY proposal creates no new event, estimate, dependency or card; source-bound native Ready is not a newly qualified plan. See [the full design](../../docs/notes/design/lossless-publication-state-planning.md).

### Context, outcome and scope

Parent [issue #246](https://github.com/open-hax/knoxx/issues/246) explicitly owns this deferred independent lossless-state slice and its closeout-first sequence. Accepted340 still reads publication/resource EDN, patches the allowed semantic state, and whole-value serializes it. Both actual state-write branches (resource and CMS-document/lock path) must preserve syntax, current immutable publication identity, siblings, accepted document/revision and authorization. Keep human-maintained comments, order and surrounding formatting where practical; do not confuse value-level unchanged siblings with byte-preserved text. Other parent lifecycle/Gardens/live-verification outcomes remain separate.

A proposed practical pure `.cljc` boundary receives complete text/canonical target/validated next state, produces a validated minimal change, exact no-op or refusal; named outer adapters retain I/O, current-state locking and JS conversion. Use existing resource canonicalization, publication law and semantic unchanged-except contract. Pinned rewrite-clj1.1.49 primary source supports CLJS string zipper/parsing APIs, but a whole-map edit may coerce away internal comments. Target the actual syntax leaf or reviewed bounded absent-field insertion; actual compiled and portable-host compatibility remains a future proof, not a planning pass. No parser is introduced for Rheos/event/receipt semantics.

### All original acceptance outcomes retained

1. **Only intended node:** single and namespace/multi-resource manifests locate exactly one canonical publication; wrong/missing/duplicate ids, wrong namespace or identity change refuse. Every other resource/value and document/garden/locale/revision/id stays intact.
2. **Syntax survives:** hostile comment/trivia/order/escaped-content/nested sibling fixtures compare actual persisted bytes. No whole-map coercion or whole-value serialization may be relabeled lossless. Review the minimal insertion region if state was absent.
3. **Malformed means no write:** reject incomplete/trailing unsupported forms, duplicate keys before collapse, duplicate target, invalid state and read/parse/stale-text failure. Do not synthesize a partial manifest. Prove zero write calls plus unchanged real private fixture bytes; errors and no-op differ.
4. **Existing regressions retained:** preserve namespace identity, immutable publication identity, sibling manifest and current CMS locking/revision/admission tests. Add meaningful actual-production-branch property/roundtrip/idempotence/no-op and independent-root/stale-write controls.
5. **Gates:** new assertions must run in the official guarded runner and deliberate failure exits nonzero. Full touched backend tests, test compile, typecheck/server build, strict lint and required boundary/production gates remain required; historical warnings/missing tools are holds, never passes. Dual-host portable/pinned-library and human-verification artifacts are future obligations.

### Verification, risks and review decisions

Future red captures current loss of comments/layout in private fictional manifests through actual state-write paths; green splits portable decisions from the required adapters without changing admission/locking policy. Use `pnpm -C backend test`, `pnpm -C backend exec shadow-cljs compile test`, `pnpm -C backend typecheck`, `pnpm -C backend lint`, relevant required boundaries and the design's dual-host/whole fixture matrix. The proposed runnable human script must prove exact serving checkout, seed/tear down only owned data, show syntax/semantic outcomes on supported actual routes and unauthenticated/refusal cases, and fail loudly on missing preconditions. No real server/provider/test/compiler runs here.

Foreign #343/#305 overlap the CMS route for admission semantics; no pending source is adopted. Fresh coordination after their qualification/current-main reconciliation is required; preserve #324/#325 and original #181/#182 zero-warning holds. Pending personal synchronization PR #1 is the intentional route, not qualification. Native events for this UUID are absent in the recovered accepted ledger, and no specific eligible planning review was recovered; leave Ready/history unchanged and obtain review for this full source-bound proposal before implementation. Review complete three-point fit, exact supported grammar/duplicate-key policy, result ABI, host compatibility, minimal insertion and stale-write/lock contracts. If too large, propose lawful breakdown instead of narrowing or manually changing lifecycle/estimate.
