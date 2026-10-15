---
category: "tasks"
labels: "tasks, 5sp, has-parent, regression, translations, frontend, cms"
dependency: []
parent: "knoxx-translation-review-chat-panel"
type: "task"
write-id: "1792063865579-0.tt8ffu0sy8aeil11hty"
points: "5"
title: "Keep translation review mutations bound to the current view"
priority: "P0"
status: "breakdown"
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

## Outcome

A reviewer may change project, language filter or selected work while a prior
mutation is pending. Authorized server work keeps its original payload and
required follow-up sequence. The page presents only the current context, a
current operation owns its own saving state, and an older completion cannot
replace current detail, reload captured coordinates or strand/clear a new
operation's pending state. An already requested SFT export keeps its original
read/download coordinates and bytes; only its shared notice/error completion
is fenced by the captured project generation and target filter.

## Scope

- At effective project change, synchronously exclude installed old rows,
  selected work/detail/segment/draft, notice/error and controls; invalidate old
  detail loads before any passive loader effect. Reset the filter to All and
  hide the old manifest. The dependent locale slice additionally resets its
  introduced resource-locale projection at this same boundary.
- At a same-project target-filter change, preserve the intended new filter,
  project-wide manifest and unfiltered resource-locale choices, but synchronously
  exclude the old filtered rows/selection/detail/segment/draft, feedback and
  pending ownership. Do not reset the reviewer's new filter to All.
- At a work-identity change, preserve the current project/filter/list and locale
  choices, but synchronously exclude old detail/segment/draft, notice/error and
  pending ownership before the first B context can combine B scope with A detail
  or segment. Keep B's safe selection and legitimate row-scoped controls; do not
  strand B in saving or weaken the current same-project refresh/selection test.
- Define a current view context covering effective project generation, target
  filter and selected work identity. Direct segment/form effects also require
  the submitted segment/form context still to be current; a later selection or
  edit is not the submitted draft. Bind this context to actual callbacks and
  closures, not only to a token recorded beside them.
- Guard direct detail, form reset, notice/error and saving completion across
  all six existing runners. Preserve their already-issued server payloads and
  mandatory follow-ups, including approval → reconcile; do not retarget,
  cancel or reissue those operations because the view changed.
- Treat `run-export!` as one separate read-only UI completion, not a seventh
  mutation runner. Preserve its original SFT request and captured download
  bytes/filename with no retarget or reissue. Only its shared notice/error
  completion requires the captured effective-project generation and target
  filter still to be current; a work-row change alone does not alter those
  export inputs. It does not acquire mutation saving ownership. No SFT/export
  content semantics, endpoint, provider or pipeline configuration change.
- Replace bare saving ownership with a unique operation identity scoped to the
  current context. A context switch detaches old visible pending state before
  new controls are shown. A new B operation owns B's saving state; old A success,
  error or finally cannot clear it or install A status. Current B completion
  releases it normally. Unmount also invalidates UI callbacks.
- A stale-project completion starts no reload. For same-project selection or
  filter changes, suppress stale direct effects and route any permitted refresh
  through the current context, preserving the existing same-project B-selection
  refresh regression unchanged. Never invoke the captured old project/filter
  reload, advance a pending current request's generation, or prevent its response
  from installing. Coalesce or skip a refresh while that context has a pending
  load; any later refresh must still revalidate the current context.
- Reuse the existing page/controller boundaries and `logic/same-work?`
  identity; add no generic async framework, endpoint, backend policy or second
  review law. Product/test scope is `review_controller.cljs`, `view.cljs`,
  `review_controller_test.cljs` and `page_interaction_test.cljs`. The related
  locale story owns `logic.cljs`, `logic_test.cljs` and locale projection wiring.
- Verification scope remains the existing shared fixture helper,
  `scripts/verify-translation-split-review-tour.sh` and
  `docs/verification/translation-split-review.md`. Preserve default consumers and
  existing default full-tour mutation behavior.

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
   regression still passes unchanged, including its refresh count.
4. After leaving pending A, B's legitimate controls are actionable. Start B's
   own operation, resolve/reject A while B is pending, and require B to remain
   saving/disabled. Complete B and require only B's saving to release. Cover both
   stale success and stale error/finally; pending state is not just reset once.
5. All six runners share this UI fence. Tests assert original server payloads
   and required follow-up calls are unchanged, particularly successful approval
   still reconciling its original publication after navigation. UI staleness is
   not server cancellation. No extra dispatched/review/approval call is issued.
6. The human artifact visibly exercises installed-A → B-pending exclusion,
   pending-A mutation → B, same-project locale and selected-row transitions,
   and old-finally versus new-B-saving ownership through normal controls. It
   fails on each violated invariant and captures screenshots/counters. No race
   is silently skipped because the locale branch normally exits before writes.
   Include already-installed feedback and the first filter/work renders, not
   only stale callbacks arriving after a switch.
7. Deferred SFT success and rejection after project or locale change cannot
   install stale shared notice/error. Assert exactly the original captured
   request and original-coordinate download filename/bytes on success, with no
   retarget/reissue, mutation or provider call. Exercise the same success/failure
   policy through normal Export SFT in the visibly synthetic browser phase;
   its held run-owned SFT response is never forwarded to a real content read.

## Verification

Use controlled deferred API fixtures for causal RED. Add explicit cases for
project, filter and work transitions, current-load preservation, already-settled
feedback, old/new operation ownership and read-only export success/rejection.
First-render RED must be observed before loading/detail passive effects, not
only after held replacement responses. For the six runners, require a named success/error/finally
coverage table rather than claiming one dispatch test proves every implementation.
Preserve existing tests; new/changed async tests use native `^:async` / `await`.
The product checks remain the full relevant frontend test and lint commands with
actual zero failures, errors and warnings. No unrelated red is waived.

The human controlled race phase uses a private browser session after normal real
UI authentication. Install the run-owned browser adapter before navigating to
the controlled translation view. The shared HTTP helper resolves `js/fetch`
dynamically on every call (`frontend/lib/api.cljs:38–43`), so a session-local
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
  or filter. Release success and rejection in separate cases; capture the
  original-coordinate download bytes/filename and current feedback. No real
  export/content GET is forwarded, no mutation/provider request is introduced,
  and owned download artifacts join the existing cleanup checks.
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

Current-context refresh can accidentally advance the loader sequence and cancel
a valid current request; test the original held request actually installs.
Project-only tokens miss locale and work changes. An unconditional old finally
can unlock a newer operation, while dropping it without boundary reset can lock
the view forever. A browser fixture that forwards an unknown POST or silently
stubs native inventory in the locale phase would misrepresent evidence; its
fail-closed separation and counters are acceptance requirements.

---
Accepted five-point P0 planning sibling from the root-reviewed PR16 split proposal SHA256 07d31d1c1a6a948effbb876ba68f4c050a85ae92369c16c4c653200e74886a05, body SHA256 339b11a8088af8da26d7f7169792fab948a695ccb18012c4e6cb051c3ad3c864. Native incoming→accepted→breakdown completed without bypass; remains breakdown. Existing knoxx-translation-resource-locale-filter-options stays three points and depends on this story. Explicit new repair total 3+5=8 (+5 discovery); broader accepted eight-point parent remains unchanged and incomplete. Original current-c7 Codex P1 roots 4282077748/4282077754/4282077767 and prior 4281890616 retain their native history. Whole-view actual callback closure, operation-owned saving, original payload/follow-ups, pending-load conservation and controlled browser races are required implementation acceptance. No live mutation/provider/publication, app/runtime, readiness or broader parent completion is claimed by this planning admission.

Planning refinement for native body-only Codex review5532308090 on b896ced5ad2785e776b39bcaf4a757f3ca7081ae: accept P2 export feedback, P2 installed feedback and P1 first filter/work-render state. Root read exact6,018B proposal SHA2567c76aa884431f3f673bffea62fbdaf4e0d23e947b76bfae7eb030684146ddef8 and explicitly admitted bounded read-only SFT shared feedback fencing, preserving original request/download bytes+filename/no retarget/reissue. Expand synchronous project/filter/work boundary exclusion with distinct retained state and installed notice/error; require causal pre-passive unit and visibly synthetic browser checkpoints plus controlled export success/rejection/download evidence. Six mutation runners stay six, source/test/verification files and lifecycle5/locale3 dependency unchanged; parentaccepted8 remains open/byte-unchanged. Canonical status omission of these actual body findings is not dismissal or clearance. Preserve all fourteen native Fixed histories, append-only provenance, pending MiMo and actual cooldown. Planning only: no application/test/browser/build/runtime/model/content/provider/publication/production effect. Separate source/event and canonical RR/portableSM commits.

---