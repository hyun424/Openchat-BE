#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCENARIO="$ROOT_DIR/k6/scenarios/11-mixed-room-workload-ramped.js"
K6_TEMPLATE="$ROOT_DIR/infra/gcp-loadtest/templates/k6-startup.sh.tftpl"
MINI_SOAK_PROFILE="$ROOT_DIR/infra/gcp-loadtest/profiles/room-partition-rolling-restart-mini-soak.tfvars.example"

grep -q 'if (CHAT_ACK_P95_THRESHOLD_MS > 0)' "$SCENARIO"
grep -q "thresholds\\['chat_ack_roundtrip_ms{presenceMode:active,clientMode:sender}'\\]" "$SCENARIO"

grep -q 'K6_VISIBLE_FRESHNESS_P95_THRESHOLD_MS="$K6_VISIBLE_FRESHNESS_P95_THRESHOLD_MS"' "$K6_TEMPLATE"
if grep -q 'K6_VISIBLE_FRESHNESS_P95_THRESHOLD_MS="1000"' "$K6_TEMPLATE"; then
  echo "post-stop probe must not hardcode freshness as a k6 hard threshold" >&2
  exit 1
fi

grep -q 'write_validation_gates ' "$K6_TEMPLATE"
grep -q 'validation-gates-' "$K6_TEMPLATE"
grep -q 'correctness:' "$K6_TEMPLATE"
grep -q 'drainTermination:' "$K6_TEMPLATE"
grep -q 'performance:' "$K6_TEMPLATE"
grep -q 'postStopFreshness:' "$K6_TEMPLATE"
grep -q 'cleanup:' "$K6_TEMPLATE"

grep -q 'k6_chat_ack_p95_threshold_ms *= *0' "$MINI_SOAK_PROFILE"
grep -q 'k6_visible_freshness_p95_threshold_ms *= *0' "$MINI_SOAK_PROFILE"

echo "rolling restart validation gate checks passed"
