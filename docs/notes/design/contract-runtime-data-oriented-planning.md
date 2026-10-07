# Whole contract-runtime data-oriented planning

This proposal refines existing Knoxx issue159 and UUID knoxx-knowledge-ops-contract-runtime-dod-restructure. The full original card is an exact prefix. Ready/P2/points:null, original hierarchy and source reference remain unchanged. No implementation or operational board edit is included.

## Recorded source and present authority

Historical source c02d4ac29986cb5e7a9edb20a4349da8a964ea9c / specs/epics/knowledge-ops-contract-runtime-dod-restructure.md is exactly11,649 bytes, blob1605f0be2e4320bba3b2c95dff0f2d87e27c1214 and SHA25617627b37bb0714488b61d6b0ad5fc7cd71e60b5c219bc52745fe8adbc8482ee5. Its superseded April17 note is separately archived. Accepted retirement2b457af is distinct from historical non-ancestor1bee966. The immutable recovery audit477b9fe71f23f3d2f475b108d91b5132f3e1ec6b records exact provenance; this candidate copies the exact archives and hashes, not its historical readiness into new admission.

Current owning accepted source3409977bca4ba35e09967f9a99d50867a679a73c is preserved by pending personal sync5670338fcf1db7d51a2690f388e4e4c5c409c48a. Current AGENTS.md and contracts/AGENTS.md govern changes. The current contract-oriented-runtime-framing note explicitly says the historical split landed under different names and its Mongo/driver discussion was not carried out as written. Existing namespaces are evidence locations, not proof that every original outcome passed.

## Whole nine-step acceptance crosswalk

Use the full nine-row table on the existing card as the required sequence. For every row, planning review must choose completed, retained or superseded and cite current code and actual verification. Completed needs actual matching behavior and compatibility proof; retained names the remaining outcome and red/green tests; superseded names the current accepted rule and demonstrates how the original intent survives. Missing files, a new folder or historical green claims cannot choose the disposition. No current row is declared fully completed by this document.

Current code locations to inspect include infra/db/policy.cljs and infra/stores for persistence; domain/contracts/{loader,resolve,roles,tools,sources} and domain/resources/{loader,namespace_file}; law/contracts.cljs and the exact external-to-Knoxx-namespace path backend/src/cljs/open_hax/contracts/schema.cljs for validation; infra/config.cljs for env composition; domain/event/{cron,dispatch} and domain/action for dispatch; infra/agent/{session,recovery,hydration,turn,tools}; infra/core_memory.cljs; and infra/routes/{resources,contracts,tools,memory}. All abbreviated Knoxx paths in that list are under backend/src/cljs/knoxx/backend; the schema exception is explicitly backend/src/cljs/open_hax/contracts/schema.cljs. File presence and the current architecture note are accepted-source observations. Purity, completed/retained/superseded classifications, complete caller coverage and behavioral parity are future review dispositions, not observed passes. Their known effectful coupling remains a reviewed decomposition concern. Do not endorse a domain namespace as pure based on its name alone.

## Dependency and interpreter obligations

The historical graph orders text/bracket decisions before loader and validator; validator, tool registry and sessions before hydration/templates/run-state/hooks; those before turns; turns before agent, triggers/integrations/persistence; thin routes last. It forbids runtime→routes, tools→runtime and inward DB imports. Preserve the semantic cycle-free intent rather than reinstalling those obsolete directories. The current architecture should make pure shape/law/domain data independent of infra, extern, filesystem, Mongo/Redis, Node and process state; orchestration composes adapters through explicit contracts. Pure portable parsing, reference resolution, grant/deny decisions and migration classification belong in .cljc where practical. Current raw interop defects require named extern adapters, not a copied shortcut into another domain namespace.

Review the entire historical interpreter loop: load actor; resolve roles; resolve capabilities; resolve tool ids; load agent resource; parse; validate; construct session context; observe/dispatch triggers; decide admission; execute tool; evaluate outcome; append accountability. Historical before/after hooks and receipts.jsonl names are not permission to revive prohibited fields or create a new ledger. Current trigger/action policies and accepted Clio/Receipt River event boundaries must carry their intent. File discovery/read/write/watch, DB/cache operations, clock/scheduler/SDK/tool calls and ledger appends are effects. Pure functions must not perform them, even though the original text described only three labelled effects.

Resolution negatives must cover unknown/malformed resources, duplicate qualified identities, wrong kind, missing references, anonymous facets attempting registration, sibling namespace aliases, dead fields, denied tools, missing actor provenance, unauthorized filesystem writes and stale mixed-snapshot contexts. Before denied actions, permit only explicitly scoped trusted read-only resource/policy resolution needed to decide admission; prove no unauthorized reads or writes and zero prohibited filesystem/DB mutation, provider/session/tool effects after failed admission. Counters must distinguish those admitted reads from writes and denied effects. Pure-law fixtures separately prove zero I/O. Positive controls traverse actual accepted loader→guard→resolution→trigger/action/tool boundary with current identities; a test stub passing itself is insufficient.

For every selected path, validate typed input and actual resource/reference identity before admission, then authenticate the actor and authorize scoped effects before filesystem writes, session/run creation, provider calls or tool dispatch; explicitly scoped trusted read-only resource/policy resolution may precede the decision. Persistence and durable run/receipt ordering must be specified at the actual accepted adapter: failed admission causes no unauthorized write; ambiguous partial effects require recorded failure/recovery, not a fabricated successful receipt. Resolution and authorization must use a coherent source/policy snapshot. Preserve public error identity and status without leaking credentials or treating a cache as actor authority.

SCI evaluation, inline functions and historical before/after hooks are not pure merely because the plan labels them decisions. Review the actual whitelist, injected environment/capabilities and possible effects; unknown or effectful evaluation remains behind guarded outer adapters and fails closed. Pure decision tests should inject effect poisons/counters and prove no I/O; current allowed anonymous-action semantics must remain under contracts/AGENTS.md rather than revive retired hook fields.

## Seven retirement decisions remain accountable

| Historical item | Required current decision and proof |
| --- | --- |
| event-agent-job->contract-edn | Determine whether any accepted caller still needs compatibility; direct authored resources cannot silently change job semantics. |
| compile-contract->sql | Preserve its rejected SQL-target intent. Do not add a SQL compiler or database migration to satisfy an obsolete path. |
| contract-librarian-contract-edn | Compare current authored librarian/resource definition and callers; no hardcoded strategy shadow or invented replacement. |
| migrate-event-agents->contracts! | Prove any required migration is complete/idempotent and accepted consumers remain compatible before removing a live path. Historical one-time wording is not deletion proof. |
| runtime_config role-tools | Locate and compare current EDN grants and current resolution/denial precedence; remove semantic duplication only with actual behavioral parity. |
| Inline tool policy lists | Map authoritative capability data and current hard denial/admission guards. Moving static data must not weaken guards. |
| normalize-event-agent-job | Preserve accepted compatibility only where required; modern agents cannot regain trigger/hooks/cadence fields. Account for all actual callers before retirement. |

Each row needs actual existence/caller search at the eventual selected source, retained API/error decisions, adversarial tests and a reviewed deletion or supersession. No live deletion is part of this planning PR.

## Resource, file, cache and operational authority

Authored files are the historical canonical resource intent. Current body identity and namespace manifests, not filename or title, determine resource identity. Current contracts/AGENTS.md requires kind-specific registration, owner-namespaced references, anonymous facets, trigger/with arguments and distinct agent/actor/role/capability/policy/source/generator/schedule/action meanings. In particular, agents do not regain trigger, source, hook or contract/version fields from archived examples. Policy denials remain absolute; model/file data does not mint invocation or persistence authority.

Current infra/db/policy.cljs is a Mongo-backed API; its actors, roles, authentication, session/run records and operational projections need an explicit authority inventory before any remaining migration. This plan does not claim every Mongo record is a disposable cache or authorize restoring PostgreSQL schemas. Distinguish authored resource authority, verified actor/policy authority, operational session/run authority and derived indexes. If two stores appear authoritative, fail closed until the owner resolves the boundary; do not silently choose the historical prose or last writer.

The old Redis contract:edn:<id> deletion and contracts:index write-through table remains an obligation to classify against actual current consumers, not a new Redis service mandate. If such indexing remains, file write/delete and index updates need accepted atomicity/recovery or a proven rebuild protocol. Cache loss/staleness must not change resource identity or authorize rejected data. Missing cache cannot make invalid resources valid; stale index must not resurrect deleted files. If these Redis consumers are retired, prove absence of all active callers and equivalent authoritative listing, rather than recreating them. Process-local loader caches are derived and cannot override current invalidation/source selection. Preserve live file metadata, resource serialization and publication behavior while choosing authority; accepted EDN formatting work has a separate owner.

## Proposed reviewed breakdown, not new operational cards

This epic remains unsized. Before implementation, review complete remaining work and fair estimates, then use Rheos to create/link/admit actual child stories as appropriate. The following proposed packages cover every step and do not manufacture UUIDs, status changes or points:

| Proposed package | Full original coverage | Dependency and required result |
| --- | --- | --- |
| A: source/authority inventory and persistence seam | Step1 plus every persistence/cache obligation | First review current stores/static tables and all seven retirement callers; retain operational APIs and authoritative data. |
| B: resources, parsing, schema and reference laws | Steps2–4 | Depends on A’s authority decisions. Pure decisions plus guarded outer loading; full current identity and positive/hostile tests. |
| C: env composition and complete event/session/tool runtime | Steps5–8 | Depends on A/B data extraction. Preserve provenance, policy, recovery, scheduling, context ownership and failure/cleanup across all entrypoints. Split this package further if fair reviewed size demands it. |
| D: route/frontend compatibility and full closeout | Step9 plus all consumer and verification obligations | After A–C. Thin guarded adapters, real served revision, human script/tour and complete gate/accountability evidence. |

Only an explicitly reviewed disposition can remove genuinely completed work from a new child’s remaining scope; its acceptance proof remains in this epic crosswalk. No implementation before planning qualification and lawful Rheos admission. Native Ready on the imported epic is visibility, not replacement for those prerequisites.

## Coordination and qualification holds

Foreign origin305 and its store, admission, session, route and startup slices (including376,341,353,349) remain unmerged foreign scope. Personal generator160/PR7, spawn161/PR6, HTTP lifecycle/PR11 and publication formatting/PR9 overlap specific adapters. Sharing a path is not a blocker by itself; compare actual semantics/accepted heads and preserve both outcomes at integration. No foreign branch is adopted, no claim of exclusive ownership is made, and absence of an assignee is not absence of a worker. A fresh whole-outcome duplication check is required before publication/implementation.

Pending personal syncPR1, unsafe eager SQUASH caller issue385, required warning/architecture owners181/182, trusted reviewer publisher visibility134 and author-walkthrough classifier Agents22 remain independent holds. No candidate-controlled key/config or model transcript can substitute for authenticated current-head reviews. This plan keeps automatic merge off and is a later draft layer until the parent and required admission are qualified. It supplies no merge, staging, deployment, native approval or completed cohort.

## Full future verification

See docs/verification/contract-runtime-data-oriented-planning.md for concrete mandatory commands, effect-negative matrix and human artifact obligations. This planning change verifies only preservation, provenance, native read visibility, receipt suffix and hygiene. It runs no backend/frontend compiler, product tests, real provider, shared service, browser or deployment. Every future failure, unavailable gate and incomplete input stays visible.
