# Hot Room Partition Autoscaling v3.1 설계

## 배경

v3에서는 hot room fan-out을 `roomId + partitionId` 기준 Redis channel로 나누고, 각 Realtime node가 자신이 소유한 partition session에만 full payload를 보내는 구조를 구현했다.

GCP 검증 결과 1500명 active/passive 조건에서 DB 저장/ack 정합성은 유지됐고, fan-out은 단일 Realtime node가 아니라 2개 Realtime node로 분산됐다. 다만 `room_partition_partition_count=4`로 실행했어도 실제로는 2개 partition만 사용됐다. 이유는 현재 `/ws-route`가 WebSocket 접속 시점의 traffic snapshot을 보고 partition 수를 계산하기 때문이다. 접속 시점에는 room work가 아직 낮아서 최소 partition 수로 시작했다.

v3.1의 목표는 이 한계를 운영 가능한 형태로 풀어내는 것이다. 오토스케일링이 단순히 Realtime pod 수를 늘리고 줄이는 데서 끝나지 않고, 기존 WebSocket 연결과 신규 연결을 안전하게 다룰 수 있어야 한다.

## 문제 정의

WebSocket은 long-lived connection이다. Realtime pod를 늘려도 이미 연결된 세션은 자동으로 새 pod로 이동하지 않는다. 반대로 pod를 줄일 때도 해당 pod에 사람이 남아 있으면 바로 종료할 수 없다.

따라서 autoscaling 가능한 hot room 구조에는 다음 네 가지가 필요하다.

- room별 partition count를 설정값이 아니라 state로 관리한다.
- scale-up 후 새 접속자는 최신 partition count로 배정한다.
- 기존 연결은 기본적으로 유지하되, 필요할 때만 점진적으로 reconnect를 유도한다.
- scale-down 대상 partition은 먼저 `DRAINING`으로 전환하고, 신규 접속 배정을 막은 뒤 기존 연결을 비운다.

## v3.1 목표

- room별 partition state를 도입한다.
- `/ws-route`를 traffic snapshot 기반이 아니라 partition state 기반으로 변경한다.
- scale-up 시 신규 접속자가 늘어난 partition으로 들어갈 수 있게 한다.
- scale-down 시 draining partition에 신규 접속자가 들어가지 않게 한다.
- 기존 WebSocket 세션은 무조건 강제 종료하지 않고, 필요한 경우에만 `room.reconnect` control message로 이동을 유도한다.
- 메시지 유실은 기존 DB sequence와 `/messages/after` 복구 경로로 막는다.

## 범위 밖

v3.1에서는 K8s/HPA를 직접 도입하지 않는다. K8s는 pod 생성/삭제와 rollout을 도와줄 수 있지만, room partition state, route, reconnect, drain은 애플리케이션 프로토콜로 먼저 정의돼야 한다.

다음 항목은 v3.1 이후로 미룬다.

- Kubernetes HPA 적용
- Redis 기반 owner discovery
- 자동 partition rebalancing scheduler
- 기존 연결의 완전 균등 재분배
- multi-region room routing

## Partition State

room별 partition 상태를 저장한다.

```text
room_partition_state
- room_id
- partition_count
- version
- status
- draining_partitions
- updated_at
- updated_by
```

`status`는 다음 값을 가진다.

| 상태 | 의미 |
| --- | --- |
| `ACTIVE` | 정상 라우팅 상태 |
| `SCALING_UP` | partition count 증가 직후, 새 partition으로 신규 접속을 받을 수 있는 상태 |
| `DRAINING` | 일부 partition을 줄이기 위해 신규 접속 배정을 막고 기존 연결을 이동시키는 상태 |

`draining_partitions`는 scale-down 대상 partition id 목록이다. v3.1에서는 단순한 문자열 또는 별도 테이블 중 구현이 쉬운 쪽을 선택한다. 핵심은 metric tag에 roomId/partitionId를 넣지 않는 것이다.

## Route 정책

`GET /api/rooms/{roomId}/ws-route`는 더 이상 traffic snapshot만 보고 partition 수를 결정하지 않는다. 우선순위는 다음과 같다.

1. `room_partition_state`가 있으면 그 state를 사용한다.
2. state가 없으면 v3의 기존 advisor 결과를 사용해 초기 state를 만든다.
3. `DRAINING` partition은 신규 접속 후보에서 제외한다.
4. 모든 partition이 draining이면 가장 낮은 partition으로 fallback하되 metric/log를 남긴다.

응답 예시는 유지한다.

```json
{
  "roomId": 1,
  "partitioned": true,
  "partitionId": 3,
  "partitionCount": 4,
  "version": 7,
  "wsUrl": "/ws/chat?roomId=1&partitionId=3&routeVersion=7"
}
```

`routeVersion`은 필수는 아니지만, reconnect나 stale route 판단에 유용하다.

## Scale-up 정책

scale-up은 partition count를 늘리는 작업이다.

예: `2 -> 4`

1. room state를 `SCALING_UP`으로 변경한다.
2. `partition_count`를 4로 증가시킨다.
3. Realtime pool에 partition 2, 3을 소유할 node가 준비돼 있어야 한다.
4. 신규 접속자는 `0..3` 중 하나로 배정한다.
5. 기존 연결은 당장 이동시키지 않는다.
6. 특정 partition의 부하가 계속 높으면 일부 세션에만 reconnect를 유도한다.
7. 안정화되면 state를 `ACTIVE`로 되돌린다.

기존 연결을 즉시 옮기지 않는 이유는 무중단이 더 중요하기 때문이다. scale-up 직후에는 새 접속자부터 분산되고, 기존 연결은 시간이 지나며 자연스럽게 빠지거나 필요한 경우에만 점진적으로 이동한다.

## Scale-down 정책

scale-down은 partition을 줄이는 작업이다.

예: `4 -> 2`

1. 줄일 partition을 `DRAINING`에 넣는다. 예: `2,3`
2. `/ws-route`는 partition 2, 3을 신규 접속 후보에서 제외한다.
3. partition 2, 3에 남은 세션에 `room.reconnect`를 점진 전송한다.
4. 클라이언트는 새 route를 받아 partition 0 또는 1로 재접속한다.
5. `lastSeenSequence` 이후 메시지를 `/messages/after`로 복구한다.
6. 연결 수가 0이 되거나 grace period가 끝나면 pod/partition을 종료할 수 있다.
7. `partition_count`를 2로 낮추고 state를 `ACTIVE`로 변경한다.

pod를 바로 종료하지 않는 것이 핵심이다. WebSocket autoscaling에서 scale-down은 `terminate`가 아니라 `drain -> reconnect -> close remaining -> terminate` 순서로 처리해야 한다.

## Internal Operation API

v3.1의 scale-up/drain 동작은 운영 자동화와 local smoke에서 주입할 수 있어야 한다. 다만 이 API는 운영 공개 API가 아니므로 기본값은 비활성화한다.

```properties
app.room-partition.admin-api-enabled=false
```

활성화된 경우에만 다음 endpoint가 등록된다.

| Endpoint | 목적 |
| --- | --- |
| `POST /api/internal/rooms/{roomId}/partitions/scale-up` | room partition count 증가 |
| `POST /api/internal/rooms/{roomId}/partitions/drain` | 줄일 partition을 draining 상태로 전환 |
| `POST /api/internal/rooms/{roomId}/partitions/drain/reconnect` | draining partition의 기존 세션에 reconnect control 전송 |
| `POST /api/internal/rooms/{roomId}/partitions/drain/complete` | drain 완료 후 partition count 축소 확정 |

scale-up request:

```json
{
  "targetPartitionCount": 4,
  "updatedBy": "local-smoke"
}
```

drain-start request:

```json
{
  "targetPartitionCount": 2,
  "drainingPartitions": [2, 3],
  "updatedBy": "local-smoke"
}
```

drain-reconnect request:

```json
{
  "reason": "scale_down",
  "retryAfterMs": 500
}
```

drain-complete request:

```json
{
  "updatedBy": "local-smoke"
}
```

응답은 smoke와 운영 도구가 처리하기 쉽도록 compact shape로 둔다.

```json
{
  "roomId": 1,
  "operation": "scale-up",
  "accepted": true
}
```

`drain/reconnect`는 대상 세션 수를 함께 반환한다.

```json
{
  "roomId": 1,
  "operation": "drain-reconnect",
  "accepted": true,
  "targetedSessions": 42
}
```

## Reconnect Control Message

서버는 필요한 경우 클라이언트에 다음 control message를 보낸다.

```json
{
  "type": "room.reconnect",
  "roomId": 1,
  "reason": "partition_rebalance",
  "retryAfterMs": 500,
  "routeVersion": 8
}
```

`reason` 후보:

| reason | 의미 |
| --- | --- |
| `partition_rebalance` | scale-up 후 특정 partition의 기존 연결 일부를 이동 |
| `scale_down` | draining partition에서 세션 이동 |
| `node_drain` | 배포/종료 예정 node에서 세션 이동 |

클라이언트 동작:

1. 현재 `lastSeenSequence`를 저장한다.
2. WebSocket을 닫는다.
3. `retryAfterMs` 이후 `/api/rooms/{roomId}/ws-route`를 다시 호출한다.
4. 새 `partitionId`로 WebSocket에 재접속한다.
5. `/api/rooms/{roomId}/messages/after?cursor={lastSeenSequence}`로 누락 메시지를 복구한다.
6. 복구 후 다시 `room.active`를 보낸다.

## 클라이언트 UX 계약

UX 표현과 화면 상태 처리는 프론트엔드 책임이다. 백엔드는 사용자가 보는 문구나 화면 전환을 결정하지 않는다. 다만 `room.reconnect`는 WebSocket protocol contract이므로, 백엔드 문서에는 클라이언트가 지켜야 할 최소 동작만 남긴다.

백엔드 책임:

- 언제 `room.reconnect`를 보낼지 결정한다.
- 어떤 세션에 reconnect를 요청할지 결정한다.
- control message schema를 유지한다.
- 새 `/ws-route` 응답을 제공한다.
- `/messages/after` 복구 API를 유지한다.
- reconnect 요청, drain 진행, close fallback metric을 기록한다.

프론트엔드 책임:

- `room.reconnect`를 받으면 background recovery로 처리한다.
- 메시지 목록, 입력창, draft, pending/optimistic message를 유지한다.
- 새 route로 조용히 재연결한다.
- `lastSeenSequence` 이후 메시지를 `/messages/after`로 복구하고 중복 제거한다.
- 정상적인 짧은 reconnect는 사용자에게 표시하지 않는다.
- 오래 걸리는 경우에도 partition, shard, scale-down, rebalance 같은 내부 용어를 노출하지 않는다.
- 실패가 길어질 때만 일반적인 네트워크 불안정 표현과 수동 재시도 UI를 제공한다.

사용자가 느껴야 하는 경험은 “서버가 재배치 중”이 아니라 “네트워크가 잠깐 느린 것 같다”에 가깝다. 정상적인 reconnect 성공에는 별도 토스트를 띄우지 않는다.

## 기존 연결 처리 원칙

scale-up에서는 기존 연결을 기본적으로 유지한다.

- 장점: 사용자 체감 끊김이 없다.
- 단점: 분산 효과가 신규 접속부터 천천히 나타난다.

scale-down에서는 기존 연결을 drain한다.

- 신규 접속은 draining partition에 배정하지 않는다.
- 기존 연결은 reconnect control로 이동시킨다.
- grace period가 끝난 연결은 close로 재접속을 유도한다.

## Metrics

고카디널리티 tag는 피한다. roomId, userId, sessionId, partitionId는 metric tag로 넣지 않는다.

새 metric 후보:

- `openchat_room_partition_state_total{state}`
- `openchat_room_partition_scale_event_total{direction,result}`
- `openchat_room_partition_draining_count`
- `openchat_room_partition_route_total{result}`
- `openchat_room_partition_route_draining_avoided_total`
- `openchat_room_reconnect_requested_total{reason}`
- `openchat_room_reconnect_sessions_targeted_total{reason}`

로그에는 tier/state 변화 시 다음 필드를 남긴다.

- `roomId`
- `oldPartitionCount`
- `newPartitionCount`
- `oldState`
- `newState`
- `drainingPartitions`
- `reason`
- `activeSessions`
- `roomWork`

## Test Plan

### 단위 테스트

- `RoomPartitionStateService`
  - state가 없으면 초기 state 생성
  - scale-up 시 partition count 증가
  - scale-down 시 draining partition 설정
  - draining 완료 시 partition count 감소
- `RoomPartitionRouteService`
  - state 기반 route 응답
  - draining partition 신규 배정 제외
  - 같은 userId는 같은 state version 안에서 안정 배정
  - state version 변경 후 route 갱신
- `RoomSessionRegistry`
  - partition별 active/passive 집계
  - draining 대상 세션 조회
  - reconnect 대상 세션 샘플링
- WebSocket handler
  - `room.reconnect` control message 전송
  - reconnect control은 DB 저장/ack 경로를 타지 않음

### 로컬 smoke

1. app 2개 실행
2. room state `partition_count=2`
3. user A/B 접속 후 partition 0/1 분산 확인
4. room state `partition_count=4`로 변경
5. 신규 user C/D가 partition 2/3에도 배정되는지 확인
6. partition 3을 `DRAINING` 처리
7. 신규 user가 partition 3에 배정되지 않는지 확인
8. partition 3 기존 세션에 `room.reconnect` 전송 확인
9. reconnect 후 `/messages/after` 복구 확인

### GCP smoke

로컬 smoke가 끝난 뒤에만 작은 GCP smoke를 1회 실행한다.

- 100명 active/passive
- partition `2 -> 4` scale-up event 수동 주입
- draining partition 1개 수동 주입
- DB rows = ack count 확인
- passive unexpected `0`
- reconnect 후 visible 복구 확인

### GCP smoke 결과

`20260507-v31-smoke`, `20260507-v31-manual` 두 번의 작은 GCP smoke로 확인했다.

`20260507-v31-smoke`는 자동 cleanup 기준으로 실행했다. k6 100명 active/passive 시나리오에서 WebSocket connect, ack, DB 저장 정합성, passive unexpected message가 정상임을 확인했다. 다만 자동 cleanup 때문에 scale-up/drain/reconnect를 수동으로 주입할 시간이 부족했다.

`20260507-v31-manual`은 `k6_cleanup_enabled=false`, `chat_duration_seconds=300`으로 실행해 내부 operation API를 수동으로 호출했다.

확인된 값:

| 항목 | 결과 |
| --- | ---: |
| run id | `20260507-v31-manual` |
| k6 VU | `100` |
| active/passive | `30 / 70` |
| checks | `600 pass / 0 fail` |
| WebSocket connect success | `100%` |
| HTTP error rate | `0%` |
| sent / ack | `8,688 / 8,688` |
| DB rows | `8,688` |
| passive unexpected messages | `0` |
| ack p95 | `18ms` |
| visible p95 | `114ms` |

수동 operation API 확인:

| 동작 | 결과 |
| --- | --- |
| `/ws-route` 초기 응답 | `partitionCount=2`, `version=1` |
| scale-up `2 -> 4` | `partitionCount=4`, `version=2` |
| drain partition `1` | 신규 route에서 partition `1` 제외, `version=3` |
| drain complete | `partitionCount=3`, `version=4` |

이 실행에서 중요한 보완점도 확인했다. `drain/reconnect`를 LB/API 경유로 호출하면 API node의 in-memory `RoomSessionRegistry`만 조회하므로 `targetedSessions=0`이 나왔다. Realtime node에는 실제 partition session metric이 있었지만, reconnect 명령이 그 Realtime owner까지 전달되지 않았다.

따라서 v3.1의 state route와 drain route 회피는 GCP에서 동작을 확인했지만, reconnect operation은 다음 구조 보강이 필요하다.

- internal reconnect API를 Realtime owner node에 직접 호출한다.
- 또는 API가 Redis control channel로 `room.reconnect` command를 broadcast하고, 각 Realtime node가 자기 registry에서 대상 세션을 찾아 전송한다.
- K8s로 가기 전에도 이 control-plane 전달 경로가 있어야 scale-down drain이 실제 운영 동작이 된다.

이번 결과는 `infra/gcp-loadtest/results/2026-05-07-hot-room-partition-v31-smoke.md`에 별도로 기록했다.

## Acceptance Criteria

- `/ws-route`가 room partition state를 기준으로 응답한다.
- scale-up 후 신규 접속자는 늘어난 partition 후보로 배정된다.
- scale-up 직후 기존 연결은 끊기지 않는다.
- scale-down 대상 partition은 신규 접속 후보에서 제외된다.
- draining partition의 기존 세션은 reconnect control을 받을 수 있다.
- reconnect 후 `/messages/after`로 누락 메시지를 복구한다.
- DB 저장/ack는 메시지당 1회만 유지된다.
- active/passive fan-out 제외는 partition state 변경 후에도 유지된다.

GCP smoke 기준으로는 앞의 네 항목과 DB/active-passive 정합성은 확인했다. 다만 reconnect control은 API node에서 직접 처리하면 Realtime node registry에 닿지 않는다는 한계를 확인했으므로, Redis control broadcast 또는 Realtime owner 직접 호출을 v3.1 보완 작업으로 둔다.

## v4로 넘어가는 기준

v3.1까지 구현되면 애플리케이션은 scale-up/scale-down을 받아들일 수 있는 프로토콜을 갖는다. 그 다음에야 K8s 도입 이유가 명확해진다.

v4에서 K8s를 검토할 기준:

- partition owner를 정적 설정이 아니라 pod identity로 관리해야 한다.
- Realtime pod 수를 metric 기반으로 자동 조절해야 한다.
- drain과 termination grace period를 배포/스케일다운 lifecycle에 연결해야 한다.
- partition owner 변경을 Redis 또는 control-plane으로 공유해야 한다.

## 포트폴리오 정리 문장

Hot room fan-out partition을 구현한 뒤, 단순히 서버를 늘리는 것만으로 기존 WebSocket 연결이 자동 분산되지 않는다는 한계를 확인했다. 그래서 room별 partition state, 신규 접속 route 변경, draining partition, reconnect control, `/messages/after` 복구를 포함한 autoscaling-aware WebSocket 운영 프로토콜을 설계했다.

## v3.2 Redis Control Plane

v3.1 GCP smoke에서 `drain/reconnect`를 API node 경유로 호출하면 `targetedSessions=0`이 나왔다. 원인은 WebSocket session registry가 node-local이고, 실제 세션은 Realtime node에 있는데 API node가 자기 메모리만 조회했기 때문이다.

v3.2에서는 API 명령과 실제 세션 이동 실행을 분리했다.

```text
API internal operation
  -> Redis control channel publish
  -> Realtime nodes subscribe
  -> 각 Realtime node가 자기 RoomSessionRegistry 조회
  -> 대상 partition 세션에 room.reconnect 전송
```

control channel은 다음 형식을 사용한다.

```text
openchat:room-partition-control:{roomId}
```

payload:

```json
{
  "type": "partition.reconnect",
  "roomId": 1,
  "partitionId": 1,
  "reason": "scale_down",
  "limit": 100,
  "retryAfterMs": 500,
  "routeVersion": 4,
  "requestedAt": 1778000000000
}
```

기존 `drain/reconnect` API 응답은 실제 targeted session 수가 아니라 Redis publish 결과를 반환한다.

```json
{
  "roomId": 1,
  "operation": "drain-reconnect",
  "accepted": true,
  "publishedCommands": 1
}
```

실제 세션 대상 수와 전송 결과는 Realtime node metric에서 확인한다.

- `openchat_room_partition_control_publish_total{type,result}`
- `openchat_room_partition_control_received_total{type,result}`
- `openchat_room_partition_control_ignored_total{reason}`
- `openchat_room_reconnect_requested_total{reason}`
- `openchat_room_reconnect_sessions_targeted_total{reason}`
- `openchat_room_reconnect_control_sent_total{reason,result}`

이 변경으로 `room.reconnect` 명령은 chat message fan-out payload와 분리된다. Redis Pub/Sub 특성상 command 유실 가능성은 v3.2에서 허용하고, ack/retry/command log는 다음 단계 보강 대상으로 둔다.


## v3.2 Broadcast Lane Backpressure Safety

v3.2 control-plane으로 reconnect/resync 경로가 검증된 뒤, 같은 복구 경로를 broadcast lane overload에도 적용했다. 목적은 성능 수치 개선이 아니라 queue full 상황에서 실시간 메시지가 조용히 drop되는 문제를 제거하는 것이다.

기존에는 broadcast lane queue가 가득 차면 caller thread inline send를 하지 않도록 바꿨지만, enqueue 실패 task는 drop되고 affected session에는 별도 신호가 없었다. 이 상태에서는 해당 lane의 세션들이 실시간 메시지를 놓쳐도 서버와 클라이언트가 복구 필요성을 명확히 알 수 없었다.

새 정책은 다음과 같다.

```text
broadcast lane enqueue 실패
  -> caller thread direct send 금지
  -> affected sessions close(1013, broadcast_queue_overloaded)
  -> FE unexpected close 감지
  -> /api/rooms/{roomId}/ws-route 재조회
  -> WebSocket reconnect
  -> /messages/after?cursor={lastSeenSequence} 복구
```

추가 metric은 다음 신호를 남긴다.

- `ws.broadcast.lane.overload.sessions_closed`
- `ws.broadcast.lane.overload.close_failed`
- `ws.broadcast.lane.overload.resync_required`

이 변경은 Redis control-plane을 거치지 않는다. queue full을 감지한 Realtime node가 이미 affected session을 알고 있으므로, 해당 node가 직접 close하고 클라이언트가 기존 reconnect/catch-up 경로로 수렴한다.

포트폴리오 관점의 표현은 다음과 같다.

> WebSocket fan-out lane이 과부하로 실시간 순서를 더 보장하기 어려운 상황을 silent drop으로 두지 않고, affected session을 명시적으로 reconnect/resync 경로로 전환했다. 최종 메시지 정합성은 DB와 `/messages/after`가 담당하게 했다.
