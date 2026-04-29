#!/usr/bin/env bash
# =============================================================================
# run-comparison.sh — Before/After 성능 비교 자동화
#
# 사용법:
#   ./run-comparison.sh              # 기본: REST + WS + E2E + Spike (~50분)
#   ./run-comparison.sh --quick      # REST + WS만 (~25분)
#   ./run-comparison.sh --full       # 전체 5개 시나리오 + Soak (~5시간)
#
# 전제:
#   - git stash 가능한 unstaged 변경사항이 있어야 함
#   - jq, k6 설치 필요
#   - MySQL, Redis가 실행 중이어야 함
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
RESULTS_DIR="$SCRIPT_DIR/results/$TIMESTAMP"

BASE_URL="${BASE_URL:-http://localhost:8080}"
WS_BASE_URL="${WS_BASE_URL:-ws://localhost:8080}"
SPRING_PROFILE="${SPRING_PROFILE:-dev,loadtest}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-120}"
APP_PID=""
INFRA_BACKUP=""
STASH_ACTIVE=false

# ── 시나리오 목록 결정 ──

MODE="${1:-default}"
case "$MODE" in
  --quick)
    SCENARIOS=("01-rest-api-load" "02-websocket-stress")
    echo "▶ Quick 모드: REST + WS (~25분)"
    ;;
  --full)
    SCENARIOS=("01-rest-api-load" "02-websocket-stress" "03-mixed-e2e" "04-spike" "05-soak")
    echo "▶ Full 모드: 전체 5개 시나리오 (~5시간)"
    ;;
  --default|default|"")
    SCENARIOS=("01-rest-api-load" "02-websocket-stress" "03-mixed-e2e" "04-spike")
    echo "▶ 기본 모드: REST + WS + E2E + Spike (~50분)"
    ;;
  *)
    echo "사용법: $0 [--quick|--full]"
    exit 1
    ;;
esac

# ── 사전 검증 ──

check_prerequisites() {
  local missing=()
  command -v k6  >/dev/null 2>&1 || missing+=("k6")
  command -v jq  >/dev/null 2>&1 || missing+=("jq")
  command -v java >/dev/null 2>&1 || missing+=("java")

  if [[ ${#missing[@]} -gt 0 ]]; then
    echo "❌ 필수 도구가 설치되지 않았습니다: ${missing[*]}"
    echo "   brew install ${missing[*]}"
    exit 1
  fi

  # unstaged 변경사항 확인
  cd "$PROJECT_ROOT"
  if git diff --quiet && git diff --cached --quiet; then
    echo "❌ unstaged/staged 변경사항이 없습니다. Before/After 비교를 위해 변경사항이 필요합니다."
    exit 1
  fi
}

# ── 앱 빌드 & 부팅 ──

build_app() {
  echo "🔨 앱 빌드 중..."
  cd "$PROJECT_ROOT"
  ./gradlew build -x test --quiet 2>&1 | tail -5
  echo "✅ 빌드 완료"
}

start_app() {
  echo "🚀 앱 시작 중 (프로필: $SPRING_PROFILE)..."
  cd "$PROJECT_ROOT"
  ./gradlew bootRun --args="--spring.profiles.active=$SPRING_PROFILE" --quiet &
  APP_PID=$!
  echo "   PID: $APP_PID"

  # Health check 폴링
  local elapsed=0
  while [[ $elapsed -lt $BOOT_TIMEOUT ]]; do
    if curl -sf "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
      echo "✅ 앱 시작 완료 (${elapsed}초)"
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
    if (( elapsed % 10 == 0 )); then
      echo "   대기 중... (${elapsed}초)"
    fi
  done

  echo "❌ 앱 시작 타임아웃 (${BOOT_TIMEOUT}초)"
  stop_app
  exit 1
}

stop_app() {
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    echo "🛑 앱 종료 중 (PID: $APP_PID)..."
    kill "$APP_PID" 2>/dev/null || true
    # Graceful shutdown 대기
    local waited=0
    while kill -0 "$APP_PID" 2>/dev/null && [[ $waited -lt 30 ]]; do
      sleep 1
      waited=$((waited + 1))
    done
    if kill -0 "$APP_PID" 2>/dev/null; then
      echo "   ⚠ 강제 종료..."
      kill -9 "$APP_PID" 2>/dev/null || true
    fi
    echo "✅ 앱 종료 완료"
  fi
  APP_PID=""
}

# ── k6 시나리오 실행 ──

run_scenarios() {
  local phase="$1"   # "before" or "after"
  local out_dir="$RESULTS_DIR/$phase"
  mkdir -p "$out_dir"

  echo ""
  echo "═══════════════════════════════════════════════"
  echo "  📊 $phase 시나리오 실행 시작"
  echo "═══════════════════════════════════════════════"

  for scenario in "${SCENARIOS[@]}"; do
    local scenario_file="$SCRIPT_DIR/scenarios/${scenario}.js"
    local result_file="$out_dir/${scenario}.json"

    if [[ ! -f "$scenario_file" ]]; then
      echo "⚠ 시나리오 파일 없음: $scenario_file (건너뜀)"
      continue
    fi

    echo ""
    echo "▶ [$phase] $scenario 실행 중..."
    k6 run \
      -e BASE_URL="$BASE_URL" \
      -e WS_BASE_URL="$WS_BASE_URL" \
      --summary-export="$result_file" \
      "$scenario_file" || {
        echo "⚠ [$phase] $scenario 실패 (결과 저장은 시도됨)"
      }

    echo "✅ [$phase] $scenario 완료 → $result_file"
    sleep 3
  done
}

# ── 클린업 핸들러 ──

cleanup() {
  echo ""
  echo "🧹 클린업 중..."
  stop_app
  cd "$PROJECT_ROOT"
  if $STASH_ACTIVE; then
    # working tree 초기화 (dump.rdb 변경 + 복원된 untracked 파일 제거)
    git checkout . 2>/dev/null || true
    git clean -fd 2>/dev/null || true
    echo "   git stash pop (변경사항 복원)..."
    git stash pop --quiet 2>/dev/null || true
    STASH_ACTIVE=false
  fi
  # 임시 백업 디렉토리 정리
  if [[ -n "$INFRA_BACKUP" && -d "$INFRA_BACKUP" ]]; then
    rm -rf "$INFRA_BACKUP"
  fi
}

trap cleanup EXIT

# =============================================================================
# 메인 실행 흐름
# =============================================================================

main() {
  check_prerequisites

  mkdir -p "$RESULTS_DIR/before" "$RESULTS_DIR/after"
  echo ""
  echo "📁 결과 디렉토리: $RESULTS_DIR"
  echo ""

  # ── 테스트 인프라 백업 (stash가 untracked 파일을 제거하므로) ──
  INFRA_BACKUP=$(mktemp -d)
  cp -r "$SCRIPT_DIR" "$INFRA_BACKUP/k6"
  if [[ -f "$PROJECT_ROOT/src/main/resources/application-loadtest.properties" ]]; then
    cp "$PROJECT_ROOT/src/main/resources/application-loadtest.properties" "$INFRA_BACKUP/"
  fi
  if [[ -f "$PROJECT_ROOT/src/main/resources/application-dev.properties" ]]; then
    cp "$PROJECT_ROOT/src/main/resources/application-dev.properties" "$INFRA_BACKUP/"
  fi
  # DevAuthController 백업 (loadtest 프로필 로그인 엔드포인트)
  local dev_auth="src/main/java/io/hyun424/openchat/auth/controller/DevAuthController.java"
  if [[ -f "$PROJECT_ROOT/$dev_auth" ]]; then
    mkdir -p "$INFRA_BACKUP/auth-controller"
    cp "$PROJECT_ROOT/$dev_auth" "$INFRA_BACKUP/auth-controller/"
  fi
  echo "📦 테스트 인프라 백업 완료: $INFRA_BACKUP"

  # ── Phase 1: Before (변경사항 stash) ──
  echo ""
  echo "═══════════════════════════════════════════════"
  echo "  Phase 1: BEFORE (원본 코드)"
  echo "═══════════════════════════════════════════════"

  cd "$PROJECT_ROOT"
  echo "📦 변경사항 stash 중..."
  git stash push -m "comparison-test-$TIMESTAMP" --include-untracked
  STASH_ACTIVE=true
  echo "✅ git stash 완료"

  # 테스트 인프라 복원 (k6 시나리오 + loadtest 프로필 + DevAuthController)
  mkdir -p "$SCRIPT_DIR/scenarios" "$SCRIPT_DIR/lib"
  cp -r "$INFRA_BACKUP/k6/scenarios/"* "$SCRIPT_DIR/scenarios/"
  cp -r "$INFRA_BACKUP/k6/lib/"* "$SCRIPT_DIR/lib/"
  mkdir -p "$RESULTS_DIR/before"
  if [[ -f "$INFRA_BACKUP/application-loadtest.properties" ]]; then
    cp "$INFRA_BACKUP/application-loadtest.properties" \
       "$PROJECT_ROOT/src/main/resources/application-loadtest.properties"
  fi
  if [[ -f "$INFRA_BACKUP/application-dev.properties" ]]; then
    cp "$INFRA_BACKUP/application-dev.properties" \
       "$PROJECT_ROOT/src/main/resources/application-dev.properties"
  fi
  # DevAuthController 복원 (Before 빌드에서도 /api/auth/login 사용 가능하도록)
  local dev_auth_dir="$PROJECT_ROOT/src/main/java/io/hyun424/openchat/auth/controller"
  if [[ -f "$INFRA_BACKUP/auth-controller/DevAuthController.java" ]]; then
    cp "$INFRA_BACKUP/auth-controller/DevAuthController.java" "$dev_auth_dir/"
  fi
  echo "✅ 테스트 인프라 복원 완료"

  build_app
  start_app
  run_scenarios "before"
  stop_app

  # before 결과를 백업에 보관
  cp -r "$RESULTS_DIR/before" "$INFRA_BACKUP/before-results"

  # working tree 초기화 (dump.rdb 변경 + 복원된 untracked 파일 제거 → stash pop 충돌 방지)
  cd "$PROJECT_ROOT"
  git checkout . 2>/dev/null || true
  git clean -fd 2>/dev/null || true

  # ── Phase 2: After (변경사항 복원) ──
  echo ""
  echo "═══════════════════════════════════════════════"
  echo "  Phase 2: AFTER (개선된 코드)"
  echo "═══════════════════════════════════════════════"

  cd "$PROJECT_ROOT"
  echo "📦 변경사항 복원 중..."
  git stash pop
  STASH_ACTIVE=false
  echo "✅ git stash pop 완료"

  # before 결과 복원
  mkdir -p "$RESULTS_DIR/before" "$RESULTS_DIR/after"
  cp -r "$INFRA_BACKUP/before-results/"* "$RESULTS_DIR/before/"

  build_app
  start_app
  run_scenarios "after"
  stop_app

  # ── Phase 3: 리포트 생성 ──
  echo ""
  echo "═══════════════════════════════════════════════"
  echo "  Phase 3: 리포트 생성"
  echo "═══════════════════════════════════════════════"

  "$SCRIPT_DIR/generate-report.sh" "$RESULTS_DIR"

  # 임시 백업 정리
  rm -rf "$INFRA_BACKUP"
  INFRA_BACKUP=""

  echo ""
  echo "═══════════════════════════════════════════════"
  echo "  ✅ 비교 테스트 완료!"
  echo "  📄 리포트: $RESULTS_DIR/report.md"
  echo "═══════════════════════════════════════════════"
}

main
