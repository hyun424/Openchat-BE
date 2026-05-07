# Hot Room Fan-out Partition v3 설계와 구현

## 문제 정의

v2의 room shard ownership은 작은 방이 많을 때 모든 Realtime 노드가 모든 방 메시지를 받는 문제를 줄인다. 하지만 단일 hot room은 여전히 하나의 방 안에서 `input_msg_tps * active_sessions`만큼 delivery work가 생긴다.

1500명 all-active 기준선은 약 `2,250,000 delivery/s`, 1500명 active/passive 조건도 약 `189,000 delivery/s`로 재해석됐다. active/passive로 불필요한 full fan-out은 줄었지만, 4 vCPU Realtime pod 하나의 budget으로 볼 대상은 아니다.

## v3 방향

v3는 K8s나 Docker Swarm을 먼저 도입하지 않고, 애플리케이션 레벨에서 hot room fan-out partition 구조를 검증한다.

- 작은 방은 v2의 `chat:room-shard:{shardId}` channel을 유지한다.
- hot/critical room은 `chat:room-partition:{roomId}:{partitionId}` channel로 나눠 publish한다.
- DB 저장과 ack는 기존처럼 메시지당 1회만 수행한다.
- Redis publish만 partition 수만큼 수행하고, 각 Realtime 인스턴스는 자신이 소유한 partition channel만 subscribe한다.
- WebSocket 연결은 `GET /api/rooms/{roomId}/ws-route`로 받은 `partitionId`를 포함해 접속한다.

## 왜 Swarm/K8s보다 앱 레벨 partition이 먼저인가

Swarm과 K8s는 replica 실행, service discovery, rollout, autoscaling을 도와준다. 그러나 어떤 room을 어떤 worker가 맡고, hot room의 active session을 어떤 기준으로 나눌지는 애플리케이션이 결정해야 한다.

따라서 v3에서는 먼저 다음을 코드와 테스트로 증명한다.

- 같은 메시지가 partition channel별로 fan-out될 수 있다.
- 같은 room의 WebSocket session이 partitionId 기준으로 분리된다.
- 각 partition owner는 자기 partition session에만 full payload를 보낸다.
- 기존 legacy/shard fan-out은 기본 설정에서 깨지지 않는다.

이 구조가 확인된 뒤에야 K8s 도입 이유가 명확해진다. v4에서는 stable pod identity, metric-based autoscaling, rollout 단위가 필요해지는 시점에 K8s를 검토한다.

## 구현 요약

- 설정:
  - `app.room-partition.enabled=false`
  - `app.room-partition.partition-count=1`
  - `app.room-partition.owned-partitions=0`
  - `app.room-partition.hot-tier-threshold=CRITICAL`
  - `app.room-partition.max-partitions-per-room=16`
- route API:
  - `GET /api/rooms/{roomId}/ws-route`
  - `partitionId = stableHash(userId) % partitionCount`
- Redis:
  - legacy: `chat:room:{roomId}`
  - shard: `chat:room-shard:{shardId}`
  - partition: `chat:room-partition:{roomId}:{partitionId}`
- fan-out:
  - partition channel subscriber는 `ChatFanoutService.fanout(message, partitionId)`를 호출한다.
  - dedupe key는 `messageId` 단독이 아니라 `partitionId + messageId`로 분리한다.
  - `RoomSessionRegistry`는 partitionId가 일치하는 active session에만 full payload를 보낸다.

## 검증 기준

- 기본 설정에서 기존 WebSocket 접속과 shard fan-out이 유지된다.
- partition mode에서 Redis publish는 partition 수만큼 발생한다.
- DB row와 ack는 메시지당 1개만 생성된다.
- partitionId가 다른 세션에는 메시지가 전송되지 않는다.
- partition 내부에서도 passive 세션은 full fan-out 대상에서 제외된다.
- K8s/Swarm 없이 로컬 다중 앱 인스턴스로 구조 검증이 가능하다.

## GCP 검증 결과

2026-05-06에 두 단계로 확인했다.

1. `20260506-v3part-smoke2`
   - 100명 active/passive smoke
   - API `e2-standard-2 x1`, Realtime `e2-standard-4 x2`, k6 `e2-standard-4 x1`
   - `/ws-route`는 100회 모두 `partitioned`로 응답했다.
   - DB rows와 k6 ack count는 `853`으로 일치했다.
   - partition publish/subscribe는 `853 * 2 = 1,706`건으로 확인됐다.
   - passive unexpected message는 `0`이었다.
   - ack p95는 `27ms`, visible p95는 `88.75ms`였다.

2. `20260506-v3part-hr1500`
   - 1500명 active/passive hot room
   - API `e2-standard-4 x1`, Realtime `e2-standard-8 x4`, k6 `e2-standard-8 x2`
   - active `450`, passive `1050`
   - DB rows와 k6 ack count는 `50,853`으로 일치했다.
   - k6 worker별 ack p95는 `18ms`, `17ms`였다.
   - k6 worker별 visible p95는 `163ms`, `97.05ms`였다.
   - passive unexpected message는 두 worker 모두 `0`이었다.
   - `/ws-route`는 1500회 모두 `partitioned`로 응답했다.
   - partition publish/subscribe는 `50,853 * 2 = 101,706`건으로 확인됐다.
   - 두 Realtime node가 partition traffic을 처리했고, 각 node의 active session max는 `225`였다.
   - `ws.send.failed`는 `0`이었다.
   - server `lane_done` p95는 처리 node 기준 worst `268.17ms`였다.

### 확인된 한계

1500명 본 실행은 `room_partition_partition_count=4`로 실행했지만 실제 fan-out partition은 2개만 사용했다. 이유는 현재 v3가 WebSocket 접속 시점에 `RoomTrafficMonitor` snapshot으로 recommended partition count를 계산하기 때문이다. 접속 시점에는 아직 메시지 트래픽이 충분히 쌓이지 않아 `effectivePartitions=1`에 가깝고, v3는 최소 partition 수인 `2`로 시작한다.

따라서 이번 결과는 “hot room fan-out이 단일 Realtime node에 고정되지 않고 2개 Realtime node로 분산될 수 있다”는 검증이다. 반면 “사전에 계획한 4개 partition을 모두 사용한다”는 검증은 아직 아니다. 이벤트성 hot room처럼 트래픽이 몰릴 것을 미리 아는 방은 v3.1에서 `expectedPartitionCount` 또는 운영자/스케줄 기반 pre-assignment를 둬야 한다.

## 포트폴리오 정리 문장

서버 수를 먼저 늘리는 대신, 단일 hot room의 fan-out work가 어디에서 폭증하는지 `room_work = input_msg_tps * active_sessions`로 정의했다. 이후 작은 방은 shard ownership으로 묶고, hot room은 room partition channel과 session partition assignment로 나누는 구조를 구현했다. 이로써 K8s 도입 전에도 애플리케이션 자체가 scale-out 가능한 fan-out 경계를 갖도록 만들었다.

GCP 검증에서는 좋은 수치만 기록하지 않고, `partition_count=4` 설정에서도 접속 시점 traffic snapshot 때문에 실제로는 2개 partition만 활성화되는 한계를 확인했다. 이 결론은 다음 단계가 단순 증설이 아니라 “hot room을 사전에 예측하거나 예약된 partition 수로 배정하는 구조”여야 한다는 근거가 된다.
