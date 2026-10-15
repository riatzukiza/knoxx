---
category: "tasks"
labels: "tasks, 5sp, has-parent, regression, translations, frontend, cms"
dependency: ["knoxx-translation-review-current-view-mutation-lifecycle"]
parent: "knoxx-translation-review-chat-panel"
type: "task"
write-id: "1792076096320-0.3jmueuyn4x7oirmpbcf"
points: "5"
title: "Preserve current translation drafts while refreshing completed reviews"
priority: "P0"
status: "breakdown"
uuid: "ae9a3cb7-a630-4d8d-8d60-c69b825d008d"
created_at: "2026-10-15T12:55:25.475Z"
---

# Preserve current translation drafts while refreshing completed reviews

## Context

Codex review5532975630 exposes a resource-review race omitted by the legacy-only
pending-draft case. `view.cljs:202-232` submits split/bulk D1 then reloads.
`review_controller.cljs:23-39` installs the refreshed logical selection and its
140-150 detail path resets segment/form. `review_form_sync.cljs:6-15` rehydrates
when immutable review identity changes. A newer D2 typed while D1 is pending can
therefore disappear without any direct `set-form!` in the resource submit runner.

The prior lifecycle card also owns a separate-but-coupled reload risk: a valid
current inventory request predating mutation completion must install, then get
one coalesced current-context catch-up. This successor owns the same-view reload
admission and form-state preservation together, using the base lifecycle fence.
It adds no backend/provider behavior. This is planning, not implementation GREEN.

Native Codex review5534710816/root4284210917 identifies omitted reload producers.
Document review also installs fresh legacy detail directly; whole-output approval
reloads after its required approval -> reconcile. Dispatch and Retry share the
sixth runner and can coexist with editable, exactly matched legacy segments.
Every applicable runner must consume the same current draft/refresh policy.

## Outcome

A current newer draft and segment survive an earlier eligible mutation's completion,
controller detail reset and form-sync rehydration within the same admitted
work/candidate context. A pristine form still reflects a legitimate new review.
A pre-mutation pending snapshot installs without cancellation; one required
current-context refresh then reflects the completed mutation without read storms,
stale coordinates or a lost draft.

Mandatory predecessor: `knoxx-translation-review-current-view-mutation-lifecycle`.
The locale3 story depends on this5 and the core5, so the full safety chain must
qualify. All remain bounded children of the unchanged/open accepted8 parent
`knoxx-translation-review-chat-panel`.

## Scope

- Reuse the predecessor's real project-generation/filter/work/mounted context
  and operation ownership. Extend reload-producing UI completion with the
  segment/candidate identity and local draft revision. No new server identity
  law or generic async framework; use current `logic/same-work?` semantics.
- Cover all six existing reload-producing runners: legacy segment, resource
  split, resource bulk, document review, publication approval and translation
  dispatch. Dispatch and Retry are two normal-UI entries to the same last runner.
  Preserve each original captured payload/status/scope/authority and required
  follow-ups. Only the three review-submit payloads contain captured D1 form
  data; document/approval/dispatch must not serialize a later draft or gain an
  extra write. Keep normal form/segment controls editable; do not freeze edits,
  change the submitted set or send D2 as another mutation.
- Use actual editable eligibility. Legacy document review coexists with its
  segment editor. Whole-output approval uses an admitted resource split set with
  every split already approved, while its selected split stays editable; retain
  the real approval enablement gate. For Dispatch and Retry use resource work
  carrying the server's allowed action and an exactly matched legacy split set,
  which the existing compatibility bridge exposes without changing that action.
  Plain candidate-less or read-only/unhydrated resource rows have no editor:
  assert that absence rather than inventing D2 or granting false coverage.
- Fence every applicable runner's detail/form reset and feedback through the complete
  direct-completion -> inventory -> selected-row -> detail -> form-sync path.
  Same-work refreshed maps are not work navigation. Preserve an existing current
  segment and newer dirty draft while showing updated canonical review state.
  Actual work/segment/candidate-authority changes retain their established safe
  selection/reset/rehydration; never transplant old edits into a new authority.
  Add no cross-segment cache, autosave, durable draft store or conflict screen.
- Distinguish draft revision from saving owner. Blocking D1's stale draft reset
  must still release its own saving when appropriate, and must never release a
  newer B operation. Preserve legitimate initial/pristine synchronization and
  ordinary D1 completion behavior when no newer edit exists.
- When a pending same-project snapshot predates an admitted mutation completion,
  conserve that exact load/generation and its installation; afterwards require
  one coalesced refresh from the then-current context. A matching read started
  after completion may satisfy freshness without a third read. Multiple eligible
  completions coalesce; no cancellation, permanent skip or automatic retry loop.
- Revalidate context at deferred-refresh start and result installation. Old
  project completions cannot refresh B; queued work cannot outlive unmount or
  target a superseded filter/work. Current failures surface normally and end
  that bounded attempt, without an automatic infinite retry.
- Product/test scope is the existing translation `view.cljs`,
  `review_controller.cljs`, `review_form_sync.cljs`, `page_interaction_test.cljs`,
  `review_controller_test.cljs` and `review_form_sync_test.cljs`. Form-sync source
  and its test are two explicit additions to the former six-file aggregate scope.
  Locale owns existing `logic.cljs`/`logic_test.cljs`. Shared paths are changed
  in dependency order; no parallel overlapping implementation or new endpoint.
- Extend the core story's private deferred adapter in the existing shared fixture
  helper, tour and verification doc. This story owns its own named unit/browser
  checkpoints, counters/screenshots and teardown. Default18/full tour and locale
  opt-in20/18/1/1 remain unchanged.

## Non-goals

Six mutation runners stay six; read-only SFT stays separate. No backend/auth/
provider/approval/reconcile/publication change, retarget/reissue, live content
mutation or release. Core retains project/filter/work/unmount boundaries,
operation ownership and read-only export feedback/real anonymous-auth checks;
locale retains unfiltered options and deterministic selection. Their own unit
and browser obligations are not waived by this story or by a shared adapter.

## Acceptance criteria

1. For EACH of the six runners in its actual editable case, start exactly one
   original operation with S1 selected, type recognizable D2 on that same S1
   while pending, then finish the real reload/detail/form-sync effects. S1/D2
   survives after effects settle; canonical state, original payload/call counts
   and saving ownership remain correct. Include changed review identity for
   review submits/document review and a changed same-authority row for approval/
   dispatch refresh. Separate rejection/finally schedules preserve D2 without
   stranding its operation; no-op fixtures cannot stand in for the actual reset.
2. For EACH of those six runners repeat S1 pending -> normal annotation selects
   S2 -> edit D2 -> old success/detail or rejection/finally last. S2/D2 remains
   current. Bulk retains its complete original submitted set. The named unit
   and normal-UI browser matrices include seven entries: the three submits,
   document review, whole-output approval, Dispatch and Retry. Prove their
   enabled action and editable form before starting, without bypassing saving,
   approval or server-action gates. Preserve actual candidate-authority change
   behavior rather than carrying legacy edits into a newly produced candidate.
3. Preserve `same-split-external-review-rehydrates-correction-and-scores` unchanged
   for a pristine form. Its new same-authority dirty counterpart covers scores,
   correction and notes through a new review identity. Initial clean load and D1
   success without a newer edit retain legitimate reset/rehydration; actual
   candidate authority changes never submit/carry old edits under the new one.
4. Hold a current same-project B inventory snapshot started before an admitted
   mutation completes. Change only private synthetic server state on completion,
   release/install B's older rows and preserve current selection, then require
   exactly one fresh current-context read showing the completed result. No
   catch-up starts while B is held, no generation is cancelled and multiple
   completions coalesce. A current post-completion read can satisfy freshness.
   Keep the existing same-project B-selection regression and count unchanged.
5. Change project/filter/work or unmount while catch-up is queued and in flight.
   No captured old-context reload or stale UI write wins; valid current loads
   survive. No deferred translation read/setter runs after unmount. Preserve
   original server follow-ups, including approval -> reconcile, and current
   bounded failure behavior with no automatic retry loop.
6. The visibly synthetic browser phase runs every named draft/reload and catch-up
   schedule, captures current segment/D2 before and after the real effects,
   updated canonical status and exact request/payload counts. Missing runner,
   reload, form-sync, ordering or cleanup checkpoints fail rather than skip.
   It supplies presentation evidence and zero live mutation/provider/publication
   requests; it does not supply genuine backend or release success.

## Verification

Causal RED uses the unchanged source first. Resource detail is derived locally
by `review_controller.cljs:121-137` from the refreshed resource inventory via
`split-review/detail`; it has no separate HTTP detail endpoint to hold. Hold the
actual resource inventory response, change immutable review fields, then await
the real controller detail reset and form-sync effects. Only legacy has the
captured `get-document` HTTP response. A stubbed-out form-sync or invented detail
request would miss the cause and cannot qualify.

For each of the six runners, with both Dispatch and Retry entries, use AC1/2
for success, rejection/finally, same-segment and changed-segment edits. The three
submit payloads stay D1; other original operation inputs stay unchanged. Only the
current operation owns saving, and the current work/segment and D2 survive all
actual indirect resets. Unit spies bind callbacks actually invoked, not an unused
token. Preserve pristine form-sync and current-selection tests. Document review
must cover direct legacy detail before and after its inventory/detail effects;
whole approval must complete the original approve -> reconcile before refresh.
A failed required follow-up remains a bounded visible failure, never retry credit.
New async tests use `^:async`/`await`; full relevant frontend suites/lint must
report zero failures, errors and warnings, not merely a compiler exit0.

For refresh RED, B's pre-mutation response must really install before catch-up;
a fixture already showing the completed mutation cannot manufacture freshness.
Cover multiple completions, an already-newer read, changed context/unmount and
bounded request failure. The guard must fail causally when scheduling or current
context admission is removed.

The browser reuses the core's private authenticated session, run-owned synthetic
identities, exact method/path/query/payload allowlists and unexpected-write abort
backstop. It drives normal segment/form/submit controls and holds original D1
responses, never forwarding a controlled mutation or content refresh live:

- Legacy: hold D1 and its captured detail as applicable; run same-S1 D2 and S2/D2
  success/detail-last and rejection/finally checkpoints.
- Resource split: hold the original split submit, edit D2, then release success
  and an inventory with a new same-candidate review identity. Let the actual
  controller derive detail; capture D2 after reset/form-sync. Repeat S2 and error.
- Resource bulk: keep the exact original set/payload while editing S1 or S2 D2;
  release new canonical split data after completion to exercise that same actual
  reload/form-sync path. Never modify the body to contain the later draft.
- Document review: use legacy Approve All with the ordinary editable segment
  form. Hold the original scoped document review, type D2, then release its
  inventory and actual legacy detail in both orders. Preserve one review POST,
  original document/locale/project/garden/status and the real detail follow-up.
- Whole-output approval: use the normal enabled action on an already-approved
  resource split set. Hold the original approval, type D2, release its original
  reconcile and refreshed same-authority inventory; derive resource detail
  locally. Preserve exactly one approval -> one reconcile, original revision/
  publication inputs and ordering. Also cover rejection and reconcile failure.
- Dispatch and Retry: use the respective server-admitted missing/failed work row
  plus the exact compatible persisted legacy split set. Verify the normal button
  and editable legacy form, hold the original publication-only dispatch POST,
  type D2, then reload same-authority legacy detail without manufacturing resource
  splits. Both labels share one runner/API and each scenario sends one POST with
  no retry flag. Also cover S2 and rejection/finally. Candidate-less/read-only
  negative cases prove no editor; an actual new candidate is an authority change
  and retains the established safe reset instead of importing the old draft.
- Catch-up: hold B's old snapshot, complete the synthetic mutation, change only
  private fixture state, release/install the old response, then record exactly
  one fresh current-context read and current selection/result. Run queued-context
  invalidation/unmount with core's normal-router checkpoints as well.

Keep real auth/resource locale verification separate and disclosed. Restore
original fetch, owned routes/promises and private session/download artifacts in
EXIT/INT/TERM cleanup; any setup/checkpoint/counter/screenshot/invariant/cleanup
failure exits nonzero. Never clear live/shared data. Preserve original locale
FAIL, seven console errors/ancillary502 and separate source-family Sandbox SDK
failure as unwaived limitations. This planning revision runs none of the product
suite, browser, build or runtime operations.

## Risks

Globally freezing form-sync hides legitimate external reviews; guarding only a
direct callback misses the controller's indirect reset. Draft and saving owners
must remain distinct. A deferred refresh can conserve a load yet silently lose
freshness; both the installation and later current read are required. Synthetic
fixtures must change real immutable review identity without claiming backend
or provider proof.

## Estimate and ownership

Five points for one same-view reload/form preservation slice: all six existing
reload-producing runners (Dispatch/Retry share one), bounded coalesced refresh
and their own unit/browser evidence,
using core's existing context fence and private adapter. No persistence, provider
or server work is added. Explicit discovery is +5 beyond prior3+5=8: core5 +
this5 + locale3 = 13. Accepted8 parent stays unchanged/open. Further independent
risk must be recorded/replanned rather than silently widening this story.

---
Accepted root-reviewed five-point P0 sibling for Codex5532975630. Canonical UUIDv4 generated once, substitution manifest983930b5d45ba8fe83eee775afc3d3729d70ef0f5fbd70a714a70d52715f3299 preserves all existing IDs. Own legacy/resource-split/resource-bulk dirty draft preservation through actual controller detail reset/form-sync, pristine external-review preservation, and required coalesced current-context post-snapshot freshness. Own causal unit and visibly synthetic normal-UI browser proof, no fabricated resource detail HTTP endpoint. Depends existing core5; locale3 depends both. Explicit +5 discovery beyond prior8 gives13 repair points; accepted8 parent stays unchanged/open. Exact reviewed body11969B SHA3a6390af117eaa6c3b5224b8d1416e99725893fbf6ff44eae4e7411947117140. Local planning only; no implementation/readiness/push/review/runtime/model/content/publication effects.

Accepted root-reviewed coverage correction for Codex5534710816/root4284210917 at ed39af2915bf6e139a3497f3e0e1be6747cf3e30. Existing draft5 owns all six reload-producing runners and seven normal UI entries: legacy segment, resource split, resource bulk, document review, whole-output approval, Dispatch and Retry (one shared dispatch runner). Preserve direct legacy document detail/reload ordering, original approval -> reconcile, actual server-admitted Dispatch/Retry plus exact compatible editable legacy segments, pristine rehydration and true candidate-authority resets. Reuse the same dirty-draft/current-context/freshness mechanism and existing product/test/private adapter scope, original payloads/follow-ups/counts/auth and zero live effects. Exact admitted one-card patch10082B SHA2a5290235cc8be9f9a30e9e7657f16ccd9297bb16376147ba20de9a80eb1e241. Core5 -> draft5 -> locale3 remains13; accepted8 parent, all points/status/dependencies and prior history stay unchanged. Planning correction only; no application tests/browser/build/model/runtime/deployment success is claimed.

---