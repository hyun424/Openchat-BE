#!/usr/bin/env bash
set -euo pipefail

INPUT=""
NODE_ID=""
OUTPUT=""
MAX_AGE_SECONDS=600
CONTRACT_VERSION="openchat.node-termination-decision.v1"

usage() {
  cat <<'USAGE'
Usage:
  openchat-node-termination-decision.sh --input ORCHESTRATOR_RESULT_JSON --node-id NODE_ID [--output PATH]

Options:
  --input PATH      Node drain orchestrator result JSON.
  --node-id NODE_ID Node expected to be terminated.
  --output PATH     Optional decision JSON output path.
  --max-age-seconds Freshness window for completed orchestrator results. Default: 600.
USAGE
}

invalid_usage() {
  local message="$1"
  echo "$message" >&2
  usage >&2
  exit 30
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --input)
      if [ "$#" -lt 2 ] || [[ "${2:-}" == --* ]]; then
        invalid_usage "--input requires a value"
      fi
      INPUT="${2:-}"
      shift 2
      ;;
    --node-id)
      if [ "$#" -lt 2 ] || [[ "${2:-}" == --* ]]; then
        invalid_usage "--node-id requires a value"
      fi
      NODE_ID="${2:-}"
      shift 2
      ;;
    --output)
      if [ "$#" -lt 2 ] || [[ "${2:-}" == --* ]]; then
        invalid_usage "--output requires a value"
      fi
      OUTPUT="${2:-}"
      shift 2
      ;;
    --max-age-seconds)
      if [ "$#" -lt 2 ] || [[ "${2:-}" == --* ]]; then
        invalid_usage "--max-age-seconds requires a value"
      fi
      MAX_AGE_SECONDS="${2:-}"
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
    exit 1
  fi
}

is_non_negative_integer() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

validate_args() {
  require_command jq
  if [ -z "$INPUT" ] || [ -z "$NODE_ID" ]; then
    usage >&2
    exit 30
  fi
  if ! is_non_negative_integer "$MAX_AGE_SECONDS"; then
    echo "--max-age-seconds must be a non-negative integer" >&2
    exit 30
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

write_input_error() {
  local result="$1"
  local exit_code="$2"
  local reason="$3"
  local created_at
  created_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  local json
  json="$(jq -n \
    --arg contractVersion "$CONTRACT_VERSION" \
    --arg nodeId "$NODE_ID" \
    --arg result "$result" \
    --arg reason "$reason" \
    --arg createdAt "$created_at" \
    '{
      contractVersion: $contractVersion,
      result: $result,
      terminationAllowed: false,
      recommendedAction: "fix_input",
      nodeId: $nodeId,
      sourceResult: null,
      sourceStatus: null,
      sourceNextAction: null,
      sourceReadinessReason: null,
      remainingSessions: null,
      reason: $reason,
      guards: [{
        name: "input_shape",
        passed: false,
        expected: "valid orchestrator result JSON with required fields",
        actual: $reason
      }],
      createdAt: $createdAt
    }')"
  write_output "$json"
  exit "$exit_code"
}

write_unexpected_input() {
  write_input_error "unexpected_input" 31 "$1"
}

validate_input_file() {
  if [ ! -r "$INPUT" ]; then
    write_unexpected_input "input file is not readable"
  fi
  if ! jq -e . "$INPUT" >/dev/null 2>&1; then
    write_unexpected_input "input is not valid JSON"
  fi
  if ! jq -e '
    type == "object"
    and (.nodeId | type == "string")
    and (.result | type == "string")
    and (.terminationAllowed | type == "boolean")
    and (.exitCode | type == "number")
    and (.lastStatus | type == "string")
    and (.lastNextAction | type == "string")
    and (.remainingSessions | type == "number")
    and (.completedAt | type == "string")
  ' "$INPUT" >/dev/null 2>&1; then
    write_unexpected_input "input missing required orchestrator result fields"
  fi
}

build_decision() {
  local created_at
  created_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  jq \
    --arg contractVersion "$CONTRACT_VERSION" \
    --arg expectedNodeId "$NODE_ID" \
    --arg createdAt "$created_at" \
    --argjson maxAgeSeconds "$MAX_AGE_SECONDS" \
    '
      def guard($name; $passed; $expected; $actual):
        {
          name: $name,
          passed: $passed,
          expected: $expected,
          actual: ($actual | tostring)
        };

      def source_result_known($result):
        ["complete", "blocked", "invalid_request", "timeout", "max_retry_exceeded", "api_failure", "unexpected_response"]
        | index($result) != null;

      def source_next_action_known($nextAction):
        ["none", "poll_status", "retry_reconnect", "wait_assignment", "wait_replacement_ready", "wait_node_heartbeat", "add_replacement_node", "fix_request", "enable_node_drain", "investigate_publish", "start_drain"]
        | index($nextAction) != null;

      . as $source
      | ($source.completedAt | fromdateiso8601?) as $completedEpoch
      | (now - ($completedEpoch // 0)) as $ageSeconds
      | [
          guard("node_id_match"; $source.nodeId == $expectedNodeId; $expectedNodeId; $source.nodeId),
          guard("source_exit_code_zero"; $source.exitCode == 0; "0"; $source.exitCode),
          guard("source_result_complete"; $source.result == "complete"; "complete"; $source.result),
          guard("source_termination_allowed"; $source.terminationAllowed == true; "true"; $source.terminationAllowed),
          guard("source_status_complete"; $source.lastStatus == "complete"; "complete"; $source.lastStatus),
          guard("source_next_action_none"; $source.lastNextAction == "none"; "none"; $source.lastNextAction),
          guard("remaining_sessions_zero"; $source.remainingSessions == 0; "0"; $source.remainingSessions),
          guard("result_fresh"; ($completedEpoch != null and $ageSeconds >= 0 and $ageSeconds <= $maxAgeSeconds); ("0 <= age <= " + ($maxAgeSeconds | tostring)); (if $completedEpoch == null then "invalid completedAt" else ($ageSeconds | floor) end))
        ] as $guards
      | ($guards | map(select(.passed == false))) as $failed
      | ($guards | map(select(.name == "node_id_match" and .passed == false)) | length > 0) as $nodeMismatch
      | ($guards | map(select(.name == "result_fresh" and .passed == false)) | length > 0) as $stale
      | ($source.terminationAllowed == true and ($failed | length > 0)) as $contradictoryAllowed
      | ((source_result_known($source.result) | not) or (source_next_action_known($source.lastNextAction) | not)) as $unexpected
      | ($failed | length == 0) as $ready
      | {
          contractVersion: $contractVersion,
          result: (
            if $ready then "ready"
            elif $unexpected then "unexpected_input"
            elif ($nodeMismatch or $stale or $contradictoryAllowed) then "unsafe"
            else "not_ready"
            end
          ),
          terminationAllowed: $ready,
          recommendedAction: (
            if $ready then "terminate_node"
            elif $unexpected then "fix_input"
            elif ($nodeMismatch or $stale or $contradictoryAllowed) then "investigate"
            else "wait"
            end
          ),
          nodeId: $expectedNodeId,
          sourceResult: $source.result,
          sourceStatus: $source.lastStatus,
          sourceNextAction: $source.lastNextAction,
          sourceReadinessReason: ($source.lastReadinessReason // null),
          sourceLastCommandId: ($source.lastCommandId // null),
          sourceReconnectCommandIds: ($source.reconnectCommandIds // []),
          sourceAttemptedReconnectCommandIds: ($source.attemptedReconnectCommandIds // []),
          sourceLastReconnectCommandId: ($source.lastReconnectCommandId // null),
          sourceDurableReconnectCommandLog: ($source.durableReconnectCommandLog // null),
	          auditEvidence: {
	            durableLogCollectionStatus: ($source.durableReconnectCommandLog.collectionStatus // null),
	            durableLogCollectionError: ($source.durableReconnectCommandLog.collectionError // null),
	            durableLogComplete: (
	              if (($source.durableReconnectCommandLog // null) == null) then null
	              elif (($source.durableReconnectCommandLog.collectionStatus // "collected") != "collected") then false
	              else (($source.durableReconnectCommandLog.missingCommandIds // []) | length == 0)
	              end
	            ),
            durableLogMissingCommandIds: ($source.durableReconnectCommandLog.missingCommandIds // []),
            durableLogRecordCount: ($source.durableReconnectCommandLog.recordCount // 0)
          },
          remainingSessions: $source.remainingSessions,
          reason: (
            if $ready then "all termination guards passed"
            elif $unexpected then "orchestrator result contains unknown enum values"
            elif $nodeMismatch then "orchestrator result nodeId does not match requested node"
            elif $stale then "orchestrator result is stale"
            elif $contradictoryAllowed then "orchestrator result allows termination but safety guards failed"
            else "orchestrator result is not ready for termination"
            end
          ),
          guards: $guards,
          createdAt: $createdAt
        }
    ' "$INPUT"
}

validate_args
validate_input_file

decision_json="$(build_decision)"
write_output "$decision_json"

result="$(printf '%s' "$decision_json" | jq -r '.result')"
case "$result" in
  ready)
    exit 0
    ;;
  not_ready)
    exit 10
    ;;
  unsafe)
    exit 20
    ;;
  unexpected_input)
    exit 31
    ;;
  *)
    exit 31
    ;;
esac
