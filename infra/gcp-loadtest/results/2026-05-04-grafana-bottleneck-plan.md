# Grafana 포함 병목 확정 측정 계획

## 목적

이 작업의 목적은 성능 튜닝이 아니라 `500명 x 3방`에서 관측된 약 `10s` tail이 어디에서 생기는지 확정하는 것이다.

비교 대상은 같은 서버 topology에서 다음 두 실행이다.

- control run: k6 1대, 3방 x 500명
- distributed run: k6 3대, worker당 1방 x 500명

단일 k6가 모든 room의 수신/파싱/metric 기록을 맡을 때만 tail이 커지는지, 서버 fan-out/send 단계 자체가 느린지 분리해서 본다.

## 구현 내용

### 모니터링 VM

- `enable_monitoring=true`일 때 monitoring VM을 생성한다.
- monitoring VM은 Docker로 Prometheus, Grafana, InfluxDB, MySQL exporter, Redis exporter를 실행한다.
- Grafana external IP는 run id와 무관한 static address `openchat-loadtest-grafana-ip`를 사용한다.
- Terraform output에 `grafana_url`과 `grafana_static_ip`를 추가했다.
- 테스트 종료 시 k6 coordinator가 monitoring VM은 삭제하지만 static IP는 유지한다.

### 접근 제어

- 일반 LB/API/Realtime/MySQL/Redis/k6 VM은 external IP 없이 Cloud NAT로 outbound만 사용한다.
- Grafana `3000`만 `grafana_source_ranges`에서 접근 가능하다.
- Prometheus `9090`과 InfluxDB `8086`은 외부에 공개하지 않는다.
- k6 worker는 내부 IP로 InfluxDB `8086`에 metric을 전송한다.
- monitoring VM은 내부 IP로 LB/API/Realtime `/actuator/prometheus`를 scrape한다.

### Grafana provisioning

Grafana startup 시 datasource와 dashboard를 자동 provisioning한다.

- datasource: `Prometheus`
- datasource: `InfluxDB-k6`
- dashboard: `OpenChat Server Pipeline`
- dashboard: `OpenChat k6 E2E`
- dashboard: `OpenChat Infra`

빈 Grafana에서 수동으로 datasource/dashboard를 붙이는 단계 없이 바로 확인하는 구성이 목표다.

### 측정 metric

k6 WebSocket send payload에 optional `clientSentAt`을 추가했다. 서버는 이 값이 있을 때만 client 기준 lag metric을 기록하고, 값이 없는 일반 클라이언트 동작은 유지한다.

추가/보강한 서버 metric:

- `ws.inbound.received.since_client_sent`
- `ws.ack.before_send.since_client_sent`
- `ws.ack.after_send.since_client_sent`
- `ws.broadcast.enqueue.since_created`
- `ws.broadcast.lane_start.since_created`
- `ws.broadcast.lane_done.since_created`

### 분산 k6 실행

- `k6_worker_count` 기본값은 `1`이다.
- `k6_worker_count>1`이면 k6 VM을 여러 대 만들고 GCS ready barrier로 동시 시작한다.
- worker별 결과는 `k6/<profile>/<worker>/` 아래 분리 저장한다.
- distributed 실행에서는 `hot_rooms`와 `vus_list`의 각 값이 `k6_worker_count`로 나누어 떨어져야 한다.

### k6 metric 출력량 제어

본테스트 초반에는 InfluxDB로 너무 많은 per-message metric이 들어가면서 `Request Entity Too Large`와 flush 지연이 발생했다. 이 상태에서는 Grafana/InfluxDB 경로가 다시 병목에 섞이므로 병목 판정에 부적합하다.

이를 막기 위해 k6 metric을 다음처럼 줄였다.

- received/sent/ack/omitted counter는 VU 내부에서 누적 후 5초마다 flush한다.
- visible freshness trend는 수신 메시지 100개마다 1개만 sampling한다.
- hot-room per-received detail metric은 기본 비활성화했다.
- InfluxDB body limit과 k6 Influx output batch 설정을 조정했다.

이 변경 후 control run에서는 InfluxDB `413`과 flush 지연 로그가 사라졌다.

## 확인 순서

1. monitoring smoke: Grafana 접속, dashboard provisioning, Prometheus target up, k6 InfluxDB metric 유입 확인.
2. control run: k6 1대, 3방 x 500명.
3. distributed run: k6 3대, worker당 1방 x 500명.
4. Grafana와 k6 summary를 함께 보고 판정한다.

## 스모크 테스트 결과

- run id: `20260504-0230-monitoring-smoke`
- Grafana static URL: `http://34.64.50.210:3000`
- Grafana health API: 정상
- datasource provisioning: `Prometheus`, `InfluxDB-k6` 확인
- dashboard provisioning: `OpenChat Server Pipeline`, `OpenChat k6 E2E`, `OpenChat Infra` 확인
- Prometheus target: `openchat-lb`, `openchat-api`, `openchat-realtime`, `mysql`, `redis`, `prometheus` 모두 `up=1` 확인
- InfluxDB-k6 metric: `chat_ack_roundtrip_ms`, `ws_visible_freshness_ms`, `ws_messages_sent_total`, `ws_messages_received_total` 등 유입 확인
- GCS worker marker: `workers/single.ready`, `workers/single.done` 확인
- VM cleanup: run label 기준 남은 VM 없음

k6 요약:

| 항목 | 값 |
| --- | ---: |
| WebSocket connect success rate | 1.0 |
| HTTP error rate | 0 |
| ack RTT p95 | 44ms |
| ack RTT p99 | 60.11ms |
| visible freshness p95 | 40ms |
| visible freshness p99 | 56ms |
| sent messages | 1,793 |
| received messages | 17,639 |
| omitted messages | 0 |

스모크 목적은 성능 판정이 아니라 모니터링/수집 경로 검증이다. Grafana, Prometheus scrape, InfluxDB-k6, worker marker, 자동 VM 정리가 모두 동작했으므로 본테스트로 진행한다.

## 본테스트 실행 이슈

- 최초 본테스트는 SSD quota 250GB를 초과했다. 이후 disk size를 줄여 control은 200GB, distributed는 220GB로 실행했다.
- `20260504-0300-mhr500x3-control`은 room name 길이 제한으로 setup 실패했다. room name을 짧게 만들고 room 생성 응답 parser를 보강했다.
- `20260504-0312-mhr500x3-control`은 k6 summary는 나왔지만 InfluxDB `Request Entity Too Large`가 반복되어 Grafana-k6 경로가 깨졌다.
- `20260504-0326-mhr500x3-control`은 InfluxDB flush가 길어져 k6 종료가 지연됐다.
- 이후 k6 metric 출력량을 줄인 뒤 `20260504-0345-mhr500x3-control`은 정상 종료했다.
- distributed는 원래 k6 worker 3대를 모두 `e2-standard-16`으로 만들 계획이었지만, GCP 전역 CPU quota 72에 걸렸다. 최종 실행은 worker-1/3이 `e2-standard-4`, worker-2가 기존 partial VM 때문에 `e2-standard-16`으로 남은 상태에서 진행됐다. 이 점 때문에 distributed 결과는 “동일 worker spec 비교”가 아니라 “k6 분산 및 k6 VM spec 민감도 확인”으로 해석해야 한다.

## 본테스트 결과

### Control run

- run id: `20260504-0345-mhr500x3-control`
- 구조: k6 1대, 3방 x 500명
- k6 VM: `e2-standard-16`
- exit code: `0`
- InfluxDB `413`/flush 지연 로그: 없음
- 주의: `visible freshness`는 수신 메시지 100개당 1개 sampling 값이다. 이전 per-message full metric 값과 절대값을 1:1 비교하지 않는다.

| 항목 | 값 |
| --- | ---: |
| WebSocket connect success rate | 1.0 |
| HTTP error rate | 0 |
| ack RTT p95 | 95ms |
| ack RTT p99 | 190ms |
| visible freshness p95 | 178ms |
| visible freshness p99 | 263ms |
| sent messages | 178,917 |
| received messages | 17,992,140 |
| omitted messages | 41,714,011 |

서버 pipeline p95:

| stage | p95 |
| --- | ---: |
| `ws.inbound.received.since_client_sent` | 73ms |
| `ws.ack.before_send.since_client_sent` | 84ms |
| `ws.ack.after_send.since_client_sent` | 84ms |
| `publish.redis.after_send.since_created` | 14ms |
| `fanout.enter.since_created` | 58ms |
| `fanout.batch.flush_start.since_created` | 151ms |
| `ws.broadcast.enqueue.since_created` | 151ms |
| `ws.broadcast.lane_start.since_created` | 151ms |
| `ws.broadcast.lane_done.since_created` | 151ms |

### Distributed run

- run id: `20260504-0400-mhr500x3-distributed`
- 구조: k6 3대, worker당 1방 x 500명
- ready barrier: `worker-1.ready`, `worker-2.ready`, `worker-3.ready` 확인
- done marker: `worker-1.done`, `worker-2.done`, `worker-3.done` 확인
- Grafana dashboard provisioning: `OpenChat Server Pipeline`, `OpenChat k6 E2E`, `OpenChat Infra` 확인
- InfluxDB `413`/flush 지연 로그: 없음
- 주의: `visible freshness`는 control과 동일하게 수신 메시지 100개당 1개 sampling 값이다.

| worker | k6 VM | exit | ack p95 | ack p99 | visible p95 | visible p99 | sent | received | omitted |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| worker-1 | `e2-standard-4` | 0 | 280ms | 486ms | 492ms | 616ms | 59,623 | 6,016,989 | 13,865,522 |
| worker-2 | `e2-standard-16` | 0 | 59ms | 112ms | 146ms | 200ms | 59,646 | 6,012,430 | 13,892,158 |
| worker-3 | `e2-standard-4` | 99 | 339.85ms | 585ms | 601ms | 759ms | 59,616 | 6,014,666 | 13,845,614 |

서버 pipeline p95:

| stage | p95 |
| --- | ---: |
| `ws.inbound.received.since_client_sent` | 12ms |
| `ws.ack.before_send.since_client_sent` | 22ms |
| `ws.ack.after_send.since_client_sent` | 22ms |
| `publish.redis.after_send.since_created` | 10ms |
| `fanout.enter.since_created` | 44ms |
| `fanout.batch.flush_start.since_created` | 142ms |
| `ws.broadcast.enqueue.since_created` | 142ms |
| `ws.broadcast.lane_start.since_created` | 142ms |
| `ws.broadcast.lane_done.since_created` | 142ms |

## 판정

이번 결과만 놓고 보면 `500명 x 3방`의 `10s` tail은 서버 fan-out/send 병목으로 재현되지 않았다.

- control에서 k6 p95가 10초대가 아니라 `visible freshness p95=178ms`였다.
- distributed에서도 서버 `ws.broadcast.lane_done.since_created p95=142ms`로 낮았다.
- worker-2는 같은 distributed run 안에서 `e2-standard-16`일 때 `visible p95=146ms`였다.
- worker-1/3은 `e2-standard-4`라서 `visible p95=492ms/601ms`로 올라갔다.

즉 현재 명확해진 것은 다음이다.

- 서버 pipeline의 ack/broadcast p95는 수백 ms 안에 있다.
- k6가 기록하는 visible freshness는 k6 worker CPU/spec과 metric 출력량에 민감하다.
- 이전의 75초급 visible freshness는 서버 lane/send 단독 병목으로 보기 어렵고, 단일 k6 수신/파싱/관측/metric 출력 경로가 섞였을 가능성이 높다.
- Realtime/API role split이 성능을 “좋게 만들었다”고 결론내릴 수는 없다. 이번 결과는 측정 경로를 고정하고 나니 서버 쪽 10초 tail이 보이지 않았다는 의미다.

다음에 더 엄밀히 보려면 CPU quota를 늘리거나 서버 VM 수를 줄여서 distributed k6 3대를 모두 같은 machine type으로 맞춰야 한다. 특히 `e2-standard-16 x 3` 또는 최소 `e2-standard-8 x 3`으로 같은 worker spec을 보장해야 한다.

## 판정 기준

| 결과 | 결론 |
| --- | --- |
| distributed에서 k6 p95가 크게 낮아지고 서버 metric도 낮음 | 단일 k6 수신/측정 병목 |
| control/distributed 모두 k6 p95가 10초대이고 서버 broadcast lag도 10초대 | 서버 fan-out/send 병목 |
| k6 p95만 높고 서버 ack/send metric은 낮음 | k6 또는 클라이언트 관측 경로 병목 |
| 서버 ack metric만 높음 | inbound ingest/ack 경로 병목 |
| broadcast enqueue는 낮고 lane_done만 높음 | WebSocket lane/send 병목 |

## 실행 예시

control run:

```bash
terraform apply \
  -var="project_id=<gcp-project-id>" \
  -var="run_id=$(date +%Y%m%d-%H%M%S)-control" \
  -var-file="profiles/multi-hot-room-500x3.tfvars.example" \
  -var="enable_monitoring=true" \
  -var='grafana_source_ranges=["<your-ip>/32"]' \
  -var="k6_worker_count=1"
```

distributed run:

```bash
terraform apply \
  -var="project_id=<gcp-project-id>" \
  -var="run_id=$(date +%Y%m%d-%H%M%S)-distributed" \
  -var-file="profiles/multi-hot-room-500x3.tfvars.example" \
  -var="enable_monitoring=true" \
  -var='grafana_source_ranges=["<your-ip>/32"]' \
  -var="k6_worker_count=3"
```

## 검증 상태

- `terraform fmt -recursive infra/gcp-loadtest`: 통과
- `terraform -chdir=infra/gcp-loadtest validate`: 통과
- `terraform plan -refresh=false` 기본 경로: 통과
- `terraform plan -refresh=false` monitoring + distributed k6 경로: 통과
- `jq empty infra/gcp-loadtest/grafana/dashboards/*.json`: 통과
- `./gradlew test`: 통과
