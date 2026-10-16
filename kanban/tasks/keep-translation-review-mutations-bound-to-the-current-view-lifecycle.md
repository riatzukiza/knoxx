---
category: "tasks"
labels: "tasks, 5sp, has-parent, regression, translations, frontend, cms"
dependency: ["knoxx-translation-review-legacy-fixture-auth-preflight"]
parent: "knoxx-translation-review-chat-panel"
type: "task"
write-id: "1792136805063-0.bmvuwxvo0g8q7leso5a"
points: "5"
title: "Keep translation review mutations bound to the current view"
priority: "P0"
status: "in_progress"
uuid: "knoxx-translation-review-current-view-mutation-lifecycle"
created_at: "2026-10-15T11:12:28.655Z"
---

# Keep translation review mutations bound to the current view

## Context

The existing translation page runs six mutations through UI closures that can
outlive the project, language filter or selected work where they began. Native
PR16 roots 4281890616, 4282077748, 4282077754 and 4282077767 identify stale
reload/detail callbacks, pending ownership leakage and missing browser race
proof. The original locale projection story must not absorb this larger repair
or leave these safety invariants as an unowned footnote. Native body review
5532308090 additionally requires synchronous installed feedback/filter/work
boundary exclusion and an explicit read-only export feedback policy.

## Current source location and historical custody

The current Markdown path is `kanban/tasks/keep-translation-review-mutations-bound-to-the-current-view-lifecycle.md`.
This is a current filename spelling correction with the same explicit UUID,
metadata and history, not a claim that the creation event used this spelling.
The native task-created event at2026-10-15T11:12:28.656Z records the original
`...-view-ifecycle.md` path. Keep that event and all historical path/receipt/comment
bytes unchanged; a later rename cannot repair an old commit-pinned review URL.
Canonical Rheos must resolve exactly one current UUID to the new path, with no
collision or duplicate card. Do not edit a source-path frontmatter/provenance
field or rewrite ledger history to make it look current.

## Outcome

This first five-point slice makes project, target-filter, work-identity and
unmount boundaries safe while operations remain pending. Authorized server work
keeps its original payload and required follow-up sequence; only the current
view may receive UI completion and a current operation owns saving state.
Read-only SFT retains its original request/download and fences shared feedback.

The separate five-point successor
`ae9a3cb7-a630-4d8d-8d60-c69b825d008d` owns same-view segment/draft
preservation through direct completion, detail reload and form rehydration, plus
coalesced freshness after a pre-mutation pending snapshot. Those obligations are
transferred intact, not dropped or claimed complete by this first slice. The
locale story depends on that successor and transitively on this one.

## Scope

- At effective project change, synchronously exclude installed old rows,
  selected work/detail/segment/draft, notice/error and controls; invalidate old
  document, manifest and detail generations synchronously in the boundary
  handler before replacement effects run. An old response resolving after the
  boundary but before B's first request must already be stale. Reset the filter to All and
  hide the old manifest. The dependent locale slice additionally resets its
  introduced resource-locale projection at this same boundary.
- At a same-project target-filter change, preserve the intended new filter,
  project-wide manifest and unfiltered resource-locale choices, but synchronously
  exclude the old filtered rows/selection/detail/segment/draft, feedback and
  pending ownership. Invalidate old filtered-document/detail generations in
  that same handler. Preserve the still-correct project-bound manifest request
  and its generation: the manifest effect depends only on project and has no
  filter replacement. Do not add a manifest refetch or reset the new filter.
- At a work-identity change, preserve the current project/filter/list and locale
  choices, but synchronously exclude old detail/segment/draft, notice/error and
  pending ownership before the first B context can combine B scope with A detail
  or segment. Keep B's safe selection and legitimate row-scoped controls; do not
  strand B in saving or weaken the current same-project refresh/selection test.
  The same exclusion applies when an admitted inventory refresh omits the current
  work: nil selection must synchronously hide the vanished work's detail, segment,
  annotation/form, feedback and mutation controls before passive effects. Preserve
  the refreshed list and current project/filter; do not select an ambient fallback.
- Define the base current-view context by effective project generation, target
  filter value plus its monotonically advancing boundary generation, selected
  work identity and mounted lifetime. Advance the filter generation on every
  effective filter transition, including a return to a prior value; an ordinary
  refresh at the same filter is not a transition. Bind it to actual UI and
  reload/detail closures, not only to a token beside them. Use `logic/same-work?`:
  a refreshed map with the same logical work identity is not a work-navigation
  boundary. The successor adds the submitted segment/draft-version guard and
  dirty-form synchronization policy within that same view.
- Guard direct detail, form reset, notice/error and saving completion across
  all six existing runners. Preserve their already-issued server payloads and
  mandatory follow-ups, including approval → reconcile; do not retarget,
  cancel or reissue those operations because the view changed.
- Changed modern-environment runner functions use `^:async` plus `await` with
  explicit `try`/`catch`/`finally`, not retained Promise-chain implementations.
  Convert the currently plain `run-segment-submit!`, `run-document-review!`,
  `run-publication-approval!`, `run-translation-dispatch!` and `run-export!` while
  preserving the already-async resource split/bulk runners. The six mutations
  remain six and export remains separate/read-only. Keep original payloads,
  response/error behavior, operation-owned finally release and required follow-up
  order, especially approval -> reconcile; this is source form for the same
  lifecycle changes, not an extra request/cancellation/async framework.
- Treat `run-export!` as one separate read-only UI completion, not a seventh
  mutation runner. Preserve its original SFT request and captured download
  bytes/filename with no retarget or reissue. Only its shared notice/error
  completion requires the captured effective-project generation, target-filter value/generation
  and mounted lifetime still to be current; a work-row change alone does not alter those
  export inputs. After router unmount, an already-issued successful export still
  completes exactly the captured download bytes/filename, while success/error
  feedback makes no abandoned-view setter call. Rejection downloads nothing.
  It does not acquire mutation saving ownership. No SFT/export content semantics,
  endpoint, provider or pipeline configuration change.
- Replace bare saving ownership with a unique operation identity scoped to the
  current context. A context switch detaches old visible pending state before
  new controls are shown. A new B operation owns B's saving state; old A success,
  error or finally cannot clear it or install A status. Current B completion
  releases it normally. Unmount cleanup invalidates mounted UI completion and
  the old document/manifest/detail generations; queued refresh in the successor
  must use that same lifetime fence.
- A stale-project completion starts no reload. For same-project selection or
  filter changes, suppress stale direct effects and route permitted refresh
  through the actual current context, preserving the existing same-project
  B-selection refresh regression and its count. Never invoke captured old
  project/filter coordinates or invalidate a valid pending current request;
  its original response must still install. Required post-snapshot freshness,
  coalescing and draft preservation belong to the named successor. There is no
  permission to permanently drop that freshness obligation; the full successor
  must qualify before the locale repair or full lifecycle can be adopted.
- Reuse the existing page/controller boundaries and `logic/same-work?`
  identity; add no generic async framework, endpoint, backend policy or second
  review law. Product/test scope is `review_controller.cljs`, `view.cljs`,
  `review_controller_test.cljs` and `page_interaction_test.cljs`. The related
  locale story owns `logic.cljs`, `logic_test.cljs` and locale projection wiring.
- Consume mandatory prerequisite `knoxx-translation-review-legacy-fixture-auth-preflight` (5 points)
  for the complete run-owned legacy fixture, real legacy POST/detail-GET auth-preflight,
  named backend extern/tests, exact cleanup and registered shell/Python proof.
  This card retains its end-to-end auth acceptance but does not implement or
  estimate that backend/harness mechanism again. The prerequisite must qualify
  before core completion or any controlled browser phase.
- Core-owned human verification remains the shared fixture helper/tour/guide's
  private deferred browser adapter and its lifecycle checkpoints, plus the real
  SFT boundary check. Preserve default consumers/cardinalities and keep the two
  real prerequisite POSTs separate from zero-forwarded synthetic race counters.

## Non-goals

No new server identity, permission, translation/review/publication semantics,
provider invocation or publication. This planning PR runs no application tests,
browser, build or runtime mutation. Future controlled race checks use no live
mutation endpoints and give no durable receipt, provider-quality or release
credit. Existing real-inventory locale checks retain their actual auth/resource
reads; the controlled race phase is visibly separate.

## Acceptance criteria

1. The first render identifying B exposes no A rows, detail, selected work,
   old segment/draft, notice/error or A-bound controls even while B's inventory/
   manifest is pending, hung or failed. Seed already-settled A success and error
   feedback before switching, as well as pending A operations. Reset visible
   pending ownership at this boundary; no later passive effect may supply the
   first-render exclusion. Apply the distinct filter/work boundary exclusions
   above before their passive effects too: fr cannot expose old es rows/actions,
   and B cannot combine its scope with A detail/segment/form. Retain current
   same-project unfiltered choices/list where their boundary requires it.
2. Start exactly one A mutation, switch project and hold B's initial load. Let
   A finish first. B's original pending load remains authoritative and can
   install; A starts no captured reload and writes no current detail, message,
   draft or saving state. Stale success, rejection and finally paths are covered.
3. Within one project, start a mutation on es then choose fr while its load is
   held, and separately start legacy review on row A then choose row B. Resolve
   old completion/detail last. Fr remains the active filter, B remains selected,
   and neither old filter inventory nor A detail appears. The real current
   reload/detail closure is checked. The existing same-project refresh/selection
   regression still passes unchanged, including its refresh count. Separately
   select work A with a visible segment/draft, then release an inventory response
   omitting A without a row click. Inspect the first committed post-refresh render
   before detail/form passive effects: no A segment, annotation/form, labels,
   feedback or stale mutation controls are exposed beside nil selection. Attempt
   a normal submit at that boundary and require zero stale mutation requests;
   preserve the new list and do not invent replacement selection. Exercise this
   disappearance schedule in unit/controller and synthetic browser checkpoints.
4. After leaving pending A, B's legitimate controls are actionable. Start B's
   own operation, resolve/reject A while B is pending, and require B to remain
   saving/disabled. Complete B and require only B's saving to release. Cover both
   stale success and stale error/finally; pending state is not just reset once.
5. All six runners share this UI fence. Tests assert original server payloads
   and required follow-up calls are unchanged, particularly successful approval
   still reconciling its original publication after navigation. UI staleness is
   not server cancellation. No extra dispatched/review/approval call is issued.
   Changed runners/export use native `^:async`/`await` and explicit error/finally
   structure; tests retain exact request counts/payloads, captured downloads,
   stale/current setter behavior and required approval -> reconcile ordering.
6. The human artifact visibly exercises installed-A → B-pending exclusion,
   pending-A mutation → B, same-project locale and selected-row transitions,
   and old-finally versus new-B-saving ownership through normal controls. It
   fails on each violated invariant and captures screenshots/counters. No race
   is silently skipped because the locale branch normally exits before writes.
   Include already-installed feedback and the first filter/work renders, not
   only stale callbacks arriving after a switch.
7. Deferred SFT success and rejection after project or locale change cannot
   install stale shared notice/error. Add the separate pending Export SFT ->
   router unmount -> success/rejection schedule in callback tests and the
   controlled browser phase. Spy on actual notice/error setters: neither is
   called after unmount. Success still completes exactly one original-coordinate
   download with captured bytes/filename; rejection produces no download. Assert
   exactly the original request, no retarget/reissue, translation reload,
   mutation/provider call or acquired/cleared mutation saving owner. Exercise
   these policies through normal Export SFT; the held run-owned SFT response is
   never forwarded to a real content read. The six mutation-runner matrix in AC8
   does not substitute for these separate read-only completion cases. Also hold
   an es export, choose fr then es in the same project, and only then release
   original success or rejection. The changed filter generation must deny both
   stale feedback setters even though the final filter value again equals es.
   Success still performs exactly one original captured es download; rejection
   downloads nothing. Cover this ABA schedule in callback and synthetic browser
   checks, with no extra export, translation reload or mutation/saving effect.
8. For each of the six runners, start exactly one operation and unmount the
   translation page before success, rejection or finally. Actual abandoned-view
   setter and reload callbacks receive no calls after cleanup, and no new
   translation inventory/detail request starts. Already-issued server work and
   required original follow-ups, including approval -> reconcile, still finish
   unchanged. A separate unmount -> remount case starts B's legitimate pending
   operation, then finishes old A; B stays pending until its own completion.
   The controlled browser uses normal router navigation with the held synthetic
   operation, records the abandoned view's disappearance before completion and
   proves no translation reload or stale feedback reaches the new surface.
   Direct no-setter-call proof belongs to the causal unit spies, not an inference
   from an absent DOM or missing React warning.
9. Before installing any synthetic interceptor, make an actual anonymous request
   to the exact source-built SFT GET URL with the real effective project/filter
   and include_corrected query. Use no cookies, bearer/API tokens or identity
   headers. Under the authenticated/policy-enabled, export-route-ready verifier
   preconditions require HTTP401/no_session, not merely any non-200 response.
   An unexpected content response fails without logging its body; an unavailable
   route/legacy503 cannot pass authentication and blocks this in-scope check.
   Also send exactly one independent credential-free
   `POST /api/publications/translations/dispatch` for the existing run-owned
   resource publication before its cleanup and any synthetic adapter. Use the
   exact frontend-built `{:publication <owned-publication-id>}` wire payload,
   no revision/retry flag, empty cookie jar and no Authorization/API/identity
   headers; require401/no_session. Establish source/route/auth/store readiness,
   unchanged real owned writer state, including hidden dispatch/claim/batch/receipt
   state as applicable to
   the configured runner. Unavailable proof fails. Count this dispatch probe
   separately from SFT, split-review probes and fixture5's two legacy POSTs plus
   one detail GET. Dispatch/Retry UI races remain synthetic with original counts
   and zero live forwarding.

10. Consume the completed source-bound artifact from prerequisite
    `knoxx-translation-review-legacy-fixture-auth-preflight` before navigation or interception.
    Both exact legacy POST routes and the exact frontend-built legacy detail GET
    must prove401/no_session with unchanged real state/counter and checked owned
    cleanup. The fixture prerequisite proves authenticated detail readiness and
    the credential-free detail request while its owned segment still exists,
    before cleanup/navigation/interception; manifest/list/SFT checks cannot
    substitute. A mocked, unavailable, unready or
    mismatched phase cannot satisfy this acceptance. Core integration preserves
    the existing resource bulk/SFT gates, default18 and locale20/18/1/1, and
    starts no synthetic race until that prerequisite's fixture is gone.

### Separate fixture/auth ownership

The native4286589102 discovery transfers the entire backend seed/read/cleanup,
named extern/test, real legacy POST/detail-GET probes and registered shell/Python/live
qualification intact to the new prerequisite5. This card's original frontend
fences, six runners, read-only SFT policy and unit/browser obligations stay5.
No accepted implementation, auth result or parent completion is implied.

## Verification

Use controlled deferred API fixtures for causal RED. Add explicit cases for
project, filter and work transitions, current-load preservation, already-settled
feedback, old/new operation ownership and read-only export success/rejection.
For export, include actual callback-spy RED for pending -> unmount -> success
and rejection, independently of the six mutation cases; preserve the successful
captured download while suppressing only abandoned-view notice/error callbacks.
First-render RED must be observed before loading/detail passive effects, not
only after held replacement responses. Add a distinct pre-passive response case:
change A→B, hold replacement effects, then resolve pending A inventory/manifest
before any B request starts. Neither may reinstall A; do not substitute the
existing A-last-after-B-starts case. Filter changes invalidate only input-dependent
loads, retaining a valid same-project manifest without refetch. Add a causal
pending-operation -> unmount -> success/error/finally matrix and an unmount ->
remount/new-B-pending case. Spy on the actual callbacks the runners would invoke;
the missing mounted-lifetime guard must produce RED. For the six runners, require a named success/error/finally
coverage table rather than claiming one dispatch test proves every implementation.
Preserve existing tests; new/changed async functions as well as tests use native
`^:async` / `await`. Causal runner regressions must observe the same original
requests/follow-ups and error/finally ownership after the source-form conversion;
a Promise-chain rewrite alone supplies no lifecycle correctness evidence.
The product checks remain the full relevant frontend test and lint commands with
actual zero failures, errors and warnings. No unrelated red is waived.

The human artifact first establishes real authenticated context/effective
project and export-route readiness, then makes the explicit anonymous SFT GET
above using an independent credential-free client before any interceptor. Source
`backend/src/cljs/knoxx/backend/infra/routes/translation.cljs:102–110,251–252` checks compatibility
readiness before authenticated context; `backend/src/cljs/knoxx/backend/infra/auth/authz.cljs:54–84` and
`backend/src/cljs/knoxx/backend/infra/auth/session.cljs:331–335` define the policy-enabled no-cookie401/no_session
path. Do not mistake an early503/unavailable proxy for proof of authentication.
The exact URL/query comes from frontend `api.cljs:96–101`, not a guessed path.
Preserve the existing anonymous inventory check and all real auth/inventory reads.

Add that dispatch assertion inside this artifact's existing real pre-interception
phase. Resolve the publication and effective tenant/project from its authenticated
run-owned resource inventory, not placeholders, a legacy segment, empty body or
whole-corpus selection (`api.cljs:62–71`). Verify the exact serving backend/route,
enabled auth/policy and configured stores; do not dispatch authenticated work to
prove readiness. The separate `verify-translation-dispatch.sh:278–288` has older
anonymous checks but is not invoked here, and its later real dispatch/provider
steps must not be run merely to inherit them. Use an independent no-cookie,
no-auth/API/identity-header client, forbid redirects and retain no sensitive
request headers/content. The existing named verification read boundary supplies
source-backed before/after owned dispatch/claim/batch/receipt snapshots for the
configured verification runner. Registered route/harness controls separately spy
on the actual queue/agent/event-turn writer dependencies and require zero calls
when authentication refuses; do not infer that call-level result from live HTTP
or unchanged snapshots. API/UI equality cannot stand in for the live hidden-state
comparison; an unavailable observation fails the precondition. No new fixture family,
public endpoint, provider/model call or writer is introduced. The legacy
prerequisite completes its own checked cleanup before controlled navigation;
this separate dispatch probe runs while the resource fixture is still present
and before that resource fixture's checked cleanup.
Add registered harness adverse controls: all other preflights401 but dispatch
returns200/400/404/503 or lacks no_session must fail;401/no_session with changed
hidden state or a writer invocation must also fail. Preserve the original probe
failure and exact retained ownership if cleanup fails. Report safe status/code,
counts/equality and cleanup facts only. Keep the existing label-counter/legacy
counts under fixture5 and keep these real auth results separate from the later
synthetic adapter and frontend callback tests.

The mandatory prerequisite `knoxx-translation-review-legacy-fixture-auth-preflight` owns the real
legacy fixture/auth phase formerly described here, including the exact legacy
detail GET's authenticated readiness and independent401/no_session proof while
that run-owned fixture exists. Consume its independently
source-bound preflight/cleanup result before installing this card's controlled
browser adapter; do not duplicate the backend/harness implementation or claim
its two real POSTs are synthetic. This story separately proves real SFT and
dispatch authentication above and the zero-forwarded controlled races below. All source-built payload,
readiness,401/no_session, unchanged-state/counter, default-count and exact
cleanup obligations remain in the named prerequisite's acceptance.

The human controlled race phase then uses its private authenticated browser session. Install the run-owned browser adapter before navigating to
the controlled translation view. The shared HTTP helper resolves `js/fetch`
dynamically on every call (`frontend/src/cljs/knoxx/frontend/lib/api.cljs:38–43`), so a session-local
deferred fetch fixture is supported without changing application source.
The installed driver's static route/body facility (`browser.js:386–405`) has no
held-response control; do not pretend an immediate route override reproduces a
pending request. Use the existing driver's `eval` surface for a narrowly scoped
private deferred adapter, not a live backend helper or new service endpoint.

The adapter must:

- Carry a fresh run ID, same-origin exact method/path/query allowlist, run-owned
  A/B document/publication/segment identities, and immutable controlled response
  fixtures matching the existing UI shapes. Make the controlled phase obvious
  in script output, documentation and screenshot names. Resource inventory and
  legacy/detail/manifest responses in this phase are explicitly synthetic so
  effective server project adoption cannot silently force the view back to A.
- Admit only the run-owned expected mutation payloads; hold and resolve/reject
  their synthetic promises under named checkpoints. Intercept every six-runner
  mutation endpoint, including approval/reconcile. Unexpected mutation methods,
  endpoints, IDs, payloads or follow-ups fail and never forward. No credential,
  request headers, content body or raw error is logged.
- Backstop with browser-level abort routes on those mutation URLs so a lost
  window adapter cannot forward a write. Before clicking a mutation, verify
  both adapter and abort guard are active. Assert zero live server mutation /
  provider / publication requests and expected synthetic operation counts.
- For A → B, hold B inventory/detail and make controlled A complete first;
  capture the DOM synchronously inside interception of the first expected B
  resource-inventory GET, before returning its held promise. The current
  controller queues `set-loading! true` immediately before invoking that GET;
  this checkpoint runs in that same JavaScript call before the passive effect's
  queued loading state can render. Bind it to the already changed Project input
  and the harness's expected B request, and assert no old row/action/detail then.
  Do not substitute a later polling assertion after loading hides the rows. If
  this source-bound checkpoint cannot be established, fail the controlled phase
  and retain the unit RED rather than granting browser invariant credit.
  For the first filter render, use its resource-GET checkpoint; for the first
  selected-B legacy detail render, inspect synchronously inside that expected
  detail GET before the queued detail-loading render. Fixtures must reach those
  actual requests, and an absent/unbound checkpoint fails rather than granting
  credit. Seed prior installed notice/error too. For late language/row races,
  hold the current response, release stale completion last and inspect the
  active selection and detail. For saving, expose B's
  legitimate controlled action, hold B, finish A, then finish B.
- In that same visibly synthetic phase, intercept the exact source-backed SFT
  GET (project/optional target_lang/include_corrected from `api.cljs:96–101`),
  hold a run-owned text response, use normal Export SFT and then change project
  or filter. Add a separate normal Export SFT -> CMS router unmount -> completion
  pair, with the translation view absent before success/rejection is released.
  Release success and rejection in separate cases; success completes the one
  original-coordinate download bytes/filename despite unmount, rejection creates
  none, and the new surface receives no stale feedback. Record exactly one SFT
  request and no post-unmount translation reload; direct no-setter-call proof
  remains in the unit spies. No real export/content GET is forwarded, no
  mutation/provider request is introduced, and owned download artifacts join the
  existing cleanup checks.
- Exercise unmount through the existing CMS router link (`app.cljs:105`,
  `app_routes.cljs:17`, `/cms`), then return with the normal Translations link
  for the remount case. This is client-side router navigation, retaining the
  private window's held fixture promises. Capture the new route and absence of
  the translation view before releasing A success/rejection/finally. Translation
  inventory/detail counters must not grow while it is abandoned, the new
  surface must receive no A feedback, and a fresh B pending owner after remount
  must survive A. Target-page reads are explicitly allowed/read-only; the
  session-wide non-read abort backstop rejects any unexpected write, including
  target-page effects, rather than forwarding it or granting a pass.
- Add the pre-passive stale-response order to the controlled fixture: record the
  boundary and release pending A inventory/manifest before the first replacement
  B request. The existing effective-server-project adoption path can provide a
  normal-UI-triggered async boundary without adding a product test endpoint.
  Record the actual order and assert A cannot install; if the driver/runtime
  cannot establish this window, fail/report it rather than silently using the
  later A-last steady-state case or granting browser credit.
- The successor extends this same fail-closed adapter with its own legacy,
  resource-split and resource-bulk draft/reload schedules and pending-snapshot
  catch-up schedule. It owns their causal unit and browser results; the base
  adapter or a project-switch demonstration cannot claim those cases passed.
- Keep real authentication and unauthenticated boundary checks separate from
  controlled UI state. Print that synthetic races prove presentation lifecycle
  only; no live mutation semantics, backend health, provider quality or durable
  receipt/publication is being proved. The locale mode retains actual native
  inventory/auth and its existing explicit empty-manifest-only override.
- Restore the original fetch, reject/settle owned pending fixture promises,
  remove only owned abort/manifest routes, and close the private session in
  EXIT/INT/TERM cleanup. Any setup, checkpoint, counter, screenshot or cleanup
  failure exits nonzero. Never clear live/shared legacy records.

Seed and teardown remain run-owned, and the default Spanish 18-row/full tour and
locale opt-in 20/18/1/1 checks remain unchanged. The controlled race fixtures are
separate browser memory, not extra live candidates. Preserve existing failed
tour, console and 502 evidence as limitations. Future private rebuild/browser
admission must bind source/image/Unix modes/nonroot security; this planning split
itself runs none of it.

## Risks

A base context token cannot preserve a newer draft or guarantee post-snapshot
freshness by itself; the named successor remains mandatory. Current-context
refresh can accidentally cancel a valid request; conserve its actual install.
Project-only tokens miss locale and work changes. An unconditional old finally
can unlock a newer operation, while dropping it without boundary reset can lock
the view forever. A browser fixture that forwards an unknown POST or silently
stubs native inventory in the locale phase would misrepresent evidence; its
fail-closed separation and counters are acceptance requirements.

## Estimate and split custody

Keep this story at five points for the bounded current-view/mounted-lifetime
fence, operation ownership, six existing runners, read-only export feedback and
its own causal/browser qualification. Move the old AC8 draft cases and AC10
freshness scheduler, with their existing proof obligations, to the separately
estimated five-point successor; add resource split/bulk form-sync coverage there.
This is not a verification-only follow-up or a way to call incomplete lifecycle
work finished. Each story owns executable unit and controlled-browser proof for
its behavior. That prior split allocated core5 + draft5 + locale3 =13,
including explicit +5 draft discovery beyond the earlier3+5=8. The new
`knoxx-translation-review-legacy-fixture-auth-preflight` independently owns fixture/auth5:
current repair total18, explicit additional+5 discovery. Core depends on that
prerequisite; draft depends on core and locale depends on core+draft. The broader
accepted8 parent remains unchanged/open; no estimate or completion is consumed
from it. All historical native comments retain their original dated estimates.

---
Accepted five-point P0 planning sibling from the root-reviewed PR16 split proposal SHA256 07d31d1c1a6a948effbb876ba68f4c050a85ae92369c16c4c653200e74886a05, body SHA256 339b11a8088af8da26d7f7169792fab948a695ccb18012c4e6cb051c3ad3c864. Native incoming→accepted→breakdown completed without bypass; remains breakdown. Existing knoxx-translation-resource-locale-filter-options stays three points and depends on this story. Explicit new repair total 3+5=8 (+5 discovery); broader accepted eight-point parent remains unchanged and incomplete. Original current-c7 Codex P1 roots 4282077748/4282077754/4282077767 and prior 4281890616 retain their native history. Whole-view actual callback closure, operation-owned saving, original payload/follow-ups, pending-load conservation and controlled browser races are required implementation acceptance. No live mutation/provider/publication, app/runtime, readiness or broader parent completion is claimed by this planning admission.

Planning refinement for native body-only Codex review5532308090 on b896ced5ad2785e776b39bcaf4a757f3ca7081ae: accept P2 export feedback, P2 installed feedback and P1 first filter/work-render state. Root read exact6,018B proposal SHA2567c76aa884431f3f673bffea62fbdaf4e0d23e947b76bfae7eb030684146ddef8 and explicitly admitted bounded read-only SFT shared feedback fencing, preserving original request/download bytes+filename/no retarget/reissue. Expand synchronous project/filter/work boundary exclusion with distinct retained state and installed notice/error; require causal pre-passive unit and visibly synthetic browser checkpoints plus controlled export success/rejection/download evidence. Six mutation runners stay six, source/test/verification files and lifecycle5/locale3 dependency unchanged; parentaccepted8 remains open/byte-unchanged. Canonical status omission of these actual body findings is not dismissal or clearance. Preserve all fourteen native Fixed histories, append-only provenance, pending MiMo and actual cooldown. Planning only: no application/test/browser/build/runtime/model/content/provider/publication/production effect. Separate source/event and canonical RR/portableSM commits.

Native PR16 current8b187 reviews5532537847 and5532590232: require pending segment/draft completion guards, credential-free real SFT boundary before synthetic interception, synchronous old list/manifest/detail invalidation at the applicable project/filter boundary, and required coalesced catch-up after a pre-mutation same-project pending snapshot settles. Preserve valid project-only manifest on filter changes, all original server payloads/followups, pending request installation, and old-project no-reload. Exact authored lifecycle body20598B sha256:cde449c7e1e546a14bbff42cc22044088bd64f21d1097cc930d7cdd9f06fef0b; full six-finding delta17244B sha256:1a51a6fd7c1784998020f7dcd304c7f02fd8c9b1b76d2ad369a4be39d8552da7. Same breakdown/P0/5points/parent and six mutation runners; no metadata/status expansion, app implementation/browser/runtime/provider/content/publication effects. Prior14 native settlements and body review5532308090 history preserved. Current8b187 MiMo actual approved review5532648270 remains historical on correction push; no duplicated review request.

Root-reviewed Codex5532975630 split keeps core5/P0/breakdown for synchronous project/filter/work/mounted boundaries, all six original runners/payloads/followups, operation saving ownership and separate read-only SFT feedback/auth checks. AC8 now explicitly requires pending->unmount->success/error/finally unit callback spies and normal CMS/router/remount browser checkpoints. Old draft AC8 and catch-up AC10 transfer intact to actual five-point sibling ae9a3cb7-a630-4d8d-8d60-c69b825d008d, extended to resource split/bulk and real controller/form-sync resets. UUID-bound body21206B SHA92d3e22312ac0bfdb627cbd1fdde1471e63bc5df6d9cfb061f50cedd94368c02 differs from root-reviewed body only by frozen prospective-symbol substitution. Core5+draft5+locale3=13, explicit+5 discovery; accepted8 parent unchanged/open. Each story owns unit/browser proof; no obligation waived, no product/runtime/publication effect.

Root-admitted native body-P1 Codex5535316442 correction at914ed5881c563b414addf1926d79bc5dcd50d3f2. Existing core5 planning requires both exact source-built legacy anonymous POST routes (segment labels and document review) before synthetic interception, under auth-enabled/legacy-route-ready preconditions, independent empty-cookie/no-auth/API/identity-header transport, HTTP401/no_session and authenticated unchanged-state/no-effective-write evidence. Non200/400/404/503/unready cannot pass; never log unexpected content/body. Retain resource bulk anonymous assertion with accurate route naming, SFT401 preflight, normal payloads/follow-ups,6runner/7UIentry coverage, zero forwarded controlled writes and default18/locale20/18/1/1 counts. Add existing API verifier to the named future verification files alongside helper/tour/guide. Core5->draft5->locale3=13, accepted8 parent, all points/status/dependencies/other cards and history stay unchanged. Planning prose only: no probes, tests, auth-vulnerability/result, browser/model/runtime/deployment success claimed.

Native CMS16 Codex body-only P1 review5535724920 on18125604b5cc499e6616ec65cbe768ae5521518c: root reviewed exact two-card patch e40ce45100723de196647e1b629455dd8f009fe9908bdaa90825ce22853010d2. Existing core5 AC10 now explicitly owns one run-namespaced pending legacy es segment seed/live authenticated read/exact cleanup before browser navigation, named Knoxx extern helper/test and registered harness paths. Legacy documents derive from segments; no separate source event or OpenPlanner companion. Refuse collisions/ambiguous upsert or wrong live storage, retain exact partial ownership and cleanup failure, compare hidden label counter through owned helper plus authenticated review state. Real valid reject-payload anonymous POSTs require401/no_session before interception; no unexpected content logging. Preserve all six runners/seven entries, draft5, locale3,13points and accepted8 parent unchanged/open. Auth tests, fixture writes and implementation remain future qualification, not performed by this planning amendment. Local-only source/provenance scope; no public/readiness/review-rerun/model/account/runtime/deployment effect.

Accepted root-reviewed planning split for native Codex5537788801/root4286589102 at7722bb23d7c2baa7006f7d2627ee2b4ec7cf338f. New prerequisite knoxx-translation-review-legacy-fixture-auth-preflight owns5points for the complete run-owned legacy seed/read/cleanup named Mongo boundary, two real auth probes, registered shell/Python/backend controls and genuine checked live cleanup. Transfer those mandatory mechanisms intact; core retains5points for all frontend context/lifetime/saving/six-runner/SFT and unit/browser obligations, consuming the qualified prerequisite before controlled races. Native single-key core dependency added; current repair total18 (fixture5+core5+draft5+locale3), explicit new+5 discovery beyond13. Existing metadata/status/parent and historical comments remain; accepted8 parent unchanged/open. Correct the short fetch-helper source reference without diagnosing the HOST ripgrep failure. Exact newBODY13705B SHA1bc5d4858cca428ee1d0ae338685614c44b6c55f340c47d047e5d1a9bf4661c1 and3BODYpatch20309B SHA10a2d22792ac07199e497debea1c200a23314baae53c2767bea85d0d18059696. Local planning only: no implementation, auth/fixture probe, test/build/browser/model/runtime, readiness, review approval or release credit.

Root-reviewed local planning correction for native Codex5538651862 body-only Export SFT unmount finding atb19f9fc8f2c7bcb9d345bddb3a6883f9c5166fb1. Add explicit pending Export SFT -> normal router unmount -> success/rejection schedules to callback-spy RED and controlled browser AC7/verification. Preserve exactly one original captured request and successful download bytes/filename after unmount; rejection downloads nothing; abandoned notice/error setters and translation reload receive no calls; export acquires/clears no mutation saving owner. Browser route/download/counters and actual unit callback spies are distinct proof, with zero real forwarded synthetic SFT/content/mutation requests. Existing core5 already owns read-only export mounted lifetime; fixture5/draft5/locale3, dependencies/status/metadata/parent/history and18total remain unchanged. Exact reviewed two-BODY patch12325B SHA712e15a1ca8457faa0d59017839b3c0c00d4836b0a213909442de49430328a7f. Local planning only; no source implementation/test/browser/model/runtime/readiness/release credit.

Root-reviewed local planning correction for native Codex5539062909/current0429, roots4287621750 and4287621762: require ^:async/await/try-catch-finally for all changed segment/document/approval/dispatch/export runners, preserving existing async resource split/bulk, request counts/payloads, saving ownership and approval->reconcile order. Consume fixture5 exact legacy detailGET readiness/auth refusal before synthetic browser interception. Correct only the current Markdown filename from -ifecycle to -lifecycle; native creation already recorded -ifecycle, so preserve its exact source-path row, every historical comment/receipt/pinned URL and UUID without source-path frontmatter mutation. Canonical readback must identify one current UUID/path. Same core5, dependencies/history/18total unchanged; planning only/no app/test/public/runtime effects.

Root-reviewed local planning correction for Codex5539622567/currente9 root4288059104: independently require ONE real credential-free POST /api/publications/translations/dispatch using the frontend publication-only selector from authenticated run-owned resource inventory, source/route/auth/store readiness, no cookies/auth/API/identity headers or redirects,401/no_session and unchanged hidden owned writer state before interception/resource cleanup. Registered route/harness controls separately prove zero queue/agent/event-turn writer calls; live snapshots do not imply call-level proof. Add dispatch200/malformed/unavailable/missing-code and changed-state/writer adverse controls. Keep dispatch count separate from legacy2POST+detailGET/SFT/resource-review probes and preserve zero-forwarded synthetic Dispatch/Retry counts, original failure/checked cleanup. Do not run the standalone script real dispatch/provider steps. Samecore5/deps/status/history/18total; no application/test/auth/model/runtime/public effects.

Root-reviewed local planning correction for Codex5539948033/current1c6282bd roots4288291874 and4288291902: explicitly cover inventory omission of current work with synchronous first-render exclusion of vanished detail/segment/form/feedback/actions before passive cleanup and zero stale submit; preserve refreshed list/current context without ambient fallback. Version effective filter transitions (including es->fr->es) so an earlier export cannot regain shared feedback authority by value equality; retain one original captured download/no reissue and add causal callback/browser schedules. Same existing core5 and dependency chain18total; preserve metadata/history and qualified earlier dispositions. Exact three-BODY patch11222B SHA41aec60aa05bcf77506315baa6bfb262cf5ff2811be06f73efc730089b289980. No application/test/auth/browser/model/runtime/readiness/release or public effect.

Bounded current-view5 implementation admission on isolated e5d69882038d59e8d54b94581997aee41a5e55a5. Genuine registered frontend baseline:464 tests/2114 assertions,0 failures/errors/warnings; fresh frontend-own frozen-lock dependencies, no copied build. Root separately qualified real fixture/auth attempt3 at e5d:60PASS,1 explicit direct-seed/no-model boundary; no native prerequisite completion inferred. First two causal regressions cover installed-A first-B-frame exclusion and old-A completion conservation of B original load. Preserve all six original mutation payload/follow-up sequences, unique current-operation saving, mounted/project/filter/work fences, read-only export policy, and unchanged same-project2inventory/1a-esdispatch B-selection control. Remaining accepted causal cases and real full frontend test/lint/build are required before qualification; no browser/provider/publication/deployment or broader-parent completion claimed.

Finite core5 browser instrumentation continuation on isolated combined64c644c6e9f2c5fe780eca2b3b8a9af914266a0a. Preserve in_progress/P0/5, all dependencies/parent/labels and history. Scope is the reviewed eight-path browser/preflight/doc proposal173ccce54975e729524719cb8f35650570aaadb60fe666f8150b40e2cd8fc0c5: three named browser guard/deferred/scenario modules, registered Node controls, existing shared fixture helper and Python precondition controls, existing human browser tour and verification guide. No application behavior, package/dependency/workflow policy or draft5 source changes.

Implement an independent method-aware browser-context abort backstop plus exact run-owned deferred public fetch/DOM fixtures, six mutation runners/seven real UI entries, first-render checkpoints and recorded pre-passive ordering, normal CMS-router unmount/remount and original SFT download/follow-up semantics. Unknown method/origin/path/query/payload or absent adapter/checkpoint fails closed; never poll a loading state as first-render proof, forward a synthetic mutation, log credentials/content or claim an unattained ordering. Separate real source-built SFT and publication-only dispatch auth preflights with exact401/no_session, readiness and unchanged scoped writer-state evidence before interception; retain distinct fixture5 counts and exact cleanup.

This local phase permits only source authoring, genuine test-first causal controls and bounded Node/Python plus inert owned loopback tests. No browser/application runtime, real backend/DB/model/provider/public writes or deployment. Browser execution remains separately held for source/dist/runtime/cleanup qualification. Core may qualify its existing context/lifetime/conserved-B-load behavior before draft; D2 and trailing catch-up remain owned by successor ae9a3cb7-a630-4d8d-8d60-c69b825d008d and no predecessor completion or state transition is claimed. Scope marker CMS16-core-browser-eight-path64c-v1.

---