#!/usr/bin/env bash
set -euo pipefail

COMPOSE_FILE=${COMPOSE_FILE:-docker-compose.loadtest.yml}
BASE_URL=${BASE_URL:-http://127.0.0.1:8080}
WS_BASE_URL=${WS_BASE_URL:-ws://127.0.0.1:8080}
SEND_INTERVAL_MS=${SEND_INTERVAL_MS:-1000}
CHAT_DURATION_SECONDS=${CHAT_DURATION_SECONDS:-120}
VUS_LIST=${VUS_LIST:-100 200 300}
RESULT_ROOT=${RESULT_ROOT:-k6/results/resource-comparison/$(date +%Y%m%d-%H%M%S)}
SCENARIO=${SCENARIO:-k6/scenarios/07-hot-room-fixed.js}
PROFILE=${1:-scale-up-small}
KEEP_STACK=${KEEP_STACK:-false}
REMOVE_VOLUMES=${REMOVE_VOLUMES:-true}
MONITORING=${MONITORING:-false}
K6_OUT=${K6_OUT:-}
K6_INFLUX_ENABLED=${K6_INFLUX_ENABLED:-false}
NO_CACHE=${NO_CACHE:-false}

cleanup() {
  if [ "${KEEP_STACK}" = "true" ]; then
    echo "Keeping compose stack running because KEEP_STACK=true"
    return
  fi
  if [ "${REMOVE_VOLUMES}" = "true" ]; then
    docker-compose -f "${COMPOSE_FILE}" --profile "${PROFILE}" down -v
  else
    docker-compose -f "${COMPOSE_FILE}" --profile "${PROFILE}" down
  fi
  if [ "${MONITORING}" = "true" ]; then
    docker-compose -f k6/docker-compose.monitoring.yml down
  fi
}

trap cleanup EXIT

wait_for_app() {
  local max_attempts=90
  local attempt=1

  until curl --max-time 2 -sf "${BASE_URL}/actuator/health" >/dev/null 2>&1; do
    if [ "$attempt" -ge "$max_attempts" ]; then
      echo "App did not become healthy at ${BASE_URL}/actuator/health" >&2
      return 1
    fi
    sleep 2
    attempt=$((attempt + 1))
  done
}

run_fixed_vu() {
  local vus=$1
  local label="${PROFILE}-${vus}vu"
  local output_dir="${RESULT_ROOT}/${PROFILE}"
  mkdir -p "$output_dir"

  echo "Running ${label}"
  if [ -n "${K6_OUT}" ]; then
    k6 run \
      --out "${K6_OUT}" \
      --summary-export "${output_dir}/${vus}vu-summary.json" \
      -e BASE_URL="${BASE_URL}" \
      -e WS_BASE_URL="${WS_BASE_URL}" \
      -e TARGET_VUS="${vus}" \
      -e TEST_LABEL="${label}" \
      -e SEND_INTERVAL_MS="${SEND_INTERVAL_MS}" \
      -e CHAT_DURATION_SECONDS="${CHAT_DURATION_SECONDS}" \
      "${SCENARIO}" | tee "${output_dir}/${vus}vu.log"
  else
    k6 run \
      --summary-export "${output_dir}/${vus}vu-summary.json" \
      -e BASE_URL="${BASE_URL}" \
      -e WS_BASE_URL="${WS_BASE_URL}" \
      -e TARGET_VUS="${vus}" \
      -e TEST_LABEL="${label}" \
      -e SEND_INTERVAL_MS="${SEND_INTERVAL_MS}" \
      -e CHAT_DURATION_SECONDS="${CHAT_DURATION_SECONDS}" \
      "${SCENARIO}" | tee "${output_dir}/${vus}vu.log"
  fi

  docker stats --no-stream > "${output_dir}/${vus}vu-docker-stats.txt" || true
}

echo "Starting profile: ${PROFILE}"
if [ "${NO_CACHE}" = "true" ]; then
  docker-compose -f "${COMPOSE_FILE}" --profile "${PROFILE}" build --no-cache
fi
docker-compose -f "${COMPOSE_FILE}" --profile "${PROFILE}" up -d --build --force-recreate

if [ "${MONITORING}" = "true" ]; then
  echo "Starting monitoring stack"
  docker-compose -f k6/docker-compose.monitoring.yml up -d
  if [ "${K6_INFLUX_ENABLED}" = "true" ] && [ -z "${K6_OUT}" ]; then
    K6_OUT="influxdb=http://127.0.0.1:8086/k6"
  fi
  mkdir -p "${RESULT_ROOT}/${PROFILE}"
  cat > "${RESULT_ROOT}/${PROFILE}/monitoring-links.txt" <<EOF
Grafana: http://localhost:3001
Prometheus: http://localhost:9090
cAdvisor: http://localhost:8081
InfluxDB: http://localhost:8086
k6 Influx output: ${K6_INFLUX_ENABLED}
EOF
fi

echo "Waiting for app health"
wait_for_app

for vus in ${VUS_LIST}; do
  run_fixed_vu "$vus"
done

echo "Results written to ${RESULT_ROOT}/${PROFILE}"
