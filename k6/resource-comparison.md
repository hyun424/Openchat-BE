# Resource Comparison Load Test

This experiment compares scale-up and scale-out under the same total app
resource budget on a local Mac M4 Pro 24GB environment.

## Goal

Measure whether splitting WebSocket sessions across multiple app instances
reduces hot-room fan-out tail latency when total app CPU and memory are kept
the same.

## Profiles

| Profile | App layout | Total app resource |
| --- | --- | --- |
| `scale-up-small` | 1 app x 2 CPU / 4GB | 2 CPU / 4GB |
| `scale-out-small` | 2 apps x 1 CPU / 2GB | 2 CPU / 4GB |
| `scale-up-large` | 1 app x 4 CPU / 8GB | 4 CPU / 8GB |
| `scale-out-large` | 2 apps x 2 CPU / 4GB | 4 CPU / 8GB |

All profiles use the same MySQL, Redis, Kafka, and Nginx load balancer layout.
The public entrypoint is always:

```text
BASE_URL=http://127.0.0.1:8080
WS_BASE_URL=ws://127.0.0.1:8080
```

## Recommended Run

Run the small comparison first:

```bash
cd k6
make resource-small
```

Run with Prometheus/Grafana monitoring:

```bash
cd k6
make resource-small-monitor
```

If Docker Desktop still has enough CPU and memory headroom, run the larger
comparison:

```bash
cd k6
make resource-large
```

Run a single profile if needed:

```bash
bash k6/run-resource-comparison.sh scale-up-small
bash k6/run-resource-comparison.sh scale-out-small
MONITORING=true bash k6/run-resource-comparison.sh scale-out-small
```

## Tunable Parameters

```bash
VUS_LIST="100 200 300"
SEND_INTERVAL_MS=1000
CHAT_DURATION_SECONDS=120
KEEP_STACK=false
REMOVE_VOLUMES=true
MONITORING=false
K6_OUT=
```

Example:

```bash
VUS_LIST="100 200" CHAT_DURATION_SECONDS=90 bash k6/run-resource-comparison.sh scale-out-small
```

## Output

Results are written under:

```text
k6/results/resource-comparison/<timestamp>/<profile>/
```

Each VU run writes:

```text
<vus>vu-summary.json
<vus>vu.log
<vus>vu-docker-stats.txt
monitoring-links.txt
```

When monitoring is enabled:

```text
Grafana: http://localhost:3001
Prometheus: http://localhost:9090
cAdvisor: http://localhost:8081
```

The monitor targets also stream k6 metrics into InfluxDB when `K6_OUT` is set:

```bash
K6_OUT="influxdb=http://127.0.0.1:8086/k6"
```

## Interpretation

Scale-out improvement is meaningful if the same total app resource shows:

- lower `ws_message_roundtrip_ms` p95/p99
- lower `ws_connect_duration_ms` p95/p99
- similar or better `ws_connect_success_rate`
- no increase in `hot_room_duplicate_received_total`
- app instances receive traffic through the load balancer

If scale-out does not improve p95/p99, likely bottlenecks are shared DB/Redis,
k6/local machine saturation, or the current blocking local fan-out path.

This experiment is not a cloud capacity claim. It is a local same-resource
architecture comparison.
