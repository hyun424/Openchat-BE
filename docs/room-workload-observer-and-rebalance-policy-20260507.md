# Room Workload Observer and Rebalance Policy

작성일: 2026-05-07

## Summary

이 문서는 OpenChat Realtime 확장 구조에서 **무엇을 보고 room shard / fan-out partition 판단을 할지** 정의한다.

현재 구현은 이미 `RoomTrafficMonitor`가 room 단위 snapshot을 만들고, `RoomScaleTier`와 partition 추천 수를 계산한다. 다만 이 값은 아직 자동 scale/rebalance 명령으로 바로 이어지지 않는다. 지금 단계의 목표는 recommendation-only 기준을 명확히 해두고, 이후 mixed-room 부하테스트와 자동화 구현의 판단 근거를 고정하는 것이다. 이 문서에서 말하는 `room_work = input_msg_tps * active_sessions`는 개념적 정의이고, 현재 코드의 `roomWorkPerSecond`는 실제 delivery work 관측치인 `outboundFanoutPerSecond` 기반 proxy다.

핵심 판단식은 다음이다.

```text
room_work = input_msg_tps * active_sessions
```

OpenChat에서는 전체 접속자 수보다 실제 full WebSocket payload를 받는 active session 수가 더 중요하다. passive session은 DB catch-up 경로로 복구할 수 있으므로 full fan-out work 계산에서 제외한다. 이후 구현에서는 개념식 기반 지표와 실제 fan-out 관측 지표를 모두 볼 수 있게 분리하는 것이 목표다.

## Observer Model

### 관측 단위

관측 단위는 `roomId`지만, Prometheus metric tag에는 `roomId`를 넣지 않는다. 방별 상세 값은 log/snapshot으로 확인하고, metric은 max/count 중심으로 유지한다.

현재 기준으로 observer가 봐야 할 값은 다음이다.

| 값 | 의미 | 사용처 |
| --- | --- | --- |
| `connectedSessions` | 방에 연결된 전체 WebSocket session 수 | 화면 상태와 관계없는 연결 규모 파악 |
| `activeSessions` | full payload fan-out 대상 session 수 | `room_work`, partition 추천 |
| `inboundMessagesPerSecond` | 초당 입력 메시지 수 | 입력 TPS, hot room 판단 |
| `outboundFanoutPerSecond` | 실제 fan-out delivery 시도량 | 현재 `roomWorkPerSecond` proxy, 기존 hot state 판단 |
| `roomWorkPerSecond` | 현재는 `outboundFanoutPerSecond` 기반 proxy, 개념적으로는 `input_msg_tps * active_sessions` | scale tier, partition 추천 |
| `deliveryLagP95Millis` | createdAt 기준 delivery lag p95 | 서버 fan-out 지연 보조 판단 |
| `laneQueueWaitP95Millis` | broadcast lane queue wait p95 | lane/backpressure 판단 |
| `recommendedPartitions` | pod budget 기준 추천 partition 수 | scale-up 후보 |
| `effectivePartitions` | max partition cap 적용 후 partition 수 | 현재 설정상 가능한 상한 |

### Prometheus metric 원칙

고카디널리티를 피하기 위해 roomId, userId, sessionId, partitionId는 metric tag에 넣지 않는다.

현재 구현에 있는 핵심 metric은 다음 범주다.

- room tier count: `openchat_room_scale_tier_count{tier}`
- room work max: `openchat_room_work_max_per_second`
- partition recommendation max: `openchat_room_partition_recommended_count_max`
- partition effective max: `openchat_room_partition_effective_count_max`
- pod budget: `openchat_room_pod_budget_delivery_per_second`
- reconnect control-plane: reconnect requested/targeted/sent 계열
- backpressure: lane enqueue fail, overload close, close failed, resync required

추가로 분리해서 보고 싶은 목표 metric은 다음이다.

- conceptual room work: `input_msg_tps * active_sessions`
- actual delivery work: 실제 outbound fan-out rate
- `/messages/after` catch-up request/success/failure count
- FE close recovery success/failure count
- reconnect 이후 duplicate/missed recovery count

room별 의사결정 로그에는 roomId를 남긴다. metric은 전체 상태를 보고, 로그는 특정 방 원인을 추적하는 역할로 나눈다.

## Tier Policy

기본 Realtime pod budget은 `4 vCPU / 8GB`, `10,000 delivery/s`로 둔다.

| Tier | 기준 |
| --- | ---: |
| `SMALL` | `< 1,000 delivery/s` |
| `MEDIUM` | `1,000 ~ 5,000 delivery/s` |
| `LARGE` | `5,000 ~ 10,000 delivery/s` |
| `HOT` | `10,000 ~ 20,000 delivery/s` |
| `CRITICAL` | `>= 20,000 delivery/s` |

승격과 강등은 순간 spike가 아니라 지속 상태를 기준으로 한다.

| 방향 | 조건 |
| --- | --- |
| 승격 | 상위 tier 기준을 `3분` 이상 지속 |
| 강등 | 하위 tier 기준을 `10분` 이상 지속 |

이 hysteresis는 방이 경계값 근처에서 계속 흔들리면서 route/drain 판단을 반복하지 않게 하기 위한 안전장치다.

## Partition Recommendation Policy

partition 추천 공식은 다음과 같다.

```text
recommended_partition_count = max(
  ceil(room_work / pod_work_budget),
  ceil(active_sessions / max_active_sessions_per_partition)
)

effective_partition_count = min(recommended_partition_count, max_partition_limit)
```

기본값은 다음과 같다.

| 항목 | 값 |
| --- | ---: |
| `pod_work_budget` | `10,000 delivery/s` |
| `max_active_sessions_per_partition` | `500` |
| `max_partition_limit` | `16` |

판단 의미는 다음이다.

- `recommendedPartitions == 1`: 단일 partition 유지 가능
- `recommendedPartitions > currentPartitionCount`: scale-up 후보
- `recommendedPartitions < currentPartitionCount`: scale-down 후보지만 즉시 drain하지 않음
- `recommendedPartitions > maxPartitionLimit`: 현재 partition cap 안에서 완전히 흡수하기 어려운 critical room

`effectivePartitions`가 cap에 걸린 경우는 성공 신호가 아니라 위험 신호다. 이 경우에는 partition 수만으로 해결하려 하지 말고 active/passive 비율, 입력 TPS 제한, room policy, 운영 개입 가능성을 함께 본다.

## Rebalance Decision Policy

### Scale-up recommendation

다음 조건을 모두 만족하면 scale-up 추천으로 본다.

1. `recommendedPartitions > currentPartitionCount`
2. 해당 상태가 `3분` 이상 지속
3. `activeSessions > 0`
4. DB 저장/ack 정합성 문제가 없음
5. send failure reason이 `send_time_limit` 또는 `buffer_limit` 중심으로 지속 증가하지 않음

scale-up은 기존 연결을 강제로 옮기지 않는다. v3.1 기준으로 새 route부터 증가한 partition count를 반영한다. 기존 연결은 유지하고, 필요할 때만 drain/reconnect로 이동시킨다.

### Scale-down recommendation

다음 조건을 모두 만족하면 scale-down 후보로 본다.

1. `recommendedPartitions < currentPartitionCount`
2. 하위 기준이 `10분` 이상 지속
3. lane queue wait와 delivery lag가 안정 상태
4. reconnect/resync 또는 close recovery 실패가 증가하지 않음
5. drain 대상 partition의 active session이 낮거나 이동 가능한 상태

scale-down은 scale-up보다 보수적으로 한다. 기존 연결이 살아 있는 partition을 없애야 하므로 반드시 `DRAINING -> room.reconnect -> /messages/after -> drain complete` 흐름을 거친다. 현재는 이 흐름을 internal API와 E2E로 검증하는 단계이고, 자동 scale-down 정책으로 바로 적용하지 않는다.

### No-auto conditions

다음 상황에서는 자동 rebalance를 하지 않는다.

- DB rows와 ack count가 맞지 않음
- Kafka durable publish 실패가 증가함
- `/messages/after` 복구 실패가 관측됨
- Redis control-plane command가 Realtime node에 도달하지 않음
- reconnect targeted는 증가하지만 sent failure가 지속됨
- `recommendedPartitions`가 `maxPartitionLimit`에 오래 걸림
- 한 방이 서비스 정책상 특별 관리 대상임

이 경우에는 자동 scale보다 원인 조사 또는 운영자 판단이 먼저다.

## Shard Assignment Policy

작은 방이 갑자기 많이 생기는 상황은 hot room partition과 다른 문제다.

Small/medium room은 room shard ownership으로 처리한다. 신규 room은 least-loaded shard에 배정하고, overloaded shard는 후보에서 제외한다.

Shard score는 현재 v2 기준을 유지한다. 이 가중치는 측정으로 확정된 최적값이 아니라, 작은 방 폭증을 피하기 위한 초기 휴리스틱이다.

```text
score = roomWorkPerSecond
      + activeSessions * 10
      + queueDepth * 50
      + roomCount * 100
```

초기 휴리스틱의 해석은 다음이다.

- room count는 작은 방 폭증을 분산하기 위한 기본 비용이다.
- active sessions는 실제 WebSocket fan-out 대상 규모를 반영한다.
- queue depth는 이미 밀리는 shard를 피하기 위한 신호다.
- room work는 실제 delivery work를 가장 직접적으로 반영한다.

모든 shard가 overloaded면 가장 낮은 score shard로 fallback하되, 이 상황은 정상 분산이 아니라 capacity 부족 신호로 기록한다.

## Mixed-room Test Direction

다음 부하테스트는 단일 hot room 숫자 경쟁이 아니라 mixed-room workload로 간다.

기본 시나리오 후보는 다음이다.

| 유형 | 예시 |
| --- | --- |
| hot room | 1500명, active 30%, passive 70% |
| medium room | 100~300명 방 여러 개 |
| small room | 5~30명 방 다수 |
| burst | 짧은 시간에 small room 다수 생성 |
| reconnect | drain/reconnect 중 메시지 복구 확인 |

확인할 질문은 다음이다.

1. hot room의 work가 small room 지연으로 번지는가?
2. 신규 small room이 overloaded shard에 계속 배정되는가?
3. active/passive 적용 후 delivery work가 active 수 근처로 줄어드는가?
4. partition recommendation이 실제 부하 구간에서 기대대로 변하는가?
5. drain/reconnect 중 메시지가 중복 없이 복구되는가?

## Implementation Boundary

현재 단계는 자동 rebalance 구현이 아니다.

우선순위는 다음 순서로 둔다.

1. observer와 decision 기준 문서화
2. 부족한 metric/snapshot 보강
3. recommendation log와 summary 정리
4. mixed-room smoke/loadtest 작성
5. recommendation 기반 수동 internal API 실행 검증
6. 자동 rebalance는 마지막에 검토

자동화는 기준이 안정된 뒤에 한다. 기준 없이 자동화하면 시스템이 더 똑똑해지는 것이 아니라, 장애를 더 빠르게 전파할 수 있다.

## Portfolio Message

이 작업은 "오토스케일링을 만들었다"가 아니라, 오토스케일링이 판단해야 할 기준을 먼저 정의한 것이다.

포트폴리오에서는 다음 문장으로 정리할 수 있다.

> 대규모 채팅의 확장 기준을 단순 접속자 수가 아니라 active fan-out work로 정의하고, 현재 구현의 actual delivery proxy와 개념적 `input_msg_tps * active_sessions` 모델을 구분했다. room tier, partition recommendation, shard assignment, drain/reconnect 금지 조건을 문서화해 작은 방 폭증과 hot room fan-out을 분리해서 판단할 수 있는 운영 모델을 세웠다.

## 2026-05-07 Mixed-room Observer Smoke

`20260507-mixed5-workload-smoke`로 recommendation-only observer가 실제 GCP role-split 환경에서 동작하는지 확인했다.

핵심 결과는 다음이다.

| 항목 | 결과 |
| --- | ---: |
| VU | `100` |
| room shape | hot `1 x 40`, medium `3 x 15`, small `5 x 3` |
| connect success | `100%` |
| HTTP error rate | `0%` |
| sent / ack / DB rows | `1,304 / 1,304 / 1,304` |
| visible p95 / p99 | `37ms / 37ms` |
| passive unexpected messages | `0` |
| partition limited count | `0` |

Realtime node별 after snapshot에서 다음 workload 계열 metric이 확인됐다.

| node | actual delivery work max | conceptual work max | scale decision work max |
| --- | ---: | ---: | ---: |
| app-2 | `386/s` | `195/s` | `386/s` |
| app-3 | `420/s` | `210/s` | `420/s` |

이 결과는 자동 rebalance 성공을 의미하지 않는다. 의미는 더 좁다.

- 현재 `roomWorkPerSecond`가 actual delivery proxy로 유지되는지 확인했다.
- 개념 모델인 `input_msg_tps * active_sessions`를 별도 metric으로 볼 수 있게 했다.
- `scaleDecisionWorkPerSecond = max(actual, conceptual)`가 recommendation 보조 신호로 남는지 확인했다.
- mixed-room smoke 조건에서는 partition cap에 걸리는 방이 없었다.

상세 결과는 `infra/gcp-loadtest/results/2026-05-07-mixed-room-workload-observer-smoke.md`에 기록했다.
