BASE_URL ?= http://localhost:8080
WS_BASE_URL ?= ws://localhost:8080
INFLUXDB_URL ?= http://localhost:8086/k6
TARGET_VUS ?= 100
SEND_INTERVAL_MS ?= 1000
CHAT_DURATION_SECONDS ?= 120

K6_ENV = -e BASE_URL=$(BASE_URL) -e WS_BASE_URL=$(WS_BASE_URL)
K6_OUT = --out influxdb=$(INFLUXDB_URL)

.PHONY: help test-rest test-ws test-e2e test-spike test-soak monitoring-up monitoring-down compare compare-quick compare-full report resource-small resource-small-monitor resource-large resource-large-monitor resource-up-small resource-out-small resource-up-large resource-out-large resource-down

help: ## 사용 가능한 명령어 목록
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

# ── 시나리오 실행 ──

test-rest: ## REST API 부하 테스트
	k6 run $(K6_ENV) $(K6_OUT) scenarios/01-rest-api-load.js

test-ws: ## WebSocket 스트레스 테스트
	k6 run $(K6_ENV) $(K6_OUT) scenarios/02-websocket-stress.js

test-e2e: ## 혼합 E2E 시나리오
	k6 run $(K6_ENV) $(K6_OUT) scenarios/03-mixed-e2e.js

test-spike: ## 스파이크 테스트
	k6 run $(K6_ENV) $(K6_OUT) scenarios/04-spike.js

test-soak: ## 내구성(Soak) 테스트 (4시간)
	k6 run $(K6_ENV) $(K6_OUT) scenarios/05-soak.js

# ── CLI 전용 (InfluxDB 없이) ──

test-rest-cli: ## REST API 부하 테스트 (CLI 출력만)
	k6 run $(K6_ENV) scenarios/01-rest-api-load.js

test-ws-cli: ## WebSocket 스트레스 테스트 (CLI 출력만)
	k6 run $(K6_ENV) scenarios/02-websocket-stress.js

test-e2e-cli: ## 혼합 E2E 시나리오 (CLI 출력만)
	k6 run $(K6_ENV) scenarios/03-mixed-e2e.js

test-hot-room-fixed-cli: ## 고정 VU Hot Room 테스트 (TARGET_VUS=100 기본)
	k6 run $(K6_ENV) -e TARGET_VUS=$(TARGET_VUS) -e SEND_INTERVAL_MS=$(SEND_INTERVAL_MS) -e CHAT_DURATION_SECONDS=$(CHAT_DURATION_SECONDS) scenarios/07-hot-room-fixed.js

# ── 모니터링 인프라 ──

monitoring-up: ## 모니터링 스택 시작 (Grafana + Prometheus + InfluxDB)
	docker-compose -f docker-compose.monitoring.yml up -d

monitoring-down: ## 모니터링 스택 중지
	docker-compose -f docker-compose.monitoring.yml down

monitoring-logs: ## 모니터링 스택 로그
	docker-compose -f docker-compose.monitoring.yml logs -f

# ── 전체 ──

test-all: test-rest test-ws test-e2e ## REST + WS + E2E 순차 실행

# ── Before/After 비교 ──

compare: ## Before/After 비교 (REST+WS+E2E+Spike, ~50분)
	bash run-comparison.sh

compare-quick: ## Before/After 빠른 비교 (REST+WS, ~25분)
	bash run-comparison.sh --quick

compare-full: ## Before/After 전체 비교 (5개 시나리오+Soak, ~5시간)
	bash run-comparison.sh --full

report: ## 기존 결과로 리포트만 재생성 (RESULT_DIR 지정 필요)
	@if [ -z "$(RESULT_DIR)" ]; then \
		echo "사용법: make report RESULT_DIR=results/20260214-153000"; \
		exit 1; \
	fi
	bash generate-report.sh $(RESULT_DIR)

# ── 동일 자원 Scale-up / Scale-out 비교 ──

resource-small: ## 2CPU/4GB 총량 기준 scale-up vs scale-out 연속 비교
	@RESULT_ROOT="k6/results/resource-comparison/$$(date +%Y%m%d-%H%M%S)-small"; \
	(cd .. && RESULT_ROOT="$$RESULT_ROOT" bash k6/run-resource-comparison.sh scale-up-small) && \
	(cd .. && RESULT_ROOT="$$RESULT_ROOT" bash k6/run-resource-comparison.sh scale-out-small)

resource-small-monitor: ## 모니터링 포함 2CPU/4GB 총량 기준 scale-up vs scale-out 연속 비교
	@RESULT_ROOT="k6/results/resource-comparison/$$(date +%Y%m%d-%H%M%S)-small-monitor"; \
	(cd .. && MONITORING=true RESULT_ROOT="$$RESULT_ROOT" bash k6/run-resource-comparison.sh scale-up-small) && \
	(cd .. && MONITORING=true RESULT_ROOT="$$RESULT_ROOT" bash k6/run-resource-comparison.sh scale-out-small)

resource-large: ## 4CPU/8GB 총량 기준 scale-up vs scale-out 연속 비교
	@RESULT_ROOT="k6/results/resource-comparison/$$(date +%Y%m%d-%H%M%S)-large"; \
	(cd .. && RESULT_ROOT="$$RESULT_ROOT" VUS_LIST="200 300 500" bash k6/run-resource-comparison.sh scale-up-large) && \
	(cd .. && RESULT_ROOT="$$RESULT_ROOT" VUS_LIST="200 300 500" bash k6/run-resource-comparison.sh scale-out-large)

resource-large-monitor: ## 모니터링 포함 4CPU/8GB 총량 기준 scale-up vs scale-out 연속 비교
	@RESULT_ROOT="k6/results/resource-comparison/$$(date +%Y%m%d-%H%M%S)-large-monitor"; \
	(cd .. && MONITORING=true RESULT_ROOT="$$RESULT_ROOT" VUS_LIST="200 300 500" bash k6/run-resource-comparison.sh scale-up-large) && \
	(cd .. && MONITORING=true RESULT_ROOT="$$RESULT_ROOT" VUS_LIST="200 300 500" bash k6/run-resource-comparison.sh scale-out-large)

resource-up-small: ## 2CPU/4GB app 1대 profile 실행 후 고정 VU 비교
	cd .. && bash k6/run-resource-comparison.sh scale-up-small

resource-out-small: ## 1CPU/2GB app 2대 profile 실행 후 고정 VU 비교
	cd .. && bash k6/run-resource-comparison.sh scale-out-small

resource-up-large: ## 4CPU/8GB app 1대 profile 실행 후 고정 VU 비교
	cd .. && VUS_LIST="200 300 500" bash k6/run-resource-comparison.sh scale-up-large

resource-out-large: ## 2CPU/4GB app 2대 profile 실행 후 고정 VU 비교
	cd .. && VUS_LIST="200 300 500" bash k6/run-resource-comparison.sh scale-out-large

resource-down: ## resource comparison compose stack 종료
	cd .. && docker-compose -f docker-compose.loadtest.yml down
