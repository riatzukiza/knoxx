---
category: "tasks"
labels: "bug, translation, publication, 3sp"
dependency: []
parent: "knoxx-translation-split-memory-feedback"
type: "task"
write-id: "1792051193398-0.5kcgxewwqhctl1gyeac"
points: "3"
title: "Preserve source-owned trailing whitespace at translation split boundaries"
priority: "P1"
status: "breakdown"
uuid: "6be15337-dc07-4669-a7db-3f99e4f27242"
created_at: "2026-10-15T07:58:57.365Z"
---

# Preserve source-owned trailing whitespace at translation split boundaries

## Context

The exact private Knoxx19f CMS journey on 2026-10-15 created an English document, changed only its Spanish intent to draft, and admitted one agent translation. Its native persisted result contains three pending-review candidate members but one collapsed translated paragraph. Source members0/1 retain `\n\n`; all three provider-returned candidate members omit trailing whitespace. Byte-exact aggregate composition therefore concatenates paragraphs. Source revision is `sha256-b9dabbf302d82857fc84387a72a8ee1005f0f134b79bc7ea0de7fdb1fb87b634`; the complete native failure, original candidate bytes and immutable lineage remain evidence.

This is a narrow child of the existing `knoxx-translation-split-memory-feedback` owner. That owner already owns immutable admitted members and deterministic candidate composition. The change belongs exclusively in Knoxx; Foresight qualification60e85d88-3960-449f-8376-10e75eae82d9 consumes the eventual accepted repair and does not implement it.

## Outcome

New provider output preserves exact server-owned trailing boundary whitespace before immutable candidate byte/digest admission. Aggregate and effective composition continue to authenticate the stored member bytes. Existing historical candidate bytes remain byte-identical through accepted-prefix reuse and completed projection recovery.

## Scope

- Define the smallest pure portable boundary operation for only the exact trailing ASCII space, tab, CR and LF suffix of each source member. Use existing admitted reconstruction data; do not introduce a second paragraph parser, Unicode trimming or a claim about leading separators.
- In complete-missing! only, normalize the newly returned validated provider text to the exact source-owned trailing suffix before pair-for/sink/candidate hashing. Do not change shared pair-for or stored-pair: the latter carries historical bytes during durable-prefix recovery.
- Guard other producers only at new immutable candidate admission: in admit-submitted-candidate! for an accepted dispatch, look up an existing candidate first, preserve that durable member when present, and apply the pure typed boundary refusal only before persisting a genuinely absent member. Do not add this new rule to the earlier generic pair-refusal/split-pair-refusal checks, which run before historical lookup. Completed dispatches retain strict existing byte-equal authentication/conflict/missing-member behavior unchanged.
- Extend the existing native structured-output fixture with the actual observed three English/Spanish member pairs and appropriate controls.
- Preserve immutable candidate/review history and the existing dispatch-state distinction: accepted-prefix replay reuses the first durable member even if a provider later changes its bytes; a completed-dispatch replay must be exact, and changed bytes conflict. Earlier malformed candidates remain historical evidence; repaired qualification uses a fresh admitted turn/candidate.

## Non-goals

No blanket separator insertion during aggregate composition or rendering; no new translation/review engine; no SDK, migration, writer-fence or deployment-controller work; no provider/model substitution; no automatic re-admission or retry of the failed fixture; no approval, reconciliation or publication. Sentence-level translation completeness remains an independent semantic judgment. The actual missing sentence in member2 is retained as review evidence and is not declared fixed by delimiter handling.

## Acceptance criteria

- The retained real failure corpus drives the actual structured adapter → sink → candidate-set path with schema-valid completed provider responses. A completion with collapsed admitted paragraph boundaries fails RED on current source for the intended structural reason.
- New structured provider output receives the exact admitted trailing suffix before its first candidate digest; a different producer cannot persist a new suffix-mismatched member. No renderer-only patch, shared replay normalization or mutation of an existing candidate satisfies this criterion.
- Valid responses already carrying correct separators retain byte equality. LF, CRLF, blank-line spaces and multiple blank-line boundaries remain exact; no duplicate separator is added, and a final member with no separator gains none. Protected Markdown/code/list content from the existing partitioner corpus remains unchanged.
- Stored candidate bytes/digests, complete candidate-set digest, receipt-bound content and effective review projection agree. An accepted legacy malformed prefix is reused unchanged. A completed legacy malformed candidate replays byte-identically and can repair a failed downstream event projection; changed completed-attempt bytes still conflict. The new guard runs only for absent accepted members. Existing pending reviews and approved/corrected histories are not rewritten.
- The original actual native failure remains recorded as failed, with no fabricated retry, provider transcript, judgment or deployment success.

## Verification

Use RED-first laws and the existing `translation_agent_structured_output_test.cljs` fixture, canonical source-partition tests and candidate/effective composition tests. Demonstrate current-source failure using the retained genuine corpus, then run affected tests and the required backend compile/typecheck/lint gates with zero new warnings. A later freshly built isolated native CMS journey must preserve complete source/output paragraphs and lineage before its separate semantic review can qualify it. Hosted owning review and production qualification remain the existing deployment obligations; this three-point repair does not waive them.

## Risks and estimate

Three provisional points: one for exact pure delimiter handling, one for the adapter/sink regression and immutable-custody controls, and one for affected verification and native-failure documentation. Blind trimming or joining can corrupt Markdown and authenticated bytes. If the exact change needs new translation semantics, a storage platform or wider refactoring, re-estimate that work under the same existing owner rather than hiding it here.