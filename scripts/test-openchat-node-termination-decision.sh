#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/openchat-node-termination-decision.sh"

fail() {
  echo "[FAIL] $*" >&2
  exit 1
}

assert_eq() {
  local expected="$1"
  local actual="$2"
  local message="$3"
  if [ "$expected" != "$actual" ]; then
    fail "$message expected=$expected actual=$actual"
  fi
}

new_case() {
  local name="$1"
  CASE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/openchat-node-termination-$name.XXXXXX")"
  export CASE_DIR
}

write_input() {
  local body="$1"
  printf '%s\n' "$body" > "$CASE_DIR/input.json"
}

run_decision() {
  "$SCRIPT" --input "$CASE_DIR/input.json" --node-id node-a --output "$CASE_DIR/result.json" > "$CASE_DIR/stdout.json"
}

ready_json() {
  local completed_at
  completed_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  cat <<'JSON' | sed "s/__COMPLETED_AT__/$completed_at/g"
{
  "nodeId": "node-a",
  "operationId": "node_drain:node-a",
  "result": "complete",
  "terminationAllowed": true,
  "exitCode": 0,
  "lastStatus": "complete",
  "lastNextAction": "none",
  "lastReadinessReason": "ready",
  "remainingSessions": 0,
  "completedAt": "__COMPLETED_AT__"
}
JSON
}

test_ready_allows_termination() {
  new_case "ready"
  write_input "$(ready_json)"

  run_decision

  assert_eq "ready" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "openchat.node-termination-decision.v1" "$(jq -r '.contractVersion' "$CASE_DIR/result.json")" "contractVersion"
  assert_eq "true" "$(jq -r '.terminationAllowed' "$CASE_DIR/result.json")" "terminationAllowed"
  assert_eq "terminate_node" "$(jq -r '.recommendedAction' "$CASE_DIR/result.json")" "recommendedAction"
  assert_eq "0" "$(jq -r '.guards | map(select(.passed == false)) | length' "$CASE_DIR/result.json")" "failed guard count"
  assert_eq "ready" "$(jq -r '.result' "$CASE_DIR/stdout.json")" "stdout result"
}

test_remaining_sessions_blocks_termination() {
  new_case "remaining"
  ready_json | jq '.result = "timeout" | .terminationAllowed = false | .exitCode = 3 | .lastStatus = "sessions_remaining" | .lastNextAction = "retry_reconnect" | .remainingSessions = 3' > "$CASE_DIR/input.json"

  set +e
  run_decision
  exit_code=$?
  set -e

  assert_eq "10" "$exit_code" "exit code"
  assert_eq "not_ready" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "false" "$(jq -r '.terminationAllowed' "$CASE_DIR/result.json")" "terminationAllowed"
  assert_eq "wait" "$(jq -r '.recommendedAction' "$CASE_DIR/result.json")" "recommendedAction"
  assert_eq "false" "$(jq -r '.guards[] | select(.name == "remaining_sessions_zero") | .passed' "$CASE_DIR/result.json")" "remaining guard"
}

test_status_blocks_termination() {
  new_case "status"
  ready_json | jq '.result = "timeout" | .terminationAllowed = false | .exitCode = 3 | .lastStatus = "sessions_remaining" | .lastNextAction = "retry_reconnect"' > "$CASE_DIR/input.json"

  set +e
  run_decision
  exit_code=$?
  set -e

  assert_eq "10" "$exit_code" "exit code"
  assert_eq "not_ready" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "retry_reconnect" "$(jq -r '.sourceNextAction' "$CASE_DIR/result.json")" "sourceNextAction"
}

test_node_mismatch_is_unsafe() {
  new_case "mismatch"
  ready_json | jq '.nodeId = "node-b"' > "$CASE_DIR/input.json"

  set +e
  run_decision
  exit_code=$?
  set -e

  assert_eq "20" "$exit_code" "exit code"
  assert_eq "unsafe" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "investigate" "$(jq -r '.recommendedAction' "$CASE_DIR/result.json")" "recommendedAction"
  assert_eq "false" "$(jq -r '.guards[] | select(.name == "node_id_match") | .passed' "$CASE_DIR/result.json")" "node guard"
}

test_invalid_json_is_unexpected_input() {
  new_case "invalid-json"
  printf '{bad json\n' > "$CASE_DIR/input.json"

  set +e
  run_decision
  exit_code=$?
  set -e

  assert_eq "31" "$exit_code" "exit code"
  assert_eq "unexpected_input" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "fix_input" "$(jq -r '.recommendedAction' "$CASE_DIR/result.json")" "recommendedAction"
}

test_missing_required_field_is_unexpected_input() {
  new_case "missing-field"
  ready_json | jq 'del(.lastNextAction)' > "$CASE_DIR/input.json"

  set +e
  run_decision
  exit_code=$?
  set -e

  assert_eq "31" "$exit_code" "exit code"
  assert_eq "unexpected_input" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
}

test_missing_arguments_are_usage_error() {
  new_case "missing-args"

  set +e
  "$SCRIPT" --node-id node-a > "$CASE_DIR/stdout.txt" 2> "$CASE_DIR/stderr.txt"
  exit_code=$?
  set -e

  assert_eq "30" "$exit_code" "exit code"
}

test_nonzero_source_exit_code_is_unsafe() {
  new_case "source-exit"
  ready_json | jq '.exitCode = 4' > "$CASE_DIR/input.json"

  set +e
  run_decision
  exit_code=$?
  set -e

  assert_eq "20" "$exit_code" "exit code"
  assert_eq "unsafe" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "false" "$(jq -r '.guards[] | select(.name == "source_exit_code_zero") | .passed' "$CASE_DIR/result.json")" "exit guard"
}

test_stale_ready_result_is_unsafe() {
  new_case "stale"
  ready_json | jq '.completedAt = "2020-01-01T00:00:00Z"' > "$CASE_DIR/input.json"

  set +e
  "$SCRIPT" --input "$CASE_DIR/input.json" --node-id node-a --max-age-seconds 60 \
    --output "$CASE_DIR/result.json" > "$CASE_DIR/stdout.json"
  exit_code=$?
  set -e

  assert_eq "20" "$exit_code" "exit code"
  assert_eq "unsafe" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "false" "$(jq -r '.guards[] | select(.name == "result_fresh") | .passed' "$CASE_DIR/result.json")" "freshness guard"
}

test_future_completed_at_is_unsafe() {
  new_case "future"
  future_completed_at="$(date -u -d '+1 hour' +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || date -u -v+1H +"%Y-%m-%dT%H:%M:%SZ")"
  ready_json | jq --arg completedAt "$future_completed_at" '.completedAt = $completedAt' > "$CASE_DIR/input.json"

  set +e
  "$SCRIPT" --input "$CASE_DIR/input.json" --node-id node-a --max-age-seconds 600 \
    --output "$CASE_DIR/result.json" > "$CASE_DIR/stdout.json"
  exit_code=$?
  set -e

  assert_eq "20" "$exit_code" "exit code"
  assert_eq "unsafe" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "false" "$(jq -r '.guards[] | select(.name == "result_fresh") | .passed' "$CASE_DIR/result.json")" "freshness guard"
}

test_unknown_source_result_is_unexpected_input() {
  new_case "unknown-result"
  ready_json | jq '.result = "weird"' > "$CASE_DIR/input.json"

  set +e
  run_decision
  exit_code=$?
  set -e

  assert_eq "31" "$exit_code" "exit code"
  assert_eq "unexpected_input" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
}

test_ready_allows_termination
test_remaining_sessions_blocks_termination
test_status_blocks_termination
test_node_mismatch_is_unsafe
test_invalid_json_is_unexpected_input
test_missing_required_field_is_unexpected_input
test_missing_arguments_are_usage_error
test_nonzero_source_exit_code_is_unsafe
test_stale_ready_result_is_unsafe
test_future_completed_at_is_unsafe
test_unknown_source_result_is_unexpected_input

echo "openchat node termination decision tests passed"
