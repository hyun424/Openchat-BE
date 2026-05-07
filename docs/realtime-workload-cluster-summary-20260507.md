# Realtime Workload Cluster Summary

작성일: 2026-05-07

## Summary

OpenChat의 shard/partition/reconnect 구조는 각 Realtime 노드의 local 상태만으로는 전체 판단을 내리기 어렵다. WebSocket 세션은 node-local이고, 각 노드는 자기 세션과 자기 fan-out만 정확히 알 수 있기 때문이다.

이 문서는 Redis 기반 workload snapshot을 사용해 클러스터 전체 room workload를 read-only로 모으는 구조를 기록한다. 목적은 자동 rebalance가 아니라, 자동화 전에 사람이 검토 가능한 recommendation 근거를 만드는 것이다.

## Why Cluster Summary Is Needed

각 Realtime 노드가 `RoomTrafficMonitor`로 local room workload를 관측하더라도, API 노드나 운영자는 다음 질문에 바로 답하기 어렵다.

- 어떤 room이 클러스터 전체에서 가장 위험한가?
- 어떤 Realtime node snapshot이 stale 상태인가?
- partition cap에 걸린 room이 있는가?
- scale-up 후보가 실제로 존재하는가?
- small room 폭증인지 hot room fan-out 문제인지 구분 가능한가?

따라서 Realtime 노드는 local snapshot을 Redis에 주기적으로 저장하고, API node는 이를 읽어 cluster summary와 recommendation을 만든다.

## Design

Redis key는 다음을 사용한다.

```text
openchat:realtime:workload:nodes
openchat:realtime:workload:nodes:{nodeId}
```

Realtime/combined node는 기본 `5s`마다 snapshot을 저장하고, snapshot TTL은 기본 `30s`다. TTL이 지난 snapshot은 stale로 보고 aggregate에서 제외한다.

Snapshot에는 다음 값을 담는다.

- node identity: `nodeId`, `role`, `reportedAt`, `expiresAt`
- ownership: `ownedShards`, `ownedPartitions`
- session: `totalSessions`, `activeSessions`, `passiveSessions`
- pressure: `broadcastQueueDepth`
- workload: `actualDeliveryWork`, `conceptualWork`, `scaleDecisionWork`
- risk: `partitionRecommendationLimitedCount`
- top rooms: `scaleDecisionWork` 기준 상위 room 후보

Prometheus metric에는 `roomId`, `nodeId`, `sessionId`, `userId`, `partitionId`를 tag로 넣지 않는다. roomId는 Redis snapshot과 internal API 응답에서만 사용한다.

## Read-only Recommendation

이번 단계는 자동 scale-up/drain을 실행하지 않는다. API는 다음 endpoint만 제공한다.

```http
GET /api/internal/realtime/workload/summary
GET /api/internal/realtime/workload/recommendations
```

초기 recommendation type은 다음이다.

| Type | 의미 |
| --- | --- |
| `NO_ACTION` | 현재 기준에서 조치 필요 없음 |
| `WATCH` | pod budget의 watch threshold에 접근 |
| `SCALE_UP_CANDIDATE` | scale decision work가 pod budget 이상 |
| `CAP_LIMITED` | recommended partition이 max cap을 초과 |
| `INVESTIGATE_STALE_NODE` | stale Realtime snapshot 존재 |
| `INVESTIGATE_SEND_FAILURE` | send failure delta 신호 존재 |

Scale-down/drain recommendation은 이번 범위가 아니다. scale-down은 기존 연결 이동과 `/messages/after` 복구가 끼므로 더 보수적인 기준이 필요하다.

## Boundary

이 작업은 다음을 하지 않는다.

- partition count 자동 증가
- drain/reconnect 자동 실행
- 기존 `/ws-route` 판단 변경
- chat Redis channel 또는 control-plane payload 변경
- K8s/HPA 도입

이번 단계의 가치는 “자동화”가 아니라 “자동화가 판단해야 할 근거를 클러스터 기준으로 볼 수 있게 만든 것”이다.

## GCP Smoke Result

- run id: `20260507-cluster-workload-smoke2`
- profile: `mixed-room-workload-smoke`, 100 VU, API `e2-standard-2 x1`, Realtime `e2-standard-4 x2`, k6 `e2-standard-4 x1`, monitoring off
- k6 exit code: `0`
- checks: `620 passed`, `0 failed`
- WebSocket connect success: `100%`
- HTTP error rate: `0%`
- active sender ack p95: `19ms`
- active observer visible p95: `53.5ms`
- DB rows: `1,309`, k6 ack count: `1,309`
- passive unexpected messages: `0`
- workload summary: `activeNodeCount=2`, `staleNodeCount=0`, `topRooms` present
- recommendation: `NO_ACTION`

첫 실행에서는 API node에서 workload summary controller는 활성화됐지만 Redis repository 조건이 bean 생성 순서에 의존해 summary service가 생성되지 않았다. 조건을 `spring.data.redis.host` property 기반으로 바꾼 뒤 재실행해 API/Realtime/k6 smoke가 통과했다.

## Portfolio Note

> WebSocket session이 Realtime node local 상태라는 제약 때문에 단일 노드 지표만으로는 shard/partition 판단을 내릴 수 없었다. 각 Realtime 노드가 Redis에 workload snapshot을 publish하고 API가 이를 read-only로 집계해, 자동 rebalance 전에 운영자가 검토 가능한 recommendation layer를 만들었다.
