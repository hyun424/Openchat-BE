#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/openchat-node-drain-orchestrate.sh"

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

make_fake_curl() {
  local case_dir="$1"
  mkdir -p "$case_dir/bin"
  cat > "$case_dir/bin/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail

state_file="$FAKE_CURL_STATE"
responses_dir="$FAKE_CURL_RESPONSES"
calls_file="$FAKE_CURL_CALLS"

count=0
if [ -f "$state_file" ]; then
  count="$(cat "$state_file")"
fi
count=$((count + 1))
printf '%s' "$count" > "$state_file"
printf '%s\n' "$*" >> "$calls_file"

response_file="$responses_dir/$count.json"
if [ ! -f "$response_file" ]; then
  response_file="$responses_dir/last.json"
fi
if [ ! -f "$response_file" ]; then
  echo "missing fake response $count" >&2
  exit 7
fi
cat "$response_file"
FAKE_CURL
  chmod +x "$case_dir/bin/curl"
}

run_case() {
  local name="$1"
  local case_dir
  case_dir="$(mktemp -d "${TMPDIR:-/tmp}/openchat-node-drain-$name.XXXXXX")"
  mkdir -p "$case_dir/responses"
  make_fake_curl "$case_dir"
  export FAKE_CURL_STATE="$case_dir/state"
  export FAKE_CURL_RESPONSES="$case_dir/responses"
  export FAKE_CURL_CALLS="$case_dir/calls"
  export PATH="$case_dir/bin:$PATH"
  export CASE_DIR="$case_dir"
}

write_response() {
  local index="$1"
  local body="$2"
  printf '%s\n' "$body" > "$CASE_DIR/responses/$index.json"
}

test_reconnect_retry_then_complete() {
  run_case "retry-complete"
  write_response 1 '{"nodeId":"node-a","operationId":"node_drain:node-a","draining":true,"status":"reconnect_published","reconnectPublished":true,"targetedSessions":47,"remainingSessions":47,"reason":"node_drain","retryable":true,"nextAction":"poll_status","readinessReason":"ready","commandId":"reconnect-a"}'
  write_response 2 '{"nodeId":"node-a","operationId":"node_drain:node-a","draining":true,"status":"sessions_remaining","reconnectPublished":false,"targetedSessions":0,"remainingSessions":47,"reason":"node_drain","retryable":true,"nextAction":"retry_reconnect","readinessReason":"ready"}'
  write_response 3 '{"nodeId":"node-a","operationId":"node_drain:node-a","draining":true,"status":"reconnect_published","reconnectPublished":true,"targetedSessions":47,"remainingSessions":47,"reason":"node_drain","retryable":true,"nextAction":"poll_status","readinessReason":"ready","commandId":"reconnect-b"}'
  write_response 4 '{"nodeId":"node-a","operationId":"node_drain:node-a","draining":true,"status":"complete","reconnectPublished":false,"targetedSessions":0,"remainingSessions":0,"reason":"node_drain","retryable":false,"nextAction":"none","readinessReason":"ready"}'

  "$SCRIPT" --base-url http://openchat.internal --node-id node-a --token test-token \
    --timeout-seconds 5 --poll-interval-ms 0 --max-reconnect-attempts 2 \
    --output "$CASE_DIR/result.json" > "$CASE_DIR/stdout.json"

  assert_eq "complete" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "true" "$(jq -r '.terminationAllowed' "$CASE_DIR/result.json")" "terminationAllowed"
  assert_eq "complete" "$(jq -r '.lastStatus' "$CASE_DIR/result.json")" "lastStatus"
  assert_eq "1" "$(jq -r '.reconnectAttempts' "$CASE_DIR/result.json")" "reconnectAttempts"
  assert_eq "4" "$(jq -r '.history | length' "$CASE_DIR/result.json")" "history length"
  assert_eq "2" "$(jq -r '.reconnectCommandIds | length' "$CASE_DIR/result.json")" "reconnectCommandIds length"
  assert_eq "reconnect-a,reconnect-b" "$(jq -r '.reconnectCommandIds | join(",")' "$CASE_DIR/result.json")" "reconnectCommandIds"
}

test_blocked_last_active_node() {
  run_case "blocked"
  write_response 1 '{"nodeId":"node-a","operationId":"node_drain:node-a","draining":false,"status":"last_active_node","reconnectPublished":false,"targetedSessions":0,"remainingSessions":10,"reason":"node_drain","retryable":false,"nextAction":"add_replacement_node","readinessReason":null}'

  set +e
  "$SCRIPT" --base-url http://openchat.internal --node-id node-a --token test-token \
    --timeout-seconds 5 --poll-interval-ms 0 --output "$CASE_DIR/result.json" > "$CASE_DIR/stdout.json"
  exit_code=$?
  set -e

  assert_eq "2" "$exit_code" "exit code"
  assert_eq "blocked" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "false" "$(jq -r '.terminationAllowed' "$CASE_DIR/result.json")" "terminationAllowed"
  assert_eq "last_active_node" "$(jq -r '.lastStatus' "$CASE_DIR/result.json")" "lastStatus"
}

test_invalid_response_shape() {
  run_case "invalid-json"
  write_response 1 '{"status":"complete"}'

  set +e
  "$SCRIPT" --base-url http://openchat.internal --node-id node-a --token test-token \
    --timeout-seconds 5 --poll-interval-ms 0 --output "$CASE_DIR/result.json" > "$CASE_DIR/stdout.json"
  exit_code=$?
  set -e

  assert_eq "6" "$exit_code" "exit code"
  assert_eq "unexpected_response" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "false" "$(jq -r '.terminationAllowed' "$CASE_DIR/result.json")" "terminationAllowed"
}

test_unknown_node_timeout() {
  run_case "timeout"
  write_response last '{"nodeId":"node-a","operationId":"node_drain:node-a","draining":false,"status":"unknown_node","reconnectPublished":false,"targetedSessions":0,"remainingSessions":0,"reason":"node_drain","retryable":true,"nextAction":"wait_node_heartbeat","readinessReason":null}'

  set +e
  "$SCRIPT" --base-url http://openchat.internal --node-id node-a --token test-token \
    --mode status-only --timeout-seconds 0 --poll-interval-ms 0 --output "$CASE_DIR/result.json" > "$CASE_DIR/stdout.json"
  exit_code=$?
  set -e

  assert_eq "3" "$exit_code" "exit code"
  assert_eq "timeout" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "unknown_node" "$(jq -r '.lastStatus' "$CASE_DIR/result.json")" "lastStatus"
}

test_sleep_crossing_deadline_times_out_without_extra_poll() {
  run_case "deadline"
  write_response 1 '{"nodeId":"node-a","operationId":"node_drain:node-a","draining":true,"status":"reconnect_published","reconnectPublished":true,"targetedSessions":10,"remainingSessions":10,"reason":"node_drain","retryable":true,"nextAction":"poll_status","readinessReason":"ready","commandId":"reconnect-deadline"}'
  write_response 2 '{"nodeId":"node-a","operationId":"node_drain:node-a","draining":true,"status":"complete","reconnectPublished":false,"targetedSessions":0,"remainingSessions":0,"reason":"node_drain","retryable":false,"nextAction":"none","readinessReason":"ready"}'

  set +e
  "$SCRIPT" --base-url http://openchat.internal --node-id node-a --token test-token \
    --timeout-seconds 1 --poll-interval-ms 1100 --output "$CASE_DIR/result.json" > "$CASE_DIR/stdout.json"
  exit_code=$?
  set -e

  assert_eq "3" "$exit_code" "exit code"
  assert_eq "timeout" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "reconnect_published" "$(jq -r '.lastStatus' "$CASE_DIR/result.json")" "lastStatus"
  assert_eq "reconnect-deadline" "$(jq -r '.lastCommandId' "$CASE_DIR/result.json")" "lastCommandId"
  assert_eq "1" "$(wc -l < "$FAKE_CURL_CALLS" | tr -d ' ')" "curl call count"
}

test_reconnect_max_retry_exceeded() {
  run_case "max-retry"
  write_response last '{"nodeId":"node-a","operationId":"node_drain:node-a","draining":true,"status":"sessions_remaining","reconnectPublished":false,"targetedSessions":0,"remainingSessions":12,"reason":"node_drain","retryable":true,"nextAction":"retry_reconnect","readinessReason":"ready"}'

  set +e
  "$SCRIPT" --base-url http://openchat.internal --node-id node-a --token test-token \
    --timeout-seconds 5 --poll-interval-ms 0 --max-reconnect-attempts 1 \
    --output "$CASE_DIR/result.json" > "$CASE_DIR/stdout.json"
  exit_code=$?
  set -e

  assert_eq "4" "$exit_code" "exit code"
  assert_eq "max_retry_exceeded" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "sessions_remaining" "$(jq -r '.lastStatus' "$CASE_DIR/result.json")" "lastStatus"
}

test_publish_failed_command_id_is_attempted_not_published() {
  run_case "publish-failed-command-id"
  write_response last '{"nodeId":"node-a","operationId":"node_drain:node-a","draining":true,"status":"publish_failed","reconnectPublished":false,"targetedSessions":12,"remainingSessions":12,"reason":"node_drain","retryable":true,"nextAction":"investigate_publish","readinessReason":"ready","commandId":"reconnect-failed"}'

  set +e
  "$SCRIPT" --base-url http://openchat.internal --node-id node-a --token test-token \
    --timeout-seconds 5 --poll-interval-ms 0 --max-reconnect-attempts 1 \
    --output "$CASE_DIR/result.json" > "$CASE_DIR/stdout.json"
  exit_code=$?
  set -e

  assert_eq "4" "$exit_code" "exit code"
  assert_eq "max_retry_exceeded" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "0" "$(jq -r '.reconnectCommandIds | length' "$CASE_DIR/result.json")" "published reconnect ids"
  assert_eq "reconnect-failed" "$(jq -r '.attemptedReconnectCommandIds | join(",")' "$CASE_DIR/result.json")" "attempted reconnect ids"
}

test_reconnect_retry_then_complete
test_blocked_last_active_node
test_invalid_response_shape
test_unknown_node_timeout
test_sleep_crossing_deadline_times_out_without_extra_poll
test_reconnect_max_retry_exceeded
test_publish_failed_command_id_is_attempted_not_published

echo "openchat node drain orchestrator tests passed"
