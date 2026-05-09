#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
METRICS_FILE="$ROOT_DIR/k6/lib/metrics.js"
WS_FILE="$ROOT_DIR/k6/lib/ws.js"

grep -q "wsLatestVisibleFreshness" "$METRICS_FILE"
grep -q "ws_visible_latest_freshness_ms" "$METRICS_FILE"
grep -q "wsVisibleGapMessages" "$METRICS_FILE"
grep -q "ws_visible_gap_messages" "$METRICS_FILE"

grep -q "wsLatestVisibleFreshness.add" "$WS_FILE"
grep -q "wsVisibleGapMessages.add" "$WS_FILE"
grep -q "latestCreatedAt" "$WS_FILE"

echo "k6 visible freshness metric tests passed"
