# Room Shard Ownership v2

## 요약

v2의 목적은 작은 방이 갑자기 많이 생겼을 때 모든 Realtime 노드가 모든 Redis room message를 받는 구조를 줄이는 것이다.

기존 구조는 Redis Pub/Sub channel을 `chat:room:{roomId}`로 만들고 Realtime 노드가 `chat:room:*`를 pattern subscribe한다. 이 방식은 실제 로컬 WebSocket 세션이 없는 방의 메시지도 모든 Realtime 노드가 받아 deserialize/fan-out 판단을 하게 된다.

v2에서는 방마다 `shardId`를 배정하고, shard mode가 켜진 경우 `chat:room-shard:{shardId}`로 publish한다. Realtime 노드는 자신이 맡은 shard만 subscribe한다. 기본값은 legacy mode라 기존 동작은 유지된다.

## 설계

- `room.shard_id`
  - 신규 방 생성 시 `RoomShardAssignmentService`가 shard를 배정한다.
  - 기존 방의 `shard_id`가 null이면 code fallback은 `0`이다.
  - 현재 프로젝트에는 migration 도구가 없으므로 dev/loadtest는 `ddl-auto=update`에 맡기고, 운영/validate 환경은 수동 DDL이 필요하다.

- Redis channel
  - legacy: `chat:room:{roomId}`
  - shard: `chat:room-shard:{shardId}`
  - `app.room-shard.enabled=false`이면 legacy publish/subscribe를 유지한다.
  - `app.room-shard.enabled=true`이면 shard channel로 publish하고 `owned-shards`만 subscribe한다.
  - `app.room-shard.legacy-subscribe-enabled=true`이면 전환 중에도 `chat:room:*`를 함께 subscribe한다.

- least-loaded assignment
  - 신규 방은 가장 낮은 score shard에 배정한다.
  - score:
    ```text
    roomWorkPerSecond
    + activeSessions * 10
    + queueDepth * 50
    + roomCount * 100
    ```
  - `OVERLOADED` shard는 후보에서 제외한다.
  - 모든 shard가 `OVERLOADED`이면 방 생성은 막지 않고 가장 낮은 score shard로 fallback한다.

- overload guard
  - 상태: `NORMAL`, `OVERLOADED`
  - overloaded 조건:
    - `roomWorkPerSecond >= podBudget * 0.8`
    - 또는 active session 과밀
    - 또는 broadcast queue depth threshold 초과
  - 승격 hysteresis: `30s`
  - 복귀 hysteresis: `3min`

## 설정

```properties
app.room-shard.enabled=false
app.room-shard.shard-count=1
app.room-shard.owned-shards=0
app.room-shard.legacy-subscribe-enabled=true
app.room-shard.pod-work-budget-delivery-per-sec=10000
app.room-shard.overload-threshold-ratio=0.8
app.room-shard.max-active-sessions-per-partition=500
app.room-shard.queue-depth-threshold=5000
app.room-shard.overload-stable-ms=30000
app.room-shard.recovery-stable-ms=180000
```

운영/validate 환경 수동 DDL:

```sql
ALTER TABLE room ADD COLUMN shard_id INT NULL;
CREATE INDEX idx_room_status_shard ON room (status, shard_id);
```

## Metric

- `openchat_room_shard_count`
- `openchat_room_shard_owned_count`
- `openchat_room_shard_overloaded_count`
- `openchat_room_shard_assignment_total{result}`
- `openchat_room_shard_publish_total{mode}`
- `openchat_room_shard_subscribe_total{mode}`
- `openchat_room_shard_work_max_per_second`

`roomId`, `sessionId`, `userId`는 metric tag에 넣지 않는다.

## v2와 v3 경계

v2는 작은 방 여러 개를 shard 단위로 묶는 구조다. 특정 hot room 하나를 여러 pod가 나눠 fan-out하는 구조는 아니다.

v3에서는 `CRITICAL` room을 일반 shard에서 분리하고, active session fan-out partition으로 나눠 여러 Realtime pod가 처리하는 방식을 다룬다.
