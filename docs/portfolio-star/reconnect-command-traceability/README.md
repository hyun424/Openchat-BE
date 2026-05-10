# Reconnect Command Traceability STAR

> 작성일: 2026-05-09
> 브랜치: `spike-reconnect-command-traceability-overnight`
> 최종 HEAD: `5f10488 fix: map reconnect trace flag for gcp validation`
> 결론: `ADOPT`

## 한 줄 요약

Node drain과 GCP VM stop은 이미 동작했지만, 운영자가 "어떤 reconnect command가 node를 비우는 데 기여했는지" 추적하기 어려웠다. 그래서 reconnect control command에 `commandId`를 도입하고, drain orchestrator와 termination decision, GCP 결과 artifact까지 같은 id가 이어지도록 만들어 3 realtime node mini-soak에서 검증했다.

## Situation

OpenChat은 dynamic realtime partition ownership 이후 다음 단계로 node drain과 GCP VM stop adapter를 구현했다.

이미 검증된 상태는 다음과 같았다.

- `/ws-route`는 assignment에 맞는 realtime node로 사용자를 보낸다.
- node drain command는 target node를 신규 route 대상에서 제외한다.
- 기존 세션에는 reconnect control frame을 보내 다른 node로 이동시킨다.
- drain orchestrator는 `remainingSessions=0`과 `terminationAllowed=true`를 확인한다.
- GCP VM stop adapter는 실제 realtime VM을 `RUNNING -> TERMINATED`로 바꾼다.
- post-stop probe는 남은 realtime node들이 route/fanout을 계속 처리하는지 확인한다.

하지만 운영 관점에서는 중요한 빈틈이 남아 있었다.

기존에는 "reconnect control을 보냈다", "drain이 완료됐다"는 aggregate 상태는 알 수 있었지만, 장애나 지연이 발생했을 때 다음 질문에 명확히 답하기 어려웠다.

- 어떤 drain 작업이 어떤 reconnect command를 발행했는가?
- 발행된 command가 orchestrator 결과와 termination decision까지 이어졌는가?
- publish 실패와 session reconnect 지연을 구분할 수 있는가?
- VM 종료를 허용한 판단이 어떤 reconnect 시도 이후에 나온 것인가?

이 문제는 기능이 안 되는 문제라기보다, 실제 운영에서 장애를 해석하고 책임 경계를 나누기 어려운 문제였다.

## Task

이번 작업의 목표는 reconnect control-plane을 크게 갈아엎지 않고, 운영자가 command 단위로 drain lifecycle을 추적할 수 있게 만드는 것이었다.

명확한 목표는 다음과 같았다.

- reconnect command에 식별 가능한 `commandId`를 부여한다.
- `commandId`가 Redis publisher, subscriber, WebSocket control payload, node drain API response, drain orchestrator result, termination decision result까지 이어지게 한다.
- production 기본 동작은 바꾸지 않는다.
- rolling deploy 중 old subscriber가 깨질 위험을 줄인다.
- GCP smoke/mini-soak에서 실제 VM stop까지 이어지는 end-to-end evidence를 남긴다.

명확히 하지 않기로 한 일도 있었다.

- Redis Pub/Sub를 Redis Streams나 durable queue로 바꾸지 않는다.
- command delivery guarantee를 이번 작업에서 보장하지 않는다.
- DB durable reconnect command log를 만들지 않는다.
- EKS/MIG 자동 종료 로직을 추가하지 않는다.
- Prometheus tag에 `commandId`를 넣지 않는다.

이번 작업의 범위는 durability가 아니라 traceability였다.

## Options

### Option A. 아무것도 하지 않음

장점:

- 변경 위험이 없다.
- 이미 node drain과 VM stop smoke가 통과했으므로 기능상 문제는 없어 보인다.

단점:

- 운영자가 drain 실패 원인을 aggregate metric과 로그 grep에 의존해야 한다.
- "어떤 reconnect command 이후 종료가 허용됐는가"를 artifact로 설명하기 어렵다.
- 포트폴리오 관점에서도 운영 가능성의 마지막 연결고리가 약하다.

판단:

- 운영 가능 수준을 목표로 한다면 부족하다.

### Option B. `commandId` 기반 traceability

장점:

- 기존 Redis Pub/Sub 구조를 유지한다.
- 작은 필드 추가와 script artifact 보강으로 운영 분석 가능성을 높인다.
- GCP smoke/mini-soak artifact로 증명하기 쉽다.
- delivery guarantee를 과장하지 않고, 현재 문제인 관찰 가능성만 개선한다.

단점:

- Pub/Sub 유실 자체는 해결하지 않는다.
- old subscriber가 unknown JSON field에 취약하면 rolling deploy 문제가 생길 수 있다.
- command id가 high-cardinality 값이므로 metric tag로 쓰면 안 된다.

판단:

- 이번 단계의 기본 선택으로 적절하다.

### Option C. DB durable reconnect command log

장점:

- command 발행, ack, retry, completion을 장기 보관할 수 있다.
- 장애 분석과 재시도 상태머신의 기반이 된다.
- termination decision의 safety guard로 확장할 수 있다.

단점:

- schema, write path, retry policy, cleanup policy가 필요하다.
- reconnect command path가 더 무거워진다.
- 지금 당장 관찰 가능성만 필요한 상황에서는 범위가 크다.

판단:

- 후속 hardening 후보로 남긴다.

### Option D. Redis Streams 전환

장점:

- consumer group, pending entry, ack 같은 delivery tracking을 활용할 수 있다.
- Pub/Sub보다 durable command 처리에 유리하다.

단점:

- control-plane 구조 변경 폭이 크다.
- 기존 subscriber/reconnect 구현과 GCP 검증 하네스까지 더 많이 바뀐다.
- 지금 단계에서 오버엔지니어링으로 보일 가능성이 크다.

판단:

- 운영 장애 패턴이 더 쌓인 뒤 검토할 선택지다.

## Decision

Option B를 선택했다.

이유는 세 가지다.

첫째, 문제의 본질이 "reconnect가 반드시 durable하게 전달되어야 한다"가 아니라 "drain과 termination 결과를 command 단위로 설명할 수 없다"였기 때문이다.

둘째, 이미 검증된 Redis Pub/Sub 기반 control-plane과 node drain flow를 크게 바꾸면, 포트폴리오 마감 단계에서 안정성을 잃을 위험이 컸다.

셋째, `commandId`는 provider-neutral한 계약이다. 현재는 GCP VM stop에 쓰지만, 나중에 MIG scale-in이나 EKS eviction에 붙어도 "종료 전 어떤 reconnect command가 관여했는가"라는 질문은 그대로 남는다.

## Action

### 1. Command ID 전파

다음 객체와 경로에 optional `commandId`를 추가했다.

- `RoomPartitionControlCommand`
- `RoomReconnectControlPayload`
- `RealtimeNodeDrainService.NodeDrainResult`
- internal node drain API response
- drain orchestrator JSON output
- termination decision JSON output
- GCP k6 startup result summary

`commandId`는 reconnect command factory에서 `reconnect-<uuid>` 형식으로 생성된다.

### 2. Orchestrator/termination artifact 보강

drain orchestrator에는 command id를 세 가지 의미로 나누어 기록했다.

- `reconnectCommandIds`: 실제 `reconnectPublished=true`였던 command id
- `attemptedReconnectCommandIds`: publish 실패 포함, 시도된 command id
- `lastReconnectCommandId`: 성공적으로 publish된 마지막 reconnect command id

termination decision에는 이를 source evidence로 넘겼다.

- `sourceReconnectCommandIds`
- `sourceAttemptedReconnectCommandIds`
- `sourceLastReconnectCommandId`

이렇게 나눈 이유는 publish 실패 command까지 성공 command처럼 보이는 문제를 막기 위해서다.

### 3. Rolling deploy risk 수정

첫 코드리뷰에서 blocker가 나왔다.

문제:

- Redis payload에 기본으로 `commandId`를 추가하면, old subscriber가 unknown field를 거부할 수 있다.
- 그러면 mixed rolling deploy 중 reconnect command가 malformed로 drop될 수 있다.

수정:

- `app.room-partition.control.command-trace-enabled=false`를 기본값으로 둔다.
- 기본 production mode에서는 Redis JSON payload에서 `commandId`를 제거한다.
- GCP 검증 profile에서만 trace flag를 true로 켠다.

트레이드오프:

- 기본 운영에서는 Redis subscriber payload에서 command id를 보지 못한다.
- 대신 기존 dev/current production shape를 보존해 rolling deploy 안전성을 우선했다.
- 실제 trace-enabled E2E 검증은 GCP profile에서 opt-in으로 수행한다.

### 4. GCP property mapping 수정

두 번째 코드리뷰에서 또 blocker가 나왔다.

문제:

- Terraform과 startup script는 `ROOM_PARTITION_CONTROL_COMMAND_TRACE_ENABLED=true`를 넘기고 있었다.
- 하지만 Spring `application.properties`에 이 env를 `app.room-partition.control.command-trace-enabled`로 매핑하는 줄이 없었다.
- 따라서 GCP profile에서 trace flag를 true로 켠 줄 알았지만 실제 publisher는 false로 동작할 수 있었다.

수정:

- `application.properties`에 다음 매핑을 추가했다.

```properties
app.room-partition.control.command-trace-enabled=${ROOM_PARTITION_CONTROL_COMMAND_TRACE_ENABLED:${env.ROOM_PARTITION_CONTROL_COMMAND_TRACE_ENABLED:false}}
```

이후 코드 리뷰어가 tfvars -> Terraform template var -> startup env -> Spring property -> publisher constructor 경로가 연결됐음을 확인했다.

### 5. 검증 방식

검증은 세 단계로 나누었다.

첫째, 로컬 테스트:

- `./gradlew test`
- `scripts/test-openchat-node-drain-orchestrate.sh`
- `scripts/test-openchat-node-termination-decision.sh`
- `git diff --check`

둘째, 코드리뷰:

- rolling deploy compatibility 리뷰
- command id 의미 일관성 리뷰
- GCP env/property mapping 리뷰

셋째, GCP:

- smoke로 빠른 route/drain/stop 검증
- mini-soak으로 3 realtime node, 200 VU, 300초 workload, post-stop probe 검증

## Result

### Local Result

최종 HEAD `5f10488` 기준:

- `./gradlew test`: PASS
- `scripts/test-openchat-node-drain-orchestrate.sh`: PASS
- `scripts/test-openchat-node-termination-decision.sh`: PASS
- `git diff --check`: PASS
- code review after mapping: blocker 없음

### GCP Smoke Result

Run id: `20260509-reconnect-traceability-smoke`

- status: PASS
- main k6 exit code: `0`
- post-stop k6 exit code: `0`
- route failure/fallback/mismatch: `0/0/0`
- main sent/ack/DB rows: `22290/22290/22290`
- post-stop sent/ack/DB rows: `624/624/624`
- reconnect command ids: `3`
- GCP stop adapter: `stopped`
- cleanup RUN_ID VM: `0`

주의:

- 이 smoke는 property mapping fix 전 HEAD에서 실행됐다.
- 따라서 drain/termination/post-stop 안정성 근거로는 유효하지만, trace-enabled payload의 최종 근거로 단독 사용하지 않는다.

### GCP Mini-soak Result

Run id: `20260509-reconnect-traceability-mini-soak3`

- status: PASS
- realtime nodes: `3`
- assignment preflight:
  - `activeNodeCount=3`
  - `assignmentCount=4`
  - `readyAssignmentCount=4`
  - `distinctOwnerCount=3`
- main k6 exit code: `0`
- post-stop k6 exit code: `0`
- main HTTP error: `0.00%`
- post-stop HTTP error: `0.00%`
- main WebSocket connect: `100%`
- post-stop WebSocket connect: `100%`
- main route failure/fallback/mismatch: `0/0/0`
- post-stop route failure/fallback/mismatch: `0/0/0`
- main sent/ack/DB rows: `110379/110379/110379`
- post-stop sent/ack/DB rows: `3026/3026/3026`
- reconnect controls received: `100`
- GCP stop adapter:
  - `result=stopped`
  - `terminationPerformed=true`
  - `beforeStatus=RUNNING`
  - `afterStatus=TERMINATED`
- drained node:
  - target `gcp-realtime-2`
  - `remainingSessions=0`
  - stop 후 `TERMINATED`
- cleanup:
  - RUN_ID GCE VM `0`
  - stable bucket 보존
  - static IP 보존

Command trace evidence:

- Orchestrator `reconnectCommandIds`:
  - `reconnect-d582deab-d425-44c1-a4a8-ba68faf53dba`
  - `reconnect-9c4da9f9-75d9-4280-97f7-73f0851868c7`
- `attemptedReconnectCommandIds`: 존재
- `lastReconnectCommandId`: `reconnect-9c4da9f9-75d9-4280-97f7-73f0851868c7`
- termination decision이 `sourceReconnectCommandIds`, `sourceAttemptedReconnectCommandIds`, `sourceLastReconnectCommandId`를 보존

Observed noise:

- `gcp-realtime-1`에서 workload 종료 부근 `closed_during_send` WARN 1건이 있었다.
- k6 failure/check failure는 `0`이고 sent/ack/DB row가 완전히 일치했으므로 acceptance에는 영향 없음으로 판단했다.

## Trade-offs Accepted

### Traceability vs Durability

이번 작업은 command가 반드시 전달됐다는 보장을 추가하지 않는다. 대신 command가 발행되고, 처리되고, drain/termination evidence로 이어졌는지를 artifact로 추적할 수 있게 한다.

이 선택은 scope를 줄이는 대신, durable command log나 Redis Streams 같은 큰 구조 변경을 뒤로 미룬다.

### Rolling Safety vs Always-on Trace

기본값 false로 둔 것은 운영 안전을 위한 결정이다.

항상 Redis payload에 `commandId`를 넣으면 trace 정보는 풍부해지지만, old subscriber와 mixed deploy 중 unknown field 문제가 생길 수 있다. 그래서 production default는 legacy payload shape를 유지하고, GCP 검증 profile에서만 trace payload를 켠다.

### Low-cardinality Metrics vs Detailed Logs

`commandId`는 high-cardinality 값이므로 Prometheus tag로 넣지 않았다.

대신 JSON artifact, orchestrator history, termination decision result, log에서 command id를 연결한다. metric cardinality 안정성을 지키면서 운영 분석 근거를 남기는 선택이다.

### Fast Spike vs Perfect Command Lifecycle

완전한 command lifecycle은 발행, ack, timeout, retry, expiration, cleanup까지 포함해야 한다.

이번 spike는 그 전체를 만들지 않고, 기존 drain/termination flow에 trace id를 꽂아 운영자가 원인을 따라갈 수 있는 최소 단계를 완성했다.

## Follow-up Plan: Durable Reconnect Command Log v1

### 왜 필요한가

`commandId` traceability는 drain/orchestrator/termination artifact를 연결해 주지만, 아직 DB에 남는 durable evidence는 아니다. GCP artifact를 잃거나 로그 보존 기간이 지나면 운영자는 command 단위의 publish/handling 결과를 나중에 다시 확인하기 어렵다.

Durable Reconnect Command Log v1의 목적은 reconnect command를 delivery guarantee로 바꾸는 것이 아니라, DB에 audit evidence를 남겨 다음 질문에 답할 수 있게 하는 것이다.

- 어떤 command가 언제 발행 시도됐는가?
- Redis publish 호출은 성공했는가, 실패했는가, receiver가 없었는가?
- 어떤 realtime node가 command를 처리했는가?
- 처리 당시 몇 개 session을 대상으로 했고, 몇 개 control frame 전송에 성공/실패했는가?
- termination decision이 어떤 durable evidence를 함께 보존했는가?

### v1 범위

v1은 `audit_only` mode다.

- `ReconnectCommandLog`: `commandId` 단위 publish audit row.
- `ReconnectCommandHandlingLog`: `commandId + handlerNodeId` 단위 subscriber handling row.
- publisher는 publish attempt, success, failure, no-receivers를 best-effort로 기록한다.
- subscriber는 no-target, sent, partial, failed handling 결과를 best-effort로 기록한다.
- drain orchestrator와 termination decision은 durable log summary를 evidence로 포함한다.
- GCP smoke는 command id artifact와 DB rows가 일치하는지 검증한다.

### 하지 않는 일

- Redis Pub/Sub를 Redis Streams로 바꾸지 않는다.
- reconnect command outbox를 만들지 않는다.
- per-session durable row를 만들지 않는다.
- client ack를 수집하지 않는다.
- terminationAllowed 계산에 durable log를 hard gate로 넣지 않는다.
- command log cleanup scheduler는 v1에서 만들지 않는다.

종료 안전 판단은 계속 `remainingSessions=0`, orchestrator `complete`, termination decision guard를 기준으로 한다. Durable log 누락은 v1에서 종료 금지 사유가 아니라 evidence 약화 또는 진단 실패로 본다.

### Schema / Contract

새 패키지는 `chat.room.partition.commandlog`로 둔다.

`ReconnectCommandLog`는 다음 정보를 가진다.

- `commandId` unique key
- `operationId`
- `commandType`
- `roomId`, `partitionId`, `targetNodeId`
- `publisherNodeId`
- `reason`, `routeVersion`, `limit`, `retryAfterMs`
- `publishStatus`: `ATTEMPTED`, `SUCCEEDED`, `FAILED`, `NO_RECEIVERS`
- `redisReceivers`, `createdAt`, `publishedAt`, `failedAt`, `lastError`

`ReconnectCommandHandlingLog`는 다음 정보를 가진다.

- unique `(commandId, handlerNodeId)`
- `status`: `NO_TARGET`, `SENT`, `PARTIAL`, `FAILED`
- `openSessionsBefore`, `targetedSessions`, `sentSessions`, `failedSessions`, `remainingOpenSessions`
- `handledAt`, `lastError`

이 repo는 Flyway를 쓰지 않고 기본 `ddl-auto=validate`를 사용한다. 따라서 새 JPA entity만 추가하면 loadtest profile의 schema initializer는 테이블을 만들 수 있지만, validate 환경은 배포 전 manual DDL이 필요하다. v1 구현 시 README 또는 별도 schema note에 DDL을 남긴다.

Manual DDL 초안:

```sql
CREATE TABLE reconnect_command_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  command_id VARCHAR(80) NOT NULL,
  operation_id VARCHAR(120) NOT NULL,
  command_type VARCHAR(40) NOT NULL,
  room_id BIGINT NULL,
  partition_id INT NULL,
  target_node_id VARCHAR(120) NULL,
  publisher_node_id VARCHAR(120) NOT NULL,
  reason VARCHAR(80) NOT NULL,
  route_version BIGINT NULL,
  limit_value INT NOT NULL,
  retry_after_ms BIGINT NOT NULL,
  publish_status VARCHAR(30) NOT NULL,
  redis_receivers BIGINT NULL,
  created_at BIGINT NOT NULL,
  published_at BIGINT NULL,
  failed_at BIGINT NULL,
  last_error TEXT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_reconnect_command_id (command_id),
  KEY idx_reconnect_command_operation (operation_id, created_at),
  KEY idx_reconnect_command_target_node (target_node_id, created_at)
);

CREATE TABLE reconnect_command_handling_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  command_id VARCHAR(80) NOT NULL,
  handler_node_id VARCHAR(120) NOT NULL,
  status VARCHAR(30) NOT NULL,
  open_sessions_before INT NOT NULL,
  targeted_sessions INT NOT NULL,
  sent_sessions INT NOT NULL,
  failed_sessions INT NOT NULL,
  remaining_open_sessions INT NOT NULL,
  handled_at BIGINT NOT NULL,
  last_error TEXT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_reconnect_command_handler (command_id, handler_node_id),
  KEY idx_reconnect_handling_command (command_id),
  KEY idx_reconnect_handling_node (handler_node_id, handled_at)
);
```

Implementation update after review:

- `ddl-auto=validate` 환경에서 새 JPA entity를 unconditional로 추가하면 `command-log.enabled=false`여도 테이블 부재 때문에 startup이 실패할 수 있다.
- 그래서 v1 구현은 Hibernate managed entity 대신 `JdbcTemplate` native repository를 사용한다.
- loadtest의 schema initializer는 `ddl-auto=update`로 이 테이블을 자동 생성하지 않으므로, command log를 켜는 환경은 위 manual DDL 또는 동등한 schema 준비가 선행되어야 한다.
- 대신 기본 disabled 환경과 기존 validate 환경은 command log 테이블이 없어도 startup 계약이 유지된다.
- publish/handling row는 MySQL `INSERT ... ON DUPLICATE KEY UPDATE` 방식으로 idempotent하게 기록한다.
- GCP loadtest harness에서는 schema initializer VM이 `command-log.enabled=true`일 때 위 DDL을 명시적으로 적용한 뒤 app container를 시작한다. 생성 후 required column probe를 수행해 partial/incompatible schema면 startup 단계에서 실패시킨다.

### Runtime Contract

새 설정은 기본 비활성이다.

- `app.room-partition.control.command-log.enabled=false`
- `app.room-partition.control.command-trace-enabled=false`

command log를 켜려면 trace도 함께 켜야 한다. subscriber handling row는 Redis payload의 `commandId`와 join되므로, `command-log.enabled=true`인데 `command-trace-enabled=false`이면 startup fail-fast로 막는다.

DB write 실패는 reconnect 흐름을 깨지 않는다. publisher/subscriber는 command log service에 best-effort로 기록하고, 실패하면 log/metric만 남긴 뒤 기존 control-plane 동작을 계속한다.

`PUBLISH_SUCCEEDED`는 Redis publish 호출 성공을 의미한다. client reconnect 완료 또는 drain 완료를 의미하지 않는다.

### Script / Artifact Contract

`openchat-node-drain-orchestrate.sh` 결과에는 nested object를 추가한다.

```json
{
  "durableReconnectCommandLog": {
    "enabled": true,
    "mode": "audit_only",
    "contractVersion": "openchat.reconnect-command-log.v1",
    "collectionStatus": "collected",
    "collectionError": null,
    "expectedCommandIds": [],
    "recordedCommandIds": [],
    "missingCommandIds": [],
    "duplicateCommandIds": [],
    "recordCount": 0,
    "lastRecordedCommandId": null,
    "records": []
  }
}
```

`openchat-node-termination-decision.sh` 결과에는 evidence만 추가한다.

```json
{
  "sourceDurableReconnectCommandLog": {},
  "auditEvidence": {
    "durableLogComplete": true,
    "durableLogMissingCommandIds": [],
    "durableLogRecordCount": 0
  }
}
```

중요한 점은 `terminationAllowed` 계산에 이 값을 넣지 않는 것이다.
`durableLogComplete`도 `collectionStatus=collected`일 때만 true가 될 수 있게 해 collection 실패와 command row 누락을 구분한다.

### GCP Acceptance

Durable Log v1 smoke는 다음을 만족해야 한다.

- k6 exit `0`
- HTTP error `0%`
- WebSocket connect `100%`
- route failure/fallback/mismatch `0/0/0`
- sent == ack == DB rows
- reconnect command ids 존재
- DB command log rows 존재
- `recordedCommandIds` contains all `attemptedReconnectCommandIds`
- `missingCommandIds=0`
- `duplicateCommandIds=0`
- termination decision preserves `sourceDurableReconnectCommandLog`
- GCP stop adapter `RUNNING -> TERMINATED`
- post-stop probe PASS
- cleanup 후 RUN_ID VM `0`

권장 run id는 `20260509-durable-reconnect-command-log-smoke`다. Smoke가 PASS한 뒤에만 mini-soak을 고려한다.

### 에이전트 작업 분할

- Documentation agent: 이 README에만 append하고 기존 기록은 삭제하지 않는다.
- Backend implementation agent: command log entity/service/repository/config와 unit/repository test를 담당한다.
- Ops script agent: orchestrator/termination decision JSON summary, GCP DB dump, shell fixture test를 담당한다.
- Review agent: rolling compatibility, idempotency, fail-open behavior, DB write cost, metric cardinality, schema/validate risk를 검토한다.
- GCP runner agent: `openchat-gcp-test-runner` skill로 smoke를 실행하고 cleanup 후 RUN_ID VM `0`을 확인한다.

### 남은 한계

Durable Log v1도 delivery guarantee는 아니다. DB row가 있다고 해서 client reconnect 완료를 뜻하지 않는다. 다만 운영자가 aggregate metric 대신 command 단위 evidence를 DB와 artifact로 연결할 수 있게 된다.

후속으로 검토할 수 있는 것은 durable reconnect outbox, Redis Streams, command ack store, strict termination gate다. 이들은 실제 장애 패턴이 durable log만으로 부족하다고 확인될 때 진행하는 것이 맞다.

## Follow-up Result: Reconnect Delivery Evidence Hardening

Durable Log v1 이후 바로 이어진 hardening의 목적은 `commandId` traceability를 termination decision이 소비할 수 있는 evidence contract로 만드는 것이었다. publish row는 "Redis publish를 시도했고 성공했는가"를 설명하지만, strict termination 판단에는 target node handler가 command를 관측했는지까지 확인할 수 있어야 한다.

### Situation

Node drain, external orchestrator, termination decision, GCP VM stop까지 연결되면서 종료 자동화의 마지막 질문은 "종료 직전에 필요한 reconnect command evidence가 충분한가"가 되었다. 기존 durable log는 commandId와 publish status를 DB에 남겼지만, strict mode에서 handler-level evidence가 빠졌는지, 실패 handler가 있었는지, 어떤 command가 termination 판단에 포함됐는지를 한 번에 설명하기 어려웠다.

### Task

기본 운영 경로를 깨지 않으면서 GCP smoke와 future ops script가 다음을 확인할 수 있어야 했다.

- expected commandId가 DB audit row에 모두 기록됐는가
- strict 대상 command의 handler row가 모두 수집됐는가
- 누락/실패 handler가 있으면 termination decision이 `not_ready`로 판단하는가
- delivery evidence 수집이 실패해도 기본 모드에서는 기존 종료 안전 판단을 흔들지 않는가
- command log table이 장기 운영에서 무한히 커지지 않는가

### Action

세 가지를 추가했다.

1. Drain orchestrator artifact에 durable reconnect command log delivery summary를 추가했다. `collectionStatus`, `complete`, `strictEligibleCommandIds`, `missingCommandIds`, `failedHandlerCommandIds`, `handlerRecordCount`로 publish/handling evidence를 commandId 기준으로 설명한다.
2. Termination decision script에 `--strict-delivery-evidence`를 추가했다. strict mode에서 delivery evidence가 불완전하면 `terminationAllowed=false`, `result=not_ready`로 판단한다. 이를 `unsafe`가 아니라 `not_ready`로 둔 이유는 세션이 남아 위험한 상태와, 증거 수집이 아직 충분하지 않은 상태를 구분하기 위해서다.
3. Reconnect command log retention cleanup을 추가했다. 기본은 disabled이며, 켜면 handling row를 command row보다 먼저 삭제한다. 기본 보관 기간은 30일이고 cleanup query를 위해 `(created_at, id)` 인덱스를 추가했다.

### Trade-offs

Strict delivery evidence는 기본값으로 켜지 않았다. DB audit write나 result collection flake가 실제 종료 자동화를 과도하게 막는 false negative가 될 수 있기 때문이다. 대신 smoke와 ops script에서 명시적으로 strict mode를 켜 검증한다.

이 작업도 reconnect delivery guarantee는 아니다. Handler row는 subscriber가 command를 처리했다는 evidence이지 client가 reconnect를 완료했다는 ack가 아니다. 종료 안전 판단은 여전히 session count, orchestrator completion, termination decision guard와 함께 봐야 한다.

Retention cleanup도 scheduler까지 만들지 않았다. v1에서는 cleanup policy와 query path를 검증하고, 자동 주기 실행은 운영 hardening 단계로 남겼다.

### Result

첫 GCP run `20260509-reconnect-delivery-hardening-smoke`는 correctness evidence는 모두 통과했지만 k6 `chat_ack_roundtrip_ms` p95가 `3279ms`로 threshold를 넘어서 exit `99`가 됐다. sent/ack/DB rows, route mismatch, delivery evidence, termination decision, cleanup은 정상이었기 때문에 기능 실패가 아니라 tail latency flake로 분석했다.

같은 HEAD로 재실행한 `20260509-reconnect-delivery-hardening-smoke2`는 PASS했다.

- k6 exit code single/post-stop `0/0`
- HTTP error `0%`
- WebSocket connect single/post-stop `149/149`, `20/20`
- route failure/fallback/mismatch `0/0/0`
- sent/ack/DB rows single `22,294 / 22,294 / 22,294`
- post-stop sent/ack/DB rows `624 / 624 / 624`
- durable reconnect command log rows `1`
- delivery evidence `collectionStatus=collected`, `complete=true`
- missing/failed handlers `0/0`
- strict termination `terminationAllowed=true`
- GCP stop adapter `RUNNING -> TERMINATED`
- cleanup 후 RUN_ID VM 잔여 `0`
- ACK p95 single/post-stop `32ms`, `19ms`

포트폴리오 표현은 "Redis Pub/Sub delivery를 보장했다"가 아니라 "node termination 전에 필요한 reconnect command evidence를 commandId 단위로 수집하고, strict mode에서 termination decision이 그 evidence를 소비하도록 만들었다"가 정확하다.

## Portfolio STAR Summary

### Situation

Dynamic realtime ownership과 node drain, GCP VM stop까지 구현했지만, 운영자가 node drain 실패나 지연을 분석할 때 "어떤 reconnect command가 어떤 종료 판단에 연결됐는지" 추적하기 어려웠다.

### Task

기존 Redis Pub/Sub control-plane을 크게 바꾸지 않고, reconnect command 단위의 추적성을 추가해 운영 가능한 drain/termination evidence를 남겨야 했다.

### Action

`commandId`를 reconnect command, WebSocket control payload, node drain response, drain orchestrator, termination decision, GCP artifact까지 전파했다. 리뷰 중 발견된 rolling deploy 문제는 trace flag 기본값 false와 Redis payload gating으로 해결했고, GCP env가 Spring property로 매핑되지 않던 문제도 수정했다. 이후 로컬 테스트, 코드리뷰, GCP smoke, 3 realtime node mini-soak으로 검증했다.

### Result

GCP mini-soak에서 main workload `110379/110379/110379`, post-stop probe `3026/3026/3026`으로 sent/ack/DB row가 일치했고, route failure/fallback/mismatch는 모든 phase에서 `0/0/0`이었다. reconnect controls `100`건을 수신했고, orchestrator의 reconnect command ids가 termination decision source ids로 보존됐다. target realtime VM은 `RUNNING -> TERMINATED`가 확인됐고 cleanup 후 RUN_ID VM은 `0`개였다.

## 면접 답변용 문장

> Node drain과 VM stop까지는 성공했지만, 운영자가 drain 실패를 분석할 때 command 단위의 연결고리가 부족했습니다. 그래서 Redis Pub/Sub 구조를 바로 갈아엎지 않고 reconnect command에 `commandId`를 추가해 publisher, subscriber, WebSocket control payload, drain orchestrator, termination decision, GCP artifact까지 추적 가능하게 만들었습니다. 리뷰 과정에서 old subscriber rolling deploy 위험과 GCP env 매핑 누락을 발견해 기본값 false feature gate와 property mapping으로 수정했고, 최종적으로 3 realtime node mini-soak에서 main `110,379/110,379/110,379`, post-stop `3,026/3,026/3,026`, route mismatch `0`, cleanup VM `0`을 확인했습니다.

## 남은 한계와 다음 단계

남은 한계:

- Redis Pub/Sub delivery guarantee는 아직 없다.
- `commandId`는 trace id이지 ack store가 아니다.
- `reconnectCommandIds`는 de-duplicated set 성격이며 순서 분석은 `history`나 `lastReconnectCommandId`를 봐야 한다.
- trace-enabled payload는 old subscriber와 혼재시키면 안 된다.

다음 단계 후보:

- durable reconnect command log를 DB에 저장할지 판단한다.
- Redis Streams 전환이 필요한 실패 패턴이 있는지 GCP soak/load 결과를 더 모은다.
- command id를 기반으로 operator runbook을 작성한다.
- `closed_during_send` WARN 1건이 반복되는지 장시간 soak에서 확인한다.

## 관련 문서

- `docs/overnight-reconnect-command-traceability-spike.md`
- `docs/results/gcp/GCP-smoke-결과-20260509-reconnect-traceability-smoke.md`
- `docs/results/gcp/GCP-soak-결과-20260509-reconnect-traceability-mini-soak3.md`
