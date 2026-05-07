# Hot Room Fan-out Partition v3 GCP 검증 결과

## 목적

v3의 목적은 K8s/Swarm 없이 애플리케이션 레벨에서 단일 hot room fan-out을 여러 Realtime node로 나눌 수 있는지 확인하는 것이다.

- DB 저장과 ack는 메시지당 1회만 수행한다.
- WebSocket fan-out은 `roomId + partitionId` 기준 Redis channel로 나눈다.
- 각 Realtime node는 자신이 소유한 partition session에만 full payload를 보낸다.
- active/passive fan-out 제외 로직은 partition 내부에서도 유지한다.

## 정적 검증

- `node --check k6/lib/ws.js`: 통과
- `node --check k6/scenarios/10-active-passive-hot-room-ramped.js`: 통과
- `node --check k6/lib/metrics.js`: 통과
- `terraform -chdir=infra/gcp-loadtest validate`: 통과
- `./gradlew test`: 통과
- `git diff --check`: 통과

## Smoke

| 항목 | 값 |
| --- | ---: |
| run id | `20260506-v3part-smoke2` |
| profile | `hot-room-partition-smoke` |
| 리소스 | API `e2-standard-2 x1`, Realtime `e2-standard-4 x2`, k6 `e2-standard-4 x1` |
| 총 VU | 100 |
| active/passive | 30 / 70 |
| k6 exit code | 0 |
| checks | 600 pass / 0 fail |
| connect success | 100% |
| HTTP error rate | 0% |
| k6 sent / ack | 853 / 853 |
| DB rows | 853 |
| ack p95 / p99 | 27ms / 33ms |
| visible p95 / p99 | 88.75ms / 88.95ms |
| passive unexpected | 0 |
| route partitioned | 100 |
| partition publish/subscribe | 1,706 / 1,706 |
| send failed | 0 |

결론: smoke에서는 `/ws-route`가 partitioned로 응답했고, 메시지당 2개 partition channel publish/subscribe가 발생했다. DB row와 ack count도 일치했다.

## 1500명 Active/Passive 본 실행

| 항목 | 값 |
| --- | ---: |
| run id | `20260506-v3part-hr1500` |
| profile | `hot-room-1500-partition-active-passive` |
| 리소스 | API `e2-standard-4 x1`, Realtime `e2-standard-8 x4`, k6 `e2-standard-8 x2` |
| 예상 리소스 | 62 vCPU / 195GB SSD |
| 총 VU | 1500 |
| active/passive | 450 / 1050 |
| k6 worker | 2대, worker당 750 VU |
| k6 exit code | worker 1: 0, worker 2: 0 |
| checks | worker별 4500 pass / 0 fail |
| connect success | worker별 100% |
| HTTP error rate | worker별 0% |
| k6 sent / ack | 50,853 / 50,853 |
| DB rows | 50,853 |
| worker 1 ack p95 / p99 | 18ms / 25ms |
| worker 2 ack p95 / p99 | 17ms / 22ms |
| worker 1 visible p95 / p99 | 163ms / 244.04ms |
| worker 2 visible p95 / p99 | 97.05ms / 158.43ms |
| passive unexpected | worker 1: 0, worker 2: 0 |
| route partitioned | 1500 |
| partition publish/subscribe | 101,706 / 101,706 |
| active sessions max per active partition node | 225 |
| passive omitted | 7,871,511 |
| server lane_done p95 worst | 268.17ms |
| send duration p95 worst | 0.18ms |
| send failed | 0 |

## 해석

1500명 active/passive 조건에서도 DB 저장 정합성은 유지됐다. k6 sent, ack, DB rows가 모두 `50,853`으로 일치했다.

WebSocket route API는 1500회 모두 partitioned로 응답했다. Redis publish/subscribe도 `50,853 * 2 = 101,706`건으로, 메시지당 2개 partition channel을 사용했다.

passive 세션은 full payload를 받지 않았다. k6의 `ws_passive_unexpected_messages_total`은 두 worker 모두 `0`이었고, 서버의 `ws.fanout.passive_omitted`는 지속 증가했다.

Realtime fan-out은 단일 node가 아니라 2개 node로 분산됐다. 각 처리 node의 active session max는 `225`였고, 이는 전체 active 450명이 2개 partition으로 나뉜 형태다.

## 확인된 한계

본 실행은 `room_partition_partition_count=4`로 실행했지만 실제 fan-out partition은 2개만 사용했다. 현재 v3는 WebSocket 접속 시점에 `RoomTrafficMonitor` snapshot으로 partition 수를 계산한다. 접속 시점에는 아직 room work가 충분히 관측되지 않았기 때문에 recommended partition count가 낮고, 최소값인 2 partition으로 시작했다.

따라서 이번 본 실행의 결론은 다음과 같다.

- 성공: hot room fan-out을 단일 Realtime node에서 분리해 2개 Realtime node로 나눌 수 있다.
- 성공: DB 저장/ack는 메시지당 1회만 유지된다.
- 성공: active/passive 제외는 partition 내부에서도 유지된다.
- 미완: 4개 partition을 사전에 모두 쓰는 구조는 아직 아니다.

다음 v3.1에서는 이벤트성 hot room처럼 미리 큰 부하가 예상되는 방에 대해 `expectedPartitionCount`를 저장하거나, 운영자/스케줄 기반으로 partition 수를 선배정해야 한다. 그래야 트래픽이 터진 뒤가 아니라 접속 단계부터 4개 이상의 Realtime node로 분산할 수 있다.

## 리소스 정리

`20260506-v3part-smoke2`, `20260506-v3part-hr1500` 모두 k6 cleanup 이후 남은 GCP VM이 없는 것을 확인했다.
