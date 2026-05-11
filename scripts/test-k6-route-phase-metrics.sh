#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
METRICS_FILE="$ROOT_DIR/k6/lib/metrics.js"
SCENARIO="$ROOT_DIR/k6/scenarios/11-mixed-room-workload-ramped.js"
WS_FILE="$ROOT_DIR/k6/lib/ws.js"

grep -q "ws_initial_route_partition_id" "$METRICS_FILE"
grep -q "ws_initial_route_node_total" "$METRICS_FILE"
grep -q "ws_reconnect_route_partition_id" "$METRICS_FILE"
grep -q "ws_reconnect_route_node_total" "$METRICS_FILE"

grep -q "wsInitialRoutePartitionId.add" "$SCENARIO"
grep -q "wsInitialRouteNodeTotal.add" "$SCENARIO"
grep -q "getWebSocketRoute(token, roomId, roomType, 'initial')" "$SCENARIO"

grep -q "wsReconnectRoutePartitionId.add" "$WS_FILE"
grep -q "wsReconnectRouteNodeTotal.add" "$WS_FILE"
grep -q "WS reconnect route" "$WS_FILE"

echo "k6 route phase metric tests passed"
