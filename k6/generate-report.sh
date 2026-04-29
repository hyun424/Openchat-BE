#!/usr/bin/env bash
# =============================================================================
# generate-report.sh — k6 결과 JSON에서 Before/After 비교 리포트 생성
#
# 사용법:
#   ./generate-report.sh <results-dir>
#   ./generate-report.sh k6/results/20260214-153000
#
# 전제:
#   - jq 설치 필요
#   - <results-dir>/before/*.json 및 <results-dir>/after/*.json 존재
# =============================================================================
set -euo pipefail

RESULTS_DIR="${1:?사용법: $0 <results-dir>}"

if ! command -v jq >/dev/null 2>&1; then
  echo "❌ jq가 설치되지 않았습니다: brew install jq"
  exit 1
fi

REPORT="$RESULTS_DIR/report.md"

# ── 유틸리티 함수 ──

# jq로 메트릭 값 추출. 없으면 "N/A" 반환.
extract_metric() {
  local file="$1"
  local path="$2"

  if [[ ! -f "$file" ]]; then
    echo "N/A"
    return
  fi

  local val
  val=$(jq -r "$path // empty" "$file" 2>/dev/null)
  if [[ -z "$val" || "$val" == "null" ]]; then
    echo "N/A"
  else
    echo "$val"
  fi
}

# 숫자를 소수점 2자리로 포맷
fmt() {
  local val="$1"
  if [[ "$val" == "N/A" ]]; then
    echo "N/A"
  else
    printf "%.2f" "$val"
  fi
}

# 퍼센트를 소수점 2자리로 포맷
fmt_pct() {
  local val="$1"
  if [[ "$val" == "N/A" ]]; then
    echo "N/A"
  else
    printf "%.2f%%" "$(echo "$val * 100" | bc -l 2>/dev/null || echo "0")"
  fi
}

# delta 계산: ((after - before) / before) * 100
calc_delta() {
  local before="$1"
  local after="$2"

  if [[ "$before" == "N/A" || "$after" == "N/A" ]]; then
    echo "N/A"
    return
  fi

  # before가 0이면 비교 불가
  if (( $(echo "$before == 0" | bc -l) )); then
    if (( $(echo "$after == 0" | bc -l) )); then
      echo "0.00"
    else
      echo "N/A"
    fi
    return
  fi

  echo "scale=2; (($after - $before) / $before) * 100" | bc -l 2>/dev/null || echo "N/A"
}

# delta에 따른 아이콘 (낮을수록 좋은 메트릭용: 응답시간, 에러율)
delta_icon_lower_better() {
  local delta="$1"
  if [[ "$delta" == "N/A" ]]; then
    echo "➖"
    return
  fi

  if (( $(echo "$delta <= -5" | bc -l) )); then
    echo "✅"
  elif (( $(echo "$delta >= 5" | bc -l) )); then
    echo "❌"
  else
    echo "➖"
  fi
}

# delta에 따른 아이콘 (높을수록 좋은 메트릭용: 성공률, 처리량)
delta_icon_higher_better() {
  local delta="$1"
  if [[ "$delta" == "N/A" ]]; then
    echo "➖"
    return
  fi

  if (( $(echo "$delta >= 5" | bc -l) )); then
    echo "✅"
  elif (( $(echo "$delta <= -5" | bc -l) )); then
    echo "❌"
  else
    echo "➖"
  fi
}

# ── 시나리오별 메트릭 정의 ──

# 각 시나리오에 대해 추출할 메트릭과 jq 경로를 정의
# 형식: "표시이름|jq경로|방향(lower/higher)"

REST_METRICS=(
  "http_req p95 (ms)|.metrics.http_req_duration[\"p(95)\"]|lower"
  "http_req p90 (ms)|.metrics.http_req_duration[\"p(90)\"]|lower"
  "http_req avg (ms)|.metrics.http_req_duration.avg|lower"
  "http_error_rate|.metrics.http_error_rate.value|lower"
  "http_req_failed|.metrics.http_req_failed.value|lower"
  "iterations|.metrics.iterations.count|higher"
)

WS_METRICS=(
  "ws_connect p95 (ms)|.metrics.ws_connect_duration_ms[\"p(95)\"]|lower"
  "ws_connect p90 (ms)|.metrics.ws_connect_duration_ms[\"p(90)\"]|lower"
  "ws_roundtrip med (ms)|.metrics.ws_message_roundtrip_ms.med|lower"
  "ws_roundtrip p95 (ms)|.metrics.ws_message_roundtrip_ms[\"p(95)\"]|lower"
  "ws_connect_success|.metrics.ws_connect_success_rate.value|higher"
  "ws_messages_sent|.metrics.ws_messages_sent_total.count|higher"
  "iterations|.metrics.iterations.count|higher"
)

GENERAL_METRICS=(
  "http_req p95 (ms)|.metrics.http_req_duration[\"p(95)\"]|lower"
  "http_req p90 (ms)|.metrics.http_req_duration[\"p(90)\"]|lower"
  "http_req avg (ms)|.metrics.http_req_duration.avg|lower"
  "http_error_rate|.metrics.http_error_rate.value|lower"
  "http_req_failed|.metrics.http_req_failed.value|lower"
  "ws_roundtrip p95 (ms)|.metrics.ws_message_roundtrip_ms[\"p(95)\"]|lower"
  "iterations|.metrics.iterations.count|higher"
)

# 시나리오 이름 → 메트릭 매핑
get_metrics_for_scenario() {
  local scenario="$1"
  case "$scenario" in
    01-rest-api-load)   echo "REST" ;;
    02-websocket-stress) echo "WS" ;;
    *)                   echo "GENERAL" ;;
  esac
}

# ── 리포트 생성 ──

generate_summary_table() {
  local scenarios=("$@")

  echo "## Summary"
  echo ""
  echo "| Scenario | Metric | Before | After | Delta | |"
  echo "|----------|--------|-------:|------:|------:|-|"

  for scenario in "${scenarios[@]}"; do
    local before_file="$RESULTS_DIR/before/${scenario}.json"
    local after_file="$RESULTS_DIR/after/${scenario}.json"
    local scenario_label="${scenario#*-}"  # "rest-api-load", "websocket-stress" 등

    # 시나리오에 맞는 핵심 메트릭만 summary에 포함
    local key_metrics=()
    case "$scenario" in
      01-rest-api-load)
        key_metrics=(
          "http_req p95 (ms)|.metrics.http_req_duration[\"p(95)\"]|lower"
          "http_error_rate|.metrics.http_error_rate.value|lower"
        )
        ;;
      02-websocket-stress)
        key_metrics=(
          "ws_roundtrip p95 (ms)|.metrics.ws_message_roundtrip_ms[\"p(95)\"]|lower"
          "ws_connect_success|.metrics.ws_connect_success_rate.value|higher"
        )
        ;;
      *)
        key_metrics=(
          "http_req p95 (ms)|.metrics.http_req_duration[\"p(95)\"]|lower"
          "http_error_rate|.metrics.http_error_rate.value|lower"
        )
        ;;
    esac

    for metric_def in "${key_metrics[@]}"; do
      IFS='|' read -r name jq_path direction <<< "$metric_def"
      local bval aval delta icon

      bval=$(extract_metric "$before_file" "$jq_path")
      aval=$(extract_metric "$after_file" "$jq_path")
      delta=$(calc_delta "$bval" "$aval")

      if [[ "$direction" == "lower" ]]; then
        icon=$(delta_icon_lower_better "$delta")
      else
        icon=$(delta_icon_higher_better "$delta")
      fi

      local bfmt afmt dfmt
      bfmt=$(fmt "$bval")
      afmt=$(fmt "$aval")
      if [[ "$delta" == "N/A" ]]; then
        dfmt="N/A"
      else
        dfmt="${delta}%"
      fi

      echo "| $scenario_label | $name | $bfmt | $afmt | $dfmt | $icon |"
    done
  done

  echo ""
}

generate_detailed_section() {
  local scenario="$1"
  local before_file="$RESULTS_DIR/before/${scenario}.json"
  local after_file="$RESULTS_DIR/after/${scenario}.json"

  echo "### ${scenario}"
  echo ""

  # 시나리오 타입에 따른 메트릭 선택
  local metrics_type
  metrics_type=$(get_metrics_for_scenario "$scenario")

  local metrics=()
  case "$metrics_type" in
    REST)    metrics=("${REST_METRICS[@]}") ;;
    WS)      metrics=("${WS_METRICS[@]}") ;;
    GENERAL) metrics=("${GENERAL_METRICS[@]}") ;;
  esac

  echo "| Metric | Before | After | Delta | |"
  echo "|--------|-------:|------:|------:|-|"

  for metric_def in "${metrics[@]}"; do
    IFS='|' read -r name jq_path direction <<< "$metric_def"
    local bval aval delta icon

    bval=$(extract_metric "$before_file" "$jq_path")
    aval=$(extract_metric "$after_file" "$jq_path")
    delta=$(calc_delta "$bval" "$aval")

    if [[ "$direction" == "lower" ]]; then
      icon=$(delta_icon_lower_better "$delta")
    else
      icon=$(delta_icon_higher_better "$delta")
    fi

    local bfmt afmt dfmt
    bfmt=$(fmt "$bval")
    afmt=$(fmt "$aval")
    if [[ "$delta" == "N/A" ]]; then
      dfmt="N/A"
    else
      dfmt="${delta}%"
    fi

    echo "| $name | $bfmt | $afmt | $dfmt | $icon |"
  done

  echo ""
}

# ── 메인 ──

main() {
  # 존재하는 시나리오 결과 파일 수집
  local scenarios=()
  for f in "$RESULTS_DIR"/before/*.json "$RESULTS_DIR"/after/*.json; do
    if [[ -f "$f" ]]; then
      local name
      name=$(basename "$f" .json)
      # 중복 제거
      local found=false
      for s in "${scenarios[@]+"${scenarios[@]}"}"; do
        if [[ "$s" == "$name" ]]; then
          found=true
          break
        fi
      done
      if [[ "$found" == "false" ]]; then
        scenarios+=("$name")
      fi
    fi
  done

  if [[ ${#scenarios[@]} -eq 0 ]]; then
    echo "❌ 결과 파일이 없습니다: $RESULTS_DIR"
    exit 1
  fi

  # 정렬
  IFS=$'\n' scenarios=($(sort <<< "${scenarios[*]}")); unset IFS

  echo "📊 리포트 생성 중... (${#scenarios[@]}개 시나리오)"

  {
    echo "# Performance Comparison Report"
    echo ""
    echo "- **Date**: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "- **Results**: \`$RESULTS_DIR\`"
    echo "- **Scenarios**: ${scenarios[*]}"
    echo ""
    echo "---"
    echo ""
    echo "> Delta 기준: 5% 이상 개선 ✅ | 5% 이상 악화 ❌ | 그 외 ➖"
    echo ""

    generate_summary_table "${scenarios[@]}"

    echo "---"
    echo ""
    echo "## Detailed Results"
    echo ""

    for scenario in "${scenarios[@]}"; do
      generate_detailed_section "$scenario"
    done

    echo "---"
    echo ""
    echo "*Generated by \`generate-report.sh\` at $(date '+%Y-%m-%d %H:%M:%S')*"
  } > "$REPORT"

  echo "✅ 리포트 생성 완료: $REPORT"
}

main
