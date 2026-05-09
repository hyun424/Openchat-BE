#!/usr/bin/env bash
set -euo pipefail

BASE_URL=""
NODE_ID=""
TOKEN="${OPENCHAT_INTERNAL_TOKEN:-}"
TIMEOUT_SECONDS=180
POLL_INTERVAL_MS=2000
RECONNECT_LIMIT=1000
RECONNECT_RETRY_AFTER_MS=500
MAX_RECONNECT_ATTEMPTS=5
OUTPUT=""
MODE="start"

ATTEMPTS=0
RECONNECT_ATTEMPTS=0
LAST_RESPONSE=""
LAST_STATUS=""
LAST_NEXT_ACTION=""
LAST_READINESS_REASON=""
LAST_REMAINING_SESSIONS=""
LAST_OPERATION_ID=""
LAST_COMMAND_ID=""
STARTED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
HISTORY_FILE="$(mktemp "${TMPDIR:-/tmp}/openchat-node-drain-history.XXXXXX")"
printf '[]' > "$HISTORY_FILE"

cleanup() {
  rm -f "$HISTORY_FILE"
}
trap cleanup EXIT

usage() {
  cat <<'USAGE'
Usage:
  openchat-node-drain-orchestrate.sh --base-url URL --node-id NODE_ID [--token TOKEN] [options]

Options:
  --mode start|status-only
  --timeout-seconds SECONDS
  --poll-interval-ms MILLIS
  --reconnect-limit COUNT
  --reconnect-retry-after-ms MILLIS
  --max-reconnect-attempts COUNT
  --output PATH
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --base-url)
      BASE_URL="${2:-}"
      shift 2
      ;;
    --node-id)
      NODE_ID="${2:-}"
      shift 2
      ;;
    --token)
      TOKEN="${2:-}"
      shift 2
      ;;
    --timeout-seconds)
      TIMEOUT_SECONDS="${2:-}"
      shift 2
      ;;
    --poll-interval-ms)
      POLL_INTERVAL_MS="${2:-}"
      shift 2
      ;;
    --reconnect-limit)
      RECONNECT_LIMIT="${2:-}"
      shift 2
      ;;
    --reconnect-retry-after-ms)
      RECONNECT_RETRY_AFTER_MS="${2:-}"
      shift 2
      ;;
    --max-reconnect-attempts)
      MAX_RECONNECT_ATTEMPTS="${2:-}"
      shift 2
      ;;
    --output)
      OUTPUT="${2:-}"
      shift 2
      ;;
    --mode)
      MODE="${2:-}"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
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
  require_command curl
  require_command jq
  if [ -z "$BASE_URL" ] || [ -z "$NODE_ID" ] || [ -z "$TOKEN" ]; then
    usage >&2
    exit 1
  fi
  case "$MODE" in
    start|status-only) ;;
    *)
      echo "--mode must be start or status-only" >&2
      exit 1
      ;;
  esac
  for value in "$TIMEOUT_SECONDS" "$POLL_INTERVAL_MS" "$RECONNECT_LIMIT" "$RECONNECT_RETRY_AFTER_MS" "$MAX_RECONNECT_ATTEMPTS"; do
    if ! is_non_negative_integer "$value"; then
      echo "Numeric options must be non-negative integers" >&2
      exit 1
    fi
  done
}

json_number_or_null() {
  local value="$1"
  if [[ "$value" =~ ^-?[0-9]+$ ]]; then
    printf '%s' "$value"
  else
    printf 'null'
  fi
}

write_result() {
  local result="$1"
  local exit_code="$2"
  local termination_allowed="$3"
  local reason="${4:-}"
  local completed_at
  completed_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

  local termination_json="false"
  if [ "$termination_allowed" = "true" ]; then
    termination_json="true"
  fi
  local remaining_json
  remaining_json="$(json_number_or_null "$LAST_REMAINING_SESSIONS")"

  local output_json
  output_json="$(jq -n \
    --arg nodeId "$NODE_ID" \
    --arg operationId "$LAST_OPERATION_ID" \
    --arg result "$result" \
    --arg startedAt "$STARTED_AT" \
    --arg completedAt "$completed_at" \
    --arg lastStatus "$LAST_STATUS" \
    --arg lastNextAction "$LAST_NEXT_ACTION" \
    --arg lastReadinessReason "$LAST_READINESS_REASON" \
    --arg lastCommandId "$LAST_COMMAND_ID" \
    --arg reason "$reason" \
    --argjson terminationAllowed "$termination_json" \
    --argjson exitCode "$exit_code" \
    --argjson attempts "$ATTEMPTS" \
    --argjson reconnectAttempts "$RECONNECT_ATTEMPTS" \
    --argjson remainingSessions "$remaining_json" \
    --slurpfile history "$HISTORY_FILE" \
    '{
      nodeId: $nodeId,
      operationId: (if $operationId == "" then null else $operationId end),
      result: $result,
      terminationAllowed: $terminationAllowed,
      exitCode: $exitCode,
      startedAt: $startedAt,
      completedAt: $completedAt,
      attempts: $attempts,
      reconnectAttempts: $reconnectAttempts,
      lastStatus: (if $lastStatus == "" then null else $lastStatus end),
      lastNextAction: (if $lastNextAction == "" then null else $lastNextAction end),
      lastReadinessReason: (if $lastReadinessReason == "" then null else $lastReadinessReason end),
      lastCommandId: (if $lastCommandId == "" then null else $lastCommandId end),
      remainingSessions: $remainingSessions,
      reason: (if $reason == "" then null else $reason end),
      history: $history[0],
      reconnectCommandIds: (
        $history[0]
        | map(select(.reconnectPublished == true) | .commandId // empty)
        | map(select(. != ""))
        | unique
      ),
      attemptedReconnectCommandIds: (
        $history[0]
        | map(.commandId // empty)
        | map(select(. != ""))
        | unique
      ),
      lastReconnectCommandId: (
        $history[0]
        | map(select(.reconnectPublished == true) | .commandId // empty)
        | map(select(. != ""))
        | last // null
      ),
      durableReconnectCommandLog: (
        ($history[0]
          | map(.commandId // empty)
          | map(select(. != ""))
          | unique) as $expected
        | {
	          enabled: false,
	          mode: "audit_only",
	          contractVersion: "openchat.reconnect-command-log.v1",
	          collectionStatus: "disabled",
	          collectionError: null,
	          expectedCommandIds: $expected,
          recordedCommandIds: [],
          missingCommandIds: [],
          duplicateCommandIds: [],
          recordCount: 0,
          lastRecordedCommandId: null,
          records: []
        }
      )
    }')"

  if [ -n "$OUTPUT" ]; then
    local tmp_output="$OUTPUT.tmp"
    printf '%s\n' "$output_json" > "$tmp_output"
    mv "$tmp_output" "$OUTPUT"
  fi
  printf '%s\n' "$output_json"
}

fail_with_result() {
  local result="$1"
  local exit_code="$2"
  local reason="${3:-}"
  write_result "$result" "$exit_code" false "$reason"
  exit "$exit_code"
}

validate_response_shape() {
  local response="$1"
  if ! printf '%s' "$response" | jq -e '
    type == "object"
    and (.status | type == "string")
    and (.nextAction | type == "string")
    and (.retryable | type == "boolean")
    and (.remainingSessions | type == "number")
  ' >/dev/null 2>&1; then
    LAST_RESPONSE="$response"
    fail_with_result "unexpected_response" 6 "response missing required drain status fields"
  fi
}

append_history() {
  local response="$1"
  local tmp_history="$HISTORY_FILE.tmp"
  jq --argjson item "$response" \
    '. + [{
      status: $item.status,
      nextAction: $item.nextAction,
      retryable: $item.retryable,
      readinessReason: ($item.readinessReason // null),
      remainingSessions: ($item.remainingSessions // null),
      reconnectPublished: ($item.reconnectPublished // null),
      targetedSessions: ($item.targetedSessions // null),
      commandId: ($item.commandId // null)
    }]' "$HISTORY_FILE" > "$tmp_history"
  mv "$tmp_history" "$HISTORY_FILE"
}

capture_response() {
  local response="$1"
  validate_response_shape "$response"
  LAST_RESPONSE="$response"
  LAST_STATUS="$(printf '%s' "$response" | jq -r '.status')"
  LAST_NEXT_ACTION="$(printf '%s' "$response" | jq -r '.nextAction')"
  LAST_READINESS_REASON="$(printf '%s' "$response" | jq -r '.readinessReason // empty')"
  LAST_REMAINING_SESSIONS="$(printf '%s' "$response" | jq -r '.remainingSessions')"
  LAST_OPERATION_ID="$(printf '%s' "$response" | jq -r '.operationId // empty')"
  LAST_COMMAND_ID="$(printf '%s' "$response" | jq -r '.commandId // empty')"
  append_history "$response"
}

api_request() {
  local method="$1"
  local path="$2"
  local url="${BASE_URL%/}$path"
  local max_time=30
  if [ "${START_EPOCH:-0}" -gt 0 ]; then
    local now
    now="$(date +%s)"
    local remaining=$((START_EPOCH + TIMEOUT_SECONDS - now))
    if [ "$remaining" -le 0 ] && [ "$ATTEMPTS" -gt 0 ]; then
      fail_with_result "timeout" 3 "$method $path skipped because timeout elapsed"
    fi
    if [ "$remaining" -gt 0 ] && [ "$remaining" -lt "$max_time" ]; then
      max_time="$remaining"
    fi
  fi
  if [ "$max_time" -lt 1 ]; then
    max_time=1
  fi
  local args=(--max-time "$max_time" -sS -f -H "Authorization: Bearer $TOKEN")
  if [ "$method" = "POST" ]; then
    args+=(-X POST)
  fi
  local response
  if ! response="$(curl "${args[@]}" "$url")"; then
    fail_with_result "api_failure" 5 "$method $path failed"
  fi
  ATTEMPTS=$((ATTEMPTS + 1))
  capture_response "$response"
}

post_drain() {
  api_request "POST" "/api/internal/room-partition/nodes/$NODE_ID/drain?limit=$RECONNECT_LIMIT&retryAfterMs=$RECONNECT_RETRY_AFTER_MS"
}

get_status() {
  api_request "GET" "/api/internal/room-partition/nodes/$NODE_ID/drain/status"
}

deadline_epoch() {
  printf '%s' "$((START_EPOCH + TIMEOUT_SECONDS))"
}

deadline_reached() {
  local now
  now="$(date +%s)"
  [ "$now" -ge "$(deadline_epoch)" ]
}

sleep_interval() {
  if [ "$POLL_INTERVAL_MS" -le 0 ]; then
    return 0
  fi
  local seconds
  seconds="$(awk "BEGIN { printf \"%.3f\", $POLL_INTERVAL_MS / 1000 }")"
  sleep "$seconds"
}

wait_then_status() {
  if deadline_reached; then
    fail_with_result "timeout" 3 "timeout before next status poll"
  fi
  sleep_interval
  if deadline_reached; then
    fail_with_result "timeout" 3 "timeout before next status poll"
  fi
  get_status
}

retry_reconnect() {
  if [ "$RECONNECT_ATTEMPTS" -ge "$MAX_RECONNECT_ATTEMPTS" ]; then
    fail_with_result "max_retry_exceeded" 4 "max reconnect attempts exceeded"
  fi
  if deadline_reached; then
    fail_with_result "timeout" 3 "timeout before reconnect retry"
  fi
  RECONNECT_ATTEMPTS=$((RECONNECT_ATTEMPTS + 1))
  post_drain
}

main_loop() {
  if [ "$MODE" = "start" ]; then
    post_drain
  else
    get_status
  fi

  while true; do
    if [ "$LAST_STATUS" = "complete" ] && [ "$LAST_NEXT_ACTION" = "none" ]; then
      write_result "complete" 0 true ""
      exit 0
    fi

    case "$LAST_NEXT_ACTION" in
      poll_status|wait_assignment|wait_replacement_ready|wait_node_heartbeat)
        wait_then_status
        ;;
      retry_reconnect|investigate_publish)
        retry_reconnect
        ;;
      start_drain)
        if [ "$MODE" != "start" ]; then
          fail_with_result "blocked" 2 "status-only mode will not start drain"
        fi
        retry_reconnect
        ;;
      add_replacement_node|enable_node_drain)
        fail_with_result "blocked" 2 "$LAST_NEXT_ACTION"
        ;;
      fix_request)
        fail_with_result "invalid_request" 1 "$LAST_NEXT_ACTION"
        ;;
      none)
        fail_with_result "unexpected_response" 6 "nextAction none without complete status"
        ;;
      *)
        fail_with_result "unexpected_response" 6 "unknown nextAction $LAST_NEXT_ACTION"
        ;;
    esac
  done
}

validate_args
START_EPOCH="$(date +%s)"
main_loop
