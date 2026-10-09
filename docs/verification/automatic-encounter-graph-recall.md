# Automatic encounter graph recall

This is the initial RED proof for Cephalon milestone B3: an authorized outside
encounter must lead to associative graph recall in the prompt sent to the maker.
The full story is Foresight task `a1e9d6af-0233-4dcb-9677-5c76fa9a2701`.
Native Rheos admitted its reviewed plan through Ready to In Progress. B1
(persistent encounter and physical field) and B2 (separate persisted and consumed
mood) remain independent unfinished requirements.

## Run the retained regression

From this Knoxx checkout, with its existing backend dependencies installed:

```bash
bash scripts/verify-local-creator-loop.sh --hermetic
```

The existing wrapper creates an owned temporary source snapshot and empty
contract root, runs the full compiled backend test target, and removes that
snapshot. It invokes no model, live social account, gateway, maker, or deployment.
Maintain the accepted 20 GiB free-space floor before starting another snapshot.
There is no passing live graph-recall verifier yet.

The fixture is
`backend/test/cljs/knoxx/backend/character/graph_recall_test.cljs`. It uses existing
encounter identity and admission laws, a fixed nonadministrator principal, a
sessionless semantic seed, and a related encounter absent from vector results
and recent decision context. It calls actual passive hydration and
`prompt-and-await!`, capturing the existing `IAgentSession` send boundary.

The graph response is synthetic transport data. It does not implement or prove
graph traversal, source authorization, field evolution, feedback, or persistence.
Those need their owning implementation and independent readback.

## Observed RED

On parent source `6e57a9966f5cd505499717f4024bf66d9e9e1e5e`, the added regression
compiled in the full suite: 1,753 tests, 8,265 assertions, four failures, zero
errors, and zero compiler warnings. The wrapper exited 2 because the suite
failed. Its actual installed SDK fixture passed. All four failures are in the
new regression:

1. Automatic hydration makes zero graph calls.
2. The authorized sessionless semantic seed is absent from recall hits.
3. Its graph-only neighbor is absent from recall hits.
4. The actual captured session prompt omits that neighbor.

The existing recent encounter does reach the prompt. That successful constituent
does not establish associative graph recall. The fixture makes no session read
and creates no fake session identity to admit an encounter.

The selected stdout excerpt and command/hash/counter evidence are retained in
`.ημ/review-evidence/cephalon-character/automatic-graph-recall-red-20261009.*`.
The complete private log hash is recorded there. Historical receipts remain
unchanged; new observations are appended.

## Graph transport outage RED

The additional regression `graph-transport-failure-is-not-successful-vector-only-recall`
uses an ordinary session memory seed and the actual session-visibility adapter.
Its held nonadministrator principal matches the stored organization, membership
and user. That authorization assertion passes. The held graph boundary rejects
with a safe transport error; automatic hydration must report that distinct
failure and include no vector-only replacement hits.

On source `6f5618cdcd3b67ef2d4aab02ca7aabbffdd9b938`, the existing isolated wrapper
ran 1,754 tests and 8,271 assertions: nine expected failures, zero errors and
zero compiler warnings. Four failures remain in the initial inclusion test;
the new test supplies five failures: no graph call, missing failed status,
missing graph-stage attribution, missing transport code and substitution of the
successful vector seed. The actual SDK fixture passed, the wrapper exited 2,
and its dedicated temporary snapshot was removed. Backend-configured
clj-kondo reported zero errors and zero warnings for the changed test.

This result isolates the required behavior at the real hydration boundary.
It does not prove production graph transport, trusted upstream authorization,
traversal, feedback or persistence. No production implementation changed for
this RED commit. The selected stdout and full-log hash are retained separately
in `.ημ/review-evidence/cephalon-character/automatic-graph-outage-red-20261009.*`;
the initial RED evidence remains byte-identical.

## Requirements before GREEN and delivery

Reauthorize encounter source/account and owner scope before semantic seeds,
graph expansion, compacted projections, trail/force influence, and feedback.
Keep denied data unable to change authorized paths, rankings, prompt content,
or writes. Verify real graph transport failures separately from empty or denied
recall. Preserve the bounded candidate and final inclusion budgets.

The inspected existing OpenPlanner graph-memory route reinforces semantic edges
independently of its optional trail-persistence flag. Filtering its response or
turning trails off does not establish a scoped read. Use the existing owning
graph boundary with reviewed scope and feedback behavior; do not add another
physical solver in Knoxx.

The broader story also requires failure and revocation cases, traced prompt
inclusion, truthful counts, and idempotent deliberate feedback. This initial RED
does not cover all of those obligations. A repaired unit fixture alone will not
qualify live behavior, an epic closure, or a deployment.

License: GPL-3.0-or-later.
