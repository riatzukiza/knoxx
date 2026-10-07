# Knowledge Ops source and runtime routing

This is the current **documentation entrypoint** for the Knoxx part of [issue164](https://github.com/open-hax/knoxx/issues/164), `knoxx-knowledge-ops-docs-source-of-truth-normalization`. It reconciles the retired documentation paths with inspected source and deployment documentation. It does not validate a running stack or complete the whole cross-repository task.

## Where a reader should start

| Question | Current entrypoint | Authority and limit |
| --- | --- | --- |
| What is implemented in Knoxx? | [Repository README](../../README.md), [backend README](../../backend/README.md), and [resource architecture](../design/resource-architecture.md) | Application source/build boundaries. Declared routes and ports are not live health evidence. |
| What owns work-item identity and status? | [Knoxx Kanban](../../kanban/README.md), the [existing normalization task](../../kanban/tasks/knowledge-ops-docs-source-of-truth-normalization.md), and actual Rheos reads | The existing task stays Ready/P2/2 points. This document does not transition it or mark its checklist complete. |
| What is the reconciliation lineage? | [Current parent wrapper](../../kanban/epics/knowledge-ops-graph-memory-reconciliation.md) and the immutable full spec linked below | Historical intent and decomposition. The short wrapper is Icebox; its old Accepted comment does not change current native status. |
| Which repository owns deployment placement? | [Services README at inspected054cad89](https://github.com/open-hax/services/blob/054cad89b9b817ad07e1021b8429d12912ee492f/README.md) and [DigitalOcean deployment documentation](https://github.com/open-hax/services/blob/054cad89b9b817ad07e1021b8429d12912ee492f/digitalocean/README.md) | Deployment topology and lifecycle documentation. No deployment or operator action is authorized or observed by this note. |
| Which upstream work must precede Knoxx cutover? | [Knoxx roadmap](../../ROADMAP.md) | Knoxx remains a downstream composition target; a routing document does not promote pending upstream plans or authorize extraction. |

## Retired paths and historical observations

Accepted [commit 1bee966](https://github.com/open-hax/knoxx/commit/1bee966fee9f29df49fdd439720ae8031404e8ea) retired `specs/` and its importer on 2026-05-28, then promoted `kanban/` as the work-item source of truth. Accordingly:

- `specs/README.md` is a historical index, not a missing file to recreate. Its content remains in Git history and fork-tax tags.
- The task's `source:` path records its import provenance; it is not a current filesystem pointer.
- The [full Graph Memory Reconciliation Spec at immutable 0141db8c](https://github.com/open-hax/knoxx/blob/0141db8cdb7adcc8858a7bf7e6e1309b74c9b056/specs/knowledge-ops-graph-memory-reconciliation.md) retains the original producer/OpenPlanner/Graph-Weaver flow, phased roadmap and child scope. That 15,968-byte document predates its deletion in `c02d4ac29986cb5e7a9edb20a4349da8a964ea9c`.
- Its date is 2026-04-05. Statements about unhealthy Knoxx, empty OpenPlanner export, ingestion arity failure, Myrmex backpressure and stale Graph-Weaver state are **historical observations in that document**, not measurements taken for this correction. They must not be republished as today's live state.
- The recovered spec explicitly gives verified runtime behavior and current source precedence over stale README language. This correction uses current owning source/documentation for static placement, and leaves runtime claims unverified.

The original full historical spec remains inspectable. The current parent Markdown is a short imported wrapper and does not contain all of that text. A reader should use the immutable historical link for the full intent and current source/owner documents for present placement.

## Current inspected source versus runtime homes

These observations are bound to owning Knoxx main `3409977bca4ba35e09967f9a99d50867a679a73c`. The proposed personal development base `5670338fcf1db7d51a2690f388e4e4c5c409c48a` preserves that accepted ancestry; it is an unqualified sync PR, not an admitted deployment.

- Knoxx application source lives in this repository. Its README already describes the shadow-cljs/Fastify backend, hybrid shadow-cljs/React frontend and JVM Clojure ingestion worker. The old Python/FastAPI description has already been superseded; this note does not claim to fix it again.
- Backend startup is declared by `backend/shadow-cljs.edn`, `knoxx.backend.entrypoint/init` and `knoxx.backend.bootstrap`. The backend/session/policy MongoDB boundary and the ingestion worker's separate PostgreSQL state must remain distinct; no storage migration follows from historical wording.
- The repository's root `ecosystem.config.cjs` is a deprecated host-configuration shim. The current README already reports its historical `services/openplanner/ecosystem.host.config.cjs` target as absent. This correction does not turn that old path into a supported launcher or invent a replacement command.
- In inspected Services main `054cad89b9b817ad07e1021b8429d12912ee492f`, Knoxx deployment artifacts are under `digitalocean/services/knoxx/`; the complete 546-entry tree has no `knoxx/README.md` or `services/knoxx/README.md`. The Services root README and `digitalocean/README.md` now provide the source/runtime separation. The card's older `services/knoxx/README.md` is therefore a placement to reconcile, not a reason to recreate the retired runtime layout.
- The Services documentation owns image/host/Compose/ingress/deployment order and live verification. Knoxx owns application behavior, source and tests. Inspected upstream heads are source observations, not proposed Foresight gitlink updates or proof of live production placement.
- OpenPlanner owning main `07085d6557b75834ce6f50e6c54b8ca47e1c7c08` [README](https://github.com/open-hax/openplanner/blob/07085d6557b75834ce6f50e6c54b8ca47e1c7c08/README.md) describes its graph monorepo/API/MongoDB boundary. That is current upstream documentation; it does not prove the API is healthy, populated or synchronized with Graph-Weaver.

No services, provider, ports, credentials or deployments were exercised. The historical `orgs/**` versus `services/**` split expresses ownership intent; those old monorepo paths do not dictate today's checkout or runtime locations.

## Whole-task acceptance still outstanding

All original goals remain: README readers reach the reconciliation anchor, current backend/runtime contradictions are corrected or explicitly superseded, source homes and runtime homes are clear, and historical donor material remains available. The card's non-goals, named files, verification and DoD are unchanged.

This correction supplies the missing Knoxx reader pointer and a source-bound routing explanation. It does **not** deliver the mandatory OpenPlanner README update: `open-hax/openplanner` is outside Foresight's declared direct repositories and requires a separately authorized owning-repository handoff. Its current README does not point to this reconciliation anchor. No OpenPlanner source or documentation is modified here.

The Services disposition above is a static documentation assessment of the current owner layout, not a claim the older local deploy was migrated or its health verified. Graph-Weaver remains an independently owned historical participant, not source adopted into Knoxx by this note. The current parent status and original task identity/Ready/P2/2 metadata remain exact.

Existing PR305/369/371 build/browser scopes and PR286 identity/projection scope remain separate. No exclusive ownership is inferred from the issue having no assignee or comments, and no foreign work is adopted. Whole164 completion still needs the external OpenPlanner prerequisite, any required current-owner reconciliation and qualified review/admission; this note supplies no synthetic approval or operational Ready evidence.

## Recording the dependency hold

The inspected accepted Rheos ef3 Promethean FSM does not contain a direct Ready→Blocked edge; [existing Rheos issue4](https://github.com/open-hax/rheos/issues/4) owns lawful obstruction reporting from unfinished stages. Moving through Breakdown solely to reach Blocked would fabricate a lifecycle change and is not proposed. Native comments can record a hold without changing the task status, and an additive GitHub `blocked` label can mark the issue while retaining its `status:ready` projection. After a separate mode-faithful fixture proved section/metadata preservation, this documentation lane applied one native HOLD comment to the owned candidate. Root independently added only the generic GitHub `blocked` label to issue164; its existing `status:ready` projection remains. The native comment stamped a write-id and serialized legacy separators; its original three parsed sections and all existing frontmatter values remained exact, and one canonical comment event appended after the exact ledger prefix. This is engine-owned formatting, not a promise of an unchanged raw card byte prefix. No hand-edited frontmatter or alternate board implementation is introduced.
