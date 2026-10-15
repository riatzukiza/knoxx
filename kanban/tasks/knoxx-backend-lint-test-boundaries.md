---
category: "tasks"
labels: ["tasks", "5sp"]
write-id: "1792028987527-0.ghvrlzsqk5i819jblxa"
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

---