#!/usr/bin/env bash
# Shared, run-scoped resource and durable-candidate fixture for translation
# review verification. The sourcing script owns traps and reporting.

# shellcheck shell=bash
# shellcheck disable=SC2034  # constants are consumed by the sourcing scripts

TRANSLATION_FIXTURE_NS="knoxx.verifysplit.r${RUN_ID}"
TRANSLATION_FIXTURE_DOC_LOCAL="doc01"
TRANSLATION_FIXTURE_DOC_ID="${TRANSLATION_FIXTURE_NS}/${TRANSLATION_FIXTURE_DOC_LOCAL}"
TRANSLATION_FIXTURE_GARDEN_ID="${TRANSLATION_FIXTURE_NS}/review-garden"
TRANSLATION_FIXTURE_PUBLICATION_ID="${TRANSLATION_FIXTURE_NS}/${TRANSLATION_FIXTURE_DOC_LOCAL}-es"
TRANSLATION_FIXTURE_PUBLICATION_PATH="/verify-split-review/${RUN_ID}/${TRANSLATION_FIXTURE_DOC_LOCAL}-es"
TRANSLATION_FIXTURE_TITLE_PREFIX="Split Review Fixture"
TRANSLATION_FIXTURE_DOCUMENT_TITLE="${TRANSLATION_FIXTURE_TITLE_PREFIX} 01"
TRANSLATION_FIXTURE_COUNT=18

TRANSLATION_FIXTURE_CONTRACTS_PARENT="$(cd "$(dirname "${CONTRACTS_DIR}")" && pwd)"
TRANSLATION_FIXTURE_CONTRACTS_NAME="$(basename "${CONTRACTS_DIR}")"
TRANSLATION_FIXTURE_SOURCE_REL="${TRANSLATION_FIXTURE_CONTRACTS_NAME}/$(basename "${FIXTURE_DIR}")/source-01.md"
TRANSLATION_FIXTURE_SOURCE_FILE="${TRANSLATION_FIXTURE_CONTRACTS_PARENT}/${TRANSLATION_FIXTURE_SOURCE_REL}"

translation_fixture_source_rel() {
  local index="$1"
  printf '%s/%s/source-%02d.md' \
    "$TRANSLATION_FIXTURE_CONTRACTS_NAME" "$(basename "$FIXTURE_DIR")" "$index"
}

translation_fixture_write() {
  mkdir -p "$FIXTURE_DIR"

  {
    printf '%s\n' ";; Throwaway fixture written by translation split-review verification."
    printf '{:namespace :%s\n :resources\n [' "$TRANSLATION_FIXTURE_NS"

    local index local_id source_rel
    for index in $(seq 1 "$TRANSLATION_FIXTURE_COUNT"); do
      local_id="$(printf 'doc%02d' "$index")"
      source_rel="$(translation_fixture_source_rel "$index")"
      printf '\n  {:document/id :%s\n' "$local_id"
      printf '   :document/title "%s %02d"\n' "$TRANSLATION_FIXTURE_TITLE_PREFIX" "$index"
      printf '   :document/source-locale :en\n'
      printf '   :document/visibility :public\n'
      printf '   :document/source {:path "%s"}}\n' "$source_rel"
    done

    printf '\n  {:garden/id :review-garden\n'
    printf '   :garden/title "Split Review Verification Garden"\n'
    printf '   :garden/status :active\n'
    printf '   :garden/locales [:en :es]}\n'

    for index in $(seq 1 "$TRANSLATION_FIXTURE_COUNT"); do
      local_id="$(printf 'doc%02d' "$index")"
      printf '\n  {:publication/id :%s-es\n' "$local_id"
      printf '   :publication/document :%s\n' "$local_id"
      printf '   :publication/garden :review-garden\n'
      printf '   :publication/target :open-hax.publication/static-site\n'
      printf '   :publication/locale :es\n'
      printf '   :publication/revision :source/current\n'
      printf '   :publication/state :published\n'
      printf '   :publication/path "/verify-split-review/%s/%s-es"\n' "$RUN_ID" "$local_id"
      printf '   :translation/review :required}'
    done
    printf ']}\n'
  } > "${FIXTURE_DIR}/resources.edn"

  printf '%s\n\n%s\n\n%s\n' \
    'First source paragraph for durable split review.' \
    'Second source paragraph for correction and rejection.' \
    'Third source paragraph for document-level review.' \
    > "$TRANSLATION_FIXTURE_SOURCE_FILE"

  local index source_file
  for index in $(seq 2 "$TRANSLATION_FIXTURE_COUNT"); do
    source_file="${TRANSLATION_FIXTURE_CONTRACTS_PARENT}/$(translation_fixture_source_rel "$index")"
    printf 'Source document %02d remains visible without candidate evidence.\n' "$index" \
      > "$source_file"
  done
}

translation_fixture_remove_files() {
  if [[ "$FIXTURE_DIR" == "${CONTRACTS_DIR}/_verify_translation_split_review"* ]] \
     && [ -d "$FIXTURE_DIR" ]; then
    rm -rf -- "$FIXTURE_DIR"
  fi
}

translation_fixture_dependency_classpath() {
  local raw_classpath dependency_dir entry
  local -a classpath_entries selected
  dependency_dir="$1"
  raw_classpath="$(cd "${REPO_ROOT}/backend" && clojure -Spath -M:cljs)" || return 1

  IFS=':' read -r -a classpath_entries <<< "$raw_classpath"
  selected=("${REPO_ROOT}/backend/scripts" "${REPO_ROOT}/backend/src/cljs" "$dependency_dir")
  for entry in "${classpath_entries[@]}"; do
    case "$entry" in
      *'/malli-'*.jar|*'/dynaload-'*.jar)
        unzip -qo "$entry" -d "$dependency_dir" || return 1
        ;;
      *.jar)
        ;;
      *)
        selected+=("$entry")
        ;;
    esac
  done
  local joined
  joined="$(IFS=:; printf '%s' "${selected[*]}")"
  printf '%s' "$joined"
}

translation_fixture_helper() {
  local action="$1" project_value dependency_dir helper_classpath output helper_status
  dependency_dir="${VERIFY_TMP_DIR}/cljs-deps"
  mkdir -p "$dependency_dir"
  helper_classpath="$(translation_fixture_dependency_classpath "$dependency_dir")" \
    || return 1
  project_value="${VERIFY_PROJECT:-__NONE__}"

  output="$(
    cd "${REPO_ROOT}/backend" || exit 1
    VERIFY_RUN_ID="$RUN_ID" \
    VERIFY_SOURCE_COMMIT="${VERIFY_SOURCE_COMMIT:-}" \
    VERIFY_LEGACY_DOCUMENT_ID="${VERIFY_LEGACY_DOCUMENT_ID:-}" \
    VERIFY_LEGACY_GARDEN_ID="${VERIFY_LEGACY_GARDEN_ID:-__NONE__}" \
    VERIFY_LEGACY_OWNERSHIP_FILE="${VERIFY_LEGACY_OWNERSHIP_FILE:-}" \
    VERIFY_ORG_ID="$VERIFY_ORG_ID" \
    VERIFY_PROJECT="$project_value" \
    VERIFY_DOCUMENT_ID="$TRANSLATION_FIXTURE_DOC_ID" \
    VERIFY_GARDEN_ID="$TRANSLATION_FIXTURE_GARDEN_ID" \
    VERIFY_PUBLICATION_ID="$TRANSLATION_FIXTURE_PUBLICATION_ID" \
    VERIFY_PUBLICATION_PATH="$TRANSLATION_FIXTURE_PUBLICATION_PATH" \
    VERIFY_DOCUMENT_TITLE="$TRANSLATION_FIXTURE_DOCUMENT_TITLE" \
    VERIFY_TARGET_LOCALE="es" \
    VERIFY_SOURCE_FILE="$TRANSLATION_FIXTURE_SOURCE_FILE" \
    KNOXX_PUBLICATION_CONTENT_ROOT="$KNOXX_PUBLICATION_CONTENT_ROOT" \
    ./node_modules/.bin/nbb \
      --classpath "$helper_classpath" \
      "${REPO_ROOT}/backend/scripts/translation_split_review_fixture.cljs" "$action"
  )"
  helper_status=$?
  [ "$helper_status" -eq 0 ] || return "$helper_status"
  printf '%s\n' "$output" | tail -n 1
}

# This phase finishes before translation navigation, interception or resource
# counters. Its independent curl client never inherits the browser's cookies.
LEGACY_SEED_ATTEMPTED=0
LEGACY_CLEANUP_ATTEMPTED=0
LEGACY_CLEANUP_SUCCEEDED=0
LEGACY_SEGMENT_ID=""

translation_fixture_legacy_fail() {
  local label="$1" record="${VERIFY_LEGACY_OWNERSHIP_FILE:-}.failure.json"
  fail "$label"
  if [ "$record" != .failure.json ] && [ -d "$(dirname "$record")" ]; then
    # Preserve the original refusal even when a later explicit cleanup succeeds.
    (set -o noclobber; umask 077
     jq -cn --arg run "$RUN_ID" --arg source "${VERIFY_SOURCE_COMMIT:-}" \
       --arg phase "$label" '{run_id:$run,source_commit:$source,first_failure:$phase}' > "$record") 2>/dev/null || :
  fi
  return 1
}

translation_fixture_legacy_http() {
  local method="$1" path="$2" authorization="$3" body="${4:-}" raw
  local args=(-q -sS --cookie '' --cookie-jar /dev/null -o /dev/stdout
    -w $'\n%{http_code}' -X "$method" --max-time 30)
  [ "${LOOPBACK_HTTP:-0}" -eq 1 ] && args+=(--noproxy '*')
  if [ "$authorization" = auth ]; then
    args+=(-H "X-API-Key: ${API_KEY:-}")
  else
    args+=(-H 'Cookie:' -H 'Authorization:' -H 'X-API-Key:'
      -H 'X-Knoxx-User-Email:' -H 'X-Knoxx-Org-Slug:'
      -H 'X-User-Id:' -H 'X-User-Email:' -H 'X-Org-Id:')
  fi
  [ -z "$body" ] || args+=(-H 'Content-Type: application/json' -d "$body")
  raw="$(curl "${args[@]}" "${BASE_URL}${path}" 2>/dev/null)" || return 1
  printf '%s\n%s' "${raw##*$'\n'}" "${raw%$'\n'*}"
}

translation_fixture_legacy_auth_http() {
  translation_fixture_legacy_http "$1" "$2" auth
}

translation_fixture_legacy_action() {
  local action="$1" output code counts record diagnostic error_file
  error_file="${VERIFY_TMP_DIR}/legacy-helper-error-${BASHPID}"
  (set -o noclobber; umask 077; : > "$error_file") || return 74
  output="$(translation_fixture_helper "$action" 2> "$error_file")"; code=$?
  # Keep actual command status and numeric cleanup/state facts, never an error
  # message, unknown result text, URI, credentials or source/translation bytes.
  counts="$(printf '%s' "$output" | jq -c '
    if type == "object" then to_entries | map(select(
      (.key | IN("segment_count","label_count","label_version_counter",
                 "remaining_segment_count","remaining_label_count",
                 "removed_segment_count","removed_label_count")) and
      (.value | type == "number" and . >= 0 and floor == .))) | from_entries
    else {} end' 2>/dev/null)"
  [ -n "$counts" ] || counts='{}'
  diagnostic="$(tail -n 1 "$error_file" | jq -c '
    if type == "object" and .error == "translation_verification_failed" then
      {error:"translation_verification_failed"} +
      (with_entries(select((.key | IN("primary_thrown","primary_error_present","cleanup_failed")) and
                           (.value | type) == "boolean"))) +
      (if (.code | IN("translation-verification-contract-invalid","translation-verification-contract-unknown",
        "translation-verification-result-action-mismatch","translation-verification-result-scope-mismatch",
        "translation-verification-artifact-path-invalid","translation-verification-resource-action-invalid",
        "verification-setting-missing","verification-resource-invalid","verification-locale-invalid",
        "verification-action-invalid","verification-ownership-file-invalid","verification-ownership-mismatch",
        "verification-owned-row-ambiguous","verification-owned-row-scope-mismatch","verification-owned-row-missing",
        "verification-owned-relation-ambiguous","verification-legacy-collision","verification-legacy-insert-uncertain",
        "verification-owned-delete-uncertain","verification-dispatch-binding-mismatch","verification-legacy-segment-invalid",
        "verification-legacy-normalized-scope-mismatch","verification-primary-and-cleanup-failed","verification-cleanup-failed",
        "native-file-exists","native-path-missing","native-access-refused","native-operation-refused","native-io-failed","unclassified"))
       then {code:.code} else {code:"unclassified"} end) +
      (if (.contract | type) == "string" then
         if (.contract | test("^:knoxx\\.backend\\.law\\.translation-review-verification-fixture/(arguments|action|settings|scope|ownership|request|result|segment-id)$"))
         then {contract:.contract} else {} end
       else {} end)
    else {} end' 2>/dev/null)"
  rm -f -- "$error_file"
  [ -n "$diagnostic" ] || diagnostic='{}'
  record="${VERIFY_LEGACY_OWNERSHIP_FILE}.action-${action}-${BASHPID}.json"
  if ! (set -o noclobber; umask 077
    jq -cn --arg run "$RUN_ID" --arg source "$VERIFY_SOURCE_COMMIT" \
      --arg action "$action" --argjson status "$code" --argjson counts "$counts" --argjson diagnostic "$diagnostic" \
      '{run_id:$run,source_commit:$source,action:$action,native_exit:$status,counts:$counts,diagnostic:$diagnostic}' > "$record"); then
    [ "$code" -ne 0 ] || code=74
  fi
  [ "$code" -eq 0 ] || return "$code"
  printf '%s\n' "$output"
}

translation_fixture_legacy_paths() {
  # Match api.cljs document-path + URLSearchParams, including spaces and nil garden.
  node -e 'const [doc,project,garden,segment]=process.argv.slice(1);
const query=new URLSearchParams({project}); if(garden!=="__NONE__")query.set("garden_id",garden);
const document="/api/translations/documents/"+encodeURIComponent(doc)+"/es";
const list=new URLSearchParams({project,target_lang:"es"});
process.stdout.write(JSON.stringify({detail:document+"?"+query,review:document+"/review",
segment:"/api/translations/segments/"+encodeURIComponent(segment),list:"/api/translations/documents?"+list}));' \
    "$VERIFY_LEGACY_DOCUMENT_ID" "$VERIFY_PROJECT" "$VERIFY_LEGACY_GARDEN_ID" "$LEGACY_SEGMENT_ID"
}

translation_fixture_legacy_result() {
  local output="$1" action="$2" garden
  garden="$(jq -cn --arg value "$VERIFY_LEGACY_GARDEN_ID" 'if $value == "__NONE__" then null else $value end')"
  printf '%s' "$output" | jq -e --arg action "$action" --arg run "$RUN_ID" \
    --arg doc "$VERIFY_LEGACY_DOCUMENT_ID" --arg org "$VERIFY_ORG_ID" \
    --arg project "$VERIFY_PROJECT" --argjson garden "$garden" \
    --arg file "$VERIFY_LEGACY_OWNERSHIP_FILE" --arg segment "$LEGACY_SEGMENT_ID" \
    '.action == $action and .run_id == $run and .document_id == $doc and
     .org_id == $org and .project == $project and .garden_id == $garden and
     .target_lang == "es" and .ownership_file == $file and
     (.segment_id | test("^[0-9a-f]{24}$")) and
     ($segment == "" or .segment_id == $segment)' >/dev/null 2>&1
}

translation_fixture_legacy_snapshot() {
  local detail segment stored detail_body segment_body
  detail="$(translation_fixture_legacy_auth_http GET "$LEGACY_DETAIL_URL")" \
    || { translation_fixture_legacy_fail "legacy authenticated detail readiness unavailable"; return 1; }
  segment="$(translation_fixture_legacy_auth_http GET "$LEGACY_SEGMENT_URL")" \
    || { translation_fixture_legacy_fail "legacy authenticated segment readiness unavailable"; return 1; }
  [ "${detail%%$'\n'*}" = 200 ] && [ "${segment%%$'\n'*}" = 200 ] \
    || { translation_fixture_legacy_fail "legacy authenticated detail/segment readiness refused"; return 1; }
  detail_body="${detail#*$'\n'}"; segment_body="${segment#*$'\n'}"
  if ! printf '%s' "$detail_body" | jq -e --arg doc "$VERIFY_LEGACY_DOCUMENT_ID" \
       --arg segment "$LEGACY_SEGMENT_ID" \
       '.document.id == $doc and (.segments | length) == 1 and
        .segments[0].id == $segment and .summary.total_segments == 1 and
        .summary.pending == 1 and .summary.approved == 0 and
        .summary.rejected == 0 and .summary.in_review == 0' >/dev/null 2>&1; then
    translation_fixture_legacy_fail "legacy live detail does not recover the exact owned fixture"; return 1
  fi
  if ! printf '%s' "$segment_body" | jq -e --arg id "$LEGACY_SEGMENT_ID" \
       --arg doc "$VERIFY_LEGACY_DOCUMENT_ID" --arg org "$VERIFY_ORG_ID" \
       --arg project "$VERIFY_PROJECT" --arg garden "$VERIFY_LEGACY_GARDEN_ID" \
       '.id == $id and .document_id == $doc and .org_id == $org and
        .project == $project and .garden_id == (if $garden == "__NONE__" then null else $garden end) and
        .target_lang == "es" and .status == "pending" and (.labels | type) == "array" and
        (.labels | length) == 0' >/dev/null 2>&1; then
    translation_fixture_legacy_fail "legacy live segment scope or state differs from owned storage"; return 1
  fi
  stored="$(translation_fixture_legacy_action legacy-read)" \
    || { translation_fixture_legacy_fail "legacy exact stored readback unavailable"; return 1; }
  translation_fixture_legacy_result "$stored" legacy-read \
    && printf '%s' "$stored" | jq -e '.segment_count == 1 and .label_count == 0 and
      (.label_version_counter | type) == "number" and .label_version_counter >= 0 and
      (.label_version_counter | floor) == .label_version_counter and
      .status == "pending" and .source_text_matches == true and .translated_text_matches == true' >/dev/null 2>&1 \
    || { translation_fixture_legacy_fail "legacy stored identity/text/counter readback refused"; return 1; }
  LEGACY_SNAPSHOT="$(jq -cnS --argjson detail "$detail_body" --argjson segment "$segment_body" \
    --argjson stored "$stored" '{detail:$detail,segment:$segment,stored:$stored}')" || return 1
}

translation_fixture_legacy_cleanup() {
  local result paths response record ownership_digest
  [ "$LEGACY_SEED_ATTEMPTED" -eq 1 ] || return 0
  if [ "$LEGACY_CLEANUP_ATTEMPTED" -eq 1 ]; then [ "$LEGACY_CLEANUP_SUCCEEDED" -eq 1 ]; return; fi
  LEGACY_CLEANUP_ATTEMPTED=1
  ownership_digest="$(sha256sum -- "$VERIFY_LEGACY_OWNERSHIP_FILE" 2>/dev/null)" \
    || { translation_fixture_legacy_fail "legacy cleanup retained ownership is unavailable"; return 1; }
  result="$(translation_fixture_legacy_action legacy-cleanup)" \
    || { translation_fixture_legacy_fail "legacy cleanup refused; exact durable ownership retained"; return 1; }
  translation_fixture_legacy_result "$result" legacy-cleaned \
    && printf '%s' "$result" | jq -e '.remaining_segment_count == 0 and .remaining_label_count == 0 and
      (.removed_segment_count == 0 or .removed_segment_count == 1) and
      (.removed_label_count | type) == "number" and .removed_label_count >= 0 and
      (.removed_label_count | floor) == .removed_label_count' >/dev/null 2>&1 \
    || { translation_fixture_legacy_fail "legacy cleanup returned unknown or remaining owned state"; return 1; }
  record="${VERIFY_LEGACY_OWNERSHIP_FILE}.cleanup-${BASHPID}.json"
  (set -o noclobber; umask 077; printf '%s\n' "$result" > "$record") \
    || { translation_fixture_legacy_fail "legacy cleanup outcome could not be retained"; return 1; }
  paths="$(translation_fixture_legacy_paths)" || return 1
  response="$(translation_fixture_legacy_auth_http GET "$(printf '%s' "$paths" | jq -r .list)")" \
    || { translation_fixture_legacy_fail "legacy cleanup authenticated list absence unavailable"; return 1; }
  [ "${response%%$'\n'*}" = 200 ] \
    && printf '%s' "${response#*$'\n'}" | jq -e --arg doc "$VERIFY_LEGACY_DOCUMENT_ID" \
      '(.documents | type) == "array" and ([.documents[].document_id] | index($doc)) == null' >/dev/null 2>&1 \
    || { translation_fixture_legacy_fail "legacy cleanup could not prove authenticated document-list absence"; return 1; }
  [ "$ownership_digest" = "$(sha256sum -- "$VERIFY_LEGACY_OWNERSHIP_FILE" 2>/dev/null)" ] \
    || { translation_fixture_legacy_fail "legacy cleanup ownership changed before verified retirement"; return 1; }
  rm -- "$VERIFY_LEGACY_OWNERSHIP_FILE" \
    || { translation_fixture_legacy_fail "legacy verified ownership record could not be retired"; return 1; }
  LEGACY_CLEANUP_SUCCEEDED=1
  LEGACY_SEED_ATTEMPTED=0
  pass "legacy cleanup verified zero owned labels/segments and scoped document-list absence"
}

translation_fixture_legacy_resume_cleanup() {
  local record="$1" stored_org
  [ -f "$record" ] && [ ! -L "$record" ] \
    || { translation_fixture_legacy_fail "legacy cleanup reentry requires the retained regular ownership file"; return 1; }
  VERIFY_LEGACY_OWNERSHIP_FILE="$(realpath -e -- "$record")" || return 1
  stored_org="$(jq -er '.org_id | select(type == "string" and length > 0)' "$record" 2>/dev/null)" \
    || { translation_fixture_legacy_fail "legacy cleanup ownership could not be decoded"; return 1; }
  [ "$stored_org" = "$VERIFY_ORG_ID" ] \
    || { translation_fixture_legacy_fail "legacy cleanup reentry organization differs from selected identity"; return 1; }
  RUN_ID="$(jq -er .run_id "$record" 2>/dev/null)" || return 1
  VERIFY_PROJECT="$(jq -er .project "$record" 2>/dev/null)" || return 1
  VERIFY_LEGACY_DOCUMENT_ID="$(jq -er .document_id "$record" 2>/dev/null)" || return 1
  VERIFY_LEGACY_GARDEN_ID="$(jq -r '.garden_id // "__NONE__"' "$record" 2>/dev/null)" || return 1
  LEGACY_SEGMENT_ID="$(jq -er .segment_id "$record" 2>/dev/null)" || return 1
  [ "$(jq -er .source_commit "$record" 2>/dev/null)" = "${VERIFY_SOURCE_COMMIT:-}" ] \
    && [ "$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null)" = "$VERIFY_SOURCE_COMMIT" ] \
    || { translation_fixture_legacy_fail "legacy cleanup reentry requires the original exact source commit"; return 1; }
  LEGACY_SEED_ATTEMPTED=1
  translation_fixture_legacy_cleanup
}

translation_fixture_legacy_preflight() {
  local seeded paths before response status error label method path payload
  [ -n "${VERIFY_PROJECT//[[:space:]]/}" ] \
    || { translation_fixture_legacy_fail "legacy preflight requires the live nonblank project"; return 1; }
  [[ "${VERIFY_SOURCE_COMMIT:-}" =~ ^[0-9a-f]{40}$ ]] \
    && [ "$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null)" = "$VERIFY_SOURCE_COMMIT" ] \
    || { translation_fixture_legacy_fail "legacy preflight requires this exact source commit"; return 1; }
  VERIFY_LEGACY_DOCUMENT_ID="${VERIFY_LEGACY_DOCUMENT_ID:-${TRANSLATION_FIXTURE_NS}/legacy-auth}"
  VERIFY_LEGACY_GARDEN_ID="${VERIFY_LEGACY_GARDEN_ID:-__NONE__}"
  VERIFY_LEGACY_OWNERSHIP_FILE="${VERIFY_LEGACY_OWNERSHIP_FILE:-${HOME}/.local/state/knoxx-verification/legacy-${RUN_ID}.json}"
  case "$VERIFY_LEGACY_OWNERSHIP_FILE" in
    /*) ;;
    *) translation_fixture_legacy_fail "legacy ownership must be an absolute durable path"; return 1 ;;
  esac
  case "$(realpath -m "$VERIFY_LEGACY_OWNERSHIP_FILE")" in
    "$VERIFY_TMP_DIR"|"$VERIFY_TMP_DIR"/*|"$FIXTURE_DIR"|"$FIXTURE_DIR"/*|"$REPO_ROOT"|"$REPO_ROOT"/*)
      translation_fixture_legacy_fail "legacy ownership must remain outside disposable scratch/fixture/source"; return 1 ;;
  esac
  [ "$VERIFY_LEGACY_DOCUMENT_ID" != "$TRANSLATION_FIXTURE_DOC_ID" ] \
    && [[ "$VERIFY_LEGACY_DOCUMENT_ID" == "${TRANSLATION_FIXTURE_NS}/"* ]] \
    || { translation_fixture_legacy_fail "legacy fixture document must be distinct and run-owned"; return 1; }
  [ ! -e "$VERIFY_LEGACY_OWNERSHIP_FILE" ] && [ ! -L "$VERIFY_LEGACY_OWNERSHIP_FILE" ] \
    && [ ! -e "${VERIFY_LEGACY_OWNERSHIP_FILE}.failure.json" ] \
    || { translation_fixture_legacy_fail "legacy ownership/evidence already exists; use exact recovery or a fresh run path"; return 1; }
  mkdir -p -m 700 "$(dirname "$VERIFY_LEGACY_OWNERSHIP_FILE")" || return 1
  LEGACY_SEED_ATTEMPTED=1
  seeded="$(translation_fixture_legacy_action legacy-seed)" \
    || { translation_fixture_legacy_fail "legacy seed refused; trap retains exact partial ownership"; return 1; }
  translation_fixture_legacy_result "$seeded" legacy-seeded \
    && printf '%s' "$seeded" | jq -e '.segment_count == 1 and .label_count == 0 and
      .label_version_counter == 0 and .status == "pending" and
      .source_text_matches == true and .translated_text_matches == true' >/dev/null 2>&1 \
    || { translation_fixture_legacy_fail "legacy seed did not establish one exact pending unlabeled segment"; return 1; }
  LEGACY_SEGMENT_ID="$(printf '%s' "$seeded" | jq -r .segment_id)"
  paths="$(translation_fixture_legacy_paths)" || return 1
  LEGACY_DETAIL_URL="$(printf '%s' "$paths" | jq -r .detail)"
  LEGACY_SEGMENT_URL="$(printf '%s' "$paths" | jq -r .segment)"
  translation_fixture_legacy_snapshot || return 1
  pass "legacy authenticated exact detail/segment readiness and stored hidden counter established"
  for label in 'legacy detail GET' 'legacy segment-label POST' 'legacy document-review POST'; do
    translation_fixture_legacy_snapshot || return 1
    before="$LEGACY_SNAPSHOT"; payload=""
    case "$label" in
      'legacy detail GET') method=GET; path="$LEGACY_DETAIL_URL" ;;
      'legacy segment-label POST')
        method=POST; path="${LEGACY_SEGMENT_URL}/labels"
        payload="$(jq -cn --arg project "$VERIFY_PROJECT" --arg garden "$VERIFY_LEGACY_GARDEN_ID" \
          '{overall:"reject",adequacy:"good",fluency:"good",terminology:"correct",risk:"safe",project:$project} |
           if $garden == "__NONE__" then . else .+{garden_id:$garden} end')" ;;
      *) method=POST; path="$(printf '%s' "$paths" | jq -r .review)"
        payload="$(jq -cn --arg project "$VERIFY_PROJECT" --arg garden "$VERIFY_LEGACY_GARDEN_ID" \
          '{overall:"reject",project:$project} | if $garden == "__NONE__" then . else .+{garden_id:$garden} end')" ;;
    esac
    response="$(translation_fixture_legacy_http "$method" "$path" anon "$payload")" \
      || { translation_fixture_legacy_fail "$label transport unavailable"; return 1; }
    status="${response%%$'\n'*}"
    error="$(printf '%s' "${response#*$'\n'}" | jq -r 'if .error_code == "no_session" then "no_session" else "unexpected_error" end' 2>/dev/null)"
    [ "$status" = 401 ] && [ "$error" = no_session ] \
      || { translation_fixture_legacy_fail "$label requires 401/no_session (status ${status:-unknown})"; return 1; }
    translation_fixture_legacy_snapshot || return 1
    [ "$before" = "$LEGACY_SNAPSHOT" ] \
      || { translation_fixture_legacy_fail "$label changed authenticated state or stored hidden counter"; return 1; }
    pass "$label 401/no_session; authenticated state and stored hidden counter unchanged"
  done
  translation_fixture_legacy_cleanup
}

# Core5-specific real probes. Resource fixture and fixture5 cleanup remain owned
# by the existing tour; no authenticated dispatch/provider call is made here.
translation_fixture_core_auth_http() {
  translation_fixture_legacy_auth_http "$1" "$2"
}

translation_fixture_core_snapshot() {
  local value
  value="$(translation_fixture_helper dispatch-read)" || return 1
  printf '%s' "$value" | jq -ce --arg run "$RUN_ID" --arg org "$VERIFY_ORG_ID" \
    --arg project "$VERIFY_PROJECT" --arg doc "$TRANSLATION_FIXTURE_DOC_ID" '
    select(.action == "dispatch-read" and .run_id == $run and .org_id == $org
      and .project == $project and .document_id == $doc
      and (.source_revision | type == "string" and length > 0)
      and (.state_sha256 | type == "string" and test("^[0-9a-f]{64}$")))
    | select([.dispatch_count,.claim_count,.batch_count,.receipt_count]
      | all(type == "number" and . >= 0 and floor == .))
    | {source_revision,state_sha256,dispatch_count,claim_count,batch_count,receipt_count}' 2>/dev/null
}

translation_fixture_core_preflight() {
  local inventory="$1" before after context ready sft_path response status proof payload
  [[ "${VERIFY_SOURCE_COMMIT:-}" =~ ^[0-9a-f]{40}$ ]] && [ -n "${VERIFY_PROJECT:-}" ] \
    || { fail 'core source/project precondition unavailable'; return 1; }
  printf '%s' "$inventory" | jq -e --arg p "$VERIFY_PROJECT" --arg id "$TRANSLATION_FIXTURE_PUBLICATION_ID" \
    '.project == $p and any(.reviews[]; .publication == $id)' >/dev/null 2>&1 \
    || { fail 'core exact owned publication inventory unavailable'; return 1; }
  context="$(translation_fixture_core_auth_http GET /api/auth/context)" \
    || { fail 'core authenticated policy readiness unavailable'; return 1; }
  [ "${context%%$'\n'*}" = 200 ] \
    && printf '%s' "${context#*$'\n'}" | jq -e --arg org "$VERIFY_ORG_ID" '.org.id == $org' >/dev/null 2>&1 \
    || { fail 'core authenticated policy readiness refused'; return 1; }
  ready="$(translation_fixture_core_auth_http GET /api/publications/translations/reviews)" \
    || { fail 'core dispatch route/store inventory unavailable'; return 1; }
  [ "${ready%%$'\n'*}" = 200 ] \
    && printf '%s' "${ready#*$'\n'}" | jq -e --arg p "$VERIFY_PROJECT" --arg id "$TRANSLATION_FIXTURE_PUBLICATION_ID" \
      '.project == $p and any(.reviews[]; .publication == $id)' >/dev/null 2>&1 \
    || { fail 'core dispatch owned readiness refused'; return 1; }
  before="$(translation_fixture_core_snapshot)" || { fail 'core scoped hidden snapshot unavailable'; return 1; }
  sft_path="/api/translations/export/sft?project=$(jq -rn --arg p "$VERIFY_PROJECT" '$p|@uri')&include_corrected=true"
  ready="$(translation_fixture_core_auth_http GET "$sft_path")" \
    || { fail 'core authenticated SFT readiness unavailable'; return 1; }
  [ "${ready%%$'\n'*}" = 200 ] || { fail 'core authenticated SFT readiness refused'; return 1; }
  payload="$(jq -cn --arg id "$TRANSLATION_FIXTURE_PUBLICATION_ID" '{publication:$id}')"
  local method endpoint body label
  for label in sft dispatch; do
    method=GET; endpoint="$sft_path"; body=''
    if [ "$label" = dispatch ]; then method=POST; endpoint=/api/publications/translations/dispatch; body="$payload"; fi
    response="$(translation_fixture_legacy_http "$method" "$endpoint" anonymous "$body")" \
      || { fail "core anonymous $label transport unavailable"; return 1; }
    status="${response%%$'\n'*}"
    [ "$status" = 401 ] \
      && printf '%s' "${response#*$'\n'}" | jq -e '.error_code == "no_session"' >/dev/null 2>&1 \
      || { fail "core anonymous $label requires401/no_session (status ${status:-unknown})"; return 1; }
  done
  after="$(translation_fixture_core_snapshot)" || { fail 'core post-probe hidden snapshot unavailable'; return 1; }
  [ "$(printf '%s' "$before" | jq -Sc .)" = "$(printf '%s' "$after" | jq -Sc .)" ] \
    || { fail 'core auth probe changed owned hidden dispatch state'; return 1; }
  proof="${VERIFY_CORE_PREFLIGHT_FILE:-${VERIFY_TMP_DIR}/core-auth-preflight.json}"
  [[ "$proof" = /* ]] && [ -d "$(dirname "$proof")" ] && [ ! -e "$proof" ] && [ ! -L "$proof" ] \
    || { fail 'core private preflight output is not fresh'; return 1; }
  (set -o noclobber; umask 077
   jq -cn --arg source "$VERIFY_SOURCE_COMMIT" --arg run "$RUN_ID" --arg org "$VERIFY_ORG_ID" \
     --arg project "$VERIFY_PROJECT" --argjson snapshot "$before" '
     {source_commit:$source,run_id:$run,org_id:$org,project:$project,
      sft_anonymous_status:401,dispatch_anonymous_status:401,state_unchanged:true,
      state_sha256:$snapshot.state_sha256,writer_call_level_proof:false}' > "$proof") \
    || { fail 'core private preflight retention failed'; return 1; }
  pass 'core real SFT GET and publication-only dispatch POST401/no_session; scoped hidden state unchanged'
}
