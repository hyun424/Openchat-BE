#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCENARIO="$ROOT_DIR/k6/scenarios/11-mixed-room-workload-ramped.js"
K6_TEMPLATE="$ROOT_DIR/infra/gcp-loadtest/templates/k6-startup.sh.tftpl"
TERRAFORM_VARIABLES="$ROOT_DIR/infra/gcp-loadtest/variables.tf"
TERRAFORM_MAIN="$ROOT_DIR/infra/gcp-loadtest/main.tf"
MINI_SOAK_PROFILE="$ROOT_DIR/infra/gcp-loadtest/profiles/room-partition-rolling-restart-mini-soak.tfvars.example"
FRESHNESS_PROFILE="$ROOT_DIR/infra/gcp-loadtest/profiles/room-partition-rolling-restart-freshness-check.tfvars.example"

grep -q 'if (CHAT_ACK_P95_THRESHOLD_MS > 0)' "$SCENARIO"
grep -q "thresholds\\['chat_ack_roundtrip_ms{presenceMode:active,clientMode:sender}'\\]" "$SCENARIO"

grep -q 'K6_VISIBLE_LATEST_FRESHNESS_P95_THRESHOLD_MS' "$SCENARIO"
grep -q "thresholds\\['ws_visible_latest_freshness_ms{presenceMode:active,clientMode:observer,roomType:hot}'\\]" "$SCENARIO"
if grep -q "thresholds\\['ws_visible_freshness_ms{presenceMode:active,clientMode:observer}'\\]" "$SCENARIO"; then
  echo "scenario 11 must not hard-gate legacy full visible freshness" >&2
  exit 1
fi

grep -q 'variable "k6_visible_latest_freshness_p95_threshold_ms"' "$TERRAFORM_VARIABLES"
grep -q 'k6_visible_latest_freshness_p95_threshold_ms' "$TERRAFORM_MAIN"
grep -q 'K6_VISIBLE_LATEST_FRESHNESS_P95_THRESHOLD_MS="${k6_visible_latest_freshness_p95_threshold_ms}"' "$K6_TEMPLATE"
grep -q 'K6_VISIBLE_LATEST_FRESHNESS_P95_THRESHOLD_MS="$K6_VISIBLE_LATEST_FRESHNESS_P95_THRESHOLD_MS"' "$K6_TEMPLATE"
grep -q 'K6_VISIBLE_FRESHNESS_P95_THRESHOLD_MS="$K6_VISIBLE_FRESHNESS_P95_THRESHOLD_MS"' "$K6_TEMPLATE"
grep -q 'K6_VISIBLE_FRESHNESS_P95_THRESHOLD_MS="$K6_VISIBLE_FRESHNESS_P95_THRESHOLD_MS"' "$K6_TEMPLATE"
grep -q 'capture("^\[^{\]+\\\\{(?<tags>.*)\\\\}$").tags' "$K6_TEMPLATE"
if grep -q 'K6_VISIBLE_FRESHNESS_P95_THRESHOLD_MS="1000"' "$K6_TEMPLATE"; then
  echo "post-stop probe must not hardcode freshness as a k6 hard threshold" >&2
  exit 1
fi
grep -q 'K6_VISIBLE_LATEST_FRESHNESS_P95_THRESHOLD_MS="0"' "$K6_TEMPLATE"
grep -q 'K6_CHAT_ACK_P95_THRESHOLD_MS="0"' "$K6_TEMPLATE"

grep -q 'write_validation_gates ' "$K6_TEMPLATE"
grep -q 'validation-gates-' "$K6_TEMPLATE"
grep -q 'correctness:' "$K6_TEMPLATE"
grep -q 'drainTermination:' "$K6_TEMPLATE"
grep -q 'performance:' "$K6_TEMPLATE"
grep -q 'postStopFreshness:' "$K6_TEMPLATE"
grep -q 'cleanup:' "$K6_TEMPLATE"
grep -q 'visibleGapP95' "$K6_TEMPLATE"
grep -q 'handlerDurationP95' "$K6_TEMPLATE"
grep -q 'jsonParseDurationP95' "$K6_TEMPLATE"
grep -q 'batchMessagesP95' "$K6_TEMPLATE"
grep -q 'NO_DATA' "$K6_TEMPLATE"
grep -q 'json_metric_value_by_tags "$summary_file" "ws_visible_latest_freshness_ms" "p(95)" "presenceMode=active" "clientMode=observer" "roomType=hot"' "$K6_TEMPLATE"
grep -q 'json_metric_present_by_tags "$summary_file" "ws_visible_latest_freshness_ms" "presenceMode=active" "clientMode=observer" "roomType=hot"' "$K6_TEMPLATE"
grep -q 'json_metric_value "$summary_file" "ws_visible_latest_slo_samples_total" "count"' "$K6_TEMPLATE"
if grep -q 'json_metric_value_by_tags "$summary_file" "ws_visible_latest_samples_total" "count" "presenceMode=active" "clientMode=observer" "roomType=hot"' "$K6_TEMPLATE"; then
  echo "validation gate must use the explicit SLO sample counter, not tagged generic latest samples" >&2
  exit 1
fi
grep -q '\[ "$metric_present" != "true" \] || \[ "$sample_count" -le 0 \]' "$K6_TEMPLATE"

grep -q 'k6_chat_ack_p95_threshold_ms *= *0' "$MINI_SOAK_PROFILE"
grep -q 'k6_visible_freshness_p95_threshold_ms *= *0' "$MINI_SOAK_PROFILE"
grep -q 'k6_visible_latest_freshness_p95_threshold_ms *= *1000' "$FRESHNESS_PROFILE"
grep -q 'k6_visible_freshness_p95_threshold_ms *= *0' "$FRESHNESS_PROFILE"

echo "rolling restart validation gate checks passed"
