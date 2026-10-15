---
category: "tasks"
labels: ["tasks", "5sp"]
write-id: "1792036883434-0.ag6ctozuksiqye1nca"
points: "5"
source: "specs/tasks/knoxx-backend-lint-test-boundaries.md"
title: "Knoxx Backend Lint — Test Boundaries and Async Cleanup"
priority: "P1"
status: "in_progress"
uuid: "knoxx-knoxx-backend-lint-test-boundaries"
created_at: "2026-05-27T00:00:00Z"
---

# Knoxx Backend Lint — Test Boundaries and Async Cleanup

> Source: `specs/tasks/knoxx-backend-lint-test-boundaries.md`
> Points: 5

Date: 2026-05-27
Status: todo
Parent epic: `specs/epics/knoxx-backend-cljs-lint-remediation.md`
Story points: 5

## Purpose

Make backend tests respect public API boundaries, modern async style, required protocol methods, and namespace requirements while preserving regression coverage.

## Problem

The lint run reports test-specific blockers and warnings:

- private-var access in `pipeline_runner_test.cljs`, `policy_actor_test.cljs`, and `tools/temp_memory_test.cljs`;
- raw `.then`/`.catch` Promise chains across many test files;

---
Scoped test-boundaries slice: split the current translation_dispatch_test.cljs (SHA256 20a358b761bf18b6b1939919c643548496b76f9c3387e36789ffd508cb32ade2) into cohesive dispatch/admission, completion and provenance suites plus one named extern fixture boundary. Preserve all 40 existing deftest names, async semantics, assertions and individual fixture setup; keep unchanged test/test-ci discovery. Refactor only the two existing protocol fixture helpers as needed for their length warnings. No product source, lint suppression or policy change. Verify exact source movement, owning lint, backend typecheck and full compiled test outcome. This is an implementation slice of this active five-point owner, not completion of the broader lint epic or a qualified combined CMS release.

Scoped next test-boundaries slice: complete the OpenPlanner history test clients in backend/test/cljs/knoxx/backend/memory_routes_test.cljs and backend/test/cljs/knoxx/backend/message_source_test.cljs through the new backend/test/cljs/knoxx/backend/extern/openplanner_history_fixture.cljs. Address the five retained missing-protocol warnings and one unused callback binding while preserving all 17 existing test names, async metadata, 34 assertion source forms, callback receiver/options, pagination/cache/auth setup, message rows and the exact rejected error. Implement all 33 actual client methods with explicit unsupported-operation refusal; sessions-only fixtures must not gain permissive enabled defaults. Preserve the prior four qualified translation-test source paths byte-exact. Verify one actual original owning primary2025 lint RED, three-path owning lint GREEN, backend typecheck, full normal Node/compiled tests and full primary lint with unrelated failures retained. Exact reviewed patch SHA256 cc4c25c78c965a36347d1883aef74915bdd84bdeec2678cd3ab2719fad513b99. No product, SDK, config, lint-policy or assertion changes; no new task or transition. This is another slice of the existing active five-point owner, not completion of that owner, the broad lint epic or a combined CMS release.

Scoped next test-boundaries slice: complete the eight partial IAgentSession fixtures in agent_control_persistence_test.cljs, hydration_publication_test.cljs, initial_admission_cleanup_test.cljs, recovery_handoff_fixture.cljs, turn_finalization_test.cljs and turn_sink_ownership_test.cljs through the new test-only extern/agent_session_fixture.cljs. Preserve all20 direct test names, async metadata,97 assertion source forms and every original method callback argument vector/body; retain original receiver, values, promises/errors, timing gates, durable writer order, startup/observer ownership and cleanup setup. Implement all9 actual port methods through an opaque identity-bearing type; unspecified calls explicitly refuse with no successful defaults. Preserve all7 prior qualified translation/history source paths byte-exact. Verify one actual original six-file primary2025 lint RED, seven-path owning GREEN0/0, registered backend typecheck/full normal Node+compiled tests/full primary lint, including complete expected eight-diagnostic delta and unchanged-owner custody. Exact root-approved independently reviewed patch SHA256 cae06736c93a92fb278b7e9a71fedeb4097bf0d22d2b54e458ca07e6caeaab08. No product, SDK, config, lint-policy, assertion, task estimate or status change; separate ISessionStore warning and deliberate legacy Promise-chain controls remain unchanged. This is a slice of this active five-point owner; full lint owner/epic and combined CMS deployment remain incomplete.

Scoped next test-boundaries slice: complete the existing RefusingEventStore in backend/test/cljs/knoxx/backend/extern/event_queue_retirement_test.cljs by adding the five missing ISessionStore methods with exact owning arities and explicit unsupported-operation refusal through one private helper. Preserve its existing zero-field defrecord, constructor, all three original method forms, five test names and async metadata, all 12 assertion source forms, native Promise return/refusal behavior, ordered queue gates and eviction settlement failure classification. Add no successful defaults, fixture namespace, assertions or tests. Preserve all 14 previously qualified translation/history/lifecycle test source files byte-exact. Verify one actual original one-file primary2025 lint RED, one-file owning GREEN with zero errors and warnings, registered backend typecheck, full normal Node/compiled tests and full primary lint with complete one-diagnostic delta and unchanged-owner custody. Exact root-approved patch SHA256 8030c91666fee8f740244ee058a36276e4266692a10319fc10e53f34074e08ab. No product, SDK, source configuration, lint policy, task estimate or status change. Deliberate legacy Promise-chain controls remain untouched. This is a slice of the existing active five-point owner; the owner, broader lint epic and combined CMS release remain incomplete.

Scoped next test-boundaries slice: remove the six actual warnings in backend/test/cljs/knoxx/backend/domain/action/run_steps_test.cljs through one explicit clojure.string require, three immediate redundant-let merges and two unused ctx formal names changed to _ctx. Preserve all 11 test names and metadata, 10 async declarations, 37 exact assertion source forms, 15 ordered callback bodies, Promise return forms, step/error ordering, awaited memory seed/cleanup and TTL60. Preserve all 15 previously qualified translation/history/lifecycle/event-store test files byte-exact and leave deliberate legacy Promise-chain controls unchanged. Verify one original owning primary2025 lint RED before application, one-file owning lint GREEN0/0, registered backend typecheck, full normal Node plus compiled tests once and full primary lint once with exact six-diagnostic delta and unchanged remaining source custody. Exact root-approved independently reviewed patch SHA256 56afaa956aefd6d6c1fbde2bdf0dc96e35b95d7d860c0d67c423f75b45dcb397. No product, SDK, source configuration, lint policy, assertion, new test, task estimate or status change. This is a slice of the existing active five-point owner; all seven application size errors, full owner/epic and combined CMS release remain incomplete.

---