# OpenChat Current Work

이 문서는 현재 진행할 작업의 실행 계획과 결과를 한 곳에서 보기 위한 작업판이다.

장기 흐름은 [OpenChat Realtime Ops Roadmap](OPENCHAT-REALTIME-OPS-ROADMAP.md)을 기준으로 본다.

운영 규칙:

- 새 작업을 시작할 때 이 파일의 현재 작업 내용을 갱신한다.
- 작업이 끝나면 `Result Section`을 채운다.
- 포트폴리오/의사결정으로 남길 내용은 decision log, experience bank, STAR 문서에 별도로 append한다.
- 사용자의 명시적 요청 없이 기존 기록을 삭제하지 않는다. 오래된 내용은 `Previous Work`, `Update`, `Result`로 내려서 보존한다.

## Current Work

작업명: `Reconnect Command Delivery & Drain Evidence Hardening`

기준일: `2026-05-09`

## Current Work Update: Phase 5 Rolling Restart / Soak Validation

작업명: `Rolling Restart / Soak Validation`

기준일: `2026-05-09`

### Context

Phase 4에서 reconnect command publish/handling evidence와 strict termination guard까지 연결했다. 다음 한계는 이 계약이 단일 node drain/stop smoke에서만 성공한 것인지, workload가 흐르는 동안 rolling restart와 mini-soak에서도 유지되는지 아직 증명하지 않았다는 점이다.

### Goal

> 단일 node drain/stop을 넘어, rolling restart 흐름에서도 route/connected node/subscriber assignment/reconnect command evidence/termination decision/GCP stop이 일관되게 유지되는지 검증한다.

### Scope

- rolling restart shell orchestrator 추가
- rolling restart fixture test 추가
- GCP k6 startup template에 rolling restart 옵션 연결
- rolling restart smoke/mini-soak profile 추가
- Phase 5 계획 문서 작성
- GCP smoke는 subagent에 위임

### Safety Decisions

- v1 기본은 replacement 없는 stop이므로 `realtime_count=3`, `target_count=1`, `min_active_nodes=2`로 제한한다.
- `target_count >= 2`는 active realtime node가 4개 이상일 때만 runtime script가 허용한다.
- strict delivery evidence는 termination decision 시점의 hard gate로만 사용한다.
- post-stop probe를 켜 route가 stopped node로 가지 않는지 검증한다.
- retention cleanup은 Phase 5 acceptance에서 제외하고, rolling/soak 중 command evidence가 수집되는지만 본다.

### Local Verification

- `bash -n scripts/openchat-rolling-restart-orchestrate.sh scripts/test-openchat-rolling-restart-orchestrate.sh infra/gcp-loadtest/templates/k6-startup.sh.tftpl` PASS
- `bash scripts/test-openchat-rolling-restart-orchestrate.sh` PASS
- `bash scripts/test-openchat-node-drain-orchestrate.sh` PASS
- `bash scripts/test-openchat-node-termination-decision.sh` PASS
- `bash scripts/test-openchat-gcp-node-terminate.sh` PASS
- `node --check k6/scenarios/11-mixed-room-workload-ramped.js` PASS
- `./gradlew test` PASS
- `terraform -chdir=infra/gcp-loadtest fmt -check` PASS
- `terraform -chdir=infra/gcp-loadtest validate` PASS outside sandbox
- `git diff --check` PASS

### Review

- 계획 리뷰어: replacement 없는 multi-stop 범위 제한, assignment readiness guard, strict evidence false-negative 위험, 정량 threshold 필요성 지적.
- 코드 리뷰어 1차: helper output 누락 시 JSON contract 깨짐, malformed nodes response, multi-stop guard 미구현, aggregate evidence 부족, post-stop probe disabled 지적.
- 코드 리뷰어 2차: blocker 없음. 이전 지적 사항 해소 확인.

### GCP

현재 실행 중:

- run id: `20260509-rolling-restart-smoke`
- runner: `openchat-gcp-test-runner` subagent
- profile: `room-partition-rolling-restart-smoke`
- cleanup policy: always

Acceptance:

- k6 exit code `0`
- HTTP error `0%`
- route failure/fallback/mismatch `0 / 0 / 0`
- sent == ack == DB rows
- rolling restart `result=complete`
- stopped node count `>= 1`
- delivery evidence `complete=true`
- missing/failed handlers empty
- GCP stop `RUNNING -> TERMINATED`
- post-stop probe PASS
- cleanup RUN_ID VM `0`

## Context

현재 완료된 것:

- reconnect commandId traceability
- GCP VM termination adapter
- Durable Reconnect Command Log v1
- durable log smoke PASS

현재 한계:

- durable log는 publish evidence 중심이다.
- subscriber handler가 실제로 command를 처리했는지 요약하는 evidence가 부족하다.
- handling evidence를 termination decision의 optional strict guard로 사용할 수 없다.
- command log retention/cleanup 정책이 없다.

오늘 목표:

> reconnect command 발행, subscriber handling evidence, strict drain decision, GCP VM stop까지 한 번의 smoke로 연결한다.

## Scope

한 브랜치에서 아래 세 계획을 순서대로 구현한다.

### Plan 1. Delivery Evidence

목표:

- commandId별로 기대한 handler와 실제 handler를 비교한다.

작업:

- expectedHandlers 계산 규칙 정의
  - node reconnect: target node가 expected handler
  - room partition reconnect: 해당 room/partition owner 또는 subscriber owner가 expected handler
- actualHandlers 조회
  - `reconnect_command_handling_log` 기준
- missingHandlers 계산
- failedHandlers 계산
- command별 handling summary 생성
- orchestrator artifact에 delivery evidence 추가

필요한 이유:

- 지금은 Redis publish와 DB publish audit row는 확인할 수 있지만, subscriber가 실제로 처리했는지 바로 판단하기 어렵다.

### Plan 2. Strict Drain Evidence Mode

목표:

- delivery evidence를 optional safety guard로 사용할 수 있게 한다.

작업:

- 기본값 off
- strict off:
  - 기존처럼 audit evidence로만 보존
  - terminationAllowed 계산은 기존 guard 기준 유지
- strict on:
  - missingHandlers 또는 failedHandlers가 있으면 termination decision block
  - output에 어떤 handler evidence가 부족한지 기록
- shell fixture test 추가

필요한 이유:

- 운영 환경에 따라 "세션 0이면 충분"과 "command 처리 증거까지 필요"를 선택할 수 있어야 한다.

### Plan 3. Retention / Cleanup

목표:

- command audit log가 무제한 쌓이지 않게 최소 보관 정책을 둔다.

작업:

- retention 설정 추가
- cleanup service/method 또는 internal command 추가
- 기본 disabled 또는 긴 retention
- createdAt 기준 삭제
- 로컬 테스트 중심으로 검증

필요한 이유:

- audit log는 운영 증거지만, 무제한 저장하면 비용과 쿼리 성능 문제가 생긴다.

## Non-goals

- Redis Streams 전환
- reconnect command outbox
- client reconnect ack store
- per-session durable row
- force-drain policy
- EKS/MIG lifecycle hook
- 긴 soak를 매 구현 중간마다 실행하는 것

## Work Order

1. 현재 PR #21 상태 확인
2. `dev` 기준 새 브랜치 생성
   - 후보: `feat-reconnect-delivery-hardening`
3. Plan 1 상세 설계 확정
4. Delivery Evidence 구현
5. Delivery Evidence 로컬 테스트
6. Plan 2 상세 설계 확정
7. Strict Drain Evidence Mode 구현
8. Strict mode shell fixture test
9. Plan 3 상세 설계 확정
10. Retention / Cleanup 최소 구현
11. 전체 로컬 검증
12. 코드 리뷰 에이전트 호출
13. 리뷰 blocker 수정
14. GCP smoke runner subagent 호출
15. 결과 문서 갱신
16. roadmap / decision log / experience bank 갱신 여부 판단
17. 커밋
18. PR 생성 또는 기존 PR 업데이트

## Local Verification

GCP 실행 전 필수:

- `./gradlew test --tests '*ReconnectCommand*'`
- `./gradlew test --tests '*RealtimeNodeDrainServiceTest'`
- `bash scripts/test-openchat-node-drain-orchestrate.sh`
- `bash scripts/test-openchat-node-termination-decision.sh`
- `bash scripts/test-openchat-gcp-node-terminate.sh`
- `./gradlew test`
- `terraform -chdir=infra/gcp-loadtest fmt -check`
- `terraform -chdir=infra/gcp-loadtest validate`
- `git diff --check`

## Review Gate

GCP 전에 code-review subagent를 호출한다.

리뷰 포인트:

- strict mode 기본값이 off인지
- strict mode가 의도치 않게 기존 terminationAllowed를 바꾸지 않는지
- missing handler 계산이 과도하게 보수적이지 않은지
- handling evidence가 stale row를 잘못 해석하지 않는지
- native SQL query 비용과 index가 적절한지
- commandId를 Prometheus tag로 넣지 않았는지
- retention cleanup이 필요한 row를 삭제하지 않는지

## GCP Strategy

GCP는 마지막에 한 번만 실행한다.

권장 run id:

- `20260509-reconnect-delivery-hardening-smoke`

GCP runner:

- `openchat-gcp-test-runner` subagent 사용
- 메인 세션은 직접 GCP 명령을 실행하지 않는다.
- runner는 result doc을 `RUNNING -> PASS/FAIL/ABORTED`로 갱신한다.
- cleanup policy는 `always`

Acceptance:

- k6 exit `0`
- HTTP error `0%`
- WebSocket connect `100%`
- route failure/fallback/mismatch `0 / 0 / 0`
- sent == ack == DB rows
- reconnect command ids 존재
- publish log rows 존재
- handling log rows 존재
- `missingHandlers=0`
- `failedHandlers=0`
- strict decision `ready`
- GCP stop `RUNNING -> TERMINATED`
- post-stop probe PASS
- cleanup 후 RUN_ID GCE VM `0`

## Stop Conditions

GCP를 돌리지 않고 멈출 조건:

- local full test 실패
- shell fixture 실패
- Terraform validate 실패
- code-review blocker 존재
- expected/actual handler 계산이 명확하지 않음
- strict mode가 false positive로 정상 drain을 막을 가능성이 큼

GCP 실패 후 바로 재실행하지 않는 조건:

- app log에 schema/query 오류가 있음
- artifact에서 missing/failed handler 원인이 설명되지 않음
- sent/ack/DB rows 불일치
- cleanup 불완전

## Result Section

작업 종료 시 아래를 append한다.

```text
## Result

- 최종 상태:
- 커밋:
- PR:
- 로컬 검증:
- GCP run id:
- GCP 결과:
- 남은 이슈:
- 다음 작업:
```

## Progress Update: 4-1 Delivery Evidence

- 상태: 로컬 구현/리뷰/검증 완료
- 브랜치: `feat-reconnect-delivery-hardening`
- 구현 요약:
  - `reconnect_command_log` publish row와 `reconnect_command_handling_log` handler row를 commandId 기준으로 요약한다.
  - target node가 있는 command는 `strictEligible=true`, expected handler를 `targetNodeId`로 계산한다.
  - target node가 없는 command는 assignment 시점 오탐을 피하기 위해 strict 대상에서 제외하고 audit evidence만 남긴다.
  - `SENT`, `NO_TARGET`은 failed handler로 보지 않고, `PARTIAL`, `FAILED`는 failed handler로 요약한다.
  - command row 누락은 `deliveryEvidence.complete=false`로 처리한다.
  - handling log 조회 실패는 publish row evidence를 지우지 않고, delivery evidence만 incomplete로 남긴다.
- 리뷰:
  - 계획 리뷰어: blocker 없음, `strictEligible`와 missing command complete 처리 권고.
  - 코드 리뷰어 1차: missing command complete false-positive와 handling query failure degradation 지적.
  - 코드 리뷰어 2차: blocker 없음.
- 로컬 검증:
  - `./gradlew test --tests '*ReconnectCommand*'` PASS
  - `./gradlew test` PASS
  - `bash scripts/test-openchat-node-drain-orchestrate.sh` PASS
  - `bash scripts/test-openchat-node-termination-decision.sh` PASS
  - `bash scripts/test-openchat-gcp-node-terminate.sh` PASS
  - `bash -n infra/gcp-loadtest/templates/k6-startup.sh.tftpl` PASS
  - `git diff --check` PASS
- 남은 리스크:
  - GCP jq enrichment path는 syntax와 리뷰로 검증했지만 dedicated fixture는 아직 없다.
  - termination decision의 기존 `durableLogComplete`는 publish-row completeness 의미를 유지한다. delivery evidence 기반 strict gate는 4-2에서 별도 구현한다.
- 다음 작업:
  - 4-1 커밋 후 4-2 Strict Drain Evidence Mode 상세 설계와 계획 리뷰 진행.

## Progress Update: 4-2 Strict Drain Evidence Mode

- 상태: 로컬 구현/리뷰/검증 완료
- 커밋 예정 범위:
  - termination decision script strict mode
  - GCP loadtest strict flag wiring
  - strict mode shell fixture tests
- 구현 요약:
  - `openchat-node-termination-decision.sh`에 `--strict-delivery-evidence` 옵션과 `OPENCHAT_STRICT_DELIVERY_EVIDENCE=true` env를 추가했다.
  - 기본값은 off라서 기존 `terminationAllowed` 판단을 바꾸지 않는다.
  - strict on일 때만 `delivery_evidence_complete` guard를 추가한다.
  - strict delivery evidence 실패는 `unsafe`가 아니라 `not_ready`로 분류한다.
  - `auditEvidence`에 delivery evidence collection status, complete, missing/failed handlers, strict eligible command count를 항상 노출한다.
  - explicit `false`가 jq `//`에 의해 `null`로 바뀌지 않도록 `has("complete")` 기반으로 처리했다.
  - Terraform/GCP k6 startup에 strict flag를 wiring했지만 기본값은 `false`다.
- 리뷰:
  - 계획 리뷰어: 기존 unsafe classification과 충돌 가능성 지적. safety guard와 strict guard를 분리하는 방향으로 반영.
  - 코드 리뷰어: blocker 없음.
- 로컬 검증:
  - `bash scripts/test-openchat-node-termination-decision.sh` PASS
  - `bash scripts/test-openchat-node-drain-orchestrate.sh` PASS
  - `bash scripts/test-openchat-gcp-node-terminate.sh` PASS
  - `bash -n scripts/openchat-node-termination-decision.sh` PASS
  - `bash -n infra/gcp-loadtest/templates/k6-startup.sh.tftpl` PASS
  - `terraform -chdir=infra/gcp-loadtest fmt -check` PASS
  - `terraform -chdir=infra/gcp-loadtest validate` PASS
  - `./gradlew test` PASS
  - `git diff --check` PASS
- 남은 리스크:
  - strict mode는 `deliveryEvidence.collectionStatus=collected`와 `complete=true`를 모두 요구한다. future producer가 collectionStatus를 누락하면 strict mode에서 block된다.
- 다음 작업:
  - 4-2 커밋 후 4-3 Retention / Cleanup 상세 설계와 계획 리뷰 진행.

## Progress Update: 4-3 Retention / Cleanup

- 상태: 로컬 구현/리뷰/검증 완료
- 커밋 예정 범위:
  - reconnect command log retention 설정
  - command/handling log cleanup service method
  - cleanup unit tests
  - GCP bootstrap DDL index 보강
- 구현 요약:
  - `app.room-partition.control.command-log.retention.*` 설정을 추가했다.
  - 기본값은 disabled이며, 자동 scheduler는 만들지 않았다.
  - retention 기본값은 30일이고, 1시간 미만 값은 1시간으로 보정한다.
  - cleanup limit 기본값은 1000이고, 1 미만 값은 1로 보정한다.
  - cleanup은 command numeric id가 아니라 `command_id` 목록을 기준으로 처리한다.
  - handling row를 먼저 삭제하고 command row를 삭제해 FK/참조 구조로 확장해도 안전한 순서를 유지한다.
  - command log 자체가 disabled이거나 retention cleanup이 disabled이면 skip result를 반환한다.
  - GCP fresh schema bootstrap DDL에 `(created_at, id)` index를 추가해 오래된 command 후보 조회가 전체 스캔으로 커지지 않게 했다.
- 리뷰:
  - 계획 리뷰어: command numeric id 대신 commandId 기준 cleanup, env override, service-level transaction, no scheduler 범위 유지 권고.
  - 코드 리뷰어: correctness blocker 없음. repository SQL integration coverage와 오래된 row 조회 index를 residual risk로 지적.
  - index residual risk는 GCP bootstrap DDL에 `idx_reconnect_command_created (created_at, id)`를 추가해 반영했다.
- 로컬 검증:
  - `./gradlew test --tests '*ReconnectCommandLogServiceTest'` PASS
  - `terraform -chdir=infra/gcp-loadtest fmt -check` PASS
  - `terraform -chdir=infra/gcp-loadtest validate` PASS
  - `bash -n infra/gcp-loadtest/templates/app-startup.sh.tftpl` PASS
  - `git diff --check` PASS
- 남은 리스크:
  - cleanup repository SQL은 unit mock 계약으로 검증했고, 실제 DB integration test는 아직 없다.
  - 기존 GCP DB에 이미 테이블이 있다면 `CREATE TABLE IF NOT EXISTS`만으로 새 index가 추가되지는 않는다. smoke의 fresh DB bootstrap 기준으로는 반영된다.
- 다음 작업:
  - 전체 테스트 완료 후 4-3 커밋.
  - 그 다음 사용자가 현재 4-1/4-2/4-3 코드를 눈으로 확인.
  - 승인 후 GCP smoke는 `openchat-gcp-test-runner` subagent에 위임.

## Progress Update: Phase 5 Rolling Restart / Soak Validation

- 상태: 구현, 로컬 검증, GCP smoke PASS, GCP mini-soak FAIL
- 브랜치: `test-rolling-restart-soak-validation`
- 구현 요약:
  - rolling restart 전용 shell orchestrator를 추가했다.
  - active realtime node 조회, target node 선택, node drain, termination decision, GCP stop, assignment readiness 확인을 한 흐름으로 묶었다.
  - child helper 실패 시에도 결과 JSON이 깨지지 않도록 `null` placeholder와 structured failure result를 남긴다.
  - multi-stop은 active realtime node가 4개 이상일 때만 허용하고, Phase 5 v1 profile은 `realtime_count=3`, `target_count=1`, `min_active_nodes=2`로 제한했다.
  - GCP k6 startup에 rolling restart 옵션과 background execution을 연결했다.
  - rolling restart path에서도 strict delivery evidence를 termination decision에 반영할 수 있도록 drain result enrichment hook을 추가했다.
  - smoke/mini-soak profile을 별도로 추가했다.
- 로컬 검증:
  - `bash -n scripts/openchat-rolling-restart-orchestrate.sh scripts/test-openchat-rolling-restart-orchestrate.sh infra/gcp-loadtest/templates/k6-startup.sh.tftpl` PASS
  - `bash scripts/test-openchat-rolling-restart-orchestrate.sh` PASS
  - `bash scripts/test-openchat-node-drain-orchestrate.sh` PASS
  - `bash scripts/test-openchat-node-termination-decision.sh` PASS
  - `bash scripts/test-openchat-gcp-node-terminate.sh` PASS
  - `node --check k6/scenarios/11-mixed-room-workload-ramped.js` PASS
  - `./gradlew test` PASS
  - `terraform -chdir=infra/gcp-loadtest fmt -check` PASS
  - `terraform -chdir=infra/gcp-loadtest validate` PASS
  - `git diff --check` PASS
- 리뷰:
  - 계획 리뷰에서 `target_count >= 2` guard, post-stop probe, ACK p95/p99 기준을 보강했다.
  - 코드 리뷰에서 child output missing, malformed nodes response, multi-stop guard, aggregate evidence, post-stop probe wiring, Terraform variable validation 이슈를 반영했다.
  - 2차 코드 리뷰에서 blocker는 없었다.
- GCP smoke:
  - run id: `20260509-rolling-restart-smoke3`
  - 결과: PASS
  - k6 exit code: main/post-stop `0/0`
  - WebSocket connect: main `151/151`, post-stop `20/20`
  - route failure/fallback/mismatch: `0/0/0`
  - sent/ack/DB rows: `33546/33546/33546`
  - rolling restart: `result=complete`, `terminationAllowed=true`, stopped node `gcp-realtime-2`
  - delivery evidence: `collectionStatus=collected`, `complete=true`, command count `3`, missing/failed handlers 없음
  - GCP stop: `RUNNING -> TERMINATED`, stopped count `1`
  - ACK latency: p95 `42ms`, p99 `282ms`, max `905ms`
  - cleanup: RUN_ID VM `0`, disk `0`
- GCP mini-soak:
  - run id: `20260509-rolling-restart-mini-soak`
  - 결과: FAIL
  - k6 exit code: main/post-stop `99/0`
  - 기능 정합성:
    - HTTP error `0%`
    - WebSocket connect main/post-stop `300/300`, `40/40`
    - route failure/fallback/mismatch `0/0/0`
    - sent/ack/DB rows `110386/110386/110386`
    - rolling restart `complete`, `terminationAllowed=true`, stopped node `gcp-realtime-2`
    - delivery evidence `collected/complete`, missing/failed handlers 없음
    - GCP stop `RUNNING -> TERMINATED`
    - cleanup RUN_ID VM `0`, disk `0`
  - 실패 원인:
    - ACK latency threshold 초과로 k6 exit code `99`
    - ACK p95 `1339ms`, p99 `3765ms`, max `5609ms`
    - 기준은 p95 `<=500ms`, p99 `<=2000ms`
    - 이전 stable mini-soak `20260509-reconnect-traceability-mini-soak3`는 비슷한 row count에서 ACK p95 `103ms`였으므로, 단순 threshold 문제로 닫지 않는다.
- 판단:
  - rolling restart 기능 경로 자체는 smoke와 mini-soak 모두에서 drain, delivery evidence, termination decision, GCP stop, cleanup까지 성공했다.
  - 하지만 mini-soak tail latency가 크게 악화됐으므로 Phase 5를 최종 완료로 커밋하지 않는다.
- 추가 확인:
  - rolling restart post-stop probe의 `TEST_LABEL`이 길어 `client_message_id` 길이 제한과 충돌할 수 있는 하네스 문제가 있었다.
  - 증상은 post-stop probe에서 route/connect checks는 통과하지만 sent 대비 ack/DB rows가 `0`으로 수집되는 형태였다.
  - rolling post-stop label을 `rr-{label}-n{nodeIndex}` 형태로 줄여 다음 GCP 실행부터 post-stop message/ack/DB 검증이 정상 수집되도록 수정했다.
- 다음 작업:
  - mini-soak latency 원인 분석.
  - post-stop probe가 route/connect 중심 검증인지, message ack 검증까지 해야 하는지 기준 재확인.
  - 필요하면 mini-soak 재실행으로 flake 여부 확인.
  - 원인 해결 또는 acceptance 기준 조정 전까지 PR/merge 대상에서 제외.

## Progress Update: Phase 5 Rolling Restart Mini-soak Rerun

- 상태: GCP mini-soak2 실행 완료, FAIL
- run id: `20260509-rolling-restart-mini-soak2`
- 목적:
  - rolling restart post-stop label shortening 후 message/ack/DB evidence가 정상 수집되는지 확인.
  - 이전 mini-soak 실패가 하네스 label 문제인지, 별도 latency/visibility 문제인지 분리.
- 결과 요약:
  - main k6 exit code: `99`
  - post-stop k6 exit code: `99`
  - route failure/fallback/mismatch: main/post-stop 모두 `0/0/0`
  - main sent/ack/DB rows: `110299/110299/110299`
  - post-stop sent/ack/DB rows: `3026/3026/3026`
  - rolling restart: `complete`
  - termination decision: `terminationAllowed=true`
  - delivery evidence: `collected`, `complete=true`, missing/failed handlers 없음
  - GCP stop: `gcp-realtime-2` `RUNNING -> TERMINATED`
  - cleanup: RUN_ID VM `0`, disk `0`
- latency:
  - main ACK p95/p99/max: `160ms / 3640ms / 4717ms`
  - post-stop ACK p95/p99/max: `74ms / 107ms / 188ms`
  - main visibility freshness p95/p99: `18360ms / 19428ms`
  - post-stop visibility freshness p95/p99: `18777ms / 19077ms`
- 판단:
  - post-stop label 문제는 해결됐다. post-stop sent/ack/DB rows가 일치한다.
  - rolling restart control-plane correctness는 재확인됐다.
  - 남은 실패는 main ACK p99 tail과 visibility freshness threshold다.
  - ACK p95는 `160ms`로 좋아졌지만 p99가 `2000ms` 기준을 초과했다. 따라서 "전체 성능 PASS"로 닫을 수 없다.
  - visibility freshness는 main/post-stop 모두 높다. ACK와 DB 정합성은 유지되므로, message 저장/ack 경로보다는 observer visibility 측정 방식, post-stop/main 동시 실행 간섭, reconnect 직후 backlog 처리, 혹은 threshold 기준 재검토가 다음 분석 대상이다.
- 다음 작업:
  - Phase 5 구현은 correctness smoke 기준으로는 보존 가능하지만, mini-soak acceptance는 보류.
  - visibility freshness metric의 의미와 threshold를 재정의할지, 실제 visibility latency를 낮출지 분리해서 결정한다.
  - ACK p99 spike가 drain/stop window와 겹치는지 artifact 로그와 Prometheus로 추가 분석한다.
  - 이 브랜치는 아직 merge하지 않는다.

## Progress Update: Phase 5 Visibility Metric Split

- 상태: 로컬 구현/검증 완료, GCP 재검증 전
- 목적:
  - mini-soak2의 visibility freshness 실패가 실제 최신 메시지 지연인지, hot-room live cap이 오래된 visible message를 일부 흘려보내며 생기는 측정값인지 분리한다.
  - 서버 fan-out 정책은 바꾸지 않고 k6 관측 지표만 추가한다.
- 변경:
  - 기존 `ws_visible_freshness_ms`는 유지한다.
  - 새 `ws_visible_latest_freshness_ms`를 추가했다.
    - 수신 batch 안에서 가장 최신 `createdAt` 기준 freshness를 1회 기록한다.
    - observer가 방의 최신 흐름을 따라가는지 확인하기 위한 지표다.
  - 새 `ws_visible_gap_messages`를 추가했다.
    - `realtimeComplete=false` batch의 `omittedCount`를 기록한다.
    - freshness가 높을 때 live cap이 얼마나 많은 메시지를 생략했는지 함께 해석하기 위한 지표다.
- 결정:
  - `LiveFanoutPolicy`의 oldest-first visible policy는 아직 바꾸지 않는다.
  - 먼저 latest freshness와 gap metric을 GCP에서 확인한 뒤, 서버 정책 변경 여부를 별도 결정한다.
- 검증:
  - `bash scripts/test-k6-visible-freshness-metrics.sh` PASS
  - `node --check k6/scenarios/11-mixed-room-workload-ramped.js` PASS
  - `node --check k6/lib/ws.js` PASS
  - `git diff --check` PASS
- 다음 작업:
  - rolling restart smoke 또는 짧은 mini-soak 재실행으로 `ws_visible_freshness_ms`, `ws_visible_latest_freshness_ms`, `ws_visible_gap_messages`를 함께 비교한다.
  - latest freshness도 높으면 실제 delivery freshness 문제로 본다.
  - latest freshness는 낮고 기존 freshness만 높으면 threshold를 latest/gap 중심으로 재정의하거나, 별도 브랜치에서 latest-first live cap 정책을 검토한다.

## Progress Update: GCP Loadtest Naming Hardening

- 상태: 로컬 구현/검증 완료, GCP 재실행 전
- 발생 상황:
  - `20260509-rolling-restart-visibility-load` 실행이 k6 시작 전 `ABORTED`됐다.
  - Terraform apply 중 service account 이름이 `openchat-lt-20260509-rolling-r`로 잘리며 기존 `20260509-rolling-restart-mini-soak2` 실행의 service account와 충돌했다.
  - 동시에 project network quota `5`개 제한도 초과했다.
- 원인:
  - 기존 Terraform naming은 `name_prefix = substr("openchat-lt-${run_id}", 0, 40)`와 `account_id = substr(name_prefix, 0, 30)` 형태였다.
  - 긴 run id가 같은 prefix를 공유하면 뒤쪽 식별자가 잘려 service account id가 충돌한다.
- 변경:
  - `run_hash = substr(sha1(var.run_id), 0, 8)`를 추가했다.
  - VM/network 계열 `name_prefix`는 human-readable prefix 뒤에 hash suffix를 붙인다.
  - service account id는 `oclt-{safe_run_id_prefix}-{hash}` 형태로 30자 이내를 유지한다.
  - `*.tfplan`은 생성 artifact이므로 `.gitignore`에 추가했다.
- 검증:
  - `bash scripts/test-gcp-loadtest-resource-naming.sh` PASS
  - `terraform -chdir=infra/gcp-loadtest fmt -check` PASS
  - `terraform -chdir=infra/gcp-loadtest validate` PASS
  - `git diff --check` PASS
- 남은 이슈:
  - hash suffix는 앞으로의 이름 충돌을 막지만, 이미 남아 있는 기존 run-scoped network/service account quota 문제를 자동 삭제하지 않는다.
  - network quota 초과는 기존 GCP 리소스 정리 또는 공유 network 설계로 별도 처리해야 한다.

## Progress Update: GCP Network Quota Cleanup

- 상태: GCP run-scoped network cleanup 완료, visibility load 재실행 중
- 정리 전 network:
  - `default`
  - `openchat-lt-20260508-auto-partition-life-net`
  - `openchat-lt-20260508-dynamic-ownership-c-net`
  - `openchat-lt-20260509-rolling-restart-min-net`
  - `openchat-lt-20260509-rolling-restart-smo-net`
- 안전 확인:
  - `openchat-lt-*` VM 없음.
  - `openchat-lt-*` disk 없음.
- 삭제한 리소스:
  - 위 4개 test network의 NAT, router, firewall rules, subnet, network.
  - `default` network는 보존.
  - stable GCS bucket/static IP는 건드리지 않음.
- 정리 후 network:
  - `default`만 남음.
- 후속 실행:
  - visibility metric split 검증 load를 `20260509-rolling-restart-visibility-load2`로 재실행 중.

## Progress Update: Visibility Load2 Aborted Before k6

- 상태: GCP 실행 결과 확인 및 cleanup 완료
- run id: `20260509-rolling-restart-visibility-load2`
- 결과 문서: `docs/results/gcp/GCP-load-결과-20260509-rolling-restart-visibility-load2.md`
- 결론:
  - 이번 실행은 OpenChat application correctness/performance 결과가 아니다.
  - k6 VM bootstrap 중 Debian security mirror sync flake로 `apt-get update`가 실패했고, k6 workload는 시작되지 않았다.
  - 따라서 visibility split 지표(`ws_visible_freshness_ms`, `ws_visible_latest_freshness_ms`, `ws_visible_gap_messages`)는 아직 GCP load에서 검증되지 않았다.
- 확인한 증거:
  - k6 startup log: `File has unexpected size ... Mirror sync in progress`
  - `workers/single.done`과 after snapshot은 업로드됐지만 k6 summary artifact는 없음.
  - Terraform apply 자체는 shared bucket/static IP import recovery 후 성공했다.
- cleanup:
  - 결과 확인 시 `mysql`, `redis` VM과 run-scoped network가 남아 있었다.
  - full destroy는 shared bucket/static IP 삭제를 포함해 `prevent_destroy`로 중단됐다.
  - shared 리소스를 제외한 target destroy를 적용해 run-scoped 리소스만 정리했다.
  - 최종 확인: RUN_ID VM 0, disk 0, network는 `default`만 남음.
- 다음 작업:
  - k6 startup의 `apt-get update`를 retry/fallback 가능하게 보강한다.
  - 같은 visibility load를 재실행해 이번에 추가한 latest freshness/gap 지표를 실제 부하에서 확인한다.

## Progress Update: GCP Startup Apt Retry Hardening

- 상태: 로컬 구현/검증 완료, GCP 재실행 위임
- 발생 상황:
  - `20260509-rolling-restart-visibility-load2`는 VM 생성 이후 k6 VM의 `apt-get update`가 Debian security mirror sync flake로 실패하면서 workload 시작 전 `ABORTED`됐다.
  - VM 생성과 app/realtime 준비 시간이 이미 소모된 뒤 테스트가 죽기 때문에 비용과 대기 시간이 아깝다.
- 변경:
  - GCP startup template 전체에 `apt_update_with_retry()`와 `apt_install_with_retry()`를 추가했다.
  - 대상:
    - `app-startup.sh.tftpl`
    - `k6-startup.sh.tftpl`
    - `lb-startup.sh.tftpl`
    - `monitoring-startup.sh.tftpl`
    - `mysql-startup.sh.tftpl`
    - `redis-startup.sh.tftpl`
  - `apt-get update`는 `Acquire::Retries=3`와 최대 5회 재시도를 사용한다.
  - 실패 시 `apt-get clean`, partial list cleanup, backoff sleep 후 재시도한다.
  - `apt-get install`도 최대 3회 재시도한다.
- 검증:
  - `bash scripts/test-gcp-startup-apt-retry.sh` PASS
  - `bash scripts/test-gcp-loadtest-resource-naming.sh` PASS
  - `terraform -chdir=infra/gcp-loadtest fmt -check` PASS
  - `terraform -chdir=infra/gcp-loadtest validate` PASS
  - `git diff --check` PASS
- 다음 작업:
  - `20260509-rolling-restart-visibility-load3`로 같은 visibility load를 재실행한다.
  - 이번에는 startup mirror flake가 발생해도 retry로 흡수되는지 확인한다.

## Progress Update: Rolling Restart Bottleneck Diagnostic Profile

- 상태: 로컬 profile 추가/검증 완료, GCP 실행 위임
- 발생 상황:
  - `20260509-rolling-restart-visibility-load3`는 correctness contract는 통과했지만 main ACK p95/p99가 `1392ms / 5096ms`로 높아 `FAIL`이었다.
  - sent/ack/DB rows는 `110358 / 110358 / 110358`로 일치했고 route failure/fallback/mismatch는 `0/0/0`이었다.
  - 따라서 다음 판단은 "서버가 느린가"보다 먼저 "k6 generator가 200 VU + WS + rolling restart 관측 비용을 감당 못 하는가"를 분리해야 한다.
- 결정:
  - 새 profile `room-partition-rolling-restart-generator-check.tfvars.example`를 추가했다.
  - 기존 mini-soak과 동일한 200 VU, 동일 rolling restart, 동일 reconnect limit을 유지한다.
  - 차이는 `k6_machine_type = "e2-standard-4"` 하나다.
- 해석 기준:
  - ACK p95/p99와 latest freshness가 크게 좋아지면 k6 generator pressure가 주요 병목이다.
  - 거의 개선되지 않으면 server-side 처리, reconnect burst, visibility backlog 쪽으로 원인을 좁힌다.
  - correctness는 계속 sent/ack/DB rows, route mismatch, rolling restart complete, cleanup 결과로 본다.
- 검증:
  - `terraform -chdir=infra/gcp-loadtest fmt -check` PASS
  - `terraform -chdir=infra/gcp-loadtest validate` PASS
  - `bash scripts/test-gcp-startup-apt-retry.sh` PASS
  - `git diff --check` PASS
- GCP run:
  - run id: `20260509-rolling-restart-generator-check`
  - 결과 문서: `docs/results/gcp/GCP-load-결과-20260509-rolling-restart-generator-check.md`

## Progress Update: Rolling Restart Generator Check Result

- 상태: GCP 실행 완료, FAIL
- run id: `20260509-rolling-restart-generator-check`
- 결과 문서: `docs/results/gcp/GCP-load-결과-20260509-rolling-restart-generator-check.md`
- 목적:
  - `20260509-rolling-restart-visibility-load3`에서 발생한 ACK/visibility tail이 k6 generator 병목인지 확인한다.
  - app/realtime/mysql/redis/rolling/reconnect 조건은 유지하고, k6 VM만 `e2-standard-2`에서 `e2-standard-4`로 키웠다.
- 결과 요약:
  - main k6 exit: `99`
  - post-stop exit: `0`
  - route failure/fallback/mismatch: `0/0/0`
  - main sent/ack/DB rows: `110308 / 106545 / 106675`
  - post-stop sent/ack/DB rows: `3023 / 0 / 0`
  - ACK p95/p99: `38442ms / 42843.56ms`
  - latest freshness p95/p99: `2703.90ms / 11212.98ms`
  - rolling restart: `complete`
  - terminationAllowed: `true`
  - delivery evidence: complete
  - GCP stop: `RUNNING -> TERMINATED`
  - cleanup: RUN_ID VM/disk/network `0/0/0`
- load3 대비:
  - ACK p95/p99는 `1392ms / 5096ms`에서 `38442ms / 42843.56ms`로 악화됐다.
  - latest freshness p95/p99는 `3869.8ms / 12241.24ms`에서 `2703.90ms / 11212.98ms`로 일부 개선됐지만 tail은 여전히 높다.
  - load3에서는 sent/ack/DB가 `110358 / 110358 / 110358`로 일치했지만, 이번 run은 main/post-stop 모두 정합성 기준을 만족하지 못했다.
- 판단:
  - k6 generator pressure가 primary contributor라는 근거는 부족하다.
  - k6 VM만 키웠는데 ACK latency와 sent/ack/DB 정합성이 개선되지 않았으므로, 다음 분석은 server/reconnect burst/visibility backlog 쪽으로 좁힌다.
- 다음 작업:
  - reconnect command handling log, realtime app log, DB insert/ack emission timestamp를 상관 분석한다.
  - 특히 main ACK deficit `3763`, DB deficit `3633`, post-stop `sent=3023/ack=0/DB=0`이 probe/harness 문제인지 실제 reconnect burst 후 처리 지연인지 분리한다.
  - 다음 GCP 재실행 전에는 raw artifact 분석으로 먼저 원인을 좁힌다.

## Progress Update: Throttled Rolling Restart Plan

- 상태: 로컬 profile 추가/검증 완료, GCP 실행 위임 예정
- 가설:
  - load3와 generator-check 결과를 종합하면 k6 generator 단독 병목보다는 rolling restart 중 reconnect burst가 ACK/visibility tail을 키우는 가능성이 높다.
  - 기존 `k6_node_drain_limit=1000`, `retryAfterMs=500`은 200 VU 환경에서 사실상 한 번에 전체 세션을 옮기는 설정이다.
- 변경:
  - 새 profile `room-partition-rolling-restart-throttled.tfvars.example` 추가.
  - 서버 사양, 200 VU, rolling restart, post-stop probe 조건은 유지한다.
  - reconnect pacing만 변경한다.
    - `k6_node_drain_limit=50`
    - `k6_node_drain_retry_after_ms=2000`
    - `k6_node_drain_orchestrator_max_reconnect_attempts=40`
    - `k6_node_drain_orchestrator_timeout_seconds=420`
- 해석 기준:
  - ACK p95/p99, latest freshness, sent/ack/DB 정합성이 개선되면 reconnect burst가 주요 원인이다.
  - drain complete 시간이 늘어나는 것은 의도된 trade-off다.
  - throttling해도 개선되지 않으면 server write path, subscriber/fanout backlog, post-stop probe 하네스를 우선 분석한다.
- 검증:
  - `terraform -chdir=infra/gcp-loadtest fmt -check` PASS
  - `terraform -chdir=infra/gcp-loadtest validate` PASS
  - `git diff --check` PASS
- GCP run:
  - run id: `20260509-rolling-restart-throttled`
  - 결과 문서: `docs/results/gcp/GCP-load-결과-20260509-rolling-restart-throttled.md`

## Progress Update: Throttled Rolling Restart Result

- 상태: GCP 실행 완료, PASS
- run id: `20260509-rolling-restart-throttled`
- 결과 문서: `docs/results/gcp/GCP-load-결과-20260509-rolling-restart-throttled.md`
- 목적:
  - rolling restart 중 reconnect burst가 ACK tail, visibility freshness tail, sent/ack/DB 불일치의 주 원인인지 확인한다.
  - 기존 200 VU rolling restart 조건은 유지하고 reconnect pacing만 늦췄다.
- 변경된 조건:
  - 기존 load3: `k6_node_drain_limit=1000`, `retryAfterMs=500`
  - throttled: `k6_node_drain_limit=50`, `retryAfterMs=2000`
  - drain timeout과 max reconnect attempts는 늘려서 천천히 비우는 방식을 허용했다.
- 결과 요약:
  - main/post-stop exit code: `0 / 0`
  - route failure/fallback/mismatch: `0 / 0 / 0`
  - main sent/ack/DB rows: `110253 / 110253 / 110253`
  - post-stop sent/ack/DB rows: `3026 / 3026 / 3026`
  - ACK p95/p99: `161ms / 916.96ms`
  - latest freshness p95/p99: `170.85ms / 426.37ms`
  - full visible freshness p95/p99: `593.60ms / 8315.60ms`
  - visible gap p95/p99/max: `345 / 479 / 858`
  - rolling restart: `complete`
  - terminationAllowed: `true`
  - delivery evidence: complete
  - GCP stop: `RUNNING -> TERMINATED`
  - cleanup: RUN_ID VM/disk/network `0/0/0`
- 비교:
  - load3 ACK p95/p99 `1392ms / 5096ms` -> throttled `161ms / 916.96ms`
  - load3 latest freshness p95/p99 `3869.8ms / 12241.24ms` -> throttled `170.85ms / 426.37ms`
  - generator-check main consistency `110308 / 106545 / 106675` -> throttled `110253 / 110253 / 110253`
  - drain duration은 load3 `8s`, generator-check `7s`, throttled `13s`로 늘었다.
- 판단:
  - reconnect burst는 likely primary contributor다.
  - throttled drain은 promising mitigation이다.
  - 노드를 빨리 비우는 대신, 서비스 ACK/visibility 품질을 유지하기 위해 reconnect를 batch 단위로 pacing하는 방향이 맞다.
- 남은 이슈:
  - full visible freshness p99 `8315.60ms`, visible gap p99 `479`는 아직 tail이 있다.
  - 최신 메시지 freshness는 정상화됐으므로, legacy/full freshness와 gap metric의 의미를 분리해야 한다.
  - 다음 구현 단계에서는 throttled pacing을 임시 profile이 아니라 drain orchestrator 기본 정책 후보로 승격하는 방안을 검토한다.

## Progress Update: Drain Reconnect Pacing Default Policy

- 상태: 구현 중
- 기준 결과: `20260509-rolling-restart-throttled`
- 결정:
  - node drain, rolling restart, termination 경로의 server-issued reconnect를 batch 기반 throttled drain으로 기본정책화한다.
  - 일반 WebSocket 신규 연결 속도는 늦추지 않는다.
  - fast drain은 제거하지 않고 API/CLI 명시 override로만 사용한다.
- 기본값:
  - reconnect batch limit: `50`
  - client retryAfterMs: `2000`
  - orchestrator timeout: `420s`
  - max reconnect attempts: `40`
  - poll interval: `2000ms`
- 이유:
  - 기존 fast drain은 `limit=1000`, `retryAfterMs=500`에 가까워 200 VU 조건에서도 drain 대상 세션을 한 번에 다시 연결시켰다.
  - throttled profile에서는 drain duration이 `8s -> 13s`로 늘었지만, ACK p95/p99가 `1392ms / 5096ms -> 161ms / 916.96ms`로 개선됐고, latest freshness p95/p99도 `3869.8ms / 12241.24ms -> 170.85ms / 426.37ms`로 개선됐다.
  - 따라서 운영 기본값은 "빨리 비우기"보다 "controlled drain으로 서비스 품질 유지"가 맞다.
- 구현 범위:
  - 앱 node drain 기본값을 `50/2000`으로 변경한다.
  - node drain/rolling restart ops script 기본 timeout/retry/pacing을 throttled 값으로 변경한다.
  - node drain/rolling restart GCP profile 기본값을 throttled 정책에 맞춘다.
  - script/unit test에서 default와 explicit override가 모두 유지되는지 검증한다.
- 검증 예정:
  - 로컬 unit/script/Terraform 검증
  - GCP run id: `20260509-rolling-restart-throttled-default`
  - 목적: 임시 throttled profile이 아니라 기본 정책 변경으로 같은 효과가 재현되는지 확인한다.

### Local Verification

- `./gradlew test --tests '*RealtimeNodeDrainServiceTest' --tests '*RoomPartitionAssignmentInternalControllerTest'` PASS
- `./gradlew test` PASS
- `bash scripts/test-openchat-node-drain-orchestrate.sh` PASS
- `bash scripts/test-openchat-rolling-restart-orchestrate.sh` PASS
- `terraform -chdir=infra/gcp-loadtest fmt -check` PASS
- `terraform -chdir=infra/gcp-loadtest validate` PASS outside sandbox after provider handshake failed inside sandbox
- `git diff --check` PASS

### GCP Verification

- runner: `openchat-gcp-test-runner` subagent
- run id: `20260509-rolling-restart-throttled-default`
- status: FAIL
- acceptance:
  - main/post-stop exit code `0/0`
  - route failure/fallback/mismatch `0/0/0`
  - main/post-stop sent == ack == DB rows
  - ACK p95/p99 and latest freshness p95/p99 remain close to `20260509-rolling-restart-throttled`
  - rolling restart `complete`
  - terminationAllowed `true`
  - GCP stop `RUNNING -> TERMINATED`
  - cleanup RUN_ID VM/disk/network `0/0/0`

Result:

- k6 exit code: main/post-stop `99/99`
- HTTP error: main/post-stop `0.00% / 0.00%`
- route failure/fallback/mismatch: `0/0/0`
- route node와 connected node mismatch: `0`
- sent/ack/DB rows:
  - main `110220 / 110220 / 110220`
  - post-stop `3026 / 3026 / 3026`
- ACK p95/p99:
  - main `156ms / 1885.81ms`
  - post-stop `94.75ms / 167ms`
- latest freshness p95/p99:
  - main `112ms / 2245.86ms`
  - post-stop `6797ms / 6958.11ms`
- rolling restart: `complete`
- terminationAllowed: `true`
- delivery evidence: complete
- GCP stop: `RUNNING -> TERMINATED`
- cleanup: RUN_ID VM/disk/network `0/0/0`

Interpretation:

- correctness path는 통과했다. route, connected node, drain, termination decision, GCP stop, sent/ack/DB rows는 모두 정상이다.
- pacing 기본값은 ACK p95와 데이터 정합성에는 효과가 유지됐다.
- 그러나 latest freshness p99가 이전 throttled run 수준을 재현하지 못했고, post-stop probe freshness threshold가 크게 초과되어 기본정책 완료로 닫을 수 없다.
- post-stop probe는 main 200 VU workload가 아직 실행 중인 동안 별도 k6 process로 추가 40 VU를 띄운다. `listen tcp 127.0.0.1:6565: bind: address already in use` 경고도 동시에 관찰됐다. workload 자체는 완료됐지만, post-stop freshness SLO를 main workload와 같은 기준으로 볼지 분리해야 한다.

Next:

- 기본값 변경 자체를 바로 커밋하지 않는다.
- 다음 수정은 앱 drain pacing보다 GCP/k6 acceptance 분리 쪽을 먼저 본다.
  - main workload correctness/ACK/latest freshness
  - post-stop route exclusion/message correctness
  - post-stop freshness는 별도 SLO 또는 settle window 적용 여부 검토
- 그 뒤 기본 throttled policy를 다시 GCP로 검증한다.

## Progress Update: Rolling Restart Validation Gate Split

- 상태: 로컬 구현/검증 완료, GCP 실행 예정
- 목적:
  - rolling restart run을 단일 k6 exit code로만 보지 않고, correctness/drainTermination/performance/postStopFreshness/cleanup gate로 분리한다.
  - 운영적으로 merge 가능한 correctness와 추가 개선이 필요한 freshness SLO를 분리해 판단한다.
- 변경:
  - `K6_CHAT_ACK_P95_THRESHOLD_MS=0`이면 ACK threshold도 비활성화하도록 k6 scenario를 변경했다.
  - canonical `room-partition-rolling-restart-mini-soak` profile은 ACK/freshness를 hard fail로 쓰지 않고 summary metric으로 수집한다.
  - post-stop probe의 hardcoded freshness threshold `1000ms`를 제거하고 profile/env 값으로 제어한다.
  - k6 startup에서 `metrics/validation-gates-*.json`을 생성한다.
    - `correctness`: HTTP/WS/route/data consistency
    - `drainTermination`: rolling restart complete, terminationAllowed, delivery evidence, GCP stop count
    - `performance`: ACK/latest freshness 수집 및 threshold가 켜진 경우 PASS/PARTIAL
    - `postStopFreshness`: post-stop freshness 분리
    - `cleanup`: runner cleanup 검증으로 위임
  - post-stop probe는 별도 `metrics/post-stop-probe-*.json`에 `correctnessResult`, `freshnessResult`, `exitCode`, `thresholdExitCode`를 남긴다.
  - freshness를 hard gate로 보는 별도 profile `room-partition-rolling-restart-freshness-check`를 추가했다.
- 로컬 검증:
  - `bash scripts/test-rolling-restart-validation-gates.sh` PASS
  - `node --check k6/scenarios/11-mixed-room-workload-ramped.js` PASS
  - `bash -n infra/gcp-loadtest/templates/k6-startup.sh.tftpl` PASS
  - `bash scripts/test-openchat-rolling-restart-orchestrate.sh` PASS
  - `bash scripts/test-openchat-node-drain-orchestrate.sh` PASS
  - `bash scripts/test-gcp-loadtest-resource-naming.sh` PASS
  - `bash scripts/test-gcp-startup-apt-retry.sh` PASS
  - `bash scripts/test-k6-visible-freshness-metrics.sh` PASS
  - `./gradlew test --tests '*RealtimeNodeDrainServiceTest' --tests '*RoomPartitionAssignmentInternalControllerTest'` PASS
  - `./gradlew test` PASS
  - `terraform -chdir=infra/gcp-loadtest fmt -check` PASS
  - `terraform -chdir=infra/gcp-loadtest validate` PASS outside sandbox after provider handshake failed inside sandbox
  - `git diff --check` PASS
- 다음 GCP:
  - run id: `20260509-rolling-restart-gate-split`
  - 기대: correctness/drainTermination/cleanup PASS, performance/postStopFreshness는 PASS 또는 PARTIAL로 분리 기록

### GCP Runner Status

- `openchat-gcp-test-runner` subagent를 두 번 호출했지만, 두 시도 모두 `RESULT_DOC`를 생성하기 전에 장시간 응답이 없어 shutdown했다.
- GCP 리소스 생성 전 단계에서 멈춘 것으로 보고, 현재 작업은 로컬 구현/검증 완료 및 GCP 실행 대기 상태로 둔다.
- 다음 실행은 같은 run id `20260509-rolling-restart-gate-split`로 다시 시도한다.

### GCP Verification Complete: Rolling Restart Gate Split

- runner: `openchat-gcp-test-runner` subagent
- run id: `20260509-rolling-restart-gate-split`
- result doc: `docs/results/gcp/GCP-load-결과-20260509-rolling-restart-gate-split.md`
- status: PASS
- GCS prefix: `gs://openchat-loadtest-openchat-495102/runs/20260509-rolling-restart-gate-split/`

Result:

- validation gates:
  - correctness `PASS`
  - drainTermination `PASS`
  - performance `COLLECTED`
  - postStopFreshness `COLLECTED`
- k6 exit code: `0`
- checks: `1504 pass / 0 fail`
- HTTP error rate: `0`
- WebSocket connect success: `1.0`
- route failure/fallback/mismatch: `0/0/0`
- sent/ack/DB rows: `110213 / 110213 / 110213`
- post-stop sent/ack/DB rows: `3026 / 3026 / 3026`
- rolling restart: `complete`
- terminationAllowed: `true`
- delivery evidence: complete
- GCP stop evidence: `gcpStoppedCount=1`
- cleanup: RUN_ID VM/disk/network `0/0/0`

Interpretation:

- Phase 5 rolling restart correctness와 drain/termination safety는 PASS로 닫을 수 있다.
- ACK/freshness는 canonical rolling restart mini-soak에서 hard fail이 아니라 `COLLECTED` gate로 분리됐다.
- 이전 `throttled-default` 실패는 운영 correctness 실패가 아니라 성능/freshness SLO와 post-stop probe 목적이 단일 k6 exit code에 섞인 검증 기준 문제였다.
- 다음 성능 작업은 rolling restart correctness가 아니라 별도 freshness SLO profile에서 다룬다.

## Progress Update: Realtime Ops Docs Structure

- 상태: 문서 구조 정리 완료
- 이유:
  - Phase 5 이후 Phase 6를 찾기 어려웠고, freshness SLO 내용이 `OPENCHAT-CURRENT-WORK.md`, `phase5-rolling-restart-soak-plan.md`, decision log에 흩어져 있었다.
  - `OPENCHAT-REALTIME-OPS-ROADMAP.md`의 Phase 6가 예전 `Optional Infra Lifecycle Integration`으로 남아 있어 현재 대화에서 말한 Phase 6와 맞지 않았다.
- 변경:
  - `docs/phases/` 디렉토리를 추가했다.
  - Phase별 색인 문서 `docs/phases/README.md`를 추가했다.
  - 기존 `docs/phase5-rolling-restart-soak-plan.md`를 `docs/phases/phase-05-node-drain-rolling-restart.md`로 이동했다.
  - 새 Phase 6 문서 `docs/phases/phase-06-freshness-slo.md`를 추가했다.
  - `docs/OPENCHAT-REALTIME-OPS-ROADMAP.md`를 현재 기준으로 정리했다.
    - 현재 위치: Phase 5 완료, Phase 6 준비
    - Phase 6: Freshness SLO / Tail Latency
    - 기존 Optional Infra Lifecycle Integration은 Phase 7로 이동
  - `.gitignore`의 `docs/*` 전체 ignore를 제거하고, GCP 실행 결과 파일만 `docs/results/gcp/GCP-*-결과-*.md`로 무시하게 정리했다.
- 원칙:
  - 기존 결과와 run id는 삭제하지 않았다.
  - Phase 5 상세 기록은 이동만 했고 내용은 유지했다.
  - 할일/로드맵/phase 문서는 기본적으로 Git에 올릴 수 있게 했다.
  - 앞으로 "Phase 6 뭐였지?"는 `docs/phases/phase-06-freshness-slo.md`를 보면 된다.

### Follow-up: Docs Directory Cleanup

- 상태: 정리 완료
- 이유:
  - `docs/` top-level에 설계, 리포트, GCP 결과, PR 초안, 아이디어 문서가 모두 섞여 있어 필요한 문서를 찾기 어려웠다.
- 변경:
  - `docs/README.md`를 추가해 문서 시작점을 만들었다.
  - top-level에는 자주 보는 핵심 문서만 남겼다.
    - `OPENCHAT-REALTIME-OPS-ROADMAP.md`
    - `OPENCHAT-CURRENT-WORK.md`
    - `OPENCHAT-BRANCH-DECISION-LOG.md`
    - `OPENCHAT-EXPERIENCE-BANK.md`
    - `LLM-WORKFLOW-RULES.md`
  - 나머지는 목적별 디렉토리로 이동했다.
    - `architecture/`
    - `load-tests/`
    - `plans/`
    - `reports/`
    - `operations/`
    - `portfolio-star/`
    - `ideas/`
    - `pr/`
    - `mobile/`
  - 로컬 GCP 결과 문서는 `docs/results/gcp/`로 이동했다.
  - `.gitignore`는 `docs/**/GCP-*-결과-*.md`만 무시하도록 조정했다.
- 검증:
  - tracked markdown local link check PASS
  - `git diff --check` PASS

## Progress Update: Phase 6 Route Phase Metrics

- 상태: 구현 완료
- 배경:
  - `20260511-freshness-slo-baseline2`에서 `ws_route_partition_id`가 모두 `1`로 보여 hot room route가 한 partition에 몰린 것처럼 보였다.
  - 코드 확인 결과 기존 `ws_route_partition_id`는 초기 route가 아니라 reconnect route 처리 시점에만 기록되고 있었다.
  - 해당 run은 `gcp-realtime-2` drain 대상 partition이 `1`이었으므로 reconnect route partition이 모두 `1`로 보인 것은 정상 해석이다.
- 변경:
  - 초기 route 관측 metric을 추가했다.
    - `ws_initial_route_partition_id`
    - `ws_initial_route_node_total`
  - reconnect route 관측 metric을 추가했다.
    - `ws_reconnect_route_partition_id`
    - `ws_reconnect_route_node_total`
  - 기존 `ws_route_partition_id`, `ws_route_node_total`은 호환성 때문에 유지했다.
  - `scripts/test-k6-route-phase-metrics.sh`를 추가해 phase-specific route metric 정의와 기록 위치를 고정했다.
- 기대 효과:
  - 다음 GCP run부터 초기 분산 문제와 drain 이후 재배치 문제를 summary artifact에서 분리해서 볼 수 있다.
  - replacement node backlog가 route 분산 문제인지, drain/reconnect 이후 특정 owner로 트래픽이 이동한 결과인지 더 빠르게 구분할 수 있다.
- 검증:
  - `node --check k6/scenarios/11-mixed-room-workload-ramped.js` PASS
  - `bash scripts/test-k6-route-phase-metrics.sh` PASS
  - `bash scripts/test-k6-visible-freshness-metrics.sh` PASS
  - `bash scripts/test-rolling-restart-validation-gates.sh` PASS
  - `git diff --check` PASS
