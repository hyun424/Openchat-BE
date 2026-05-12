# OpenChat Realtime Ops Roadmap

이 문서는 OpenChat realtime 운영성 작업의 큰 흐름을 놓치지 않기 위한 상태판이다.

상세 구현 계획, GCP 결과, PR 본문은 별도 문서에 둔다. 이 문서는 "지금 어디까지 왔고, 다음에 왜 그 작업을 하는가"만 빠르게 확인하는 용도다.

현재 실행 계획은 [OpenChat Current Work](OPENCHAT-CURRENT-WORK.md)에서 관리한다. Phase별 상세 문서는 [Realtime Ops Phases](phases/README.md) 아래에 둔다.

## Document Map

| Purpose | Document |
| --- | --- |
| 전체 로드맵 / phase 색인 | 이 문서 |
| 현재 진행 작업 | [OPENCHAT-CURRENT-WORK.md](OPENCHAT-CURRENT-WORK.md) |
| 시간순 의사결정 요약 | [OPENCHAT-BRANCH-DECISION-LOG.md](OPENCHAT-BRANCH-DECISION-LOG.md) |
| phase별 상세 문서 | [phases/README.md](phases/README.md) |
| Phase 5 상세 | [phases/phase-05-node-drain-rolling-restart.md](phases/phase-05-node-drain-rolling-restart.md) |
| Phase 6 상세 | [phases/phase-06-freshness-slo.md](phases/phase-06-freshness-slo.md) |
| Phase 6.9 상세 | [phases/phase-06-9-runtime-role-contract.md](phases/phase-06-9-runtime-role-contract.md) |

## Current Phase Index

| Phase | Status | Focus | Detail |
| --- | --- | --- | --- |
| Phase 1 | Done | Ownership, node-aware routing, node drain | Roadmap summary |
| Phase 2 | Done | Drain orchestrator, termination decision, GCP VM stop adapter | Roadmap summary |
| Phase 3 | Done | Reconnect command traceability, durable audit log | Roadmap summary |
| Phase 4 | Done | Reconnect delivery evidence hardening, strict termination guard | Roadmap summary |
| Phase 5 | Done | Rolling restart, mini-soak, validation gate split | [Phase 5](phases/phase-05-node-drain-rolling-restart.md) |
| Phase 6 | Planned | Freshness SLO, tail latency, user-facing delivery quality | [Phase 6](phases/phase-06-freshness-slo.md) |
| Phase 6.9 | Planned | Runtime role contract, AI worker readiness | [Phase 6.9](phases/phase-06-9-runtime-role-contract.md) |
| Phase 7 | Planned | Active room rolling AI memory, unread recent summary | [Phase 7](phases/phase-07-active-room-ai-memory.md) |
| Phase 8 | Later | Optional infra lifecycle integration | Roadmap summary |

## Goal

앱 레벨에서 realtime node lifecycle을 안전하게 제어하고, 나중에 GCP VM, MIG, EKS 같은 인프라 lifecycle과 연결 가능한 상태를 만든다.

핵심 목표는 단순히 서버를 많이 띄우는 것이 아니다.

- route 결과와 실제 WebSocket connected node가 일치한다.
- Redis subscriber owner와 room partition assignment가 일치한다.
- node drain 중 기존 세션이 reconnect로 안전하게 이동한다.
- node 종료 가능 상태를 앱이 명확히 판단한다.
- reconnect command 발행/처리 증거가 artifact와 DB에 남는다.
- GCP smoke/load/soak으로 이 계약을 재현 가능하게 검증한다.

## Current Position

현재 위치: **Phase 5 완료, Phase 6 준비**

완료된 주요 작업:

- Dynamic Realtime Partition Ownership
- Node-aware routing
- Dynamic subscriber ownership
- Node drain
- Node drain status contract
- Drain orchestrator v1
- Provider-neutral node termination decision contract
- GCP VM termination adapter
- Reconnect command traceability
- Durable Reconnect Command Log v1
- Reconnect Delivery Evidence Hardening
- Rolling Restart / Soak Validation
- Gate-based validation split

최근 검증:

- `20260509-reconnect-traceability-mini-soak3`
  - main sent/ack/DB rows `110,379 / 110,379 / 110,379`
  - post-stop sent/ack/DB rows `3,026 / 3,026 / 3,026`
  - route failure/fallback/mismatch `0 / 0 / 0`
  - reconnect controls `100`
  - GCP stop `RUNNING -> TERMINATED`
  - cleanup RUN_ID VM `0`
- `20260509-durable-reconnect-command-log-smoke`
  - k6 exit code single/post-stop `0 / 0`
  - HTTP error `0%`
  - WebSocket connect single/post-stop `149/149`, `20/20`
  - route failure/fallback/mismatch `0 / 0 / 0`
  - sent/ack/DB rows single `22,301 / 22,301 / 22,301`
  - post-stop sent/ack/DB rows `624 / 624 / 624`
  - reconnect command ids `2`
  - `reconnect_command_log` DB rows `2`
  - durable log `collectionStatus=collected`
  - missing/duplicate command ids `0 / 0`
  - GCP stop `RUNNING -> TERMINATED`
  - post-stop probe PASS
  - cleanup RUN_ID VM `0`
- `20260509-reconnect-delivery-hardening-smoke2`
  - k6 exit code single/post-stop `0 / 0`
  - route failure/fallback/mismatch `0 / 0 / 0`
  - sent/ack/DB rows single `22,294 / 22,294 / 22,294`
  - post-stop sent/ack/DB rows `624 / 624 / 624`
  - delivery evidence `complete=true`
  - strict termination `terminationAllowed=true`
  - GCP stop `RUNNING -> TERMINATED`
  - cleanup RUN_ID VM `0`
- `20260509-rolling-restart-gate-split`
  - k6 exit code `0`
  - correctness `PASS`
  - drainTermination `PASS`
  - performance `COLLECTED`
  - postStopFreshness `COLLECTED`
  - route failure/fallback/mismatch `0 / 0 / 0`
  - sent/ack/DB rows `110,213 / 110,213 / 110,213`
  - post-stop sent/ack/DB rows `3,026 / 3,026 / 3,026`
  - rolling restart `complete`
  - terminationAllowed `true`
  - cleanup RUN_ID VM/disk/network `0 / 0 / 0`

## Roadmap

### Phase 1. Ownership & Drain

Status: Done

목표:

- realtime node registry를 기준으로 room partition owner를 계산한다.
- `/ws-route`가 owner node로 안내한다.
- WebSocket connected node와 route node 일치를 검증한다.
- target realtime node를 drain 상태로 만들고 신규 route에서 제외한다.
- 기존 세션에 reconnect control을 보내고 drained node session count가 0이 되는지 확인한다.

주요 결과:

- route failure/fallback/mismatch `0 / 0 / 0`
- drained node openSessions `0`
- sent/ack/DB rows 일치

남은 한계:

- 실제 인프라 종료는 이 phase 범위가 아니었다.

### Phase 2. Drain Orchestration & Termination Contract

Status: Done

목표:

- `POST drain`과 `GET drain/status` 응답 계약을 외부 runner가 소비한다.
- `nextAction`, `retryable`, `readinessReason`을 기준으로 poll/retry/block을 자동 판단한다.
- provider-neutral termination decision command로 종료 가능 여부를 JSON contract로 판단한다.
- GCP VM stop adapter로 실제 VM stop과 post-stop probe를 검증한다.

주요 결과:

- orchestrator exitCode `0`
- `terminationAllowed=true`
- termination decision `ready`
- GCP stop `RUNNING -> TERMINATED`
- post-stop probe PASS

남은 한계:

- MIG/EKS lifecycle hook은 아직 연결하지 않았다.

### Phase 3. Command Traceability & Durable Audit Evidence

Status: Done

목표:

- reconnect control command에 commandId를 부여한다.
- GCP artifact가 attempted command ids, last command id, reconnect controls를 보존한다.
- Durable Reconnect Command Log v1로 commandId 단위 DB audit row를 남긴다.
- durable log는 audit/diagnosis evidence로 두고, termination hard gate에는 넣지 않는다.

주요 결과:

- reconnect command ids 존재
- `reconnect_command_log` DB rows 존재
- `missingCommandIds=0`
- `duplicateCommandIds=0`
- termination decision에 `sourceDurableReconnectCommandLog` 보존

남은 한계:

- v1은 delivery guarantee가 아니다.
- DB row가 있다고 해서 client reconnect 완료를 의미하지 않는다.
- subscriber handling evidence를 termination decision에 적극적으로 쓰지는 않는다.
- Redis Streams, reconnect outbox, client ack store는 후속 후보로 남긴다.

### Phase 4. Reconnect Command Delivery & Drain Evidence Hardening

Status: Done

목표:

- 발행된 reconnect command가 기대한 handler node에서 실제 처리됐는지 evidence로 계산한다.
- strict drain evidence mode를 optional로 제공한다.
- command log retention/cleanup 정책을 최소 구현한다.

세부 계획:

- Plan 1. Delivery Evidence
  - expectedHandlers 계산
  - actualHandlers 조회
  - missingHandlers / failedHandlers 계산
  - commandId별 handling summary 생성
  - orchestrator artifact 확장
- Plan 2. Strict Drain Evidence Mode
  - 기본 off
  - strict on이면 missing/failed handler가 있을 때 termination decision block
  - strict off이면 기존 audit-only behavior 유지
- Plan 3. Retention / Cleanup
  - retention 설정
  - cleanup method 또는 internal command
  - 기본 disabled 또는 긴 retention

결과:

- `20260509-reconnect-delivery-hardening-smoke`는 correctness evidence는 통과했지만 ACK tail latency threshold로 k6 exit `99`가 발생했다.
- 같은 HEAD로 재실행한 `20260509-reconnect-delivery-hardening-smoke2`는 PASS했다.
- strict delivery evidence가 termination decision에 연결될 수 있음을 확인했다.
- cleanup 후 RUN_ID VM 잔여 `0`을 확인했다.

### Phase 5. Rolling Restart / Soak Validation

Status: Done

상세 문서: [Phase 5 Node Drain / Rolling Restart](phases/phase-05-node-drain-rolling-restart.md)

목표:

- 단일 node drain/stop이 아니라 rolling restart 흐름을 검증한다.
- 장시간 실행에서 reconnect, session registry, subscriber assignment, DB command log가 누적 문제 없이 유지되는지 본다.

검증 후보:

- rolling restart smoke
- mini-soak
- 1시간 soak

Soak에서 볼 것:

- memory/connection leak
- Redis subscriber 누락
- reconnect retry 누적
- command log 누적과 cleanup
- p95/p99 장기 악화
- sent/ack/DB rows 장기 일치

결과:

- `20260509-rolling-restart-gate-split` PASS
- correctness `PASS`
- drainTermination `PASS`
- route failure/fallback/mismatch `0 / 0 / 0`
- sent/ack/DB rows `110,213 / 110,213 / 110,213`
- rolling restart `complete`
- terminationAllowed `true`
- cleanup RUN_ID VM/disk/network `0 / 0 / 0`

현재 해석:

- rolling restart 운영 correctness와 drain/termination safety는 닫을 수 있다.
- ACK/freshness는 canonical rolling restart에서 hard fail이 아니라 `COLLECTED` gate로 분리했다.
- 남은 freshness/tail latency는 Phase 6에서 별도 SLO 작업으로 다룬다.

### Phase 6. Freshness SLO / Tail Latency

Status: Planned

상세 문서: [Phase 6 Freshness SLO](phases/phase-06-freshness-slo.md)

목표:

- 사용자 체감 freshness SLO를 명확히 정의한다.
- rolling restart correctness와 performance/freshness SLO를 분리해서 검증한다.
- `ws_visible_freshness_ms`, `ws_visible_latest_freshness_ms`, `ws_visible_gap_messages`를 함께 해석한다.
- tail latency가 서버 fanout, reconnect burst, WebSocket send queue, k6 generator pressure, post-stop probe 간섭 중 어디서 생기는지 좁힌다.

검증 후보:

- freshness-check baseline
- generator pressure 비교
- post-stop settle-window probe
- reconnect pacing 변형

성공 기준 후보:

- route failure/fallback/mismatch `0 / 0 / 0`
- sent == ack == DB rows
- latest freshness p95/p99 목표치 충족
- full visible freshness와 gap metric은 별도 표로 해석
- cleanup RUN_ID VM/disk/network `0 / 0 / 0`

### Phase 6.9. Runtime Role Contract / AI Worker Readiness

Status: Planned

상세 문서: [Phase 6.9 Runtime Role Contract](phases/phase-06-9-runtime-role-contract.md)

목표:

- Phase 7 이후 AI/RAG worker를 붙이기 전에 runtime role 경계를 고정한다.
- single image + explicit runtime role 구조를 유지하면서 `api`, `realtime`, `ai-worker`, `combined`의 책임을 명확히 한다.
- WebSocket/subscriber/fanout 같은 realtime capability가 AI worker에 섞이지 않도록 Spring context test와 GCP startup contract로 검증한다.
- Docker image 분리는 지금 하지 않고, AI worker의 배포 주기/의존성/보안 경계가 실제로 갈라진 뒤 판단한다.

성공 기준 후보:

- `api` role에서 realtime-only bean이 뜨지 않는다.
- `realtime` role에서 WebSocket/subscriber/heartbeat가 정상 동작한다.
- `ai-worker` role에서 WebSocket/subscriber/fanout/outbox polling이 비활성화된다.
- invalid role은 startup fail-fast 된다.
- 기존 API/Realtime GCP smoke가 회귀 없이 통과한다.

### Phase 7. Active Room AI Memory / Unread Recent Summary

Status: Planned

상세 문서: [Phase 7 Active Room AI Memory](phases/phase-07-active-room-ai-memory.md)

목표:

- 모든 사용자 요청마다 LLM을 호출하지 않고, active room 단위로 rolling room memory를 유지한다.
- 사용자에게는 `읽지 않은 최근 메시지를 요약했어요.`라는 UX로 최근 unread 흐름을 빠르게 보여준다.
- AI 비용을 사용자 수가 아니라 active room/message segment 수에 비례하도록 만든다.
- Realtime 서버는 AI/RAG를 몰라야 하며, 요약 실패가 WebSocket fanout, ACK, drain/reconnect 안정성에 영향을 주면 안 된다.

v1 정책:

- active room: 최근 1시간 메시지 수 `>= 100`
- segment size: `100 messages`
- rolling memory: 최근 `3 segments`
- 노출 조건: `unreadCount >= 100` and rolling memory exists
- API: 요약 가능 여부와 결과 조회
- AI worker: active room detection, segment summary build, rolling memory update
- Python RAG server: signal extraction, topic segmentation, evidence selection, summary generation

남은 결정:

- scheduler polling vs message count threshold event
- 실제 Python RAG server를 바로 만들지, mock adapter로 contract를 먼저 고정할지
- summary signal type enum과 평가 fixture 범위

### Phase 8. Optional Infra Lifecycle Integration

Status: Later

목표:

- 앱 레벨 drain/termination contract를 실제 인프라 lifecycle hook과 연결한다.

후보:

- MIG scale-in hook
- EKS preStop / lifecycle hook
- Kubernetes readiness/drain 연계
- Redis Streams 또는 reconnect command outbox
- client reconnect ack store
- force-drain policy

이 phase는 포트폴리오 필수 범위는 아니다. Phase 4~5까지 안정적으로 끝나면 운영성 관점의 핵심은 충분히 설명 가능하다.

## Definition Of Done

Realtime ops 쪽을 포트폴리오 기준으로 "충분히 완성"이라고 말하려면 다음이 필요하다.

- route / connected node / subscriber owner 일치
- node drain completion evidence
- termination decision contract
- 실제 GCP VM stop 검증
- reconnect command publish evidence
- reconnect command handling evidence
- optional strict drain evidence guard
- command log retention 정책
- smoke PASS
- 최소 1회 mini-soak 또는 soak PASS
- cleanup RUN_ID VM `0`

## Update Rule

- 계획이 바뀌면 기존 항목을 삭제하지 말고 `Update`로 이유를 남긴다.
- GCP 결과는 숫자만 요약하고 상세 결과 문서로 링크한다.
- 오늘 할 세부 작업은 `docs/worklogs/` 아래 일자별 문서에서 관리한다.
