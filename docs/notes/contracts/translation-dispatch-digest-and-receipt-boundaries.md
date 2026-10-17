<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Translation dispatch digest and receipt boundaries

This note preserves rationale moved from three API docstrings in
`knoxx.backend.law.translation-dispatch`. Source functions and schemas remain the
callable authority. No admission, retry, persistence or receipt behavior changes.

## Current boundaries

`pin-refusal` compares a proposed work revision with the source digest observed
at dispatch. It returns `:source-unverifiable` when the context recorded no digest,
`:pin-not-tied-to-observable-bytes` for a mismatch, and nil for equality. The worker
and agent dispatch adapters use this decision before attempting the work.

`source-drift-refusal` compares the completion observation to the **recorded
source digest**, not an opaque dispatch revision. Missing recorded evidence is
`:source-unverifiable`. If a recorded digest exists, an unequal observation,
including nil, is `:source-moved-since-dispatch`; equality returns nil. The
completion adapter performs that observation before receipt persistence or
terminal drift settlement. Agreement between repository observations does not
prove which bytes an external OpenPlanner worker read. That limit remains real.

`translation-receipt` has three existing arities: record/output revision/time,
then an optional content digest, then optional split evidence. It projects the
record and caller-supplied values, conditionally adds attempt/content identity,
merges `split-evidence` last, and validates the complete result with the existing
receipt law. This describes the actual merge order; it does not create a new
restriction on keys accepted at that constructor. The worker completion adapter
and split projection call the same constructor. The latter supplies lineage
from its admitted turn, candidate set and current reviewed output.

## Preserved original rationale

The following raw docstring source contents are copied verbatim from revision
`a64f2f27e015d1b9322fd14fc2e797e26238c56a`; they are byte-identical to the selected
functions at `fde4d2381345174869530abc9bb05b6c5c053e5b`. Original escaping is
retained as source spelling. Historical descriptions and withdrawn overclaims
remain inspectable here; concise source docs and actual schemas describe the API.

### `pin-refusal`

````text
Why `work` cannot be dispatched under `context`'s observed digest, or nil.

   The case this exists for is a pin that was *never* the bytes on disk. An
   intent may pin any nonblank revision, including a historical or opaque one, and
   nothing here can resolve such a token to content — there is no version
   authority, only the current file. So at dispatch time the observed digest is
   the *current* content while `:revision` names something else entirely.

   Left alone, that mismatch predates dispatch and the drift guard never sees it:
   the worker translates its current document, completion compares the current
   digest against the current digest, they agree, and a receipt claims the pinned
   revision was translated. A false receipt produced without anything ever
   changing.

   So a pin must equal the digest that can be observed, or it is refused before a
   batch is created. `:source/current` intents are unaffected — the gate resolves
   them to that same digest, so the two are equal by construction. A pin that
   happens to name the current content is likewise fine. A pin that names anything
   else cannot be tied to bytes, and no receipt about it could be substantiated.
````

### `source-drift-refusal`

````text
Refusal when the source no longer hashes to the revision that was dispatched.

   The worker is handed a document *id*, not bytes, and it fetches the current
   content when it eventually runs. So the bytes it translated are whatever the
   document held at run time, while the receipt asserts the revision Knoxx
   hashed at dispatch time. If the source changed in between, that assertion is
   false: a pinned old revision would be reported translated on the strength of
   a translation of different bytes.

   ## What this does and does not establish

   It establishes that the **repository source** did not change between dispatch
   and completion. It does NOT establish that the worker translated those bytes:
   the worker fetches its input independently from OpenPlanner's document store,
   not from this checkout, so a document already divergent over there would be
   translated while both of these observations agree.

   An earlier version of this docstring claimed the unchanged digest meant the
   worker necessarily fetched those bytes. That was an overclaim and it is
   withdrawn — the two stores are different, and nothing here reaches the one the
   worker reads.

   What remains worth doing: a source that moved locally is a receipt that is
   definitely wrong, and refusing those is strictly better than refusing none.
   Closing the rest needs the batch contract to carry a digest of what was
   actually translated, which is a change in another repository — recorded on the
   card rather than papered over here.

   Compared against `:dispatch/source-digest`, **not** against
   `:dispatch/revision`. Those are different things and conflating them was a
   defect: `law.publication/PublicationRevision` admits any nonblank string, so
   an intent may pin an opaque revision like `\"abc123\"` while the observer can
   only ever produce a `sha256-...` content digest. Comparing the two reported
   drift on every completion of every pinned intent, forever. The record
   therefore carries the digest observed at dispatch time alongside whatever
   revision the intent named, and the comparison is digest to digest.

   A nil `observed-digest` means the source could not be read at all, which is
   also not proof, and is refused for the same reason.
````

### `translation-receipt`

````text
Mint completed-translation evidence from a resolved binding.

   Every identity field comes from the *record*, never from the worker's report.
   The worker was never told the revision, so it cannot supply one, and a report
   that appeared to carry one would be describing something it could not know.

  `output-revision` is the produced translation's own identity, supplied by the
  caller from the worker's answer. It is validated as a concrete revision like
  any other, which is what stops a worker replying `\"source/current\"` from
  becoming the revision an approval is later pinned to.

  `split-evidence`, when present, is the complete lineage of an agent-produced
  candidate set. It is merged only at this constructor boundary so worker and
  agent completions still mint the same receipt law rather than maintaining
  parallel evidence shapes.
````
