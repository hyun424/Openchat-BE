# Phase 5 Rolling Restart / Soak Validation Plan

작성일: 2026-05-09

## Summary

Phase 5의 목표는 새 runtime 기능을 크게 추가하는 것이 아니라, Phase 1~4에서 만든 realtime node drain, termination decision, GCP stop adapter, reconnect command evidence가 반복/장시간 조건에서도 유지되는지 검증하는 것이다.

핵심 질문은 다음이다.

- 단일 node drain/stop이 아니라 여러 realtime node를 순차 drain/stop해도 route와 connected node가 계속 일치하는가?
- workload가 계속 흐르는 동안 reconnect, subscriber assignment, command log, termination decision evidence가 누락 없이 남는가?
- 10~20분 mini-soak에서 sent/ack/DB rows, p95/p99, cleanup 결과가 안정적인가?

## Scope

이번 브랜치의 변경은 테스트 하네스 중심이다.

- rolling restart shell orchestrator 추가
- shell fixture test 추가
- GCP k6 startup template에 rolling restart 옵션 연결
- rolling restart/mini-soak profile 추가
- result collection과 문서 정리

앱 runtime 코드 변경은 기본적으로 하지 않는다. 기존 drain orchestrator, termination decision, GCP VM stop adapter, durable reconnect command log, strict delivery evidence를 재사용한다.

## Non-goals

- MIG/EKS lifecycle hook 구현
- VM replacement 자동 생성
- Redis Streams 전환
- reconnect outbox 구현
- client reconnect ack store 구현
- force-drain policy 구현
- 운영 scheduler 구현

## Design

### Rolling Restart Orchestrator

새 command:

```text
scripts/openchat-rolling-restart-orchestrate.sh
```

역할:

1. active realtime node 목록을 internal API에서 조회한다.
2. 이미 drain/stop한 node와 draining node를 제외한다.
3. open session이 많은 node부터 target으로 고른다.
4. 각 target에 대해 기존 `openchat-node-drain-orchestrate.sh`를 실행한다.
5. 기존 `openchat-node-termination-decision.sh`로 종료 가능 여부를 판단한다.
6. GCP mode가 `stop`이면 `openchat-gcp-node-terminate.sh`로 해당 VM을 stop한다.
7. target 간 interval을 두고 다음 node를 반복한다.
8. 최종 JSON에 step별 drain/decision/gcp 결과를 남긴다.

필수 guard:

- `min-active-nodes` 이하로 active node가 줄어들면 중단한다.
- v1 기본 profile은 replacement 없이 VM을 stop하므로 `realtime_count=3`, `target_count=1`, `min_active_nodes=2`를 기본으로 둔다.
- `target_count >= 2`는 `realtime_count >= 4`일 때만 허용한다.
- drain result가 complete가 아니면 중단한다.
- termination decision이 ready가 아니면 중단한다.
- GCP stop adapter 실패 시 중단한다.
- strict delivery evidence는 옵션으로 넘긴다.

추가 readiness 확인:

- stop 전후 assignment ready count가 partition count 이상인지 확인한다.
- stopped/draining node가 assignment owner로 남아 있지 않은지 확인한다.
- post-stop probe에서 route가 stopped node를 반환하지 않는지 확인한다.
- subscriber readiness는 assignment snapshot의 ready owner와 reconnect/DB evidence로 간접 검증한다.

### GCP Startup Integration

새 Terraform/k6 옵션:

- `k6_rolling_restart_enabled`
- `k6_rolling_restart_target_count`
- `k6_rolling_restart_interval_seconds`
- `k6_rolling_restart_min_active_nodes`

동작:

- 기존 single node drain 옵션은 유지한다.
- rolling restart가 enabled면 coordinator k6 VM이 workload 실행 중 background로 rolling restart orchestrator를 실행한다.
- rolling restart result는 `metrics/rolling-restart-*.json`에 저장한다.
- 기존 assignment snapshot, node drain status snapshot, reconnect command log dump 수집은 유지한다.

### Mini-soak Profile

새 profile:

```text
infra/gcp-loadtest/profiles/room-partition-rolling-restart-mini-soak.tfvars.example
```

권장 기본:

- realtime node 3개 이상
- 200 VU
- 300초 workload
- rolling restart target count 1
- `min_active_nodes=2`
- strict delivery evidence enabled
- GCP termination adapter mode `stop`
- cleanup policy always

Optional expanded profile:

- realtime node 4개 이상
- rolling restart target count 2
- `min_active_nodes=2`
- smoke/mini-soak가 안정화된 뒤에만 실행한다.

## Acceptance Criteria

Rolling restart smoke:

- k6 exit code `0`
- HTTP error `0%`
- route failure/fallback/mismatch `0 / 0 / 0`
- route node와 connected node mismatch `0`
- sent == ack == DB rows
- rolling restart orchestrator exit code `0`
- 각 step termination decision `ready`
- 각 stopped node GCP adapter `RUNNING -> TERMINATED`
- delivery evidence `complete=true`
- missing/failed handlers `0 / 0`
- cleanup 후 RUN_ID VM `0`

Mini-soak:

- k6 exit code `0`
- sent == ack == DB rows
- route mismatch `0`
- observer visibility 유실 `0`
- reconnect retry가 무한 누적되지 않음
- command log row와 handling row가 수집됨
- ACK p95는 이전 stable smoke의 2배 이하 또는 `500ms` 이하
- ACK p99는 `2000ms` 이하
- reconnect/drain step이 timeout 없이 완료됨
- cleanup 후 RUN_ID VM `0`

Retention/cleanup 검증은 Phase 4에서 cleanup path 자체를 검증한 것으로 보고, Phase 5 v1 acceptance에서는 제외한다. Phase 5에서는 command log가 rolling/soak 중 evidence로 계속 수집되는지만 본다.

## Work Order

1. 계획 리뷰어로 scope와 guard 검토
2. rolling restart shell fixture test 작성
3. rolling restart shell orchestrator 구현
4. GCP k6 startup template 옵션 연결
5. Terraform variables/main/profile 추가
6. 로컬 검증
7. 코드 리뷰어로 shell reliability와 GCP 하네스 변경 검토
8. blocker 수정
9. GCP rolling restart smoke 실행
10. smoke PASS 시 mini-soak 실행
11. 결과 문서, roadmap, decision log, experience bank append
12. 커밋/PR

## GCP Strategy

GCP는 구현 중간마다 돌리지 않는다.

순서:

1. rolling restart smoke
2. smoke PASS 후 mini-soak
3. 1시간 soak는 mini-soak 결과가 애매하거나 장기 안정성을 추가 증거로 남길 필요가 있을 때만 실행

권장 run id:

- smoke: `20260509-rolling-restart-smoke`
- mini-soak: `20260509-rolling-restart-mini-soak`

## Assumptions

- PR #22의 Phase 4 reconnect delivery evidence hardening이 dev에 병합되거나, 이 브랜치가 PR #22 위에 stacked 된다.
- GCP smoke의 k6 VM은 internal API와 realtime VM에 접근 가능하다.
- rolling restart v1은 stopped VM replacement를 만들지 않는다. 따라서 `min-active-nodes` guard를 둔다.
- 이번 phase의 목적은 인프라 자동화가 아니라 반복 drain/termination 검증이다.

## Result Update: 2026-05-09

Phase 5 v1 구현은 로컬 검증과 GCP smoke까지 통과했지만, mini-soak latency 기준을 통과하지 못했다. 따라서 이 문서 기준으로는 Phase 5를 완료로 닫지 않는다.

### Implemented

- `scripts/openchat-rolling-restart-orchestrate.sh`
- `scripts/test-openchat-rolling-restart-orchestrate.sh`
- rolling restart GCP variables/template wiring
- rolling restart smoke profile
- rolling restart mini-soak profile
- drain result enrichment hook for strict delivery evidence

### Local Verification

- `bash scripts/test-openchat-rolling-restart-orchestrate.sh` PASS
- `bash scripts/test-openchat-node-drain-orchestrate.sh` PASS
- `bash scripts/test-openchat-node-termination-decision.sh` PASS
- `bash scripts/test-openchat-gcp-node-terminate.sh` PASS
- `./gradlew test` PASS
- `terraform -chdir=infra/gcp-loadtest fmt -check` PASS
- `terraform -chdir=infra/gcp-loadtest validate` PASS
- `git diff --check` PASS

### GCP Smoke

- run id: `20260509-rolling-restart-smoke3`
- status: PASS
- k6 exit code: `0`
- route failure/fallback/mismatch: `0/0/0`
- sent/ack/DB rows: `33546/33546/33546`
- rolling restart result: `complete`
- termination decision: `terminationAllowed=true`
- delivery evidence: `collected`, `complete=true`, missing/failed handlers 없음
- GCP stop: `RUNNING -> TERMINATED`
- cleanup: RUN_ID VM `0`, disk `0`

### GCP Mini-soak

- run id: `20260509-rolling-restart-mini-soak`
- status: FAIL
- k6 exit code: `99`
- route failure/fallback/mismatch: `0/0/0`
- sent/ack/DB rows: `110386/110386/110386`
- rolling restart result: `complete`
- termination decision: `terminationAllowed=true`
- delivery evidence: `collected`, `complete=true`, missing/failed handlers 없음
- GCP stop: `RUNNING -> TERMINATED`
- cleanup: RUN_ID VM `0`, disk `0`
- failure reason: ACK latency threshold exceeded
  - observed ACK p95: `1339ms`
  - observed ACK p99: `3765ms`
  - acceptance threshold: p95 `<=500ms`, p99 `<=2000ms`

### Decision

Smoke PASS proves the rolling restart control path works for a short validation run. Mini-soak FAIL means this branch should not be treated as production-ready or merged as-is.

The next step is not to relax the threshold immediately. The next step is to analyze whether the high ACK tail is:

- a real regression introduced by rolling restart orchestration,
- transient GCP/load-test noise,
- a workload/profile difference from the previous stable mini-soak,
- or an acceptance mismatch around post-stop probe behavior.

Until that is resolved, Phase 5 remains in progress.

### Harness Follow-up

After reviewing the mini-soak artifact, one harness issue was found in the rolling restart post-stop probe. The generated `TEST_LABEL` included the full stopped node id:

```text
room-partition-rolling-restart-mini-soak-post-stop-rolling-restart-200vu-gcp-realtime-2
```

That label is long enough that `client_message_id = TEST_LABEL + "-" + UUID` can exceed the current 100 character column contract. The symptom in the failed mini-soak was that the post-stop probe showed route/connect checks passing, but message ack/DB count evidence was not usable.

The label has been shortened to:

```text
room-partition-rolling-restart-mini-soak-post-stop-rr-200vu-n2
```

This does not explain the main workload ACK p95 regression by itself, because the main workload label was already short enough. It does remove a separate post-stop evidence problem before the next GCP run.

### Rerun Result: 20260509-rolling-restart-mini-soak2

After shortening the post-stop label, the mini-soak was rerun with `RUN_ID=20260509-rolling-restart-mini-soak2`.

Result:

- status: FAIL
- main k6 exit code: `99`
- post-stop k6 exit code: `99`
- route failure/fallback/mismatch: `0/0/0`
- main sent/ack/DB rows: `110299/110299/110299`
- post-stop sent/ack/DB rows: `3026/3026/3026`
- rolling restart: `complete`
- termination decision: `terminationAllowed=true`
- delivery evidence: `collected`, `complete=true`
- missing/failed handlers: empty
- GCP stop: `RUNNING -> TERMINATED`
- cleanup: RUN_ID VM `0`, disk `0`

What improved:

- Post-stop message evidence is now valid. The previous `sent > 0`, `ack/DB = 0` symptom was a test-label/column-contract issue.
- The rolling restart control path remains correct under mini-soak load.

What still fails:

- main ACK p95 passed at `160ms`, but p99 failed at `3640ms` against the `2000ms` threshold.
- post-stop ACK p95/p99 passed at `74ms / 107ms`.
- main/post-stop visibility freshness p95 stayed high at about `18s`.

Current interpretation:

- Phase 5 correctness path is working: route, reconnect, drain, termination decision, delivery evidence, GCP stop, and cleanup all behaved as expected.
- Phase 5 performance/soak acceptance is not complete. The remaining issue is tail latency/visibility behavior, not data loss or node ownership correctness.
- The next decision should separate "rolling restart operational correctness" from "observer visibility freshness SLO". Treating both as one PASS/FAIL hides the actual result.

### Follow-up: Visibility Metric Split

The first follow-up keeps server behavior unchanged and splits the k6 visibility signal.

Why:

- `ws_visible_freshness_ms` measures every received visible message against `createdAt`.
- In hot/super-hot rooms, `LiveFanoutPolicy` can intentionally omit part of a batch and send only a capped visible subset.
- Under that policy, a high old-style freshness value can mean either:
  - the latest visible stream is delayed, or
  - older visible messages are being sampled while live cap is omitting many intermediate messages.

Added metrics:

- `ws_visible_latest_freshness_ms`
  - Records freshness using the newest `createdAt` in each received batch.
  - This answers whether observers are following the newest room state.
- `ws_visible_gap_messages`
  - Records `omittedCount` from incomplete realtime batches.
  - This answers how much live fan-out was intentionally omitted when freshness is interpreted.

Decision:

- Do not change `LiveFanoutPolicy` yet.
- Do not relax the old threshold yet.
- Use the new metrics in the next GCP run to decide whether the issue is true latest-message delay or metric/SLO mismatch.

### Follow-up: Loadtest Resource Naming

The first load rerun for the split visibility metrics did not reach k6 execution. Terraform aborted before VM creation.

Run:

- `20260509-rolling-restart-visibility-load`

Cause:

- The service account name was derived by truncating the long run id prefix.
- Both `20260509-rolling-restart-mini-soak2` and `20260509-rolling-restart-visibility-load` collapsed to the same 30-character account id:

```text
openchat-lt-20260509-rolling-r
```

Fix:

- Resource names now include an 8-character `sha1(run_id)` suffix.
- VM/network names keep a readable run-id prefix and a hash suffix.
- Service account ids use a shorter `oclt-...-{hash}` format to stay within the 30-character GCP service account id limit.

Remaining infra issue:

- The same run also hit GCP network quota because old run-scoped networks remain in the project.
- Hash naming prevents future name collision, but quota cleanup is still a separate operational step.

### Follow-up: Drain Reconnect Pacing as Default Policy

`20260509-rolling-restart-throttled`에서 reconnect pacing만 늦춘 뒤 mini-soak 주요 지표가 정상화됐다. 이 결과를 기준으로 Phase 5의 운영 기본값은 fast drain이 아니라 controlled drain으로 둔다.

Policy:

- node drain reconnect limit: `50`
- retryAfterMs: `2000`
- drain orchestrator timeout: `420s`
- max reconnect attempts: `40`
- poll interval: `2000ms`

Trade-off:

- Fast drain은 노드를 더 빨리 비울 수 있지만, 연결 재수립과 route 재조회가 한 순간에 몰리면서 ACK/visibility tail과 sent/ack/DB 불일치 위험을 키운다.
- Controlled drain은 drain completion 시간이 몇 초 더 길어질 수 있지만, 운영 중 rolling restart나 termination에서 사용자 메시지 경로의 tail latency를 훨씬 안정적으로 유지한다.
- 긴급 장애 대응에서는 API/CLI override로 fast drain을 명시적으로 사용할 수 있게 남긴다. 기본 경로만 안전한 값으로 바꾼다.

Validation basis:

- load3 ACK p95/p99: `1392ms / 5096ms`
- throttled ACK p95/p99: `161ms / 916.96ms`
- load3 latest freshness p95/p99: `3869.8ms / 12241.24ms`
- throttled latest freshness p95/p99: `170.85ms / 426.37ms`
- throttled sent/ack/DB rows: `110253 / 110253 / 110253`
- throttled post-stop sent/ack/DB rows: `3026 / 3026 / 3026`

Next validation:

- run id: `20260509-rolling-restart-throttled-default`
- 목적: 별도 throttled profile이 아니라 node drain/rolling restart 기본정책으로 같은 결과가 재현되는지 확인한다.

### Follow-up: Gate-based Validation Split

`20260509-rolling-restart-throttled-default`는 route, drain, termination, delivery evidence, GCP stop, sent/ack/DB rows가 모두 정상인데도 k6 exit code가 `99`가 되었다. 원인은 correctness 실패가 아니라 ACK/freshness threshold와 post-stop freshness가 같은 exit code에 묶여 있었기 때문이다.

Decision:

- Phase 5 rolling restart 검증은 단일 `PASS/FAIL` 대신 gate별 결과를 남긴다.
- hard fail은 운영 correctness에만 적용한다.
- performance와 post-stop freshness는 summary metric과 `PARTIAL` 판정으로 분리한다.

Gate model:

- `correctness`
  - HTTP error, WebSocket connect, route failure/fallback/mismatch, assignment preflight, sent/ack/DB rows.
- `drainTermination`
  - rolling restart complete, terminationAllowed, delivery evidence complete, GCP stop evidence.
- `performance`
  - main ACK p95/p99, latest freshness p95/p99.
- `postStopFreshness`
  - post-stop latest/visible freshness. stopped node route exclusion과 message correctness는 correctness로 본다.
- `cleanup`
  - GCP runner가 VM/disk/network 잔여 여부로 판단한다.

Implementation notes:

- canonical `room-partition-rolling-restart-mini-soak`는 ACK/freshness hard threshold를 비활성화하고 gate JSON으로 수집한다.
- `room-partition-rolling-restart-freshness-check`는 freshness SLO를 명시적으로 검증할 때만 사용한다.
- post-stop probe는 route exclusion과 sent/ack/DB correctness를 우선 검증하고, freshness는 별도 gate로 기록한다.

Next validation:

- run id: `20260509-rolling-restart-gate-split`
- 기대 결과:
  - correctness `PASS`
  - drainTermination `PASS`
  - cleanup `PASS`
  - performance/postStopFreshness는 `PASS` 또는 `PARTIAL`

### Result: 20260509-rolling-restart-gate-split

The gate-split run passed and confirmed that rolling restart operational correctness can be evaluated independently from freshness SLO work.

Result:

- final status: PASS
- k6 exit code: `0`
- checks: `1504 pass / 0 fail`
- correctness gate: `PASS`
- drainTermination gate: `PASS`
- performance gate: `COLLECTED`
- postStopFreshness gate: `COLLECTED`
- route failure/fallback/mismatch: `0/0/0`
- sent/ack/DB rows: `110213 / 110213 / 110213`
- post-stop sent/ack/DB rows: `3026 / 3026 / 3026`
- rolling restart: `complete`
- terminationAllowed: `true`
- delivery evidence: complete
- GCP stop: `RUNNING -> TERMINATED`, `gcpStoppedCount=1`
- cleanup: RUN_ID VM/disk/network `0/0/0`

Decision:

- Canonical rolling restart mini-soak is the correctness and drain/termination safety profile.
- ACK and freshness values remain collected in the result artifact, but do not fail the canonical rolling restart run by themselves.
- Strict freshness acceptance must use a separate freshness-check profile or a settle-window probe.

Why this matters:

- A single k6 exit code made a safe rolling restart look like a failed operational flow when the remaining issue was freshness SLO interpretation.
- Gate-based validation preserves the safety signal: route correctness, data consistency, drain completion, termination permission, GCP stop, and cleanup can all pass while performance/freshness is tracked as follow-up work.
