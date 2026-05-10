# Overnight Spike: Reconnect Command Traceability

> 작성일: 2026-05-09
> 브랜치: `spike-reconnect-command-traceability-overnight`
> 기준 HEAD: `feat-gcp-vm-termination-adapter` `0884179`
> 운영 방식: 아침 검토 전까지 커밋하지 않는 실험 브랜치. 마음에 들지 않으면 브랜치/worktree 변경을 폐기하고, 마음에 들면 커밋 후보로 정리한다.

## Goal

Drain orchestrator, termination decision, GCP VM stop까지 이어지는 경로는 검증됐다. 다음 약점은 reconnect control command가 "발행됐다"는 metric은 있지만, 운영자가 command 단위로 lifecycle을 추적하기 어렵다는 점이다.

이번 spike의 목표는 다음 질문에 답할 수 있는 최소 구조를 찾는 것이다.

- 어떤 drain/rebalance 작업이 어떤 reconnect command를 발행했는가?
- command가 몇 개 session을 target했고, 몇 개 session에 실제 control frame을 보냈는가?
- publish 실패, subscriber 처리 실패, send 실패를 command 단위로 구분할 수 있는가?
- node drain complete/termination decision이 참조할 수 있을 만큼 command 결과가 관찰 가능한가?

## Non-goals

- durable command queue를 완성하지 않는다.
- Redis Pub/Sub를 Streams로 바꾸지 않는다.
- VM/EKS/MIG 종료 로직을 추가하지 않는다.
- production 기본 동작을 바꾸지 않는다.
- 아침 검토 전에는 커밋하지 않는다.

## Candidate Direction

v1 spike는 DB durable log보다 먼저, 낮은 위험의 "trace id + local observation"을 우선 검토한다.

- reconnect command에 `commandId`를 추가한다.
- publisher result, subscriber handler result, node drain response에 `commandId`를 노출한다.
- snapshot/summary 또는 internal API에서 최근 command 관측치를 볼 수 있게 한다.
- 기존 DTO와 k6는 backward-compatible하게 처리한다.

DB durable log는 다음 조건이 명확해졌을 때 별도 작업으로 승격한다.

- Pub/Sub 유실 가능성을 실제 운영 리스크로 다뤄야 한다.
- command retry/ack 상태를 node 종료 판단의 필수 조건으로 써야 한다.
- command 이력을 장애 분석 evidence로 장기간 보존해야 한다.

## Work Log

- 시작: `2026-05-09`
- 현재 상태: 실험 브랜치 생성 완료.
- RED: `commandId`가 없는 상태에서 publisher/subscriber/drain result 테스트가 컴파일 실패하는 것을 확인했다.
- GREEN: `RoomPartitionControlCommand.commandId`, `RoomReconnectControlPayload.commandId`, `NodeDrainResult.commandId`, `NodeDrainResponse.commandId`를 추가했고 targeted test가 통과했다.
  - 실행: `./gradlew test --tests '*RedisRoomPartitionControlPublisherTest' --tests '*RoomPartitionControlSubscriberTest' --tests '*RealtimeNodeDrainServiceTest' --tests '*RoomPartitionAssignmentInternalControllerTest'`
  - 결과: PASS
- 확장: external drain orchestrator result에 `lastCommandId`, `reconnectCommandIds`를 추가했고, termination decision에도 `sourceReconnectCommandIds`를 보조 evidence로 전달했다.
  - 실행: `bash scripts/test-openchat-node-drain-orchestrate.sh`
  - 실행: `bash scripts/test-openchat-node-termination-decision.sh`
  - 결과: PASS
- GCP harness: `k6-startup.sh.tftpl`의 node drain output summary에 `lastCommandId`, `reconnectCommandIds`를 top-level로 노출했다.
- Local full gate:
  - `./gradlew test`: PASS
  - `bash scripts/test-openchat-node-drain-orchestrate.sh`: PASS
  - `bash scripts/test-openchat-node-termination-decision.sh`: PASS
  - `bash -n infra/gcp-loadtest/templates/k6-startup.sh.tftpl`: PASS
  - `terraform -chdir=infra/gcp-loadtest fmt -check`: PASS
  - `git diff --check`: PASS
- Checkpoint commit: `eaeebd5 feat: trace reconnect control commands`
- Review 1:
  - High: default Redis payload에 `commandId`가 포함되면 mixed rolling deploy에서 old subscriber가 unknown field를 malformed로 처리할 수 있음.
  - Medium: `reconnectCommandIds`가 publish failed attempt까지 포함할 수 있어 이름이 과장됨.
  - Low: `lastCommandId`가 final `complete` response 기준이면 null이라 ambiguous.
- Fix checkpoint: `32c85cb fix: gate reconnect command trace payloads`
  - production default `app.room-partition.control.command-trace-enabled=false` 추가.
  - default Redis payload에서는 `commandId` 제거해 legacy shape 유지.
  - GCP node drain smoke/mini-soak profile에서만 `room_partition_control_command_trace_enabled=true`.
  - `reconnectCommandIds`는 `reconnectPublished=true`인 command만 집계.
  - `attemptedReconnectCommandIds`, `lastReconnectCommandId` 추가.
  - local full/script/diff/terraform fmt gates 다시 PASS.
- GCP smoke:
  - run id: `20260509-reconnect-traceability-smoke`
  - status: PASS
  - result doc: `docs/results/gcp/GCP-smoke-결과-20260509-reconnect-traceability-smoke.md`
  - purpose: command trace가 orchestrator artifact, termination decision, GCP VM stop smoke까지 유지되는지 확인.
  - result: main/post-stop k6 exit `0`, route failure/fallback/mismatch `0/0/0`, sent/ack/DB rows `22290/22290/22290` and `624/624/624`, reconnect command ids `3`, GCP stop adapter `stopped`, RUN_ID VM cleanup `0`.
- Review 2:
  - Medium: GCP profile/Terraform은 `ROOM_PARTITION_CONTROL_COMMAND_TRACE_ENABLED=true`를 전달하지만, Spring `application.properties`에 `app.room-partition.control.command-trace-enabled` 매핑이 없어 실제 publisher feature gate가 계속 false일 수 있음.
  - 판단: trace-enabled GCP 검증을 최종 evidence로 쓰려면 blocker다. 기존 smoke는 route/drain/stop 안정성 evidence로는 유효하지만, trace-enabled Redis payload 검증 evidence로는 부족하다.
- Fix checkpoint: `5f10488 fix: map reconnect trace flag for gcp validation`
  - `application.properties`에 `ROOM_PARTITION_CONTROL_COMMAND_TRACE_ENABLED` -> `app.room-partition.control.command-trace-enabled` 매핑 추가.
  - local full gate: `./gradlew test` PASS.
  - shell syntax: `bash -n scripts/openchat-node-drain-orchestrate.sh`, `bash -n infra/gcp-loadtest/templates/app-startup.sh.tftpl`, `bash -n infra/gcp-loadtest/templates/k6-startup.sh.tftpl` PASS.
  - diff hygiene: `git diff --check` PASS.
- GCP mini-soak attempt 1:
  - run id: `20260509-reconnect-traceability-mini-soak`
  - status: ABORTED
  - reason: first review에서 rolling deploy compatibility blocker가 발견되어 apply 전 중단.
  - cleanup: RUN_ID GCE VM `0`, stable bucket/static IP 보존.
- GCP mini-soak attempt 2:
  - run id: `20260509-reconnect-traceability-mini-soak2`
  - status: ABORTED
  - reason: second review에서 Spring property mapping blocker가 발견되어 apply 전 중단.
  - cleanup: RUN_ID GCE VM `0`, stable bucket/static IP 보존.
- GCP mini-soak attempt 3:
  - run id: `20260509-reconnect-traceability-mini-soak3`
  - status: PASS by subagent on `5f10488`
  - purpose: property mapping fix 이후 trace-enabled payload, orchestrator command ids, termination decision source ids, GCP VM stop, post-stop probe를 3 realtime node / 200 VU / 300s workload로 재검증.
  - result: main/post-stop k6 exit `0/0`, HTTP error `0.00%/0.00%`, WS connect `100%/100%`, route failure/fallback/mismatch `0/0/0` in both phases, sent/ack/DB rows `110379/110379/110379` and `3026/3026/3026`, reconnect controls received `100`, orchestrator reconnect command ids `2`, termination decision source command ids preserved, GCP stop adapter `stopped`, target realtime VM `TERMINATED`, post-stop probe PASS, cleanup RUN_ID VM `0`.
- Review 3:
  - status: PASS / no blocker
  - reviewer conclusion: GCP profile trace flag is now wired from tfvars -> Terraform template var -> `ROOM_PARTITION_CONTROL_COMMAND_TRACE_ENABLED` -> `application.properties` -> `RedisRoomPartitionControlPublisher`.
  - reviewer conclusion: default `false` keeps Redis payload compatible with current `dev` shape by removing `commandId`.
  - reviewer conclusion: `commandId`, `reconnectCommandIds`, `attemptedReconnectCommandIds`, and `lastReconnectCommandId` semantics are consistent across API/script/termination decision.
  - caveat: `trace=true` should not be mixed with old subscribers that do not tolerate unknown `commandId`. This is acceptable because production default is false and current GCP validation profiles opt in explicitly.
  - caveat: `reconnectCommandIds` and `attemptedReconnectCommandIds` are de-duplicated sets rather than chronological lists. Order-sensitive analysis should use history entries or `lastReconnectCommandId`.

## Merge Decision Packet Draft

### Recommendation

현재 추천은 `ADOPT`다. `commandId`가 publisher -> subscriber -> control payload -> node drain response -> orchestrator artifact -> termination decision evidence까지 이어지고, GCP smoke와 mini-soak에서 같은 흐름이 실제 artifact로 확인됐다. 첫 코드리뷰의 rolling deploy blocker는 기본 Redis payload에서 `commandId`를 제거하는 feature-gate 방식으로 수정했고, 두 번째 코드리뷰의 GCP property mapping blocker는 `5f10488`로 수정했다.

단, 이 작업은 reconnect command delivery guarantee가 아니라 traceability다. Redis Pub/Sub 유실 자체를 해결한 것은 아니고, durable command log/ack store는 후속 hardening 후보로 남는다.

Update: 두 번째 코드리뷰에서 GCP trace flag가 Spring property로 매핑되지 않은 문제가 발견되어 `5f10488`로 수정했다. 최종 `ADOPT` 판단은 `20260509-reconnect-traceability-mini-soak3` 결과를 기준으로 한다. 이전 smoke PASS는 drain/termination/post-stop 안정성 근거로 유지하지만, trace-enabled payload의 최종 evidence로 단독 사용하지 않는다.

아침 최종 추천은 다음 중 하나로 고정한다.

- `ADOPT`: 로컬 전체 테스트, script fixture, GCP smoke/repeated validation이 모두 통과하고 command trace가 artifact에 남는다.
- `PARTIAL`: 코드 변경은 안전하지만 GCP 반복 검증이나 문서 설득력이 부족하다.
- `DISCARD`: 기존 reconnect/drain 동작, route 정합성, DB row 일치, cleanup 중 하나라도 흔들린다.

### Why This Matters

OpenChat은 이미 node drain, termination decision, GCP VM stop까지 검증했다. 하지만 운영 관점에서는 "node를 비웠다"는 최종 상태만으로는 부족하다. drain이 느리거나 실패했을 때 운영자는 다음 질문에 답할 수 있어야 한다.

- 어떤 reconnect command가 발행됐는가?
- 그 command가 어떤 node/partition을 대상으로 했는가?
- subscriber가 같은 command를 처리했는가?
- session control frame으로 같은 command가 내려갔는가?
- drain complete가 어떤 reconnect 시도 이후 관측됐는가?

`commandId`는 delivery guarantee가 아니라 traceability contract다. 즉, Redis Pub/Sub의 유실 가능성을 완전히 해결하지는 않지만, 기존 control-plane을 크게 바꾸지 않고 운영 분석 가능성을 올린다.

### Options Considered

| Option | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| A. 아무것도 하지 않음 | 변경 위험 없음 | drain 실패 원인 분석이 aggregate metric/log에 의존 | 운영 만족도 부족 |
| B. `commandId` traceability | 작은 변경, backward-compatible, GCP artifact 검증 가능 | durable guarantee는 아님 | 이번 spike의 기본 선택 |
| C. DB durable reconnect command log | 장기 감사/재시도 근거 강함 | schema, write path, retry 상태머신 필요 | 후속 hardening 후보 |
| D. Redis Streams 전환 | delivery/consumer tracking 확장 가능 | control-plane 구조 변경 폭 큼 | 지금 범위에서는 과함 |

### Merge Safety Checks

- 기존 field 삭제 없음.
- `commandId`는 optional field라 legacy JSON과 기존 client를 깨지 않아야 한다.
- Prometheus tag에는 `commandId`를 넣지 않는다.
- production default와 lifecycle 정책은 바꾸지 않는다.
- termination safety guard는 여전히 `remainingSessions=0`, `status=complete`, `nextAction=none`, decision guards 기반이다. `commandId`는 보조 evidence다.
- GCP 예산 guard: run-scoped smoke/load만 실행하고, stable bucket/static IP 삭제 금지, RUN_ID VM cleanup 필수. 300,000 KRW credit 한도 내에서만 진행한다.

### Required Evidence Before ADOPT

- Local:
  - `./gradlew test` PASS
  - script fixture tests PASS
  - `git diff --check` PASS
- GCP:
  - k6 exit code `0`
  - route failure/fallback/mismatch `0/0/0`
  - route node와 connected node mismatch `0`
  - sent == ack == DB rows
  - node drain reconnect controls `> 0`
  - drain result/orchestrator artifact/log에서 같은 `commandId` 확인
  - drained node openSessions `0`
  - termination decision `ready`
  - GCP VM stop adapter `stopped`
  - post-stop probe PASS
  - cleanup 후 RUN_ID VM `0`

### Current Evidence

| Area | Result | Evidence |
| --- | --- | --- |
| App unit/full tests | PASS | `./gradlew test` |
| Script fixture tests | PASS | drain orchestrator, termination decision |
| Shell syntax | PASS | `bash -n` for changed scripts/template |
| Terraform formatting | PASS | `terraform -chdir=infra/gcp-loadtest fmt -check` |
| Diff hygiene | PASS | `git diff --check` |
| GCP smoke | PASS | `20260509-reconnect-traceability-smoke` |

Update:

| Area | Result | Evidence |
| --- | --- | --- |
| Property mapping blocker | FIXED | `5f10488` |
| Local full tests after mapping | PASS | `./gradlew test` |
| Mini-soak attempt 1 | ABORTED | stopped before apply due review blocker |
| Mini-soak attempt 2 | ABORTED | stopped before apply due property mapping blocker |
| Mini-soak attempt 3 | PASS | `20260509-reconnect-traceability-mini-soak3`: main `110379/110379/110379`, post-stop `3026/3026/3026`, cleanup VM `0` |
| Code review after mapping | PASS | no blocker, caveat documented |

### Final Evidence Update

`20260509-reconnect-traceability-mini-soak3` closes the spike with operating-level evidence:

- 3 realtime nodes were registered and assignment preflight passed with `activeNodeCount=3`, `assignmentCount=4`, `readyAssignmentCount=4`, `distinctOwnerCount=3`.
- Node drain targeted `gcp-realtime-2`; GCP stop adapter changed the VM from `RUNNING` to `TERMINATED`.
- Main workload produced sent/ack/DB rows `110379/110379/110379`.
- Post-stop probe produced sent/ack/DB rows `3026/3026/3026`.
- Main and post-stop route failure/fallback/mismatch were `0/0/0`.
- `ws_reconnect_controls_received_total=100`.
- Orchestrator preserved published reconnect ids:
  - `reconnect-d582deab-d425-44c1-a4a8-ba68faf53dba`
  - `reconnect-9c4da9f9-75d9-4280-97f7-73f0851868c7`
- Termination decision preserved `sourceReconnectCommandIds`, `sourceAttemptedReconnectCommandIds`, and `sourceLastReconnectCommandId`.
- Cleanup left RUN_ID GCE VM `0`; stable bucket and static IP were preserved.

Observed noise:

- `gcp-realtime-1` emitted one `closed_during_send` WARN near workload shutdown.
- It did not affect acceptance: k6 check failures were `0`, route mismatch was `0`, and sent/ack/DB rows were exactly equal.

### What Changed

- `RoomPartitionControlCommand`
  - New optional `commandId`.
  - New commands generated through factory methods receive `reconnect-<uuid>`.
  - Legacy JSON without `commandId` remains readable.
- `RoomReconnectControlPayload`
  - New optional `commandId`.
  - Existing factory signature remains, new overload accepts command id.
- `RoomPartitionControlHandler`
  - Passes command id into session control payload.
- `RealtimeNodeDrainService`
  - `reconnect_published` and `publish_failed` responses include the command id used for publish.
  - Non-publish observation states keep `commandId=null`.
- Internal node drain API
  - `NodeDrainResponse.commandId` added as a backward-compatible optional field.
- Ops scripts
  - Drain orchestrator records `lastCommandId`, `history[].commandId`, `reconnectCommandIds`.
  - Termination decision records `sourceReconnectCommandIds`.
- GCP harness
  - Node drain output summary exposes `lastCommandId` and `reconnectCommandIds`.

### Backward Compatibility

- Existing JSON consumers should ignore unknown `commandId`.
- Legacy Redis command JSON without `commandId` deserializes with `commandId=null`.
- Production default에서는 Redis command JSON에 `commandId`를 포함하지 않는다. mixed rolling deploy에서 old subscriber가 unknown field를 만나 reconnect command를 drop하는 위험을 줄이기 위한 조치다.
- GCP 검증 profile에서만 `room_partition_control_command_trace_enabled=true`로 end-to-end trace payload를 켠다.
- Existing `/drain/status` consumers keep all old fields.
- k6 reconnect client does not need to understand `commandId`.
- Metrics cardinality is unchanged because `commandId` is not used as a Prometheus tag.

### Operational Value

Before this change, reconnect observability was mostly aggregate:

```text
reconnect requested count
reconnect control sent count
node drain final status
```

After this change, an operator can connect artifacts:

```text
POST drain response commandId
-> Redis subscriber log commandId (trace-enabled profile)
-> session room.reconnect payload commandId (trace-enabled profile)
-> orchestrator history reconnectCommandIds
-> termination decision sourceReconnectCommandIds
-> GCP result artifact reconnectCommandIds
```

This does not prove guaranteed delivery. It proves command-level traceability without replacing Redis Pub/Sub or adding durable storage.

### Objections and Answers

#### Objection: `commandId` does not make reconnect durable.

Correct. This is intentionally traceability, not delivery guarantee. Durable reconnect command logging or Redis Streams would be a larger control-plane change and should be justified by observed failure patterns. This spike keeps blast radius low while making failures easier to diagnose.

#### Objection: The API response changed.

The response only gained an optional field. No existing field was removed or renamed. Old clients that ignore unknown fields keep working.

#### Objection: UUID per reconnect command could hurt metrics.

The id is not added to metrics tags. It appears only in JSON artifacts/logs/control payloads where high-cardinality trace identifiers are acceptable.

#### Objection: Why not wait until EKS/MIG?

The trace contract is provider-neutral. Whether the final adapter is GCP VM stop, MIG scale-in, or EKS eviction, the app-level question is the same: which reconnect command helped drain the node before termination was allowed?

### Operating-Level Acceptance

아침에 이 브랜치를 dev merge 후보로 보려면 단순히 테스트가 통과하는 것보다 다음 운영 질문에 답할 수 있어야 한다.

- Drain이 완료됐을 때 어떤 reconnect command가 관련됐는지 찾을 수 있는가?
- Publish 실패와 subscriber/session 처리 실패를 구분할 수 있는가?
- 재시도해야 하는지, 기다려야 하는지, 인프라 조사가 필요한지 판단할 수 있는가?
- 실패해도 기존 검증된 VM stop adapter 브랜치를 오염시키지 않고 폐기 가능한가?

Current answer:

- Drain 완료와 관련된 reconnect command는 `reconnectCommandIds`로 찾을 수 있다.
- Publish 실패는 `publish_failed` status와 동일 command id로 응답된다.
- Subscriber/session 처리 흔적은 command id가 포함된 control payload/log/artifact로 연결 가능하다.
- Termination decision은 command id를 safety guard로 쓰지는 않지만, `sourceReconnectCommandIds` evidence로 보존한다.
- GCP smoke는 VM stop 이후 post-stop probe까지 정상이고 cleanup도 완료됐다.

## Backend Scalability Completion Checklist

이 작업이 `ADOPT`되면 백엔드 확장성/운영성 마감 기준은 다음 상태가 된다.

| Capability | Status | Evidence |
| --- | --- | --- |
| Measurement reliability | Done | role-aware k6, GCP infra |
| Fan-out work reduction | Done | active/passive fan-out |
| Hot room partition | Done | partition route/state |
| Auto partition lifecycle | Done | scale-up/rebalance/drain smoke |
| Dynamic realtime ownership | Done | route node == connected node |
| Node drain status contract | Done | retryable/nextAction/readinessReason |
| External drain orchestrator | Done | `terminationAllowed=true` smoke |
| Provider-neutral termination decision | Done | `ready/terminate_node` contract |
| GCP VM stop adapter | Done | VM `RUNNING -> TERMINATED`, post-stop probe |
| Reconnect command traceability | Done | `commandId` chain, GCP smoke PASS |
| Repeated/failure validation | In progress | fixture tests done; mini-soak2 running |

`ADOPT` 기준으로도 남는 한계:

- Redis Pub/Sub 자체를 durable하게 만든 것은 아니다.
- 장기 보관 가능한 DB audit log는 없다.
- EKS/MIG actual adapter는 없다.
- 운영 dashboard/alert는 별도 작업이다.

## Morning Review Checklist

- 변경이 기존 GCP smoke/load 성공 기준을 흔들지 않는가?
- 기존 reconnect payload 소비자와 backward compatibility가 유지되는가?
- "traceability"라고 부를 만큼 commandId가 publisher, subscriber, status/result까지 이어지는가?
- durable log 없이도 포트폴리오/운영 설명 가치가 있는가?
- 마음에 들면 커밋 후보를 1개 또는 2개로 나눌 수 있는가?
