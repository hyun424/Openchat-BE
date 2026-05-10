# 4 vCPU 기준 Room Work Sharding 설계

## 요약

Realtime pod의 기본 단위를 `4 vCPU / 8GB`로 잡고, 방의 부하는 인원수가 아니라 `room_work = input_msg_tps * active_sessions`로 판단한다.

v1에서는 실제 라우팅을 바꾸지 않는다. 기존 Redis channel `chat:room:{roomId}`와 fan-out routing은 유지하고, room tier와 fan-out partition 추천 수만 계측한다. 목적은 K8s나 shard routing을 바로 도입하는 것이 아니라, pod budget 기준으로 어느 방이 단일 pod 대상이고 어느 방이 partition 대상인지 설명 가능한 기준을 세우는 것이다.

## 기준 pod와 budget

| 항목 | 기준 |
| --- | ---: |
| 기본 Realtime pod | `4 vCPU / 8GB` |
| pod work budget | `10,000 delivery/s` |
| partition당 최대 active sessions | `500` |
| 최대 partition 수 | `16` |
| small shard 옵션 | `2 vCPU / 4GB` |
| hot room 실험 옵션 | `8 vCPU / 16GB` |

`2 vCPU / 4GB`는 작은 방을 비용 효율적으로 묶는 선택지이고, `8 vCPU / 16GB`는 특수 hot room이나 실험용으로만 둔다. 기본 판단 단위는 `4 vCPU / 8GB`다.

## Room Work

```text
room_work = input_msg_tps * active_sessions
```

현재 구현에서는 fan-out 직전의 실제 active 대상 수와 메시지 수를 기반으로 `openchat_room_work_max_per_second`를 계산한다. 즉 passive 세션은 full payload를 받지 않으므로 room work에 포함되지 않는다.

예를 들어 1500명 방에서 모든 사람이 active이고 초당 1500개 메시지가 들어오면 `2,250,000 delivery/s`가 된다. 반면 1500명 중 active 450명, active sender가 약 423명이라면 `약 189,000 delivery/s`가 된다. 같은 방 인원이어도 active/passive fan-out을 적용하면 full delivery work가 달라진다.

## Tier 기준

| Tier | room work |
| --- | ---: |
| `SMALL` | `< 1,000 delivery/s` |
| `MEDIUM` | `1,000 ~ 5,000 delivery/s` |
| `LARGE` | `5,000 ~ 10,000 delivery/s` |
| `HOT` | `10,000 ~ 20,000 delivery/s` |
| `CRITICAL` | `>= 20,000 delivery/s` |

승격은 기준 초과가 `3분` 지속될 때 적용하고, 강등은 하위 기준이 `10분` 지속될 때 적용한다. 순간 spike로 room tier가 계속 흔들리면 shard routing이 더 불안정해질 수 있으므로 hysteresis를 둔다.

## Partition 추천 공식

```text
recommended_partition_count = max(
  ceil(room_work / pod_work_budget),
  ceil(active_sessions / max_active_sessions_per_partition)
)

effective_partition_count = min(recommended_partition_count, max_partition_limit)
```

첫 번째 항은 delivery work 기준이고, 두 번째 항은 단일 partition에 너무 많은 active WebSocket session이 몰리는 것을 막기 위한 기준이다.

예시:

| 상황 | room work | active sessions | recommended | effective |
| --- | ---: | ---: | ---: | ---: |
| active/passive 1500명 | `189,000/s` | `450` | `19` | `16` |
| 1001명 active, 낮은 TPS | `1,000/s` | `1,001` | `3` | `3` |
| 매우 큰 active room | `300,000/s` | `30,000` | `60` | `16` |

`effective`는 현재 설정 cap을 적용한 값이다. cap에 걸린다는 것은 v1 기준으로는 “단일 room을 현재 pod budget 모델 안에서 완전히 흡수하기 어렵다”는 신호다.

## v1 구현 범위

- 기존 Redis channel `chat:room:{roomId}` 유지
- 기존 fan-out routing 유지
- `RoomTrafficMonitor`에서 room work, scale tier, partition recommendation 계측
- room tier 변화 시 `roomId`, `oldTier`, `newTier`, `roomWork`, `recommendedPartitions` 로그 기록
- Prometheus metric에 roomId/sessionId/userId 같은 고카디널리티 tag는 넣지 않음

추가 metric:

- `openchat_room_work_max_per_second`
- `openchat_room_scale_tier_count{tier}`
- `openchat_room_partition_recommended_count_max`
- `openchat_room_partition_effective_count_max`
- `openchat_room_pod_budget_delivery_per_second`

## 기존 결과 재해석

### 1500명 all-active hot room

- 추정 room work: `약 2,250,000 delivery/s`
- tier: `CRITICAL`
- 해석: 기존 36 vCPU 테스트에서 안정적으로 보였더라도, `4 vCPU / 8GB` pod 하나가 맡을 단위는 아니다. K8s로 전환하면 단일 hot room을 하나의 pod에 고정하는 방식보다 fan-out partition을 전제로 봐야 한다.

### 1500명 active/passive hot room

- 측정 결과: active/passive `450 / 1050`
- sent/ack/DB rows: `50,870 / 50,870 / 50,870`
- passive unexpected: `0`
- 추정 room work: `약 189,000 delivery/s`
- tier: `CRITICAL`
- 해석: active/passive v1로 full fan-out 대상은 줄었지만, 4 vCPU pod budget 기준으로는 여전히 partition 대상이다. 이 결과는 active/passive가 “hot room을 단일 pod로 해결했다”는 뜻이 아니라, 불필요한 delivery work를 줄인 뒤 남은 실제 active fan-out work를 계측 가능하게 만들었다는 의미다.

## 다음 단계

1. v1: room tier와 partition 추천 수 계측
   - 지금 단계다.
   - 라우팅은 바꾸지 않고 기준을 수립한다.

2. v2: Redis room shard channel 도입
   - 작은 방 여러 개를 shard 단위로 묶는다.
   - pod가 모든 방 메시지를 받는 구조를 줄이고, room shard owner가 맡은 방만 fan-out한다.

3. v3: hot room fan-out partition routing
   - 단일 pod budget을 넘는 hot room은 active session fan-out partition으로 나눈다.
   - 한 방의 모든 메시지 저장/순서는 유지하되, delivery work는 여러 realtime pod가 나눠 처리한다.

## 포트폴리오 관점 결론

이 작업의 핵심은 “서버를 더 크게 쓰면 더 많은 인원을 받을 수 있다”가 아니다.

핵심은 작은 방은 pod budget 안에서 효율적으로 묶고, hot room은 room work 기준으로 partition 대상임을 판단하는 구조를 세운 것이다. 이를 통해 OpenChat의 확장성 설명을 단순 인원 수가 아니라 `입력 TPS`, `active sessions`, `delivery work`, `pod budget`, `partition recommendation`으로 분리해서 말할 수 있다.
