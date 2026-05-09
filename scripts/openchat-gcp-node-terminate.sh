#!/usr/bin/env bash
set -euo pipefail

DECISION=""
PROJECT=""
ZONE=""
RUN_ID=""
NODE_ID=""
MODE="dry-run"
INSTANCE=""
OUTPUT=""
MAX_DECISION_AGE_SECONDS=600

STARTED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
INSTANCE_JSON=""
INSTANCE_NAME=""
EXPECTED_APP_INDEX=""
BEFORE_STATUS=""
AFTER_STATUS=""

usage() {
  cat <<'USAGE'
Usage:
  openchat-gcp-node-terminate.sh --decision PATH --project PROJECT --zone ZONE --run-id RUN_ID --node-id NODE_ID [options]

Options:
  --mode dry-run|stop
  --instance INSTANCE_NAME
  --output PATH
  --max-decision-age-seconds SECONDS
USAGE
}

invalid_usage() {
  echo "$1" >&2
  usage >&2
  exit 30
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --decision)
      if [ "$#" -lt 2 ] || [[ "${2:-}" == --* ]]; then
        invalid_usage "--decision requires a value"
      fi
      DECISION="${2:-}"
      shift 2
      ;;
    --project)
      if [ "$#" -lt 2 ] || [[ "${2:-}" == --* ]]; then
        invalid_usage "--project requires a value"
      fi
      PROJECT="${2:-}"
      shift 2
      ;;
    --zone)
      if [ "$#" -lt 2 ] || [[ "${2:-}" == --* ]]; then
        invalid_usage "--zone requires a value"
      fi
      ZONE="${2:-}"
      shift 2
      ;;
    --run-id)
      if [ "$#" -lt 2 ] || [[ "${2:-}" == --* ]]; then
        invalid_usage "--run-id requires a value"
      fi
      RUN_ID="${2:-}"
      shift 2
      ;;
    --node-id)
      if [ "$#" -lt 2 ] || [[ "${2:-}" == --* ]]; then
        invalid_usage "--node-id requires a value"
      fi
      NODE_ID="${2:-}"
      shift 2
      ;;
    --mode)
      if [ "$#" -lt 2 ] || [[ "${2:-}" == --* ]]; then
        invalid_usage "--mode requires a value"
      fi
      MODE="${2:-}"
      shift 2
      ;;
    --instance)
      if [ "$#" -lt 2 ] || [[ "${2:-}" == --* ]]; then
        invalid_usage "--instance requires a value"
      fi
      INSTANCE="${2:-}"
      shift 2
      ;;
    --output)
      if [ "$#" -lt 2 ] || [[ "${2:-}" == --* ]]; then
        invalid_usage "--output requires a value"
      fi
      OUTPUT="${2:-}"
      shift 2
      ;;
    --max-decision-age-seconds)
      if [ "$#" -lt 2 ] || [[ "${2:-}" == --* ]]; then
        invalid_usage "--max-decision-age-seconds requires a value"
      fi
      MAX_DECISION_AGE_SECONDS="${2:-}"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      invalid_usage "Unknown argument: $1"
      ;;
  esac
done

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 30
  fi
}

is_non_negative_integer() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

validate_args() {
  require_command jq
  require_command gcloud
  if [ -z "$DECISION" ] || [ -z "$PROJECT" ] || [ -z "$ZONE" ] || [ -z "$RUN_ID" ] || [ -z "$NODE_ID" ]; then
    usage >&2
    exit 30
  fi
  case "$MODE" in
    dry-run|stop) ;;
    *)
      echo "--mode must be dry-run or stop" >&2
      exit 30
      ;;
  esac
  if ! is_non_negative_integer "$MAX_DECISION_AGE_SECONDS"; then
    echo "--max-decision-age-seconds must be a non-negative integer" >&2
    exit 30
  fi
  if [[ "$NODE_ID" =~ ^gcp-realtime-([0-9]+)$ ]]; then
    EXPECTED_APP_INDEX="${BASH_REMATCH[1]}"
  else
    write_result "unsafe" false "unsupported nodeId format" 20
  fi
}

json_string_or_null() {
  local value="$1"
  if [ -n "$value" ]; then
    jq -Rn --arg value "$value" '$value'
  else
    printf 'null'
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

build_guards_json() {
  local decision_contract="${1:-}"
  local decision_result="${2:-}"
  local decision_allowed="${3:-}"
  local decision_action="${4:-}"
  local decision_node="${5:-}"
  local decision_created_at="${6:-}"
  local decision_remaining_sessions="${7:-}"
  local decision_source_status="${8:-}"
  local decision_source_next_action="${9:-}"
  local decision_guards_all_passed="${10:-}"
  local instance_run_id="${11:-}"
  local instance_role="${12:-}"
  local instance_app_index="${13:-}"
  local instance_status="${14:-}"

  jq -n \
    --arg decisionContract "$decision_contract" \
    --arg decisionResult "$decision_result" \
    --arg decisionAllowed "$decision_allowed" \
    --arg decisionAction "$decision_action" \
    --arg decisionNode "$decision_node" \
    --arg decisionCreatedAt "$decision_created_at" \
    --arg decisionRemainingSessions "$decision_remaining_sessions" \
    --arg decisionSourceStatus "$decision_source_status" \
    --arg decisionSourceNextAction "$decision_source_next_action" \
    --arg decisionGuardsAllPassed "$decision_guards_all_passed" \
    --arg expectedNode "$NODE_ID" \
    --arg instanceRunId "$instance_run_id" \
    --arg expectedRunId "$RUN_ID" \
    --arg instanceRole "$instance_role" \
    --arg instanceAppIndex "$instance_app_index" \
    --arg expectedAppIndex "$EXPECTED_APP_INDEX" \
    --arg instanceStatus "$instance_status" \
    --argjson maxDecisionAgeSeconds "$MAX_DECISION_AGE_SECONDS" \
    '[
      {name:"decision_fresh", passed:(($decisionCreatedAt | fromdateiso8601?) as $epoch | ($epoch != null and (now - $epoch) >= 0 and (now - $epoch) <= $maxDecisionAgeSeconds)), expected:("0 <= age <= " + ($maxDecisionAgeSeconds | tostring)), actual:(($decisionCreatedAt | fromdateiso8601?) as $epoch | if $epoch == null then "invalid createdAt" else ((now - $epoch) | floor | tostring) end)},
      {name:"decision_contract_version", passed:($decisionContract == "openchat.node-termination-decision.v1"), expected:"openchat.node-termination-decision.v1", actual:$decisionContract},
      {name:"decision_ready", passed:($decisionResult == "ready"), expected:"ready", actual:$decisionResult},
      {name:"decision_termination_allowed", passed:($decisionAllowed == "true"), expected:"true", actual:$decisionAllowed},
      {name:"decision_recommended_action", passed:($decisionAction == "terminate_node"), expected:"terminate_node", actual:$decisionAction},
      {name:"decision_node_id_match", passed:($decisionNode == $expectedNode), expected:$expectedNode, actual:$decisionNode},
      {name:"decision_remaining_sessions_zero", passed:($decisionRemainingSessions == "0"), expected:"0", actual:$decisionRemainingSessions},
      {name:"decision_source_status_complete", passed:($decisionSourceStatus == "complete"), expected:"complete", actual:$decisionSourceStatus},
      {name:"decision_source_next_action_none", passed:($decisionSourceNextAction == "none"), expected:"none", actual:$decisionSourceNextAction},
      {name:"decision_guards_all_passed", passed:($decisionGuardsAllPassed == "true"), expected:"true", actual:$decisionGuardsAllPassed},
      {name:"instance_run_id_match", passed:($instanceRunId == $expectedRunId), expected:$expectedRunId, actual:$instanceRunId},
      {name:"instance_role_realtime", passed:($instanceRole == "realtime"), expected:"realtime", actual:$instanceRole},
      {name:"instance_app_index_match", passed:($instanceAppIndex == $expectedAppIndex), expected:$expectedAppIndex, actual:$instanceAppIndex},
      {name:"instance_running", passed:($instanceStatus == "RUNNING"), expected:"RUNNING", actual:$instanceStatus}
    ]'
}

write_result() {
  local result="$1"
  local termination_performed="$2"
  local reason="$3"
  local exit_code="$4"
  local guards_json="${5:-[]}"
  local completed_at
  completed_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

  local instance_json
  instance_json="$(json_string_or_null "$INSTANCE_NAME")"
  local before_json
  before_json="$(json_string_or_null "$BEFORE_STATUS")"
  local after_json
  after_json="$(json_string_or_null "$AFTER_STATUS")"

  local json
  json="$(jq -n \
    --arg result "$result" \
    --arg mode "$MODE" \
    --arg nodeId "$NODE_ID" \
    --arg project "$PROJECT" \
    --arg zone "$ZONE" \
    --arg runId "$RUN_ID" \
    --arg reason "$reason" \
    --arg createdAt "$STARTED_AT" \
    --arg completedAt "$completed_at" \
    --argjson terminationPerformed "$termination_performed" \
    --argjson instance "$instance_json" \
    --argjson beforeStatus "$before_json" \
    --argjson afterStatus "$after_json" \
    --argjson guards "$guards_json" \
    '{
      result: $result,
      terminationPerformed: $terminationPerformed,
      mode: $mode,
      nodeId: $nodeId,
      instance: $instance,
      project: $project,
      zone: $zone,
      runId: $runId,
      beforeStatus: $beforeStatus,
      afterStatus: $afterStatus,
      guards: $guards,
      reason: $reason,
      createdAt: $createdAt,
      completedAt: $completedAt
    }')"
  write_output "$json"
  exit "$exit_code"
}

write_unexpected_input() {
  write_result "unexpected_input" false "$1" 31
}

load_decision_fields() {
  if [ ! -r "$DECISION" ]; then
    write_unexpected_input "decision file is not readable"
  fi
  if ! jq -e . "$DECISION" >/dev/null 2>&1; then
    write_unexpected_input "decision is not valid JSON"
  fi
  if ! jq -e '
    type == "object"
    and (.contractVersion | type == "string")
    and (.result | type == "string")
    and (.terminationAllowed | type == "boolean")
    and (.recommendedAction | type == "string")
    and (.nodeId | type == "string")
    and (.createdAt | type == "string")
    and (.remainingSessions | type == "number")
    and (.sourceStatus | type == "string")
    and (.sourceNextAction | type == "string")
    and (.guards | type == "array")
  ' "$DECISION" >/dev/null 2>&1; then
    write_unexpected_input "decision missing required fields"
  fi
}

resolve_instance() {
  if [ -n "$INSTANCE" ]; then
    INSTANCE_NAME="$INSTANCE"
  else
    local instances_json
    if ! instances_json="$(gcloud compute instances list \
      --project "$PROJECT" \
      --filter "zone:($ZONE) AND labels.run_id=$RUN_ID AND labels.role=realtime AND labels.app_index=$EXPECTED_APP_INDEX" \
      --format=json)"; then
      write_result "gcp_failure" false "failed to list candidate instances" 40
    fi
    local count
    count="$(printf '%s' "$instances_json" | jq 'length' 2>/dev/null || echo 0)"
    if [ "$count" != "1" ]; then
      write_result "gcp_failure" false "expected exactly one candidate instance, found $count" 40
    fi
    INSTANCE_NAME="$(printf '%s' "$instances_json" | jq -r '.[0].name')"
  fi

  if ! INSTANCE_JSON="$(gcloud compute instances describe "$INSTANCE_NAME" \
    --project "$PROJECT" \
    --zone "$ZONE" \
    --format=json)"; then
    write_result "gcp_failure" false "failed to describe instance $INSTANCE_NAME" 40
  fi
  BEFORE_STATUS="$(printf '%s' "$INSTANCE_JSON" | jq -r '.status // ""')"
  AFTER_STATUS="$BEFORE_STATUS"
}

main() {
  validate_args
  load_decision_fields

  local decision_contract
  local decision_result
  local decision_allowed
  local decision_action
  local decision_node
  local decision_created_at
  local decision_remaining_sessions
  local decision_source_status
  local decision_source_next_action
  local decision_guards_all_passed
  decision_contract="$(jq -r '.contractVersion' "$DECISION")"
  decision_result="$(jq -r '.result' "$DECISION")"
  decision_allowed="$(jq -r '.terminationAllowed' "$DECISION")"
  decision_action="$(jq -r '.recommendedAction' "$DECISION")"
  decision_node="$(jq -r '.nodeId' "$DECISION")"
  decision_created_at="$(jq -r '.createdAt' "$DECISION")"
  decision_remaining_sessions="$(jq -r '.remainingSessions' "$DECISION")"
  decision_source_status="$(jq -r '.sourceStatus' "$DECISION")"
  decision_source_next_action="$(jq -r '.sourceNextAction' "$DECISION")"
  decision_guards_all_passed="$(jq -r 'all(.guards[]?; .passed == true)' "$DECISION")"

  local pre_gcp_guards
  pre_gcp_guards="$(build_guards_json "$decision_contract" "$decision_result" "$decision_allowed" "$decision_action" "$decision_node" "$decision_created_at" "$decision_remaining_sessions" "$decision_source_status" "$decision_source_next_action" "$decision_guards_all_passed" "" "" "" "")"
  if [ "$decision_node" != "$NODE_ID" ]; then
    write_result "unsafe" false "decision nodeId does not match requested node" 20 "$pre_gcp_guards"
  fi
  local decision_fresh
  decision_fresh="$(printf '%s' "$pre_gcp_guards" | jq -r '.[] | select(.name == "decision_fresh") | .passed')"
  if [ "$decision_fresh" != "true" ]; then
    write_result "unsafe" false "decision result is stale or from the future" 20 "$pre_gcp_guards"
  fi
  if [ "$decision_result" != "ready" ] || [ "$decision_allowed" != "true" ] || [ "$decision_action" != "terminate_node" ]; then
    write_result "blocked" false "decision is not ready for GCP termination" 10 "$pre_gcp_guards"
  fi
  if [ "$decision_contract" != "openchat.node-termination-decision.v1" ]; then
    write_unexpected_input "unsupported decision contractVersion"
  fi
  local pre_gcp_failed_count
  pre_gcp_failed_count="$(printf '%s' "$pre_gcp_guards" | jq 'map(select((.name | startswith("decision_")) and .passed == false)) | length')"
  if [ "$pre_gcp_failed_count" != "0" ]; then
    write_result "unsafe" false "decision safety guards failed" 20 "$pre_gcp_guards"
  fi

  resolve_instance

  local instance_run_id
  local instance_role
  local instance_app_index
  instance_run_id="$(printf '%s' "$INSTANCE_JSON" | jq -r '.labels.run_id // ""')"
  instance_role="$(printf '%s' "$INSTANCE_JSON" | jq -r '.labels.role // ""')"
  instance_app_index="$(printf '%s' "$INSTANCE_JSON" | jq -r '.labels.app_index // ""')"

  local guards
  guards="$(build_guards_json "$decision_contract" "$decision_result" "$decision_allowed" "$decision_action" "$decision_node" "$decision_created_at" "$decision_remaining_sessions" "$decision_source_status" "$decision_source_next_action" "$decision_guards_all_passed" "$instance_run_id" "$instance_role" "$instance_app_index" "$BEFORE_STATUS")"
  local failed_count
  failed_count="$(printf '%s' "$guards" | jq 'map(select(.passed == false)) | length')"
  if [ "$failed_count" != "0" ]; then
    write_result "unsafe" false "GCP instance safety guards failed" 20 "$guards"
  fi

  if [ "$MODE" = "dry-run" ]; then
    write_result "dry_run_ready" false "all guards passed; dry-run only" 0 "$guards"
  fi

  if ! gcloud compute instances stop "$INSTANCE_NAME" \
    --project "$PROJECT" \
    --zone "$ZONE" \
    --quiet >/dev/null; then
    write_result "gcp_failure" false "failed to stop instance $INSTANCE_NAME" 40 "$guards"
  fi

  if ! INSTANCE_JSON="$(gcloud compute instances describe "$INSTANCE_NAME" \
    --project "$PROJECT" \
    --zone "$ZONE" \
    --format=json)"; then
    write_result "gcp_failure" true "stopped instance but failed to describe final status" 40 "$guards"
  fi
  AFTER_STATUS="$(printf '%s' "$INSTANCE_JSON" | jq -r '.status // ""')"
  if [ "$AFTER_STATUS" != "TERMINATED" ]; then
    write_result "gcp_failure" true "instance stop did not reach TERMINATED status" 40 "$guards"
  fi

  write_result "stopped" true "GCP realtime VM stopped" 0 "$guards"
}

main
