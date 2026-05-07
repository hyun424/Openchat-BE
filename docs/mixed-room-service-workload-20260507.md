# Mixed-room Service Workload

작성일: 2026-05-07

## Summary

이 문서는 단일 hot room 숫자 경쟁이 아니라, 실제 서비스에 가까운 mixed-room workload를 정의하고 GCP 실행 결과를 함께 기록한다. 목적은 `Cluster-level Room Workload Summary`가 small/medium/large/hot room이 섞인 상황에서 위험 후보를 구분할 수 있는지 확인하는 것이다.

핵심 질문은 다음이다.

> 같은 총 접속자 수에서도 room 분포가 다르면 fan-out work가 달라진다. 클러스터 summary가 small room 폭증과 hot room 위험을 구분할 수 있는가?

## Workload Model

방 위험도는 단순 인원수가 아니라 다음 기준으로 본다.

```text
conceptualRoomWorkPerSecond = inbound_msg_tps * active_sessions
actualDeliveryWorkPerSecond = observed outbound fan-out delivery work
scaleDecisionWorkPerSecond = max(conceptualRoomWorkPerSecond, actualDeliveryWorkPerSecond)
```

현재 자동 rebalance는 하지 않는다. 이번 단계는 read-only recommendation과 관측 신뢰도 확인이다.

## Scenario A: Service-shaped 1500 VU

일반 서비스형 분포다. 다수의 small room, 여러 medium room, 일부 large room, hot room 1개를 섞는다.

| Type | 방 수 | 방당 VU | active 비율 | sender interval | 총 VU |
| --- | ---:| ---:| ---:| ---:| ---:|
| Hot | 1 | 500 | 30% | 1s | 500 |
| Large | 2 | 150 | 35% | 2s | 300 |
| Medium | 10 | 35 | 40% | 3s | 350 |
| Small | 70 | 5 | 50% | 7s | 350 |
| Total | 83 | - | - | - | 1500 |

예상 해석:

- small room은 shard에 묶어도 되는 후보여야 한다.
- large/hot room은 topRooms 상위에 올라와야 한다.
- 위험 신호가 약하면 recommendation은 `NO_ACTION` 또는 `WATCH`가 정상이다.

## Scenario B: Hot-biased Risk Injection 1500 VU

같은 1500명에서 hot room 비중만 키운다. 목적은 recommendation이 위험 신호를 실제로 올리는지 확인하는 것이다.

| Type | 방 수 | 방당 VU | active 비율 | sender interval | 총 VU |
| --- | ---:| ---:| ---:| ---:| ---:|
| Hot | 1 | 800 | 35% | 1s | 800 |
| Large | 2 | 125 | 35% | 2s | 250 |
| Medium | 8 | 25 | 40% | 3s | 200 |
| Small | 50 | 5 | 50% | 7s | 250 |
| Total | 61 | - | - | - | 1500 |

예상 해석:

- hot room이 topRooms 1위에 올라와야 한다.
- `WATCH` 또는 `SCALE_UP_CANDIDATE`가 나오면 recommendation path가 위험 신호를 포착한 것이다.
- 계속 `NO_ACTION`이면 현재 threshold가 너무 보수적이거나 workload가 아직 pod budget에 못 미친 것이다.

## Acceptance Criteria

- k6 exit code `0`
- WebSocket connect success `>= 99%`
- HTTP error rate `< 1%`
- DB rows = k6 ack count
- passive unexpected messages `0`
- workload summary:
  - `activeNodeCount = realtime node count`
  - `staleNodeCount = 0`
  - `topRooms` 존재
- recommendation:
  - Service-shaped: `NO_ACTION` 또는 `WATCH`
  - Risk injection: `WATCH`, `SCALE_UP_CANDIDATE`, 또는 threshold 근거가 설명 가능한 `NO_ACTION`

## GCP Results

### Scenario A: Service-shaped 1500 VU

| 항목 | 값 |
| --- | --- |
| run id | `20260507-mixed-service-1500b` |
| profile | `mixed-room-service-workload-1500` |
| 앱 리소스 | API `e2-standard-2 x1`, Realtime `e2-standard-4 x2` |
| 부하/스토리지 리소스 | k6 `e2-standard-8 x1`, MySQL/Redis/LB `e2-standard-2` |
| 총 리소스 | 약 `24 vCPU`, SSD `110GB` |
| monitoring | off |
| scenario | `k6/scenarios/11-mixed-room-workload-ramped.js` |
| 실행 형태 | 83 rooms, 1500 VU, 120s ramp, 120s chat |

#### k6 / DB 결과

| 지표 | 결과 | 기준 | 판정 |
| --- | ---:| ---:| --- |
| k6 exit code | `0` | `0` | 통과 |
| checks | `99.87%` | 참고 | 통과 |
| WebSocket connect success | `100%` | `>= 99%` | 통과 |
| HTTP error rate | `0.1305%` | `< 1%` | 통과 |
| sender ack p95 / p99 | `24ms / 30ms` | p95 `< 300ms` | 통과 |
| observer visible p95 / p99 | `116ms / 127ms` | p95 `< 500ms` | 통과 |
| sent / acked | `42,909 / 42,909` | 일치 | 통과 |
| DB rows | `42,909` | ack count와 일치 | 통과 |
| passive unexpected messages | `0` | `0` | 통과 |

#### Cluster Workload Summary

첫 실행 `20260507-mixed-service-1500`에서는 k6 종료 후 summary만 수집해서 세션과 work gauge가 이미 `0`으로 내려간 상태였다. 그래서 k6 startup에 실행 중 workload snapshot 수집을 추가하고 `20260507-mixed-service-1500b`로 재실행했다.

peak에 가까운 snapshot은 `realtime-workload-summary-during-1500vu-7.json` 기준이다.

| 지표 | 값 |
| --- | ---:|
| active realtime nodes | `2` |
| stale realtime nodes | `0` |
| total sessions | `1,237` |
| active sessions | `727` |
| passive sessions | `510` |
| max actual delivery work/sec | `7,500` |
| max conceptual room work/sec | `5,175` |
| max scale decision work/sec | `7,500` |
| partition recommendation limited count | `0` |
| send failed delta | 당시 placeholder `0` |
| reconnect sent delta | 당시 placeholder `0` |

Top room candidates:

| 순위 | roomId | source node | actual work/s | conceptual work/s | decision work/s | active sessions | recommendation partitions |
| ---:| ---:| --- | ---:| ---:| ---:| ---:| ---:|
| 1 | `1` | `gcp-realtime-2` | `7,500` | `5,175` | `7,500` | `75` | `1` |
| 2 | `1` | `gcp-realtime-1` | `6,878` | `4,650` | `6,878` | `75` | `1` |
| 3 | `2` | `gcp-realtime-2` | `1,120` | `560` | `1,120` | `35` | `1` |
| 4 | `3` | `gcp-realtime-2` | `1,103` | `595` | `1,103` | `35` | `1` |

Recommendation:

```json
[
  {
    "type": "WATCH",
    "reason": "room scale decision work reached watch threshold",
    "roomId": 1,
    "nodeId": "gcp-realtime-2",
    "observedValue": 7500,
    "threshold": 7000
  }
]
```

해석:

- 1500명 mixed-room 서비스형 부하는 현재 리소스에서 사용자 체감 지연과 DB 정합성 기준을 통과했다.
- `roomId=1` hot room이 top candidate로 잡혔고, `scaleDecisionWorkPerSecond=7,500`으로 pod budget `10,000`의 70% watch threshold를 넘었다.
- 이 결과는 자동 scale-up을 실행할 정도의 `SCALE_UP_CANDIDATE`는 아니지만, operator가 지켜봐야 하는 hot room 후보를 cluster summary가 포착했다는 의미다.
- `partitionRecommendationLimitedCount=0`이므로 현재 max partition cap에 걸린 방은 없었다.
- 이 실행 시점의 `sendFailedDelta`, `reconnectSentDelta`는 snapshot schema에는 있었지만 실제 counter delta가 아니라 placeholder `0`이었다.
- 이후 workload signal delta 보강으로 두 값은 node-local 누적 counter의 snapshot 간 증가량으로 채워진다. 다음 mixed-room/risk-injection 실행에서는 이 두 값을 결과 표에 함께 기록한다.

### Scenario B: Hot-biased Risk Injection 1500 VU

이번 실행에서는 비용과 목적을 고려해 Scenario A까지만 GCP에서 검증했다. Scenario B는 다음 단계에서 recommendation threshold가 `WATCH`에서 `SCALE_UP_CANDIDATE`로 올라가는지 확인하기 위한 위험 주입 시나리오로 남긴다.

## Portfolio Note

> 단일 hot room 최대치만 보지 않고, small/medium/large/hot room이 섞인 서비스형 workload를 정의했다. Cluster summary가 node-local WebSocket 상태를 Redis snapshot으로 모아 top room과 scale recommendation을 제공하는지 검증해, 자동 rebalance 이전에 운영자가 믿을 수 있는 판단 근거를 먼저 만들었다.
