#!/usr/bin/env bash
set -euo pipefail

BASE_URL=""
TOKEN="${OPENCHAT_INTERNAL_TOKEN:-}"
PROJECT=""
ZONE=""
RUN_ID=""
TARGET_COUNT=1
MIN_ACTIVE_NODES=2
INTERVAL_SECONDS=30
OUTPUT=""
GCP_MODE="dry-run"
STRICT_DELIVERY_EVIDENCE="false"
TIMEOUT_SECONDS=420
POLL_INTERVAL_MS=2000
RECONNECT_LIMIT=50
RECONNECT_RETRY_AFTER_MS=2000
MAX_RECONNECT_ATTEMPTS=40
PARTITION_COUNT=4

STARTED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DRAIN_SCRIPT="${OPENCHAT_NODE_DRAIN_ORCHESTRATOR_SCRIPT:-$SCRIPT_DIR/openchat-node-drain-orchestrate.sh}"
DECISION_SCRIPT="${OPENCHAT_NODE_TERMINATION_DECISION_SCRIPT:-$SCRIPT_DIR/openchat-node-termination-decision.sh}"
GCP_TERMINATE_SCRIPT="${OPENCHAT_GCP_NODE_TERMINATE_SCRIPT:-$SCRIPT_DIR/openchat-gcp-node-terminate.sh}"
DRAIN_RESULT_ENRICHER="${OPENCHAT_ROLLING_RESTART_DRAIN_ENRICHER:-}"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/openchat-rolling-restart.XXXXXX")"
STEPS_FILE="$WORK_DIR/steps.json"
STOPPED_FILE="$WORK_DIR/stopped.txt"
printf '[]' > "$STEPS_FILE"
: > "$STOPPED_FILE"

cleanup() {
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

usage() {
  cat <<'USAGE'
Usage:
  openchat-rolling-restart-orchestrate.sh --base-url URL --token TOKEN --project PROJECT --zone ZONE --run-id RUN_ID [options]

Options:
  --target-count COUNT
  --min-active-nodes COUNT
  --interval-seconds SECONDS
  --gcp-mode dry-run|stop
  --strict-delivery-evidence
  --partition-count COUNT
  --timeout-seconds SECONDS
  --poll-interval-ms MILLIS
  --reconnect-limit COUNT
  --reconnect-retry-after-ms MILLIS
  --max-reconnect-attempts COUNT
  --output PATH
USAGE
}

invalid_usage() {
  echo "$1" >&2
  usage >&2
  exit 1
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --base-url) BASE_URL="${2:-}"; shift 2 ;;
    --token) TOKEN="${2:-}"; shift 2 ;;
    --project) PROJECT="${2:-}"; shift 2 ;;
    --zone) ZONE="${2:-}"; shift 2 ;;
    --run-id) RUN_ID="${2:-}"; shift 2 ;;
    --target-count) TARGET_COUNT="${2:-}"; shift 2 ;;
    --min-active-nodes) MIN_ACTIVE_NODES="${2:-}"; shift 2 ;;
    --interval-seconds) INTERVAL_SECONDS="${2:-}"; shift 2 ;;
    --gcp-mode) GCP_MODE="${2:-}"; shift 2 ;;
    --strict-delivery-evidence) STRICT_DELIVERY_EVIDENCE="true"; shift ;;
    --partition-count) PARTITION_COUNT="${2:-}"; shift 2 ;;
    --timeout-seconds) TIMEOUT_SECONDS="${2:-}"; shift 2 ;;
    --poll-interval-ms) POLL_INTERVAL_MS="${2:-}"; shift 2 ;;
    --reconnect-limit) RECONNECT_LIMIT="${2:-}"; shift 2 ;;
    --reconnect-retry-after-ms) RECONNECT_RETRY_AFTER_MS="${2:-}"; shift 2 ;;
    --max-reconnect-attempts) MAX_RECONNECT_ATTEMPTS="${2:-}"; shift 2 ;;
    --output) OUTPUT="${2:-}"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) invalid_usage "Unknown argument: $1" ;;
  esac
done

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

is_non_negative_integer() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

validate_args() {
  require_command curl
  require_command jq
  if [ -z "$BASE_URL" ] || [ -z "$TOKEN" ] || [ -z "$PROJECT" ] || [ -z "$ZONE" ] || [ -z "$RUN_ID" ]; then
    usage >&2
    exit 1
  fi
  case "$GCP_MODE" in
    dry-run|stop) ;;
    *) invalid_usage "--gcp-mode must be dry-run or stop" ;;
  esac
  for value in "$TARGET_COUNT" "$MIN_ACTIVE_NODES" "$INTERVAL_SECONDS" "$TIMEOUT_SECONDS" "$POLL_INTERVAL_MS" "$RECONNECT_LIMIT" "$RECONNECT_RETRY_AFTER_MS" "$MAX_RECONNECT_ATTEMPTS" "$PARTITION_COUNT"; do
    if ! is_non_negative_integer "$value"; then
      invalid_usage "Numeric options must be non-negative integers"
    fi
  done
  if [ "$TARGET_COUNT" -lt 1 ]; then
    invalid_usage "--target-count must be at least 1"
  fi
}

write_output() {
  local json="$1"
  if [ -n "$OUTPUT" ]; then
    local tmp_output="$OUTPUT.tmp"
    printf '%s\n' "$json" > "$tmp_output"
    mv "$tmp_output" "$OUTPUT"
  fi
  printf '%s\n' "$json"
}

build_result() {
  local result="$1"
  local exit_code="$2"
  local reason="${3:-}"
  local termination_allowed="false"
  if [ "$result" = "complete" ]; then
    termination_allowed="true"
  fi
  local completed_at
  completed_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  local completed_count
  completed_count="$(jq -r '[.[] | select(.result == "complete")] | length' "$STEPS_FILE")"
  jq -n \
    --arg contractVersion "openchat.rolling-restart-orchestrator.v1" \
    --arg result "$result" \
    --arg reason "$reason" \
    --arg startedAt "$STARTED_AT" \
    --arg completedAt "$completed_at" \
    --arg gcpMode "$GCP_MODE" \
    --arg strictDeliveryEvidence "$STRICT_DELIVERY_EVIDENCE" \
    --argjson exitCode "$exit_code" \
    --argjson targetCount "$TARGET_COUNT" \
    --argjson minActiveNodes "$MIN_ACTIVE_NODES" \
    --argjson completedCount "$completed_count" \
    --argjson terminationAllowed "$termination_allowed" \
    --slurpfile steps "$STEPS_FILE" \
    '($steps[0]) as $stepList
    | {
      contractVersion: $contractVersion,
      result: $result,
      terminationAllowed: $terminationAllowed,
      exitCode: $exitCode,
      reason: (if $reason == "" then null else $reason end),
      startedAt: $startedAt,
      completedAt: $completedAt,
      targetCount: $targetCount,
      minActiveNodes: $minActiveNodes,
      completedCount: $completedCount,
      gcpMode: $gcpMode,
      strictDeliveryEvidence: ($strictDeliveryEvidence == "true"),
      stoppedNodeIds: ($stepList | map(select(.result == "complete") | .nodeId)),
      allTerminationDecisionsReady: (
        ($stepList | length) > 0
        and ($stepList | all(.decision.result == "ready" and .decision.terminationAllowed == true))
      ),
      allDeliveryEvidenceComplete: (
        ($stepList | length) > 0
        and ($stepList | all((.drain.durableReconnectCommandLog.deliveryEvidence.collectionStatus // "missing") == "collected"
          and (.drain.durableReconnectCommandLog.deliveryEvidence.complete // false) == true))
      ),
      missingHandlers: (
        $stepList
        | map(.drain.durableReconnectCommandLog.deliveryEvidence.missingHandlers // [])
        | add // []
      ),
      failedHandlers: (
        $stepList
        | map(.drain.durableReconnectCommandLog.deliveryEvidence.failedHandlers // [])
        | add // []
      ),
      gcpStoppedCount: (
        $stepList
        | map(select((.gcp.afterStatus // "") == "TERMINATED" or (.gcp.terminationPerformed // false) == true))
        | length
      ),
      steps: $stepList
    }'
}

finish() {
  local result="$1"
  local exit_code="$2"
  local reason="${3:-}"
  write_output "$(build_result "$result" "$exit_code" "$reason")"
  exit "$exit_code"
}

append_step() {
  local step_json="$1"
  local tmp="$STEPS_FILE.tmp"
  jq --argjson step "$step_json" '. + [$step]' "$STEPS_FILE" > "$tmp"
  mv "$tmp" "$STEPS_FILE"
}

ensure_json_file() {
  local file="$1"
  if [ ! -s "$file" ] || ! jq -e . "$file" >/dev/null 2>&1; then
    printf 'null\n' > "$file"
  fi
}

fetch_nodes() {
  local output="$1"
  curl --max-time 15 -sf \
    -H "Authorization: Bearer $TOKEN" \
    "$BASE_URL/api/internal/room-partition/nodes" \
    > "$output"
}

fetch_assignments() {
  local output="$1"
  curl --max-time 15 -sf \
    -H "Authorization: Bearer $TOKEN" \
    "$BASE_URL/api/internal/room-partition/assignments?partitionCount=$PARTITION_COUNT" \
  > "$output" || printf '{"assignments":{}}' > "$output"
}

wait_assignment_ready_after_step() {
  local target_node="$1"
  local step_dir="$2"
  local owner_count="unknown"
  local ready_count="unknown"
  local assignments_file="$step_dir/assignments-after.json"

  for attempt in $(seq 1 15); do
    fetch_assignments "$assignments_file" || true
    owner_count="$(jq -r --arg node "$target_node" '[ (.assignments // {})[] | select(.nodeId == $node) ] | length' "$assignments_file" 2>/dev/null || echo "parse_failed")"
    ready_count="$(jq -r '[ (.assignments // {})[] | select(.ready == true) ] | length' "$assignments_file" 2>/dev/null || echo "parse_failed")"
    if [ "$owner_count" = "0" ] && [[ "$ready_count" =~ ^[0-9]+$ ]] && [ "$ready_count" -ge "$PARTITION_COUNT" ]; then
      jq -n \
        --arg targetNode "$target_node" \
        --argjson attempt "$attempt" \
        --argjson ownerCount "$owner_count" \
        --argjson readyCount "$ready_count" \
        '{targetNode:$targetNode,attempt:$attempt,ownerCount:$ownerCount,readyCount:$readyCount,ready:true}' \
        > "$step_dir/assignment-readiness.json"
      return 0
    fi
    sleep 2
  done

  jq -n \
    --arg targetNode "$target_node" \
    --arg ownerCount "$owner_count" \
    --arg readyCount "$ready_count" \
    '{targetNode:$targetNode,ownerCount:$ownerCount,readyCount:$readyCount,ready:false}' \
    > "$step_dir/assignment-readiness.json"
  return 1
}

select_target_node() {
  local nodes_file="$1"
  local stopped_json
  stopped_json="$(jq -Rn '[inputs | select(. != "")]' < "$STOPPED_FILE")"
  jq -r --argjson stopped "$stopped_json" '
    [(.activeNodes // [])
      | .[]
      | select((.draining // false) != true)
      | .nodeId as $nodeId
      | select(($stopped | index($nodeId)) == null)]
    | sort_by(.openSessions // 0)
    | reverse
    | .[0].nodeId // ""
  ' "$nodes_file"
}

available_active_count() {
  local nodes_file="$1"
  local stopped_json
  stopped_json="$(jq -Rn '[inputs | select(. != "")]' < "$STOPPED_FILE")"
  jq -r --argjson stopped "$stopped_json" '
    [(.activeNodes // [])
      | .[]
      | select((.draining // false) != true)
      | .nodeId as $nodeId
      | select(($stopped | index($nodeId)) == null)]
    | length
  ' "$nodes_file"
}

run_step() {
  local index="$1"
  local target_node="$2"
  local step_dir="$WORK_DIR/step-$index"
  mkdir -p "$step_dir"
  local drain_output="$step_dir/drain.json"
  local decision_output="$step_dir/decision.json"
  local gcp_output="$step_dir/gcp.json"
  local before_assignments="$step_dir/assignments-before.json"
  local readiness_output="$step_dir/assignment-readiness.json"

  fetch_assignments "$before_assignments" || true

  set +e
  "$DRAIN_SCRIPT" \
    --base-url "$BASE_URL" \
    --node-id "$target_node" \
    --token "$TOKEN" \
    --timeout-seconds "$TIMEOUT_SECONDS" \
    --poll-interval-ms "$POLL_INTERVAL_MS" \
    --reconnect-limit "$RECONNECT_LIMIT" \
    --reconnect-retry-after-ms "$RECONNECT_RETRY_AFTER_MS" \
    --max-reconnect-attempts "$MAX_RECONNECT_ATTEMPTS" \
    --output "$drain_output" \
    > "$step_dir/drain.stdout"
  local drain_status=$?
  set -e
  if [ "$drain_status" -ne 0 ]; then
    ensure_json_file "$drain_output"
    append_step "$(jq -n --arg nodeId "$target_node" --argjson index "$index" --argjson exitCode "$drain_status" --slurpfile drain "$drain_output" '{index:$index,nodeId:$nodeId,result:"drain_failed",exitCode:$exitCode,drain:($drain[0] // null)}')"
    finish "api_failure" 5 "drain orchestrator failed"
  fi
  ensure_json_file "$drain_output"
  if [ -n "$DRAIN_RESULT_ENRICHER" ]; then
    set +e
    "$DRAIN_RESULT_ENRICHER" "rolling-restart-step-$index-$target_node" "$drain_output"
    local enrich_status=$?
    set -e
    ensure_json_file "$drain_output"
    if [ "$enrich_status" -ne 0 ]; then
      append_step "$(jq -n --arg nodeId "$target_node" --argjson index "$index" --argjson exitCode "$enrich_status" --slurpfile drain "$drain_output" '{index:$index,nodeId:$nodeId,result:"enrichment_failed",exitCode:$exitCode,drain:($drain[0] // null)}')"
      finish "api_failure" 5 "drain result enrichment failed"
    fi
  fi

  local decision_args=(
    --input "$drain_output"
    --node-id "$target_node"
    --output "$decision_output"
  )
  if [ "$STRICT_DELIVERY_EVIDENCE" = "true" ]; then
    decision_args+=(--strict-delivery-evidence)
  fi

  set +e
  "$DECISION_SCRIPT" "${decision_args[@]}" > "$step_dir/decision.stdout"
  local decision_status=$?
  set -e
  if [ "$decision_status" -ne 0 ]; then
    ensure_json_file "$decision_output"
    append_step "$(jq -n --arg nodeId "$target_node" --argjson index "$index" --argjson exitCode "$decision_status" --slurpfile drain "$drain_output" --slurpfile decision "$decision_output" '{index:$index,nodeId:$nodeId,result:"decision_failed",exitCode:$exitCode,drain:($drain[0] // null),decision:($decision[0] // null)}')"
    finish "api_failure" 5 "termination decision failed"
  fi
  ensure_json_file "$decision_output"

  set +e
  "$GCP_TERMINATE_SCRIPT" \
    --decision "$decision_output" \
    --project "$PROJECT" \
    --zone "$ZONE" \
    --run-id "$RUN_ID" \
    --node-id "$target_node" \
    --mode "$GCP_MODE" \
    --output "$gcp_output" \
    > "$step_dir/gcp.stdout"
  local gcp_status=$?
  set -e
  if [ "$gcp_status" -ne 0 ]; then
    ensure_json_file "$gcp_output"
    append_step "$(jq -n --arg nodeId "$target_node" --argjson index "$index" --argjson exitCode "$gcp_status" --slurpfile drain "$drain_output" --slurpfile decision "$decision_output" --slurpfile gcp "$gcp_output" '{index:$index,nodeId:$nodeId,result:"gcp_failed",exitCode:$exitCode,drain:($drain[0] // null),decision:($decision[0] // null),gcp:($gcp[0] // null)}')"
    finish "api_failure" 5 "gcp termination failed"
  fi
  ensure_json_file "$gcp_output"

  local assignment_status=0
  wait_assignment_ready_after_step "$target_node" "$step_dir" || assignment_status=$?
  local owner_count_after
  local ready_count_after
  owner_count_after="$(jq -r '.ownerCount // 0' "$readiness_output" 2>/dev/null || echo "0")"
  ready_count_after="$(jq -r '.readyCount // 0' "$readiness_output" 2>/dev/null || echo "0")"
  if [ "$assignment_status" -ne 0 ]; then
    append_step "$(jq -n \
      --arg nodeId "$target_node" \
      --argjson index "$index" \
      --arg ownerCountAfter "$owner_count_after" \
      --arg readyCountAfter "$ready_count_after" \
      --slurpfile drain "$drain_output" \
      --slurpfile decision "$decision_output" \
      --slurpfile gcp "$gcp_output" \
      '{index:$index,nodeId:$nodeId,result:"assignment_not_ready",exitCode:5,ownerCountAfter:$ownerCountAfter,readyCountAfter:$readyCountAfter,drain:($drain[0] // null),decision:($decision[0] // null),gcp:($gcp[0] // null)}')"
    finish "api_failure" 5 "assignment readiness guard failed"
  fi

  append_step "$(jq -n \
    --arg nodeId "$target_node" \
    --argjson index "$index" \
    --argjson ownerCountAfter "$owner_count_after" \
    --argjson readyCountAfter "$ready_count_after" \
    --slurpfile drain "$drain_output" \
    --slurpfile decision "$decision_output" \
    --slurpfile gcp "$gcp_output" \
    '{index:$index,nodeId:$nodeId,result:"complete",exitCode:0,ownerCountAfter:$ownerCountAfter,readyCountAfter:$readyCountAfter,drain:($drain[0] // null),decision:($decision[0] // null),gcp:($gcp[0] // null)}')"
  printf '%s\n' "$target_node" >> "$STOPPED_FILE"
}

validate_args

for index in $(seq 1 "$TARGET_COUNT"); do
  nodes_file="$WORK_DIR/nodes-before-$index.json"
  if ! fetch_nodes "$nodes_file"; then
    finish "api_failure" 5 "failed to fetch active realtime nodes"
  fi
  active_count="$(available_active_count "$nodes_file" 2>/dev/null || echo "parse_failed")"
  if ! [[ "$active_count" =~ ^[0-9]+$ ]]; then
    finish "unexpected_response" 6 "unexpected active node response"
  fi
  if [ "$index" -eq 1 ] && [ "$GCP_MODE" = "stop" ] && [ "$TARGET_COUNT" -ge 2 ] && [ "$active_count" -lt 4 ]; then
    finish "blocked" 2 "multi-stop requires at least 4 active realtime nodes"
  fi
  if [ "$((active_count - 1))" -lt "$MIN_ACTIVE_NODES" ]; then
    finish "blocked" 2 "min_active_nodes guard blocks next termination"
  fi
  target_node="$(select_target_node "$nodes_file" 2>/dev/null || echo "")"
  if [ -z "$target_node" ] || [ "$target_node" = "null" ]; then
    finish "blocked" 2 "no target realtime node available"
  fi
  run_step "$index" "$target_node"
  if [ "$index" -lt "$TARGET_COUNT" ] && [ "$INTERVAL_SECONDS" -gt 0 ]; then
    sleep "$INTERVAL_SECONDS"
  fi
done

finish "complete" 0 ""
