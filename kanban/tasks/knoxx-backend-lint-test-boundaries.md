---
category: "tasks"
labels: ["tasks", "5sp"]
write-id: "1792031752192-0.68ega867m6o2fb7x75b"
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

---