#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATE="$ROOT_DIR/infra/gcp-loadtest/templates/app-startup.sh.tftpl"

grep -q 'app_role *= *"api"' "$ROOT_DIR/infra/gcp-loadtest/main.tf"
grep -q 'app_role *= *"realtime"' "$ROOT_DIR/infra/gcp-loadtest/main.tf"

grep -q 'case "$APP_ROLE" in' "$TEMPLATE"
grep -q 'tr '\''\[:upper:\]'\'' '\''\[:lower:\]'\''' "$TEMPLATE"
grep -q 'api)' "$TEMPLATE"
grep -q 'realtime|combined)' "$TEMPLATE"
grep -q 'ai-worker)' "$TEMPLATE"
grep -q 'unsupported app_role=$APP_ROLE' "$TEMPLATE"

grep -q 'REALTIME_WORKLOAD_PUBLISH_ENABLED_VALUE=false' "$TEMPLATE"
grep -q 'ROOM_PARTITION_ENABLED_VALUE=false' "$TEMPLATE"
grep -q 'ROOM_PARTITION_ASSIGNMENT_ENABLED_VALUE=false' "$TEMPLATE"
grep -q 'ROOM_PARTITION_ASSIGNMENT_DYNAMIC_SUBSCRIBE_ENABLED_VALUE=false' "$TEMPLATE"
grep -q 'ROOM_PARTITION_ASSIGNMENT_NODE_DRAIN_ENABLED_VALUE=false' "$TEMPLATE"
grep -q 'ROOM_PARTITION_LIFECYCLE_ENABLED_VALUE=false' "$TEMPLATE"
grep -q 'APP_ROLE="$APP_ROLE"' "$TEMPLATE"

echo "runtime role startup contract checks passed"
