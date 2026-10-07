#!/usr/bin/env bash
# Real Fastify/trigger/runner/Clio admission; provider session and prompt are local fixtures.
# Requires an already prepared, privately owned isolation runner. Never targets PM2/live services.
set -euo pipefail
repo=$(git -C "$(dirname "$0")/.." rev-parse --show-toplevel)
runner=${1:?Usage: verify-trigger-spawn-boundary.sh /absolute/private/isolated-command.py}
[[ "$runner" = /* && -f "$runner" ]] || { echo 'FAIL provide an absolute prepared isolation runner'; exit 1; }
head_before=$(git -C "$repo" rev-parse HEAD)
probe=$(mktemp -d)
trap 'rm -rf -- "$probe"' EXIT INT TERM
printf 'Testing checkout %s at %s\n' "$repo" "$head_before"
# The prepared runner contract is the same one used for the recorded proof:
# offline, cwd, argv; private network/tmp/home/caches, clear environment, host read-only.
set +e
python3 "$runner" offline "$repo/backend" pnpm exec node scripts/run-shadow-tests-ci.mjs >"$probe/output" 2>&1
result=$?
set -e
cat "$probe/output"
[[ "$head_before" = "$(git -C "$repo" rev-parse HEAD)" ]] || { echo 'FAIL checkout changed during verification'; exit 1; }
[[ $result -eq 0 ]] || { echo "FAIL guarded compiled suite returned $result"; exit "$result"; }
grep -Fq 'INFO checks cover HTTP401/202' "$probe/output" || { echo 'FAIL real HTTP fixture did not execute'; exit 1; }
printf 'PASS guarded suite: unauthenticated HTTP refused; admitted HTTP dispatch creates a real runner run, durable Clio thread, and active agent session.\n'
printf 'WARN provider construction/hydration/prompt seams are local; no paid model or deployed authentication qualification.\n'
