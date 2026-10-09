#!/usr/bin/env bash
# GPL-3.0-or-later.
# Supported checks only: isolated fixture suite OR read-only served-code gate.
# No backend starts/restarts, shared DB fixtures, social calls, or model calls.
# Scheduled intake has source fixtures; there is no live model-verification endpoint.
set -euo pipefail

KNOXX_VERIFY_REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KNOXX_VERIFY_MODE="${1:---help}"
KNOXX_VERIFY_TMP=""
KNOXX_VERIFY_CHILD=""
KNOXX_VERIFY_PASSES=0
KNOXX_VERIFY_FAILURES=0

usage() {
  cat <<'USAGE'
Usage: bash scripts/verify-local-creator-loop.sh --hermetic | --production | --served | --full

  --hermetic  Seed a dedicated temporary source/fixture snapshot, run the
              existing backend test target, and clean up this run's data.
  --production  Compile the existing backend typecheck/server target in the
                same isolated source snapshot; start no backend or hot reload.
  --served    Read-only check that a running Docker backend serves this clean,
              compiled checkout. Never builds or changes the running service.
  --full      Refuse: source fixtures cannot establish an activated continuous
              creator. See docs/verification/local-creator-loop.md.

For --served: KNOXX_CREATOR_CONTAINER defaults to knoxx-social-local-backend-1.
The container's compiled root is /app/dist; the local root is backend/dist.
No API key, credential, public test post, or model invocation is needed.
USAGE
}

pass() { KNOXX_VERIFY_PASSES=$((KNOXX_VERIFY_PASSES + 1)); printf 'PASS  %s\n' "$*"; }
warn() { printf 'WARN  %s\n' "$*"; }
fail() { KNOXX_VERIFY_FAILURES=$((KNOXX_VERIFY_FAILURES + 1)); printf 'FAIL  %s\n' "$*" >&2; }
abort() { fail "$*"; exit 2; }
need() { command -v "$1" >/dev/null 2>&1 || abort "missing required command: $1"; }

cleanup() {
  local code=$?
  if [[ -n "${1:-}" ]]; then code="$1"; fi
  trap - EXIT INT TERM
  # Only this script's explicitly isolated process group can be signalled.
  # Existing Shadow servers, backend processes, makers, and tests are untouched.
  if [[ -n "$KNOXX_VERIFY_CHILD" ]]; then
    local group
    group="$(ps -o pgid= -p "$KNOXX_VERIFY_CHILD" 2>/dev/null | tr -d ' ' || true)"
    if [[ "$group" == "$KNOXX_VERIFY_CHILD" ]]; then
      kill -TERM -- "-$KNOXX_VERIFY_CHILD" 2>/dev/null || true
      wait "$KNOXX_VERIFY_CHILD" 2>/dev/null || true
    fi
  fi
  if [[ -n "$KNOXX_VERIFY_TMP" && -d "$KNOXX_VERIFY_TMP" ]]; then
    rm -rf -- "$KNOXX_VERIFY_TMP"
    printf 'CLEAN  removed only this run\047s dedicated temporary snapshot\n'
  fi
  if (( code != 0 )); then
    printf 'FAILED  verification stopped with status %s; %s check(s) failed\n' "$code" "$KNOXX_VERIFY_FAILURES" >&2
  fi
  exit "$code"
}

case "$KNOXX_VERIFY_MODE" in
  --help|-h) usage; exit 0 ;;
  --full)
    warn "The source intake harness exists; served activation and natural decisions still need operator observation."
    printf 'FAIL  no full creator-loop PASS can be established by this script\n' >&2
    exit 2 ;;
  --hermetic|--production|--served) ;;
  *) usage >&2; exit 2 ;;
esac
[[ $# -eq 1 ]] || abort "supply exactly one verification mode"

trap cleanup EXIT
trap 'cleanup 130' INT
trap 'cleanup 143' TERM
need git
need node
need mktemp
KNOXX_VERIFY_TMP="$(mktemp -d "${TMPDIR:-/tmp}/knoxx-local-creator-loop.XXXXXXXX")"

hermetic() {
  need pnpm
  need tar
  need python3
  need setsid
  need ps
  local fixture="$KNOXX_VERIFY_TMP/checkout"
  local logfile="$KNOXX_VERIFY_TMP/backend-tests.log"
  local listfile="$KNOXX_VERIFY_TMP/source-files"
  local test_root="backend/test/cljs/knoxx/backend/character"
  local required_tests=(tool_modes_test.cljs mode_runtime_test.cljs encounter_test.cljc
                        authority_test.cljs encounter_admission_test.cljs encounter_openplanner_test.cljs
                        social_encounters_test.cljs encounter_runtime_test.cljs encounter_timestamp_test.cljs
                        decision_input_test.cljs turn_context_test.cljs graph_recall_test.cljs membership_policy_route_test.cljs)
  local file
  for file in "${required_tests[@]}"; do
    [[ -f "$KNOXX_VERIFY_REPO/$test_root/$file" ]] || abort "required fixture suite is absent: $file; finish source integration before verification"
  done
  [[ -d "$KNOXX_VERIFY_REPO/backend/node_modules" ]] || abort "install this checkout's backend dependencies before verification"
  [[ -f "$KNOXX_VERIFY_REPO/backend/test/js/focused_builtin_tools.test.mjs" ]] || abort "required actual SDK/validator fixture is absent"
  mkdir -p "$fixture" "$KNOXX_VERIFY_TMP/contracts"
  # Snapshot tracked and new feature files, never ignored DB/state/artifacts.
  # Original source and the original compiler's output/cache remain untouched.
  git -C "$KNOXX_VERIFY_REPO" ls-files --cached --others --exclude-standard -z -- . \
    ':(exclude,glob)**/node_modules' ':(exclude,glob)**/node_modules/**' >"$listfile"
  (cd "$KNOXX_VERIFY_REPO" && tar --null -T "$listfile" -cf -) | tar -xf - -C "$fixture"
  ln -s "$KNOXX_VERIFY_REPO/backend/node_modules" "$fixture/backend/node_modules"
  # This is an exact edit of a known copied configuration, not a replacement
  # resource parser. Refuse drift rather than accidentally bind a shared port.
  python3 - "$fixture/backend/shadow-cljs.edn" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
text = path.read_text()
old = ':nrepl {:port 4500}'
if text.count(old) != 1:
    raise SystemExit('FAIL  copied nREPL configuration changed; review isolation before running')
path.write_text(text.replace(old, ':nrepl {:port 0}'))
PY
  pass "seeded a dedicated checkout snapshot and empty contract root; test state cannot enter the live overlay"
  # --force-spawn is a supported Shadow CLI option. The copied project has its
  # own cache/output directories; no connection to an existing compiler is made.
  mkdir -p "$KNOXX_VERIFY_TMP/workspace" "$KNOXX_VERIFY_TMP/generated" "$KNOXX_VERIFY_TMP/content" "$KNOXX_VERIFY_TMP/tmp"
  # HOME supplies the existing dependency cache; user npm credentials do not.
  # A literal fixture token satisfies checked-in npm interpolation only.
  local fixture_env=(-i "PATH=$PATH" "HOME=$HOME" "NPM_CONFIG_USERCONFIG=/dev/null" "NPM_TOKEN=fixture")
  if [[ -n "${JAVA_HOME:-}" ]]; then fixture_env+=("JAVA_HOME=$JAVA_HOME"); fi
  if [[ "$KNOXX_VERIFY_MODE" == "--production" ]]; then
    setsid env "${fixture_env[@]}" CONTRACTS_DIR="$KNOXX_VERIFY_TMP/contracts" \
      KNOXX_CONTRACTS_DIR="$KNOXX_VERIFY_TMP/contracts" KNOXX_DISABLE_EVENT_RUNTIMES=true \
      WORKSPACE_ROOT="$KNOXX_VERIFY_TMP/workspace" KNOXX_GENERATED_CONTRACTS_DIR="$KNOXX_VERIFY_TMP/generated" \
      KNOXX_PUBLICATION_CONTENT_ROOT="$KNOXX_VERIFY_TMP/content" TMPDIR="$KNOXX_VERIFY_TMP/tmp" \
      pnpm -C "$fixture/backend" typecheck --force-spawn >"$logfile" 2>&1 &
    KNOXX_VERIFY_CHILD=$!
    local production_status=0
    wait "$KNOXX_VERIFY_CHILD" || production_status=$?
    KNOXX_VERIFY_CHILD=""
    cat "$logfile"
    (( production_status == 0 )) || abort "isolated production compilation failed"
    if ! grep -Eq '^\[:server\] Build completed\. .*0 warnings' "$logfile"; then
      abort "production compiler did not report a completed zero-warning server build"
    fi
    pass "the actual backend typecheck script compiles the server target with zero compiler warnings; no server started"
    warn "Production compilation is not live SDK/Mongo compatibility, activation or maker-output proof."
    return
  fi
  if ! env "${fixture_env[@]}" TMPDIR="$KNOXX_VERIFY_TMP/tmp" \
      node --test "$fixture/backend/test/js/focused_builtin_tools.test.mjs"; then
    abort "actual supported SDK/strict schema fixture failed"
  fi
  pass "actual SDK factories/session prompt rebuild/tool ceiling/history and strict JSON Schema validation pass without a provider call"
  setsid env "${fixture_env[@]}" CONTRACTS_DIR="$KNOXX_VERIFY_TMP/contracts" \
    KNOXX_CONTRACTS_DIR="$KNOXX_VERIFY_TMP/contracts" \
    KNOXX_DISABLE_EVENT_RUNTIMES=true \
    WORKSPACE_ROOT="$KNOXX_VERIFY_TMP/workspace" \
    KNOXX_GENERATED_CONTRACTS_DIR="$KNOXX_VERIFY_TMP/generated" \
    KNOXX_PUBLICATION_CONTENT_ROOT="$KNOXX_VERIFY_TMP/content" \
    TMPDIR="$KNOXX_VERIFY_TMP/tmp" \
    pnpm -C "$fixture/backend" exec shadow-cljs compile test --force-spawn >"$logfile" 2>&1 &
  KNOXX_VERIFY_CHILD=$!
  local compiler_status=0
  wait "$KNOXX_VERIFY_CHILD" || compiler_status=$?
  KNOXX_VERIFY_CHILD=""
  cat "$logfile"
  (( compiler_status == 0 )) || abort "isolated backend compilation/test process failed; see output above"
  # Reuse the repository's existing counter parser. Shadow can exit zero even
  # when tests fail; absent or nonzero counters must not become a green result.
  if ! node --input-type=module - "$logfile" "$fixture/backend/scripts/run-shadow-tests-ci.mjs" <<'JS'
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
const { parseTestCounters } = await import(pathToFileURL(process.argv[3]).href);
const counters = parseTestCounters(fs.readFileSync(process.argv[2], 'utf8'));
if (!counters || counters.failures !== 0 || counters.errors !== 0) process.exit(1);
JS
  then
    abort "backend suite had failures/errors or did not report counters"
  fi
  pass "the compiled fixture snapshot completed the full backend test target with zero failures and errors"
  local namespaces=(tool-modes mode-runtime authority encounter encounter-admission encounter-openplanner
                    social-encounters encounter-runtime encounter-timestamp decision-input turn-context graph-recall membership-policy-route)
  local explanations=(
    "mode fixtures preserve character/conversation and refuse hidden, unknown, or revoked capabilities"
    "dispatcher fixtures validate delegated arguments and refresh current authority before touching a closure"
    "authority fixtures preserve tenant/membership scope and recheck actor selection and current stored grants"
    "encounter fixtures preserve scoped external evidence, edited input, emoji/reactions/media, and fresh-input priority over self-output within bounded later context"
    "admission fixtures reuse durable rows on replay/reconstructed ports and cannot advance a failed page cursor"
    "existing-store adapter fixtures pin owner/stream and expose duplicate progress or unconfirmed retention"
    "social source fixtures fetch newer material on the next poll, retain source media/reactions and deny unbound scopes before reading"
    "automatic intake/action fixtures use canonical account/source grants and retained checkpoints across retry/reconstructed ports"
    "timestamp fixtures normalize valid source precision/timezones and refuse invalid calendar facts"
    "decision input fixtures let authorized external encounters shape scoped recall while preserving quoted source data"
    "turn attachment fixtures reload admitted evidence for maker/reply context while preserving persona and fast incoming replies"
    "actual scoped graph adapter and automatic turn fixtures include the graph-only neighbor/path in the provider-session prompt and retain safe graph failures"
    "membership policy route fixtures preserve same-org scoped operators, required permission, cross-org refusal and existing system-admin handling"
  )
  local index
  for index in "${!namespaces[@]}"; do
    if ! grep -Fq "Testing knoxx.backend.character.${namespaces[$index]}-test" "$logfile"; then
      abort "required namespace did not report execution: ${namespaces[$index]}; a compiler PASS alone is insufficient"
    fi
    pass "${explanations[$index]} — isolated fixture evidence"
  done
  warn "Fixture adapter reconstruction is not an actual backend restart or live provider demonstration."
}

served() {
  need docker
  local container="${KNOXX_CREATOR_CONTAINER:-knoxx-social-local-backend-1}"
  local paths=(backend/src backend/deps.edn backend/shadow-cljs.edn backend/package.json
               backend/pnpm-lock.yaml pnpm-lock.yaml shared/src shared/deps.edn)
  git -C "$KNOXX_VERIFY_REPO" diff --quiet HEAD -- "${paths[@]}" || abort "source/dependency changes are uncommitted; an image revision label cannot identify this feature snapshot"
  [[ -z "$(git -C "$KNOXX_VERIFY_REPO" ls-files --others --exclude-standard -- "${paths[@]}")" ]] || abort "untracked feature source cannot be identified by the image revision"
  local head revision running
  head="$(git -C "$KNOXX_VERIFY_REPO" rev-parse HEAD)"
  running="$(docker inspect --format '{{.State.Running}}' "$container")"
  [[ "$running" == true ]] || abort "selected backend container is not running"
  revision="$(docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$container")"
  [[ "$revision" == "$head" ]] || abort "selected backend image revision differs from this source checkout"
  pass "running backend image identifies this clean checkout revision; compiled-content comparison follows"
  local dist="$KNOXX_VERIFY_REPO/backend/dist"
  [[ -f "$dist/server.js" ]] || abort "compile the server from this reviewed checkout before comparing served code"
  local source module
  local required_sources=(
    infra/agent/session.cljs infra/agent/turn.cljs extern/eta_mu.cljs extern/tools.cljs
    domain/character/tool_modes.cljc law/character/tool_modes.cljc
    infra/character/mode_runtime.cljs domain/character/encounter_context.cljc
    infra/character/encounter_admission.cljs infra/character/encounter_openplanner.cljs
    domain/character/social_encounters.cljc infra/character/social_encounters.cljs
    infra/character/encounter_runtime.cljs infra/character/intake_action.cljs
    domain/action/character_intake.cljs
    domain/character/decision_input.cljc extern/character_encounters.cljs
  )
  for source in "${required_sources[@]}"; do
    module="knoxx.backend.${source//\//.}"
    module="${module%.*}.js"
    [[ -f "$dist/cljs-runtime/$module" ]] || abort "required feature module is absent from compiled server: $module"
    [[ ! "$KNOXX_VERIFY_REPO/backend/src/cljs/knoxx/backend/$source" -nt "$dist/cljs-runtime/$module" ]] || abort "compiled module predates its source: $module; rebuild the reviewed server"
  done
  local hash_code
  hash_code='import fs from "node:fs"; import path from "node:path"; import crypto from "node:crypto";
const root=process.argv[1], rows=[];
function walk(dir){for(const entry of fs.readdirSync(dir,{withFileTypes:true}).sort((a,b)=>a.name.localeCompare(b.name))){const file=path.join(dir,entry.name); if(entry.isDirectory())walk(file); else if(entry.isFile()&&entry.name.endsWith(".js"))rows.push([path.relative(root,file),crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex")]);}}
walk(root); if(rows.length===0)process.exit(2); rows.sort((a,b)=>a[0].localeCompare(b[0])); process.stdout.write(JSON.stringify(rows));'
  node --input-type=module -e "$hash_code" "$dist" >"$KNOXX_VERIFY_TMP/local-code.json"
  docker exec "$container" node --input-type=module -e "$hash_code" /app/dist >"$KNOXX_VERIFY_TMP/served-code.json"
  if ! cmp -s "$KNOXX_VERIFY_TMP/local-code.json" "$KNOXX_VERIFY_TMP/served-code.json"; then
    abort "served compiled JavaScript differs from this checkout's server build; no behavioral checks can count against this revision"
  fi
  pass "all served compiled JavaScript matches this checkout, including required mode/intake/context/session modules"
  warn "Code provenance does not establish enabled intake, actual recall, live authorization, or model-driven decisions."
}

if [[ "$KNOXX_VERIFY_MODE" == --served ]]; then served; else hermetic; fi
warn "Intake contract activation and a natural live creator-loop walkthrough remain separate operator gates."
printf '\n%s PASS; %s FAIL. Selected %s checks passed; full creator-loop behavior remains unverified.\n' \
  "$KNOXX_VERIFY_PASSES" "$KNOXX_VERIFY_FAILURES" "$KNOXX_VERIFY_MODE"
