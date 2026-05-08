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
  cat <<'JSON'
{
  "nodeId": "node-a",
  "operationId": "node_drain:node-a",
  "result": "complete",
  "terminationAllowed": true,
  "exitCode": 0,
  "lastStatus": "complete",
  "lastNextAction": "none",
  "lastReadinessReason": "ready",
  "remainingSessions": 0
}
JSON
}

test_ready_allows_termination() {
  new_case "ready"
  write_input "$(ready_json)"

  run_decision

  assert_eq "ready" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "true" "$(jq -r '.terminationAllowed' "$CASE_DIR/result.json")" "terminationAllowed"
  assert_eq "terminate_node" "$(jq -r '.recommendedAction' "$CASE_DIR/result.json")" "recommendedAction"
  assert_eq "0" "$(jq -r '.guards | map(select(.passed == false)) | length' "$CASE_DIR/result.json")" "failed guard count"
  assert_eq "ready" "$(jq -r '.result' "$CASE_DIR/stdout.json")" "stdout result"
}

test_remaining_sessions_blocks_termination() {
  new_case "remaining"
  ready_json | jq '.remainingSessions = 3' > "$CASE_DIR/input.json"

  set +e
  run_decision
  exit_code=$?
  set -e

  assert_eq "2" "$exit_code" "exit code"
  assert_eq "not_ready" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "false" "$(jq -r '.terminationAllowed' "$CASE_DIR/result.json")" "terminationAllowed"
  assert_eq "wait" "$(jq -r '.recommendedAction' "$CASE_DIR/result.json")" "recommendedAction"
  assert_eq "false" "$(jq -r '.guards[] | select(.name == "remaining_sessions_zero") | .passed' "$CASE_DIR/result.json")" "remaining guard"
}

test_status_blocks_termination() {
  new_case "status"
  ready_json | jq '.lastStatus = "sessions_remaining" | .lastNextAction = "retry_reconnect"' > "$CASE_DIR/input.json"

  set +e
  run_decision
  exit_code=$?
  set -e

  assert_eq "2" "$exit_code" "exit code"
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

  assert_eq "2" "$exit_code" "exit code"
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

  assert_eq "6" "$exit_code" "exit code"
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

  assert_eq "6" "$exit_code" "exit code"
  assert_eq "unexpected_input" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
}

test_missing_arguments_are_usage_error() {
  new_case "missing-args"

  set +e
  "$SCRIPT" --node-id node-a > "$CASE_DIR/stdout.txt" 2> "$CASE_DIR/stderr.txt"
  exit_code=$?
  set -e

  assert_eq "1" "$exit_code" "exit code"
}

test_ready_allows_termination
test_remaining_sessions_blocks_termination
test_status_blocks_termination
test_node_mismatch_is_unsafe
test_invalid_json_is_unexpected_input
test_missing_required_field_is_unexpected_input
test_missing_arguments_are_usage_error

echo "openchat node termination decision tests passed"
