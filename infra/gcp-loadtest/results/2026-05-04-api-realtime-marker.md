# 2026-05-04 API/Realtime Role Split + Published Marker 테스트

## 배경

- 브랜치: `perf-api-realtime-role-split`
- 대상 profile: `multi-hot-room-500x3`
- 목표: API/Realtime role split과 outbox `PUBLISHED` 비동기 마킹이 500명 x 3 hot room의 visible freshness tail을 줄이는지 검증한다.
- 이전 500 x 3 기준 결과:
  - WebSocket connect success: `1500/1500`
  - `chat_ack_roundtrip_ms` p95: `252ms`
  - `ws_visible_freshness_ms` p95: `44.135s`
  - 별도 관측된 visible freshness 최악 회귀: `75.891s`

## 테스트 대상 변경

- 같은 Spring Boot 앱을 role 기반으로 API 노드와 Realtime 노드로 분리했다.
- API 노드는 HTTP API 트래픽을 처리한다.
- Realtime 노드는 WebSocket 트래픽을 처리하고, 1차 단계에서는 WebSocket 메시지 저장도 계속 담당한다.
- `PostCommitLivePublishService`가 live publish 이후 outbox `PUBLISHED` 마킹을 동기적으로 기다리지 않도록 변경했다.
- `OutboxPublishedMarker`가 published mark를 비동기로 모아 batch flush한다.
- LB startup script가 k6 시작 전에 API upstream과 Realtime upstream의 직접 health를 모두 확인하도록 변경했다.

LB health gate 변경은 필요했다. 이전 smoke에서는 routed `/actuator/health`만 확인했는데, 이 요청은 API 노드로만 통과할 수 있었다. 그 결과 Realtime 노드가 아직 준비되지 않은 상태에서도 k6가 시작되어 WebSocket `502`가 발생했다. 이 실패는 애플리케이션 코드 경로의 병목이 아니라 테스트 환경 준비 순서 문제였다.

## 스모크 테스트

- Run id: `20260504-0138-marker-smoke2`
- 시나리오: small smoke, 10 VUs
- 결과: 성공

| Metric | Value |
| --- | ---: |
| k6 exit code | `0` |
| checks | `44/44` |
| HTTP error rate | `0%` |
| WebSocket connect success | `100%` |
| WebSocket connect p95 | `195.6ms` |
| `chat_ack_roundtrip_ms` p95 | `37.55ms` |
| `chat_ack_roundtrip_ms` p99 | `222ms` |
| `ws_visible_freshness_ms` p95 | `129.05ms` |
| `ws_visible_freshness_ms` p99 | `263ms` |

스모크 결과가 안정적이어서 500 x 3 본테스트를 진행했다.

## 본테스트

- Run id: `20260504-0144-mhr500x3mk`
- 시나리오: `k6/scenarios/09-multi-hot-room-ramped.js`
- Profile: `infra/gcp-loadtest/profiles/multi-hot-room-500x3.tfvars.example`
- 부하: 3 hot rooms x 500 users = 1500 VUs
- Connect ramp: `120s`
- Chat duration: `120s`
- 결과 파일:
  - `gs://openchat-loadtest-openchat-495102/runs/20260504-0144-mhr500x3mk/k6/multi-hot-room-500x3/1500vu-summary.json`
  - `gs://openchat-loadtest-openchat-495102/runs/20260504-0144-mhr500x3mk/k6/multi-hot-room-500x3/1500vu.log`
  - `gs://openchat-loadtest-openchat-495102/runs/20260504-0144-mhr500x3mk/k6/multi-hot-room-500x3/1500vu-exit-code.txt`

### 본테스트 결과

| Metric | Value |
| --- | ---: |
| k6 exit code | `99` |
| checks | `6008/6008` |
| HTTP error rate | `0%` |
| WebSocket connect success | `1500/1500` |
| WebSocket connect p95 | `37.05ms` |
| WebSocket connect p99 | `63ms` |
| `chat_ack_roundtrip_ms` median | `715ms` |
| `chat_ack_roundtrip_ms` p90 | `9.031s` |
| `chat_ack_roundtrip_ms` p95 | `10.102s` |
| `chat_ack_roundtrip_ms` p99 | `11.707s` |
| `ws_visible_freshness_ms` median | `796ms` |
| `ws_visible_freshness_ms` p90 | `9.059s` |
| `ws_visible_freshness_ms` p95 | `10.144s` |
| `ws_visible_freshness_ms` p99 | `11.747s` |
| `ws_visible_freshness_ms` max | `15.806s` |
| WebSocket messages sent | `178,820` |
| WebSocket messages received | `17,488,705` |
| Realtime omitted messages | `39,955,170` |

본테스트는 연결 실패 없이 완료됐지만, ack와 visible freshness threshold는 실패했다.

## 이전 결과와 비교

| Run | Connect | Ack p95 | Visible freshness p95 | k6 exit |
| --- | ---: | ---: | ---: | ---: |
| 이전 role-only 기준 | `1500/1500` | `252ms` | `44.135s` | `99` |
| 이전 500 x 3 최악 관측 | `1500/1500` | 미기록 | `75.891s` | threshold 실패 |
| 이번 run | `1500/1500` | `10.102s` | `10.144s` | `99` |

visible freshness는 수십 초대에서 약 10초 p95까지 크게 줄었다. 하지만 목표 threshold인 `500ms`와는 아직 거리가 크다.

ack p95는 이전 role-only 기준보다 나빠졌다. 이번 run에서 ack와 visible freshness가 모두 10초 근처이므로, 남은 지연은 서버 publish latency 하나만의 문제가 아니라 k6 클라이언트의 수신/측정 처리 지연 또는 per-connection event loop 압박과 함께 봐야 한다.

## 서버 측 Metrics Snapshot

테스트 종료 후 Prometheus snapshot에서는 10초 수준의 서버 publish 지연이나 WebSocket lane queue 지연이 보이지 않았다.

| Metric | 관측 범위 |
| --- | ---: |
| Realtime 노드 `live_publish.total` p95 | `0.7ms - 1.0ms` |
| `publish.redis.after_send.since_created` p95 | `10.7ms - 11.8ms` |
| `fanout.batch.flush_start.since_created` p95 | `96ms - 201ms` |
| `ws.broadcast.lane.queue_wait` p95 | `0.25ms - 0.43ms` |
| `openchat_room_hot_state_delivery_lag_p95_max_ms` | `109ms - 213ms` |
| Hikari pending connections | `0` |

이 수치만 보면 기존에 의심했던 전역 live publish queue나 동기식 outbox published marking은 이번 run의 남은 10초 tail의 주 병목으로 보이지 않는다.

## 해석

1차 role split과 published marker 비동기화는 방향성이 있다.

- 1500명 WebSocket 연결은 안정적으로 성공했다.
- 서버 측 live publish와 broadcast lane 지표는 낮게 유지됐다.
- 이전 multi-room tail 대비 visible freshness가 크게 줄었다.

하지만 본테스트 결과는 아직 통과 수준이 아니다.

- k6 exit code는 `99`다.
- ack p95와 visible freshness p95가 모두 약 `10s`다.
- 단일 k6 VM process가 약 `17.5M`개의 WebSocket 메시지를 수신했고, 약 `40M`개의 realtime 메시지를 omitted 처리했다.

현재 가장 그럴듯한 다음 병목은 load generator, 즉 k6 클라이언트 측 측정 경로다. 이 fan-out 규모에서는 단일 k6 VM이 매우 높은 메시지 처리량을 받아 파싱하고 metric을 기록한다. 서버 측 Realtime delivery metric이 낮아도 k6 쪽 freshness 측정이 뒤로 밀릴 수 있다.

## Cleanup

k6 cleanup script가 결과를 업로드하고 임시 load-test VM을 삭제했다. 마지막으로 `gcloud compute instances list`를 `name~openchat-lt-20260504-0144-mhr500x3mk` filter로 확인했을 때 남은 matching instance는 없었다.

## 다음 액션

1. k6 load generation을 room 단위 또는 room subset 단위로 분산해 각 k6 process가 받는 fan-out 트래픽을 줄인다.
2. 같은 서버 topology에서 distributed k6 worker로 다시 측정하고 아래 지표를 비교한다.
   - k6-side freshness p95
   - 서버 측 `openchat_room_hot_state_delivery_lag_p95_max_ms`
   - `ws.broadcast.lane.queue_wait`
   - `publish.redis.after_send.since_created`
3. 현재 API/Realtime role split과 published marker 변경은 다음 실험에도 유지한다. 서버 측 지표상 live publish나 published marking이 더 이상 주요 tail로 보이지 않기 때문이다.
4. distributed k6에서도 k6-side freshness가 10초대인데 서버 metric은 계속 sub-second라면, 모든 클라이언트가 모든 room message를 파싱하는 방식과 별도로 더 가벼운 서버 발행 sequence/lag metric을 추가한다.
