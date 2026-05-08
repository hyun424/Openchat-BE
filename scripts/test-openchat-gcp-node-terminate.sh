#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/openchat-gcp-node-terminate.sh"

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
  CASE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/openchat-gcp-node-terminate-$name.XXXXXX")"
  mkdir -p "$CASE_DIR/bin"
  export CASE_DIR
  export FAKE_GCLOUD_CALLS="$CASE_DIR/gcloud.calls"
  export FAKE_GCLOUD_STOP_MARKER="$CASE_DIR/stopped"
  export FAKE_GCLOUD_LIST_JSON="$CASE_DIR/list.json"
  export FAKE_GCLOUD_DESCRIBE_JSON="$CASE_DIR/describe.json"
  make_fake_gcloud
  export PATH="$CASE_DIR/bin:$PATH"
}

make_fake_gcloud() {
  cat > "$CASE_DIR/bin/gcloud" <<'FAKE_GCLOUD'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$*" >> "$FAKE_GCLOUD_CALLS"

if [ "${FAKE_GCLOUD_FAIL:-false}" = "true" ]; then
  echo "forced fake gcloud failure" >&2
  exit 7
fi

args="$*"
if [[ "$args" == *"compute instances list"* ]]; then
  cat "$FAKE_GCLOUD_LIST_JSON"
  exit 0
fi

if [[ "$args" == *"compute instances describe"* ]]; then
  if [ -f "$FAKE_GCLOUD_STOP_MARKER" ]; then
    jq '.status = "TERMINATED"' "$FAKE_GCLOUD_DESCRIBE_JSON"
  else
    cat "$FAKE_GCLOUD_DESCRIBE_JSON"
  fi
  exit 0
fi

if [[ "$args" == *"compute instances stop"* ]]; then
  printf 'stopped' > "$FAKE_GCLOUD_STOP_MARKER"
  exit 0
fi

echo "unsupported fake gcloud call: $args" >&2
exit 8
FAKE_GCLOUD
  chmod +x "$CASE_DIR/bin/gcloud"
}

write_decision() {
  local body="$1"
  printf '%s\n' "$body" > "$CASE_DIR/decision.json"
}

ready_decision_json() {
  cat <<'JSON'
{
  "contractVersion": "openchat.node-termination-decision.v1",
  "result": "ready",
  "terminationAllowed": true,
  "recommendedAction": "terminate_node",
  "nodeId": "gcp-realtime-2",
  "remainingSessions": 0
}
JSON
}

write_instance() {
  local name="${1:-openchat-lt-run-realtime-2}"
  local status="${2:-RUNNING}"
  local run_id="${3:-20260508-gcp-vm-termination-adapter-smoke}"
  local role="${4:-realtime}"
  local app_index="${5:-2}"
  jq -n \
    --arg name "$name" \
    --arg status "$status" \
    --arg runId "$run_id" \
    --arg role "$role" \
    --arg appIndex "$app_index" \
    '{name: $name, status: $status, labels: {run_id: $runId, role: $role, app_index: $appIndex}}' \
    > "$CASE_DIR/describe.json"
  jq -s '.' "$CASE_DIR/describe.json" > "$CASE_DIR/list.json"
}

run_adapter() {
  "$SCRIPT" \
    --decision "$CASE_DIR/decision.json" \
    --project openchat-495102 \
    --zone asia-northeast3-a \
    --run-id 20260508-gcp-vm-termination-adapter-smoke \
    --node-id gcp-realtime-2 \
    --output "$CASE_DIR/result.json" \
    "$@" > "$CASE_DIR/stdout.json"
}

test_dry_run_ready_does_not_stop() {
  new_case "dry-run"
  write_decision "$(ready_decision_json)"
  write_instance

  run_adapter --mode dry-run

  assert_eq "dry_run_ready" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "false" "$(jq -r '.terminationPerformed' "$CASE_DIR/result.json")" "terminationPerformed"
  assert_eq "RUNNING" "$(jq -r '.beforeStatus' "$CASE_DIR/result.json")" "beforeStatus"
  assert_eq "RUNNING" "$(jq -r '.afterStatus' "$CASE_DIR/result.json")" "afterStatus"
  if grep -q "compute instances stop" "$FAKE_GCLOUD_CALLS"; then
    fail "dry-run should not call gcloud stop"
  fi
}

test_stop_ready_stops_instance() {
  new_case "stop"
  write_decision "$(ready_decision_json)"
  write_instance

  run_adapter --mode stop

  assert_eq "stopped" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "true" "$(jq -r '.terminationPerformed' "$CASE_DIR/result.json")" "terminationPerformed"
  assert_eq "RUNNING" "$(jq -r '.beforeStatus' "$CASE_DIR/result.json")" "beforeStatus"
  assert_eq "TERMINATED" "$(jq -r '.afterStatus' "$CASE_DIR/result.json")" "afterStatus"
  grep -q "compute instances stop" "$FAKE_GCLOUD_CALLS" || fail "stop call missing"
}

test_not_ready_decision_is_blocked() {
  new_case "not-ready"
  ready_decision_json | jq '.result = "not_ready" | .terminationAllowed = false | .recommendedAction = "wait"' > "$CASE_DIR/decision.json"
  write_instance

  set +e
  run_adapter --mode stop
  exit_code=$?
  set -e

  assert_eq "10" "$exit_code" "exit code"
  assert_eq "blocked" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
}

test_node_mismatch_is_unsafe() {
  new_case "node-mismatch"
  ready_decision_json | jq '.nodeId = "gcp-realtime-1"' > "$CASE_DIR/decision.json"
  write_instance

  set +e
  run_adapter --mode stop
  exit_code=$?
  set -e

  assert_eq "20" "$exit_code" "exit code"
  assert_eq "unsafe" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "false" "$(jq -r '.guards[] | select(.name == "decision_node_id_match") | .passed' "$CASE_DIR/result.json")" "node guard"
}

test_run_id_mismatch_is_unsafe() {
  new_case "run-id-mismatch"
  write_decision "$(ready_decision_json)"
  write_instance "openchat-lt-other-realtime-2" "RUNNING" "other-run" "realtime" "2"

  set +e
  run_adapter --mode stop
  exit_code=$?
  set -e

  assert_eq "20" "$exit_code" "exit code"
  assert_eq "unsafe" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "false" "$(jq -r '.guards[] | select(.name == "instance_run_id_match") | .passed' "$CASE_DIR/result.json")" "run guard"
}

test_role_mismatch_is_unsafe() {
  new_case "role-mismatch"
  write_decision "$(ready_decision_json)"
  write_instance "openchat-lt-run-api-2" "RUNNING" "20260508-gcp-vm-termination-adapter-smoke" "api" "2"

  set +e
  run_adapter --mode stop
  exit_code=$?
  set -e

  assert_eq "20" "$exit_code" "exit code"
  assert_eq "unsafe" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "false" "$(jq -r '.guards[] | select(.name == "instance_role_realtime") | .passed' "$CASE_DIR/result.json")" "role guard"
}

test_app_index_mismatch_is_unsafe() {
  new_case "app-index-mismatch"
  write_decision "$(ready_decision_json)"
  write_instance "openchat-lt-run-realtime-1" "RUNNING" "20260508-gcp-vm-termination-adapter-smoke" "realtime" "1"

  set +e
  run_adapter --mode stop
  exit_code=$?
  set -e

  assert_eq "20" "$exit_code" "exit code"
  assert_eq "unsafe" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "false" "$(jq -r '.guards[] | select(.name == "instance_app_index_match") | .passed' "$CASE_DIR/result.json")" "app index guard"
}

test_instance_not_found_is_gcp_failure() {
  new_case "not-found"
  write_decision "$(ready_decision_json)"
  printf '[]\n' > "$CASE_DIR/list.json"
  write_instance

  set +e
  run_adapter --mode stop
  exit_code=$?
  set -e

  assert_eq "40" "$exit_code" "exit code"
  assert_eq "gcp_failure" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
}

test_invalid_decision_json_is_unexpected_input() {
  new_case "invalid-decision"
  printf '{bad json\n' > "$CASE_DIR/decision.json"
  write_instance

  set +e
  run_adapter --mode stop
  exit_code=$?
  set -e

  assert_eq "31" "$exit_code" "exit code"
  assert_eq "unexpected_input" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
}

test_missing_args_are_usage_error() {
  new_case "missing-args"

  set +e
  "$SCRIPT" --project openchat-495102 > "$CASE_DIR/stdout.txt" 2> "$CASE_DIR/stderr.txt"
  exit_code=$?
  set -e

  assert_eq "30" "$exit_code" "exit code"
}

test_dry_run_ready_does_not_stop
test_stop_ready_stops_instance
test_not_ready_decision_is_blocked
test_node_mismatch_is_unsafe
test_run_id_mismatch_is_unsafe
test_role_mismatch_is_unsafe
test_app_index_mismatch_is_unsafe
test_instance_not_found_is_gcp_failure
test_invalid_decision_json_is_unexpected_input
test_missing_args_are_usage_error

echo "openchat gcp node terminate tests passed"
