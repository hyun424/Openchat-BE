# Dynamic Realtime Partition Ownership와 Node Drain STAR 기록

> 작성일: 2026-05-08
> 목적: 포트폴리오, 이력서, 면접 답변에서 재사용할 수 있도록 `왜 했는가`, `어떤 문제를 풀었는가`, `어떻게 검증했는가`를 STAR 형식으로 정리한다.

## 한 줄 요약

OpenChat의 hot room partition 구조를 단순히 여러 서버로 route하는 수준에서 끝내지 않고, **route 결과, 실제 WebSocket 연결 node, Redis subscriber owner, reconnect 기반 node drain 결과가 같은 assignment contract를 따르는지** GCP smoke로 검증했다.

## Situation

OpenChat은 WebSocket 기반 실시간 채팅 서비스다. 단일 서버에서는 한 방에 연결된 세션을 메모리에서 순회해 fan-out하면 되지만, 서버가 여러 대로 늘어나면 연결 세션이 각 node에 흩어진다.

초기 구조에서는 Redis Pub/Sub으로 멀티 인스턴스 fan-out 문제를 해결했다. 그러나 모든 realtime node가 모든 room message를 구독하고 처리하면, 서버 수를 늘릴수록 중복 subscriber work와 Redis egress가 증가한다. 특히 hot room에서는 `input_msg_tps * active_sessions` 형태로 fan-out work가 커지고, 작은 방이 많아지는 상황과 단일 hot room이 커지는 상황도 서로 다른 방식으로 다뤄야 했다.

그래서 이전 단계에서 다음 기반을 만들었다.

- active/passive fan-out으로 실제 보고 있는 세션만 full payload 대상화
- room work와 pod budget 기준 partition recommendation
- hot room partition lifecycle 자동화
- route reconnect와 drain 기반 scale-down
- k6/GCP 기반 WebSocket 정합성 검증

하지만 아직 남은 문제가 있었다. partition을 여러 개로 늘려도, **어떤 realtime node가 어떤 partition을 실제로 소유하는지**가 route, WebSocket 연결, Redis subscriber, fan-out 경로 전체에서 일관되게 증명되어야 했다.

## Task

목표는 "서버를 여러 대 띄웠다"가 아니라, 다음 질문에 답하는 것이었다.

1. `/ws-route`가 특정 partition의 owner node를 일관되게 반환하는가?
2. k6 클라이언트가 route 응답의 `wsUrl`로 실제 해당 realtime node에 연결되는가?
3. Redis subscriber owner도 같은 assignment를 따르는가?
4. node가 drain 상태가 되면 신규 route에서 제외되는가?
5. 기존 WebSocket 연결도 reconnect control을 통해 다른 node로 이동하는가?
6. drain 대상 node의 open session count가 0이 되는 것을 외부에서 확인할 수 있는가?
7. 이 과정에서 메시지 ack, DB rows, observer visibility 정합성이 깨지지 않는가?

또한 GCP VM 기반 smoke에서 먼저 증명하되, 나중에 EKS/pod 환경으로 옮겨도 앱 로직이 유지되도록 설계해야 했다. 그래서 `nodeId`는 기존 `app.instance-id`로 통일하고, v1은 VM direct `wsUrl`로 실제 연결 node를 증명하는 방향으로 잡았다.

## Action

### 1. Node registry를 routing/assignment 기준으로 분리

workload snapshot은 부하 판단용으로 유지하고, node registry를 routing/assignment의 source of truth로 분리했다.

registry node에는 다음 정보를 포함시켰다.

- `nodeId`
- `role`
- `wsUrl`
- `draining`
- `reportedAt`
- `expiresAt`
- `subscribedPartitions`
- `openSessions`

이 중 `openSessions`는 node drain 검증을 위해 추가했다. node가 draining 상태가 되면 active node 계산에서 제외되도록 했고, TTL이 만료된 node나 malformed entry는 registry 조회 시 정리되도록 했다.

### 2. Deterministic assignment contract 정의

v1 assignment는 복잡한 weighted balancing 대신 deterministic modulo 방식으로 정의했다.

```text
active node = TTL 유효 + role=realtime 또는 combined + draining=false
owner = sorted(activeNodes)[partitionId % activeNodeCount]
assignmentVersion = sorted active node ids + partitionCount 기반 stable hash
```

이 선택은 의도적이었다. v1의 목표는 최적 분산이 아니라, route/subscriber/reconnect가 같은 contract를 따르는지 검증하는 것이었다. 그래서 누구나 설명할 수 있고 테스트하기 쉬운 deterministic rule을 먼저 사용했다.

### 3. Node-aware routing 구현

`/ws-route` 응답을 backward-compatible하게 확장했다.

기존 필드는 유지했다.

- `roomId`
- `partitioned`
- `partitionId`
- `partitionCount`
- `version`
- `wsUrl`

새 필드는 optional로 추가했다.

- `nodeId`
- `assignmentVersion`
- `fallbackReason`

registry나 assignment 조회가 실패해도 5xx로 바로 실패시키지 않고 기존 local route로 fallback하게 했다. 운영 환경에서 registry 일시 장애가 곧 연결 실패로 이어지는 것은 좋지 않기 때문이다. 대신 GCP smoke에서는 fallback count를 acceptance criterion으로 두어, 정상 검증에서는 fallback이 0이어야 통과하도록 했다.

### 4. Subscriber ownership과 readiness 관찰

각 realtime node가 자신이 맡아야 할 partition set을 계산하고, heartbeat에 `subscribedPartitions`를 반영하도록 했다. dynamic subscribe가 켜진 경우 실제 runtime subscription 상태를 registry에 보고하고, assignment API에서 owner readiness를 확인할 수 있게 했다.

이렇게 한 이유는 route만 바뀌어서는 부족하기 때문이다. 신규 연결이 owner node로 가더라도, 그 node가 Redis partition channel을 아직 subscribe하지 않았다면 fan-out이 빠질 수 있다. 따라서 route 전환과 subscriber readiness를 같은 contract로 묶었다.

### 5. Node drain을 room partition drain과 분리

room partition drain은 특정 room의 partition count를 줄이기 위한 절차다. 반면 node drain은 특정 realtime node를 비워서 종료 가능 상태로 만드는 절차다. 목적과 범위가 다르기 때문에 별도 command로 분리했다.

node drain 흐름은 다음과 같이 설계했다.

```text
1. target node를 draining=true로 표시
2. assignment에서 target node 제외
3. replacement owner가 ready 될 때까지 대기
4. target node의 기존 sessions에 node-level reconnect command 발송
5. 클라이언트가 /ws-route 재호출 후 새 node로 재접속
6. target node openSessions가 0인지 확인
```

마지막 active node는 drain하지 못하게 막았다. replacement node가 준비되지 않은 상태에서 reconnect를 보내면 route fallback이나 연결 실패를 만들 수 있으므로, readiness 대기 후 reconnect를 발송하도록 했다.

### 6. Redis control-plane 안전성 보강

node-specific reconnect command는 Redis publish receiver count를 확인하도록 했다. partition broadcast command와 달리 node-specific command는 대상 node subscriber가 없으면 의미가 없다.

따라서 `convertAndSend()` 결과가 0이면 성공으로 처리하지 않고 `publish_failed`로 기록했다. 이로써 "명령을 보냈다고 생각했지만 실제 수신자가 없었다"는 상태를 숨기지 않게 했다.

### 7. k6/GCP smoke가 실제 운영 흐름을 검증하게 함

k6 startup runner에 node drain 절차를 넣었다.

검증 흐름은 다음과 같다.

```text
1. assignment snapshot 수집
2. openSessions가 있는 active realtime node 선택
3. internal API로 node drain 요청
4. drain response status 확인
5. assignment polling
6. target node가 owner에서 빠졌는지 확인
7. replacement assignment가 ready인지 확인
8. target node openSessions가 0인지 확인
9. k6 sent/ack/DB rows 정합성 확인
10. cleanup 후 RUN_ID VM 잔여 없음 확인
```

이 검증은 단순히 "API가 성공했다"가 아니라, 실제 WebSocket 연결 이동과 메시지 정합성까지 확인한다.

## Result

### Local verification

커밋 전 로컬에서 다음 검증을 통과했다.

- `./gradlew test`
- `node --check k6/scenarios/11-mixed-room-workload-ramped.js`
- `bash -n infra/gcp-loadtest/templates/k6-startup.sh.tftpl`
- `terraform fmt -check infra/gcp-loadtest`
- `terraform validate`
- `git diff --check`

### GCP smoke result

GCP node drain smoke는 다음 결과로 PASS했다.

| 항목 | 결과 |
|---|---:|
| RUN_ID | `20260508-node-drain-smoke` |
| k6 exit code | `0` |
| custom HTTP error rate | `0.00%` |
| WebSocket connect success | `145/145` |
| route failure/fallback/mismatch | `0/0/0` |
| node drain reconnect controls received | `45` |
| sent / ack / DB rows | `22300 / 22300 / 22300` |
| drained node | `gcp-realtime-1` |
| drained node openSessions | `0` |
| after drain assignment owner | `gcp-realtime-2` |
| after drain assignment readiness | all 4 ready |
| cleanup | RUN_ID GCE VM 잔여 없음 |

이 결과는 다음을 의미한다.

- route 결과와 실제 connected node가 일치했다.
- draining node는 신규 route/assignment에서 제외됐다.
- 기존 연결은 reconnect control을 받고 다른 node로 이동했다.
- 이동 중 메시지 ack와 DB 저장 정합성이 깨지지 않았다.
- node의 open session count가 0이 되어 VM 종료 가능 상태를 앱 레벨에서 확인했다.

## Why This Matters

이 작업의 핵심은 "오토스케일링을 구현했다"가 아니다.

더 정확한 표현은 다음과 같다.

> WebSocket 세션이 특정 node 메모리에 묶이는 실시간 시스템에서, partition owner 변경과 node drain을 reconnect/resync 기반으로 안전하게 처리할 수 있는 control-plane을 만들었다.

서버를 더 띄우는 것만으로는 실시간 채팅이 자동으로 확장되지 않는다. 다음 계약이 함께 맞아야 한다.

- route contract
- connection target
- subscriber ownership
- fan-out path
- reconnect path
- drain completion signal

이번 작업은 이 계약을 GCP VM 환경에서 end-to-end로 증명했다. EKS/MIG 자동 종료는 아직 구현하지 않았지만, 그 전 단계인 "이 realtime node는 안전하게 비워졌으니 종료해도 된다"는 판단 근거를 만들었다.

## Trade-offs

### 왜 sorted modulo assignment를 선택했나

v1의 목표는 최적 분산이 아니라 contract 검증이었다. Rendezvous hashing이나 weighted assignment는 더 좋지만, 처음부터 넣으면 route/subscriber/drain 문제와 balancing 문제가 섞인다. 그래서 deterministic modulo로 시작해 테스트 가능성을 우선했다.

### 왜 VM direct wsUrl을 사용했나

GCP smoke에서 route node와 connected node 일치를 증명하려면 L7/LB 뒤에 숨기기보다 실제 VM 내부 IP로 직접 연결하는 편이 명확했다. 운영 구조는 나중에 LB/EKS로 바꿀 수 있지만, v1 검증에서는 "정말 그 node에 붙었는가"가 더 중요했다.

### 왜 node drain을 room partition drain과 분리했나

room partition drain은 특정 room의 partition 수를 줄이는 기능이고, node drain은 특정 realtime node를 비워 배포/종료 가능 상태로 만드는 기능이다. 둘을 섞으면 scale-down과 node lifecycle의 실패 조건이 섞인다. 그래서 command를 분리해 운영 의미를 명확히 했다.

### 왜 max grace 강제 종료를 넣지 않았나

v1에서는 안전성을 우선했다. 세션이 남아 있는데 강제로 unsubscribe하거나 node를 종료하면 메시지 누락 또는 reconnect 폭주가 생길 수 있다. 그래서 세션 0 확인을 기준으로 하고, max grace 이후 강제 정책은 후속 운영 hardening으로 남겼다.

## Interview Answer Draft

면접에서는 다음 흐름으로 설명할 수 있다.

> 단순히 WebSocket 서버를 여러 대 띄우는 것만으로는 실시간 채팅이 scale-out되지 않는다는 점을 확인했습니다. 세션은 node 메모리에 있고, Redis subscriber도 node별로 다르기 때문에 route 결과, 실제 연결 node, subscriber owner, fan-out 경로가 모두 같은 assignment를 따라야 했습니다.
>
> 그래서 node registry를 source of truth로 두고, partition owner를 deterministic하게 계산했습니다. `/ws-route`는 owner node의 direct `wsUrl`과 `nodeId`를 반환하고, k6는 실제 연결 node가 route node와 일치하는지 검증했습니다.
>
> 이후 운영 상황까지 고려해 node drain을 구현했습니다. 특정 realtime node를 draining으로 표시하면 신규 route에서 제외하고, replacement owner가 ready 된 뒤 기존 세션에 reconnect control을 보냅니다. 클라이언트는 `/ws-route`를 다시 호출해 다른 node로 이동하고, registry heartbeat의 open session count가 0이 되면 해당 node를 종료 가능한 상태로 봅니다.
>
> GCP smoke에서는 WebSocket 연결 성공 145/145, route failure/fallback/mismatch 0, reconnect control 45건, sent/ack/DB rows 22300건 일치를 확인했습니다. 이를 통해 단순 성능 수치가 아니라, node ownership 변경 중에도 메시지 정합성과 운영 가능한 drain 경로가 유지된다는 점을 검증했습니다.

## Resume Bullet Draft

- WebSocket hot room 확장을 위해 node registry 기반 dynamic partition ownership을 설계하고, `/ws-route` 결과와 실제 connected node, Redis subscriber owner가 같은 assignment contract를 따르도록 구현했다.
- Realtime node drain control-plane을 구현해 draining node를 신규 route에서 제외하고, replacement owner readiness 확인 후 기존 WebSocket sessions에 reconnect를 전송해 node open session count를 0으로 수렴시켰다.
- GCP smoke에서 route failure/fallback/mismatch `0/0/0`, reconnect control `45`건, sent/ack/DB rows `22300/22300/22300`, drained node openSessions `0`을 검증해 node ownership 변경 중 메시지 정합성을 확인했다.

## Follow-up

이번 단계 이후 남은 작업은 운영 hardening이다.

- node drain max grace 이후 정책 정의
- LB/EKS 환경에서 direct `wsUrl` 대신 service discovery 기반 접속 검증
- rendezvous hashing 또는 weighted assignment 검토
- node drain과 rolling deploy/MIG scale-in 연계
- 장시간 soak에서 reconnect 반복과 observer visibility 누적 확인

### Follow-up: Node Drain Status-only Hardening

기존 node drain smoke는 "draining node의 세션이 0이 된다"는 결과를 증명했지만, 운영자나 future orchestrator가 응답만 보고 다음 행동을 판단하기에는 정보가 부족했다.

이번 hardening의 방향은 force 종료나 background orchestrator가 아니라, drain 판단 계약을 명확히 하는 것이다.

- `POST /nodes/{nodeId}/drain`은 action endpoint로 유지한다.
- `GET /nodes/{nodeId}/drain/status`를 추가해 publish 없이 상태를 조회한다.
- 응답에 `retryable`, `nextAction`, `readinessReason`을 추가한다.
- `complete`, `reconnect_published`, `sessions_remaining`, `assignment_unavailable`, `drained_node_still_owner`, `owner_not_ready`, `publish_failed`의 의미를 고정한다.
- 세션이 0이어도 replacement assignment가 준비되지 않았으면 `complete`로 보지 않는다.

중요한 경계도 남겼다.

- status endpoint는 reconnect publish나 `markDraining`을 수행하지 않는다.
- Redis registry의 `nodes()` 조회는 기존 구현상 stale node/draining flag cleanup을 할 수 있으므로, "완전 무부작용 GET"이 아니라 "drain command를 실행하지 않는 observation endpoint"로 해석한다.
- Redis Pub/Sub command durability, ack/retry log, force-drain, EKS/MIG lifecycle hook은 후속 작업으로 둔다.

### Follow-up Result: Node Drain Status-only Hardening Smoke

`20260508-node-drain-hardening-smoke`로 GCP regression smoke를 실행했다. 이 실행은 새 테스트 하네스를 만들지 않고 기존 node drain smoke profile을 사용했으며, 앱 코드, k6, Terraform, production default를 변경하지 않는 `EXECUTION_ONLY` 검증이었다.

#### Result

| 항목 | 결과 |
|---|---:|
| k6 exit code | `0` |
| HTTP error rate | `0.00%` |
| WebSocket connect success | `149/149` |
| route failure/fallback/mismatch | `0/0/0` |
| node drain reconnect controls | `49` |
| sent/ack/DB rows | `22,271 / 22,271 / 22,271` |
| observer visible freshness p95 | `122.45ms` |
| target node | `gcp-realtime-1` |
| drained node openSessions | `0` |
| cleanup | RUN_ID GCE VM 잔여 없음 |

#### Status Contract Evidence

node drain 응답과 status polling snapshot에서 다음 상태 전이가 확인됐다.

1. `POST drain`: `status=reconnect_published`, `retryable=true`, `nextAction=poll_status`, `readinessReason=ready`, `targetedSessions=47`
2. after-request `GET drain/status`: `status=sessions_remaining`, `retryable=true`, `nextAction=retry_reconnect`, `readinessReason=ready`, `remainingSessions=47`
3. progress `GET drain/status`: `status=complete`, `retryable=false`, `nextAction=none`, `readinessReason=ready`, `remainingSessions=0`

이 결과는 status-only hardening의 핵심 가정, 즉 future orchestrator가 응답만 보고 `poll`, `retry`, `wait`, `complete`를 구분할 수 있다는 점을 GCP 환경에서 확인한 것이다.

#### Interpretation

이번 단계는 자동 VM 종료나 EKS eviction을 수행하지 않는다. 대신 "이 node를 종료해도 되는가?"를 앱 레벨에서 판단할 수 있는 contract를 고정했다.

포트폴리오에서는 다음처럼 설명한다.

> WebSocket node drain을 단순 reconnect command가 아니라 운영 판단 contract로 확장했다. `retryable`, `nextAction`, `readinessReason`을 도입하고, replacement owner readiness를 확인한 뒤에만 `complete`로 판정하도록 보강했다. GCP smoke에서 `reconnect_published -> sessions_remaining -> complete` 전이, route mismatch `0`, sent/ack/DB rows `22,271`건 일치, drained node openSessions `0`을 확인해 future MIG/EKS scale-in이 붙을 수 있는 앱 레벨 종료 가능 신호를 검증했다.

#### Remaining Work

- drain orchestrator 또는 운영 스크립트가 `nextAction`을 읽고 poll/retry/wait/abort/complete를 자동 수행하게 만든다.
- 반복 reconnect가 필요한 상황에서 command durability와 ack/retry log가 필요한지 검토한다.
- MIG/EKS scale-in hook은 drain orchestrator가 `complete`를 확인한 뒤 연결한다.
- smoke 이후 load/soak에서 반복 drain, observer visibility, ack/DB 정합성 누적을 확인한다.
