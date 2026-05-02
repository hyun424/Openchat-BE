# Hot Room State Decision Plan

## Purpose

OpenChat hot room 성능 개선은 사람 수만 기준으로 판단하지 않는다.

목표는 사용자가 느리다고 느끼기 전에 방 상태를 단계적으로 올리고, 상태에 맞는 전송 정책을 적용하는 것이다.

## Target Scenarios

### Main Exposure

방이 메인에 노출되면 서버는 유입 증가 가능성을 미리 알 수 있다.

이 경우 실제 지연이 발생하기 전이라도 해당 방을 최소 `WARM` 후보로 본다.

### External Link Spike

외부 사이트나 커뮤니티에 방 링크가 올라오면 서버는 사전에 알기 어렵다.

이 경우 `join_rate`, `connection_rate`, `inbound_messages_per_sec`, `outbound_fanout_per_sec` 같은 실시간 지표로 감지한다.

## State Model

```text
NORMAL
-> WATCHED
-> WARM
-> HOT
-> SUPER_HOT
```

### NORMAL

기본 상태다.

정책:

- 즉시 전송
- 가벼운 지표만 수집

항상 수집할 지표:

- connected sessions
- join rate
- inbound messages per second
- outbound fanout per second
- last activity time

### WATCHED

위험 후보 상태다.

전송 정책은 아직 바꾸지 않고, 정밀 계측을 시작한다.

승격 후보:

- 메인 노출
- join rate 급증
- connected sessions 증가
- inbound messages per second 증가
- outbound fanout per second 증가

추가로 볼 지표:

- delivery lag sample
- lane queue wait sample
- slow session candidate

### WARM

곧 hot room이 될 가능성이 높은 상태다.

정책 후보:

- 10ms micro-batch
- 정밀 계측 강화
- slow session 후보 추적

진입 기준 초안:

- main exposed
- join_rate >= 5/sec for 5s
- delivery_lag_p95 >= 50ms
- outbound_fanout_per_sec >= 5,000

### HOT

즉시 전송만으로는 지연이 커질 가능성이 높은 상태다.

정책 후보:

- 20ms micro-batch
- per-session backlog 측정
- shard candidate 등록

진입 기준 초안:

- delivery_lag_p95 >= 100ms for 10s
- inbound_messages_per_sec >= 30
- outbound_fanout_per_sec >= 10,000 ~ 20,000
- lane_queue_wait가 지속적으로 증가

### SUPER_HOT

batch만으로 부족할 수 있는 보호 대상 상태다.

정책 후보:

- 50ms micro-batch
- slow session isolation
- REST sync 보정
- fanout shard 적용 후보

진입 기준 초안:

- delivery_lag_p95 >= 300ms
- outbound_fanout_per_sec >= 50,000
- connected_sessions >= 500 and inbound_messages_per_sec 증가
- slow_session_ratio >= 5%
- queue backlog가 계속 누적

## Upgrade And Downgrade Rules

상태를 올리는 것은 빠르게, 내리는 것은 느리게 한다.

```text
NORMAL -> WATCHED:
  즉시 또는 5초 조건 만족

WATCHED -> WARM:
  5~10초 조건 만족

WARM -> HOT:
  10초 조건 만족

HOT -> SUPER_HOT:
  즉시 또는 5초 조건 만족

SUPER_HOT -> HOT:
  60초 이상 안정

HOT -> WARM:
  60초 이상 안정

WARM -> WATCHED/NORMAL:
  120~300초 이상 안정
```

상태가 경계선에서 계속 흔들리지 않게 hysteresis를 둔다.

## Metrics Strategy

모든 방을 항상 정밀 계측하지 않는다.

```text
All active rooms:
  cheap counters only

WATCHED and above:
  sampled delivery lag
  sampled lane queue wait
  slow session candidate tracking

HOT and above:
  detailed backlog metrics
  batch candidate metrics
```

Prometheus에는 모든 roomId를 label로 노출하지 않는다.

대신 아래처럼 제한된 지표만 노출한다.

- state별 room count
- hot room count
- super hot room count
- top N hot room summary
- global delivery lag
- max room delivery lag

## Implementation Order

### Phase 1: Metrics Only

전송 정책을 바꾸지 않고 판단 재료만 추가한다.

- `RoomHotState` enum 추가
- `RoomTrafficStats` 추가
- room별 connected sessions 추적
- room별 join rate 추적
- room별 inbound messages per second 추적
- room별 outbound fanout per second 추정
- delivery lag sampling
- lane queue wait sampling
- `RoomHotStateClassifier` 추가
- 상태 변경 로그와 제한된 metric 노출

### Phase 2: Load Test Threshold Calibration

같은 GCP 환경에서 단계별로 다시 측정한다.

```text
100 VU
150 VU
200 VU
250 VU
300 VU
400 VU
500 VU
```

확인할 항목:

- 어느 시점에 WATCHED/WARM/HOT으로 올라가는지
- delivery_lag_p95가 50ms, 100ms, 300ms를 넘는 시점
- lane_queue_wait p95가 1초를 넘는 시점
- outbound_fanout_per_sec와 RTT p95의 관계

### Phase 3: Adaptive Batching

상태별 전송 정책을 적용한다.

```text
NORMAL:
  immediate send

WARM:
  10ms micro-batch

HOT:
  20ms micro-batch

SUPER_HOT:
  50ms micro-batch
  slow session isolation candidate
```

### Phase 4: Slow Session Isolation

느린 세션이 전체 방의 tail latency를 끌어올리는지 확인하고 격리한다.

후보 정책:

- per-session outbound queue depth 추적
- 일정 시간 이상 backlog가 큰 세션을 slow로 마킹
- slow session은 batch 크기 확대 또는 reconnect/sync 유도
- 메시지 본문은 DB에 저장하고 WebSocket 실시간 누락은 REST sync로 보정

### Phase 5: Fanout Shard

batch와 slow session isolation 후에도 SUPER_HOT 상태가 유지될 때만 검토한다.

샤딩 기준:

- batch 적용 후에도 delivery_lag_p95가 300ms 이상
- queue backlog가 계속 증가
- outbound_fanout_per_sec가 app 단일 처리 한계를 초과
- 여러 hot room이 동시에 발생

## Current Decision

다음 구현은 batch가 아니라 `Phase 1: Metrics Only`다.

먼저 어떤 방이 언제 hot room이 되는지 판단할 수 있게 만든 뒤, 같은 GCP 부하테스트에서 threshold를 보정한다.
