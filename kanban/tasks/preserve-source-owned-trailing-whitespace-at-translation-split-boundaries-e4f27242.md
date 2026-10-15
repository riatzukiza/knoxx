---
category: "tasks"
labels: "bug, translation, publication, 5sp, has-parent"
dependency: []
parent: "knoxx-translation-split-memory-feedback"
type: "task"
write-id: "1792054479327-0.nizfpgzebs6dityink"
points: "5"
title: "Preserve source-owned trailing whitespace at translation split boundaries"
priority: "P1"
status: "breakdown"
uuid: "6be15337-dc07-4669-a7db-3f99e4f27242"
created_at: "2026-10-15T07:58:57.365Z"
---

# Preserve source-owned trailing whitespace at translation split boundaries

## Context

The exact private Knoxx19f CMS journey on 2026-10-15 created an English document, selected Spanish draft intent and admitted one agent translation. Its saved result contains three pending-review candidate members but one collapsed translated paragraph. Source members0/1 retain `\n\n`; all three saved candidate members omit trailing whitespace. Byte-exact aggregate composition exposes the missing boundaries.

The complete privacy-safe source/candidate bytes are checked in as [`native_translation_boundary_fixture.cljc`](../../backend/test/cljs/knoxx/backend/law/native_translation_boundary_fixture.cljc), not merely referenced by a local path. Its `observed-pairs` contains all three exact pairs; `provenance` binds the source revision, complete saved HTTP response hash, candidate/manifest IDs and assembled candidate hash. These are saved CMS readback bytes, not a raw provider HTTP transcript. The source SHA256 is `b9dabbf302d82857fc84387a72a8ee1005f0f134b79bc7ea0de7fdb1fb87b634`; the assembled candidate SHA256 is `00e0e9c99c0dcd68dfde32c83a4077c8f5f891fcfee10e21bf30a1411ed41d51`. The missing source sentence remains visible in the exact corpus.

This is a child of `knoxx-translation-split-memory-feedback`, which owns admitted members, deterministic composition and review corrections. The work belongs exclusively in Knoxx. Foresight qualification60e85d88-3960-449f-8376-10e75eae82d9 consumes the accepted repair and does not implement it. Native Codex review5530154011 identified the corpus, live-verifier, non-ASCII-separator and correction-path obligations incorporated below.

The current operator contract explicitly requires comma-separated scalar labels for Rheos compatibility. This card's scalar form is intentional; historical array-form neighbours do not authorize a label-format migration. Points and labels are changed through native Rheos operations, and the current native readback retains the scalar plus five-point estimate. This repair does not implement or alter Rheos parsing.

## Outcome

New provider output and genuinely new reviewer corrections preserve the exact server-owned trailing boundary suffix before their first immutable content identity is derived. Aggregate and effective composition continue to authenticate stored bytes. Existing candidates and review/correction histories remain byte-identical through replay and projection recovery.

## Scope

- Define the smallest pure portable trailing-boundary operation using the existing source partitioner's own blank-character classification. Preserve every character of the admitted suffix, including non-ASCII horizontal blank characters recognized by that partitioner; `\n\u00a0\n` must not become a single newline. Share the existing classification rather than create another paragraph parser. Do not normalize line endings, leading separators or arbitrary interior text.
- In `complete-missing!` only, restore newly returned validated provider text to the exact source-owned suffix before `pair-for`, sink admission and first candidate hashing. Shared `pair-for` and `stored-pair` also carry historical candidates and must remain byte-exact.
- Guard other candidate producers only at genuinely new immutable admission: in an accepted dispatch, look up the durable candidate first and preserve it when present; apply a typed boundary refusal only before persisting an absent member. Keep the earlier generic `pair-refusal`/`split-pair-refusal` and completed-attempt authentication compatible with historical bytes.
- Apply the same source-owned operation to genuinely new explicit reviewer correction text in `infra/routes/translation_review.cljs`, before new operation/correction/receipt identities. Compare exact intent only with the current effective same-principal review, never by searching older matching history, so A→B→A remains a new intent. Resolve that current-review replay before applying the new rule. An exact replay of an old malformed correction retains its stored bytes/IDs; document-level reuse of current corrections and historical receipt validation do not normalize or rewrite them. Effective composition remains byte-exact and gains no renderer join patch.
- Extend the existing structured-output, sink and review-route fixtures with the checked-in genuine pairs and mixed-delimiter controls. Preserve accepted-prefix reuse, strict completed replay/conflict and projection-repair behavior for old malformed candidates and old correction receipts.
- Extend the existing human verification pair `scripts/verify-translation-split-review.sh` and `docs/verification/translation-split-review.md`, shared run-scoped fixture helper and `scripts/verify-translation-split-review-tour.sh`. The live HTTP/browser checks must expose a correction that omits invisible boundary whitespace and verify the persisted corrected/effective text and displayed paragraphs. The fixture-seeded tour explicitly proves the review seam, not real provider quality; retain its WARN and separately require a fresh real CMS/provider journey.

## Non-goals

No blanket separator insertion during aggregate composition or rendering; no replacement translation/review engine; no SDK, database migration, writer-fence or deployment-controller work; no provider/model substitution; no re-admission or overwrite of the original failed candidate; no automatic approval, reconciliation or publication. Sentence-level adequacy remains an independent semantic judgment. The omitted sentence in observed member2 is retained and is not declared fixed by whitespace preservation.

## Acceptance criteria

1. The checked-in exact corpus drives the real structured adapter → sink → complete candidate-set fixture with schema-valid completed provider responses. Current source fails RED for collapsed boundaries, and all three original pairs plus source/assembled candidate hashes can be verified without access to another workspace.
2. New provider responses acquire the exact admitted suffix before first candidate digest; a different producer cannot persist a new suffix-mismatched member. Existing equal separators retain byte equality. LF, CRLF, lone CR, ASCII spaces/tabs, non-ASCII horizontal blanks recognized by the partitioner, mixed/multiple blank lines and final members with/without suffix all have explicit controls. No duplicate or invented separator appears; the existing protected Markdown/code/list corpus remains unchanged.
3. A new single-split correction with omitted or changed trailing whitespace produces persisted corrected text and effective output with the exact source boundary, with operation/correction/receipt identities derived from those first stored bytes. Corrected text already carrying the proper suffix is unchanged. Replaying the same corrected intent is idempotent; conflicting new intent retains existing review-history semantics.
4. Historical malformed candidate bytes/digests, complete-set digest and receipt-bound content remain immutable. Accepted-prefix replay reuses its first durable member; completed equal replay can repair failed downstream projection and changed completed bytes still conflict. Exact historical malformed-correction replay and document-level preservation retain the original receipt/correction bytes and identities; new rules do not invalidate historical review verification or approved output records.
5. The extended runnable verifier checks serving-checkout identity and all existing preconditions before writes, seeds only run-owned filesystem/database facts, covers unauthenticated access and the collapsed-boundary failure mode, reports what each assertion proves, exits nonzero on failures and removes its owned data on EXIT/INT/TERM. Its guide documents commands, scope, retained WARNs and cleanup. The browser tour captures the correction and effective-output paragraph states under ignored screenshots without silently skipping the changed surface.
6. The original failed native journey remains recorded as failed. A freshly built isolated CMS journey must subsequently retain complete paragraph structure and lineage before its separate semantic judgments, whole approval, publication and production qualification.

## Verification

Run genuine RED-first controls through the existing structured-output/sink/review-route suites, canonical partition/effective-composition laws and the checked-in corpus. Run the required backend compiled tests, server compile, typecheck and owning lint with zero new/touched warnings. Extend and execute the existing live verifier and browser tour against the exact served checkout in an owned isolated environment; retain native outcomes, cleanup and screenshots. Then run a separate real CMS → Knoxx/local-provider → persisted candidate journey. Fixture-seeded verification is never reported as provider inference. Existing hosted review, migration/durability and release gates remain outstanding until separately qualified.

## Risks and estimate

Five provisional points, re-estimated from three after native review: one for shared exact suffix handling including partitioner-recognized non-ASCII blanks, one for provider/sink custody and replay controls, one for new-correction admission with historical receipt compatibility, and two for the checked-in failure corpus, live verifier/browser changes and affected gates. Blind trimming, global receipt normalization and renderer joins can corrupt authenticated history. If the change needs a new translation/storage protocol or wider refactoring, re-estimate under the same existing parent before expanding it.

---
Planning refinement for native Codex review5530154011 roots4280675440/448/456/461: check in byte-exact privacy-safe three-pair CMS failure corpus with verifiable source/candidate hashes; require the existing runnable split-review verifier/guide/browser tour and cleanup; preserve all trailing blank characters recognized by the canonical source partitioner including NBSP mixed delimiters; include genuinely new correction-boundary handling before first immutable correction/receipt identities while preserving exact historical replay and bulk reuse. Re-estimate 3→5 points and keep scalar labels per explicit operator contract/current Rheos. Body/proposal frozen at planning-native-findings-v2; no production implementation/tests executed, no readiness claim or original failed-candidate rewrite.

---