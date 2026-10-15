---
category: "tasks"
labels: ["tasks", "5sp"]
write-id: "1792024611247-0.1awu2j51zq6iptz4lf"
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