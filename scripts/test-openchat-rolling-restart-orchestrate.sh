#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/openchat-rolling-restart-orchestrate.sh"

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

assert_file_contains() {
  local file="$1"
  local expected="$2"
  local message="$3"
  if ! grep -Fq -- "$expected" "$file"; then
    fail "$message missing=$expected file=$file"
  fi
}

line_count_or_zero() {
  local file="$1"
  if [ -f "$file" ]; then
    wc -l < "$file" | tr -d ' '
  else
    echo 0
  fi
}

make_fake_curl() {
  local case_dir="$1"
  mkdir -p "$case_dir/bin"
  cat > "$case_dir/bin/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$*" >> "$FAKE_CURL_CALLS"
cat "$FAKE_NODES_JSON"
FAKE_CURL
  chmod +x "$case_dir/bin/curl"
}

make_fake_stage_scripts() {
  local case_dir="$1"
cat > "$case_dir/fake-drain.sh" <<'FAKE_DRAIN'
#!/usr/bin/env bash
set -euo pipefail

original_args="$*"
node_id=""
output=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --node-id) node_id="$2"; shift 2 ;;
    --output) output="$2"; shift 2 ;;
    *) shift ;;
  esac
done
printf '%s\n' "$original_args" >> "$FAKE_DRAIN_CALLS"
if [ "${FAKE_DRAIN_FAIL_NODE:-}" = "$node_id" ]; then
  jq -n --arg node "$node_id" '{nodeId:$node,result:"timeout",terminationAllowed:false,exitCode:3,lastStatus:"sessions_remaining",lastNextAction:"retry_reconnect",remainingSessions:12,completedAt:(now|todateiso8601)}' > "$output"
  exit 3
fi
if [ "${FAKE_DRAIN_FAIL_NO_OUTPUT_NODE:-}" = "$node_id" ]; then
  exit 7
fi
jq -n --arg node "$node_id" '{nodeId:$node,result:"complete",terminationAllowed:true,exitCode:0,lastStatus:"complete",lastNextAction:"none",remainingSessions:0,completedAt:(now|todateiso8601),reconnectCommandIds:["cmd-" + $node],attemptedReconnectCommandIds:["cmd-" + $node],durableReconnectCommandLog:{deliveryEvidence:{collectionStatus:"collected",complete:true,missingHandlers:[],failedHandlers:[]}}}' > "$output"
FAKE_DRAIN
  chmod +x "$case_dir/fake-drain.sh"

  cat > "$case_dir/fake-decision.sh" <<'FAKE_DECISION'
#!/usr/bin/env bash
set -euo pipefail

node_id=""
output=""
strict="false"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --node-id) node_id="$2"; shift 2 ;;
    --output) output="$2"; shift 2 ;;
    --strict-delivery-evidence) strict="true"; shift ;;
    *) shift 2 2>/dev/null || shift ;;
  esac
done
printf '%s strict=%s\n' "$node_id" "$strict" >> "$FAKE_DECISION_CALLS"
if [ "${FAKE_DECISION_FAIL_NODE:-}" = "$node_id" ]; then
  jq -n --arg node "$node_id" '{nodeId:$node,result:"not_ready",terminationAllowed:false,recommendedAction:"wait",createdAt:(now|todateiso8601)}' > "$output"
  exit 32
fi
jq -n --arg node "$node_id" '{nodeId:$node,result:"ready",terminationAllowed:true,recommendedAction:"terminate_node",createdAt:(now|todateiso8601)}' > "$output"
FAKE_DECISION
  chmod +x "$case_dir/fake-decision.sh"

  cat > "$case_dir/fake-gcp.sh" <<'FAKE_GCP'
#!/usr/bin/env bash
set -euo pipefail

node_id=""
output=""
mode=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --node-id) node_id="$2"; shift 2 ;;
    --output) output="$2"; shift 2 ;;
    --mode) mode="$2"; shift 2 ;;
    *) shift 2 2>/dev/null || shift ;;
  esac
done
printf '%s mode=%s\n' "$node_id" "$mode" >> "$FAKE_GCP_CALLS"
jq -n --arg node "$node_id" --arg mode "$mode" '{nodeId:$node,result:"terminated",terminationPerformed:($mode == "stop"),beforeStatus:"RUNNING",afterStatus:(if $mode == "stop" then "TERMINATED" else "RUNNING" end)}' > "$output"
FAKE_GCP
  chmod +x "$case_dir/fake-gcp.sh"

  cat > "$case_dir/fake-enrich.sh" <<'FAKE_ENRICH'
#!/usr/bin/env bash
set -euo pipefail

label="$1"
output="$2"
printf '%s %s\n' "$label" "$output" >> "$FAKE_ENRICH_CALLS"
tmp="$output.tmp"
jq '.durableReconnectCommandLog.deliveryEvidence = {
  enabled: true,
  mode: "audit_only",
  collectionStatus: "collected",
  complete: true,
  commandCount: 1,
  strictEligibleCommandCount: 1,
  missingHandlers: [],
  failedHandlers: []
}' "$output" > "$tmp"
mv "$tmp" "$output"
FAKE_ENRICH
  chmod +x "$case_dir/fake-enrich.sh"
}

run_case() {
  local name="$1"
  CASE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/openchat-rolling-$name.XXXXXX")"
  mkdir -p "$CASE_DIR/bin"
  make_fake_curl "$CASE_DIR"
  make_fake_stage_scripts "$CASE_DIR"
  export PATH="$CASE_DIR/bin:$PATH"
  export FAKE_CURL_CALLS="$CASE_DIR/curl.calls"
  export FAKE_DRAIN_CALLS="$CASE_DIR/drain.calls"
  export FAKE_DECISION_CALLS="$CASE_DIR/decision.calls"
  export FAKE_GCP_CALLS="$CASE_DIR/gcp.calls"
  export FAKE_ENRICH_CALLS="$CASE_DIR/enrich.calls"
  export OPENCHAT_NODE_DRAIN_ORCHESTRATOR_SCRIPT="$CASE_DIR/fake-drain.sh"
  export OPENCHAT_NODE_TERMINATION_DECISION_SCRIPT="$CASE_DIR/fake-decision.sh"
  export OPENCHAT_GCP_NODE_TERMINATE_SCRIPT="$CASE_DIR/fake-gcp.sh"
  unset FAKE_DRAIN_FAIL_NODE
  unset FAKE_DRAIN_FAIL_NO_OUTPUT_NODE
  unset FAKE_DECISION_FAIL_NODE
}

write_nodes() {
  local body="$1"
  FAKE_NODES_JSON="$CASE_DIR/nodes.json"
  export FAKE_NODES_JSON
  printf '%s\n' "$body" > "$FAKE_NODES_JSON"
}

test_two_node_rolling_restart_completes() {
  run_case "two-complete"
  write_nodes '{"activeNodes":[{"nodeId":"gcp-realtime-1","openSessions":30},{"nodeId":"gcp-realtime-2","openSessions":20},{"nodeId":"gcp-realtime-3","openSessions":10},{"nodeId":"gcp-realtime-4","openSessions":5}],"assignments":{"0":{"nodeId":"gcp-realtime-3","ready":true},"1":{"nodeId":"gcp-realtime-4","ready":true},"2":{"nodeId":"gcp-realtime-3","ready":true},"3":{"nodeId":"gcp-realtime-4","ready":true}}}'

  "$SCRIPT" \
    --base-url http://openchat.internal \
    --token test-token \
    --project openchat-test \
    --zone asia-northeast3-a \
    --run-id run-1 \
    --target-count 2 \
    --min-active-nodes 2 \
    --interval-seconds 0 \
    --gcp-mode stop \
    --strict-delivery-evidence \
    --output "$CASE_DIR/result.json" > "$CASE_DIR/stdout.json"

  assert_eq "complete" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "true" "$(jq -r '.terminationAllowed' "$CASE_DIR/result.json")" "terminationAllowed"
  assert_eq "2" "$(jq -r '.completedCount' "$CASE_DIR/result.json")" "completedCount"
  assert_eq "gcp-realtime-1,gcp-realtime-2" "$(jq -r '.steps | map(.nodeId) | join(",")' "$CASE_DIR/result.json")" "target order"
  assert_eq "gcp-realtime-1,gcp-realtime-2" "$(jq -r '.stoppedNodeIds | join(",")' "$CASE_DIR/result.json")" "stopped node ids"
  assert_eq "true" "$(jq -r '.allTerminationDecisionsReady' "$CASE_DIR/result.json")" "decision aggregate"
  assert_eq "true" "$(jq -r '.allDeliveryEvidenceComplete' "$CASE_DIR/result.json")" "delivery evidence aggregate"
  assert_eq "2" "$(jq -r '.gcpStoppedCount' "$CASE_DIR/result.json")" "gcp stopped count"
  assert_eq "gcp-realtime-1 strict=true" "$(sed -n '1p' "$FAKE_DECISION_CALLS")" "strict flag first"
  assert_eq "gcp-realtime-2 mode=stop" "$(sed -n '2p' "$FAKE_GCP_CALLS")" "gcp mode second"
  assert_file_contains "$FAKE_DRAIN_CALLS" "--reconnect-limit 50" "default reconnect limit"
  assert_file_contains "$FAKE_DRAIN_CALLS" "--reconnect-retry-after-ms 2000" "default reconnect retryAfter"
  assert_file_contains "$FAKE_DRAIN_CALLS" "--timeout-seconds 420" "default drain timeout"
  assert_file_contains "$FAKE_DRAIN_CALLS" "--max-reconnect-attempts 40" "default reconnect attempts"
}

test_explicit_drain_pacing_overrides_defaults() {
  run_case "explicit-pacing"
  write_nodes '{"activeNodes":[{"nodeId":"gcp-realtime-1","openSessions":30},{"nodeId":"gcp-realtime-2","openSessions":20},{"nodeId":"gcp-realtime-3","openSessions":10}],"assignments":{"0":{"nodeId":"gcp-realtime-2","ready":true},"1":{"nodeId":"gcp-realtime-3","ready":true},"2":{"nodeId":"gcp-realtime-2","ready":true},"3":{"nodeId":"gcp-realtime-3","ready":true}}}'

  "$SCRIPT" \
    --base-url http://openchat.internal \
    --token test-token \
    --project openchat-test \
    --zone asia-northeast3-a \
    --run-id run-1 \
    --target-count 1 \
    --min-active-nodes 2 \
    --interval-seconds 0 \
    --gcp-mode stop \
    --timeout-seconds 91 \
    --reconnect-limit 7 \
    --reconnect-retry-after-ms 1234 \
    --max-reconnect-attempts 3 \
    --output "$CASE_DIR/result.json" > "$CASE_DIR/stdout.json"

  assert_eq "complete" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_file_contains "$FAKE_DRAIN_CALLS" "--reconnect-limit 7" "explicit reconnect limit"
  assert_file_contains "$FAKE_DRAIN_CALLS" "--reconnect-retry-after-ms 1234" "explicit reconnect retryAfter"
  assert_file_contains "$FAKE_DRAIN_CALLS" "--timeout-seconds 91" "explicit drain timeout"
  assert_file_contains "$FAKE_DRAIN_CALLS" "--max-reconnect-attempts 3" "explicit reconnect attempts"
}

test_enrichment_hook_runs_before_strict_decision() {
  run_case "enrichment-hook"
  write_nodes '{"activeNodes":[{"nodeId":"gcp-realtime-1","openSessions":30},{"nodeId":"gcp-realtime-2","openSessions":20},{"nodeId":"gcp-realtime-3","openSessions":10}],"assignments":{"0":{"nodeId":"gcp-realtime-2","ready":true},"1":{"nodeId":"gcp-realtime-3","ready":true},"2":{"nodeId":"gcp-realtime-2","ready":true},"3":{"nodeId":"gcp-realtime-3","ready":true}}}'

  OPENCHAT_ROLLING_RESTART_DRAIN_ENRICHER="$CASE_DIR/fake-enrich.sh" \
    "$SCRIPT" \
      --base-url http://openchat.internal \
      --token test-token \
      --project openchat-test \
      --zone asia-northeast3-a \
      --run-id run-1 \
      --target-count 1 \
      --min-active-nodes 2 \
      --interval-seconds 0 \
      --gcp-mode stop \
      --strict-delivery-evidence \
      --output "$CASE_DIR/result.json" > "$CASE_DIR/stdout.json"

  assert_eq "complete" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "1" "$(line_count_or_zero "$FAKE_ENRICH_CALLS")" "enrich calls"
  assert_eq "true" "$(jq -r '.steps[0].drain.durableReconnectCommandLog.deliveryEvidence.enabled' "$CASE_DIR/result.json")" "step enriched"
  assert_eq "true" "$(jq -r '.allDeliveryEvidenceComplete' "$CASE_DIR/result.json")" "aggregate enriched"
}

test_min_active_nodes_blocks_before_over_draining() {
  run_case "min-active"
  write_nodes '{"activeNodes":[{"nodeId":"gcp-realtime-1","openSessions":30},{"nodeId":"gcp-realtime-2","openSessions":20}]}'

  set +e
  "$SCRIPT" \
    --base-url http://openchat.internal \
    --token test-token \
    --project openchat-test \
    --zone asia-northeast3-a \
    --run-id run-1 \
    --target-count 1 \
    --min-active-nodes 2 \
    --interval-seconds 0 \
    --gcp-mode stop \
    --output "$CASE_DIR/result.json" > "$CASE_DIR/stdout.json"
  exit_code=$?
  set -e

  assert_eq "2" "$exit_code" "exit code"
  assert_eq "blocked" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "0" "$(jq -r '.completedCount' "$CASE_DIR/result.json")" "completedCount"
  assert_eq "0" "$(line_count_or_zero "$FAKE_DRAIN_CALLS")" "drain calls"
}

test_multi_stop_requires_four_active_nodes() {
  run_case "multi-stop-guard"
  write_nodes '{"activeNodes":[{"nodeId":"gcp-realtime-1","openSessions":30},{"nodeId":"gcp-realtime-2","openSessions":20},{"nodeId":"gcp-realtime-3","openSessions":10}]}'

  set +e
  "$SCRIPT" \
    --base-url http://openchat.internal \
    --token test-token \
    --project openchat-test \
    --zone asia-northeast3-a \
    --run-id run-1 \
    --target-count 2 \
    --min-active-nodes 1 \
    --interval-seconds 0 \
    --gcp-mode stop \
    --output "$CASE_DIR/result.json" > "$CASE_DIR/stdout.json"
  exit_code=$?
  set -e

  assert_eq "2" "$exit_code" "exit code"
  assert_eq "blocked" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "multi-stop requires at least 4 active realtime nodes" "$(jq -r '.reason' "$CASE_DIR/result.json")" "reason"
  assert_eq "0" "$(line_count_or_zero "$FAKE_DRAIN_CALLS")" "drain calls"
}

test_decision_failure_stops_sequence() {
  run_case "decision-failure"
  write_nodes '{"activeNodes":[{"nodeId":"gcp-realtime-1","openSessions":30},{"nodeId":"gcp-realtime-2","openSessions":20},{"nodeId":"gcp-realtime-3","openSessions":10}]}'
  export FAKE_DECISION_FAIL_NODE="gcp-realtime-1"

  set +e
  "$SCRIPT" \
    --base-url http://openchat.internal \
    --token test-token \
    --project openchat-test \
    --zone asia-northeast3-a \
    --run-id run-1 \
    --target-count 1 \
    --min-active-nodes 1 \
    --interval-seconds 0 \
    --gcp-mode stop \
    --output "$CASE_DIR/result.json" > "$CASE_DIR/stdout.json"
  exit_code=$?
  set -e

  assert_eq "5" "$exit_code" "exit code"
  assert_eq "api_failure" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "1" "$(jq -r '.steps | length' "$CASE_DIR/result.json")" "step count"
  assert_eq "0" "$(line_count_or_zero "$FAKE_GCP_CALLS")" "gcp calls"
}

test_drain_failure_without_output_keeps_result_json() {
  run_case "drain-no-output"
  write_nodes '{"activeNodes":[{"nodeId":"gcp-realtime-1","openSessions":30},{"nodeId":"gcp-realtime-2","openSessions":20},{"nodeId":"gcp-realtime-3","openSessions":10}]}'
  export FAKE_DRAIN_FAIL_NO_OUTPUT_NODE="gcp-realtime-1"

  set +e
  "$SCRIPT" \
    --base-url http://openchat.internal \
    --token test-token \
    --project openchat-test \
    --zone asia-northeast3-a \
    --run-id run-1 \
    --target-count 1 \
    --min-active-nodes 1 \
    --interval-seconds 0 \
    --gcp-mode stop \
    --output "$CASE_DIR/result.json" > "$CASE_DIR/stdout.json"
  exit_code=$?
  set -e

  assert_eq "5" "$exit_code" "exit code"
  assert_eq "api_failure" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
  assert_eq "drain_failed" "$(jq -r '.steps[0].result' "$CASE_DIR/result.json")" "step result"
  assert_eq "null" "$(jq -r '.steps[0].drain' "$CASE_DIR/result.json")" "missing drain json"
}

test_invalid_nodes_response_returns_unexpected_response() {
  run_case "invalid-nodes"
  write_nodes 'not-json'

  set +e
  "$SCRIPT" \
    --base-url http://openchat.internal \
    --token test-token \
    --project openchat-test \
    --zone asia-northeast3-a \
    --run-id run-1 \
    --target-count 1 \
    --min-active-nodes 1 \
    --interval-seconds 0 \
    --gcp-mode stop \
    --output "$CASE_DIR/result.json" > "$CASE_DIR/stdout.json"
  exit_code=$?
  set -e

  assert_eq "6" "$exit_code" "exit code"
  assert_eq "unexpected_response" "$(jq -r '.result' "$CASE_DIR/result.json")" "result"
}

test_two_node_rolling_restart_completes
test_explicit_drain_pacing_overrides_defaults
test_enrichment_hook_runs_before_strict_decision
test_min_active_nodes_blocks_before_over_draining
test_multi_stop_requires_four_active_nodes
test_decision_failure_stops_sequence
test_drain_failure_without_output_keeps_result_json
test_invalid_nodes_response_returns_unexpected_response

echo "openchat rolling restart orchestrator tests passed"
