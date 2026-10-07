---
uuid: "knoxx-knowledge-ops-contract-runtime-dod-restructure"
title: "Contract Runtime: Data-Oriented Restructure"
status: ready
priority: "P2"
labels: ["epics"]
created_at: "2026-05-28T22:40:14.383Z"
source: "specs/epics/knowledge-ops-contract-runtime-dod-restructure.md"
points: null
category: "epics"
---
# Contract Runtime: Data-Oriented Restructure

> Source: `specs/epics/knowledge-ops-contract-runtime-dod-restructure.md`

**Date:** 2026-04-19  
**Status:** Accepted  
**Supersedes:** `docs/notes/2026.04.17.10.11.17.md`

---
## Framing: Why "Actor"

The system needs a single term for any decision-making entity — human or AI. "User" is human-only by convention. "Agent" is AI-only by convention. "Actor" is semantically neutral: it denotes any entity that has agency and takes actions in the system. The term appears in the actor model of computation, in theatre (a role that acts), and in legal contexts (a party who acts). None of these connotations conflict with the intended meaning here.

- `:actor/kind :human` — a person operating through the UI or API
- `:actor/kind :ai` — an AI agent operating under a contract
- All actors have an id, roles, and capabilities. No other distinction at the data layer.

Triage 2026-05-29: Spec is thorough and internally consistent — defines a data-oriented separation of the Knoxx contract runtime (EDN data layer vs. ClojureScript interpreter layer), with a clear 9-step migration order, dependency graph with no cycles, deletion table, and a named supersession of the prior plan. No external blockers are identified; the migration is fully self-contained within the knoxx backend. Purpose is clear (eliminate god-objects, dissolve policy_db.cljs, replace inlined role/tool data with EDN files), scope is large but broken into safe sequential steps, and DoD is implied per step. Suitable for breakdown next. Verdict: accepted (P2). --tasks-dir /home/err/devel/orgs/open-hax/openplanner/packages/agents/knoxx/kanban
---


## Current whole-epic planning refinement (2026-10-07)

This is a proposed refinement of the entire existing issue159 outcome, not an actor-only replacement or an implementation. The original card above remains unchanged, including its historical Accepted heading and triage. Ready/P2/unsized metadata is observed native state; it supplies no new planning qualification. No lifecycle, hierarchy, identity or estimate change is proposed here.

### Context and outcome

The retired source specification is recoverable byte-exact from accepted ancestor c02d4ac29986cb5e7a9edb20a4349da8a964ea9c: blob1605f0be2e4320bba3b2c95dff0f2d87e27c1214, 11,649 bytes, SHA25617627b37bb0714488b61d6b0ad5fc7cd71e60b5c219bc52745fe8adbc8482ee5. Accepted retirement2b457af4ef587fbcde717f1b612e37fc264e75f0 removed the file when cards became authoritative. Its historical acceptance is provenance, not authority to restore old runtime semantics. The full recovered source and its superseded April17 note remain inspectable in the verification packet.

The outcome remains a data-oriented contract/resource runtime: authored EDN and explicit portable shapes/laws/decisions, with effects at named outer adapters; no god-object merely moved into a new directory. All nine original obligations below, the dependency graph, interpreter loop, seven retirement decisions and file/cache authority must have reviewed current applicability and end-to-end evidence before this epic is complete.

### Full original order and required current crosswalk

| Step | Recovered obligation | Current evidence and unresolved acceptance |
| --- | --- | --- |
| 1 | Extract db/ from policy_db.cljs first. | The old file is absent; infra/db/policy.cljs is Mongo-backed. Review the actual policy/store boundary, static grants and actor persistence; do not restore PG pools, SQL compiler or migrations. Preserve identity, authorization and durable-write behavior. |
| 2 | Write roles, capabilities, actors and agent jobs as contracts/ EDN. | Current contracts/AGENTS.md governs resource body identity, namespace/composite resources and references. Prove authoritative data extraction, not directory existence; no new agent trigger/hooks/version fields. |
| 3 | Write a loader that reads and parses EDN. | domain/contracts/loader.cljs and domain/resources/loader.cljs exist. Classify their current I/O coupling and identity/rejection behavior; pure parsing/normalization decisions belong inward, filesystem/discovery/watching outward. |
| 4 | Validate through Malli with explicit result/errors. | law/contracts.cljs and open_hax/contracts/schema.cljs exist. Review full type/identity/reference guards, malformed/unknown/duplicate negatives and required caller validation. Validation must not authorize effects or trust filenames. |
| 5 | Slim runtime_config to environment-only configuration; remove inline roles/tool/job data. | infra/config.cljs exists, with current env/default composition. Audit remaining semantic tables and startup consumers; preserve defaults/compatibility through explicit adapters. The historical under60-line aspiration is not evidence of compliance or a reason to suppress required configuration. |
| 6 | Split event_agents into event and cron triggers. | Current domain/event, domain/action and infra runtime boundaries separate event/trigger/action/schedule/source/generator roles. Prove the whole dispatch path, trusted context, scheduling and cleanup; preserve161/160/lifecycle work and do not put triggers back into agents. |
| 7 | Separate util, session and memory responsibilities. | Current infra/agent/session.cljs, infra/core_memory.cljs and other named adapters exist. Inventory the full former responsibilities and accepted APIs; do not create a utils junk drawer or call the historical zero-risk premise verified. |
| 8 | Extract the agent_turns tool dispatch inner loop. | infra/agent/turn.cljs, infra/agent/tools.cljs and domain/action seams exist. Review tool resolution, execution policy, typed dispatch, result/error compatibility and recovery. No domain import of runtime services or named-adapter bypass. |
| 9 | Move thin routes last. | infra/routes/resources.cljs, contracts.cljs and tools.cljs are existing surfaces. Preserve current routes/statuses/authentication, load/write/reload semantics and frontend consumers; no route-only or rename-only closeout. |

These are planning classifications, not nine completed steps. The design note requires an explicit completed/retained/superseded decision and proof for every row. Keep the original extraction-before-moves order for any remaining migration; current accepted implementations are examined before recreating them.

### Scope and non-goals

Preserve the complete data/interpreter distinction, actor/role/capability/tool resolution, validated session context, trigger/action dispatch, tool execution and durable accountability; review the complete dependency and retirement crosswalk in docs/notes/design/contract-runtime-data-oriented-planning.md. Pure law/shape/domain decisions use portable .cljc where practical, and named extern/infra boundaries own JS, filesystem, databases, clocks, SDKs and lifecycle. No backend TypeScript, coordinated OpenPlanner changes, SQL compiler, Redis-as-authority restoration, generic board parser, new contract language, unrelated UI feature or runtime implementation is included in this planning change.

### Acceptance and verification

1. Every recovered step, dependency edge, interpreter stage and deletion-table row has a reviewed current disposition, accepted-source locations, compatibility obligations and positive/hostile verification. Unproven work remains open; historical labels and file absence do not close it.
2. Current resource identity follows contracts/AGENTS.md: body identities, qualified namespace/composite registrations, owner-namespaced references, anonymous facets and trigger/with arguments; no prohibited dead fields or title/filename-only authority. Actor kind/role/capability and tool grants/denials retain current semantics.
3. Authored resource files retain their intended canonical role. Map every current Mongo policy/store write, process cache and any Redis use separately; do not classify actor/session/run authority as disposable merely because historical resource files were canonical. Stale cache, missing/deleted files, invalid resources, duplicate identity, conflicting projections, failed writes and recovery refuse unsafe effects. No storage migration is authorized by this plan.
4. Reviewed category/contract boundaries and a cycle-free current dependency graph prevent effects and adapter types from leaking into portable laws. Effectful legacy namespaces are candidates for decomposition, not accepted pure APIs because of their names.
5. The original unsized epic gets a fair reviewed breakdown covering all nine obligations before implementation. Proposed work packages and ordering are in the design note; no new child UUIDs/points/links or Ready transitions are manufactured here. Existing native Ready stays unchanged but does not substitute for planning review of current scope.
6. Future red tests fail for the actual decision/boundary; green follows domain then adapters. Full backend tests, production typecheck/release, zero-warning lint, boundary/error/size/duplication and affected frontend/route gates remain required. A guarded runnable human script plus documentation and browser tour for affected UI must prove the served exact revision, seed/cleanup owned data and exercise failure modes. Documentation preparation is not these future passes.
7. Parent synchronization, required exact-head reviews/checks, publisher134, eagermerge385, lint/architecture181/182 and author-walkthrough classifier Agents22 remain independent holds. Foreign305/341/353/349/376 and personal160/161/HTTP lifecycle scopes need fresh integration checks; no foreign branch is adopted or deemed absent.

### Risks and review decisions

The file-versus-operational-store authority map, actual legacy compatibility needed, remaining loader coupling, fair epic sizing, current deletion safety and foreign source overlap must be decided with current source and reviewer evidence. A parent or dependency marked done historically does not prove this complete crosswalk. This plan keeps all original outcomes open until their evidence exists; it does not claim whole159 implementation, current trust qualification, staging, deployment or merge admission.
