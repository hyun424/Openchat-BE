# Phase 6.9 Runtime Role Contract / AI Worker Readiness

작성일: 2026-05-11

## Summary

Phase 6.9의 목표는 AI/RAG 기능을 바로 구현하는 것이 아니라, 나중에 채팅방 요약, embedding, 중요 메시지 판정 같은 AI service가 들어와도 기존 API/Realtime 운영 경계가 흔들리지 않도록 runtime role 계약을 먼저 고정하는 것이다.

현재 OpenChat은 이미 같은 backend image를 `APP_ROLE=api` 또는 `APP_ROLE=realtime`로 실행한다. GCP loadtest도 API VM과 Realtime VM을 나눠 검증한다. 따라서 이번 단계의 기본 방향은 Docker image를 즉시 분리하는 것이 아니라, **single image + explicit runtime role** 구조를 더 명확하게 만드는 것이다.

물리 image 분리는 RAG/AI worker의 배포 주기, 의존성, 보안 경계가 실제로 API/Realtime과 갈라졌을 때 후속으로 판단한다.

## Why Phase 6.9

Phase 6은 freshness SLO와 tail latency를 다룬다. Phase 7 이후에는 AI/RAG 기반 room summary 같은 사용자 기능을 붙일 수 있다.

이 사이에는 운영상 별도의 checkpoint가 필요하다.

- Realtime node는 WebSocket connection, subscriber ownership, drain/rolling restart 영향을 크게 받는다.
- API node는 HTTP request, DB transaction, route/internal query 중심이다.
- AI worker는 prompt, embedding, 요약 queue, model 비용 제어처럼 수정 빈도가 높고 의존성이 달라질 가능성이 크다.

따라서 AI 기능을 API/Realtime에 바로 섞기 전에, role별로 어떤 bean, scheduler, subscriber, endpoint가 켜져야 하는지 명확히 해야 한다.

## Role Contract

지원 role은 다음으로 고정한다.

| Role | Purpose | Enabled capability | Disabled capability |
| --- | --- | --- | --- |
| `combined` | local/dev 편의용 | API + Realtime | AI worker capability |
| `api` | REST/API, 인증, room/message write, route/internal query | HTTP API, DB write, route/admin query | WebSocket, realtime subscriber, fanout worker, outbox polling |
| `realtime` | WebSocket 연결과 realtime fanout | WebSocket, Redis subscriber, fanout, dynamic partition subscriber, node heartbeat, drain handling | 일반 API write path 최소화 |
| `ai-worker` | 향후 room summary/RAG/embedding worker | summary queue consumer, embedding, summarization, vector DB 연동 | WebSocket, realtime subscriber, fanout, route ownership |

운영/GCP profile에서는 `combined`를 쓰지 않는 것을 원칙으로 한다. `combined`는 local 개발과 단일 프로세스 smoke 편의를 위해 유지한다.

알 수 없는 role은 startup fail-fast 대상이다. role 오타가 조용히 `combined`처럼 동작하면 운영 검증이 무너질 수 있기 때문이다.

## Implementation Direction

### Role-aware condition

현재 일부 bean은 다음과 같은 string expression으로 role을 판단한다.

```java
@ConditionalOnExpression("'${app.role:combined}'.toLowerCase() != 'api'")
```

Phase 6.9에서는 이를 role-aware condition으로 통일한다.

예시 방향:

```java
@ConditionalOnRuntimeRole(capabilities = RuntimeCapability.REALTIME)
```

또는 동일한 효과를 내는 내부 condition을 둔다.

목표는 신규 AI worker bean을 추가할 때 annotation만 보고 "어느 role에서 켜지는지" 판단 가능하게 만드는 것이다.

### Capability 중심 분리

role 이름 자체보다 capability를 기준으로 조건을 둔다.

- `API`
- `REALTIME`
- `AI_WORKER`

이렇게 두면 나중에 `summary-worker`, `embedding-worker` 같은 세부 role이 생겨도 capability 조합으로 확장할 수 있다.

### GCP startup contract

`infra/gcp-loadtest/templates/app-startup.sh.tftpl`의 role 분기는 다음을 명확히 해야 한다.

- `api`: realtime 관련 env를 false로 둔다.
- `realtime`: WebSocket/subscriber/fanout 관련 env를 true로 둔다.
- `ai-worker`: WebSocket/subscriber/fanout/outbox polling을 false로 둔다.
- invalid role: 즉시 실패한다.

Phase 6.9에서는 AI worker VM을 실제로 만들 필요는 없다. 다만 startup script가 role을 이해하고, future profile에서 안전하게 켤 수 있는 자리만 만든다.

## Image Split Policy

이번 단계에서는 image를 나누지 않는다.

현재 선택:

- image: `openchat-backend` 하나
- deployment unit: role별 process/VM/pod
- runtime switch: `APP_ROLE`

나중에 image 분리를 검토할 조건:

- AI/RAG worker만 자주 배포해야 한다.
- AI SDK, vector DB client, model runtime 등 의존성이 API/Realtime과 크게 달라진다.
- Realtime rolling restart를 AI 실험 배포 때문에 흔들고 싶지 않다.
- worker에는 외부 port가 없어야 하고 API/Realtime과 보안 경계가 달라진다.
- 빌드 크기, 취약점 범위, cold start, 배포 검증 기준이 role별로 갈라진다.

즉, Phase 6.9는 image split을 미리 하지 않고 **언제든 split 가능한 role boundary**를 먼저 만든다.

## Test Plan

### Spring context tests

- `api` role:
  - WebSocket handler가 뜨지 않는다.
  - Redis subscriber container가 뜨지 않는다.
  - realtime fanout/outbox worker가 뜨지 않는다.
- `realtime` role:
  - WebSocket/realtime subscriber/heartbeat 관련 bean이 뜬다.
  - node drain/assignment 관련 bean이 조건에 맞게 뜬다.
- `ai-worker` role:
  - WebSocket/realtime subscriber/fanout/outbox worker가 뜨지 않는다.
  - 향후 summary worker capability만 켤 수 있는 구조를 가진다.
- invalid role:
  - application startup이 실패한다.
- `combined` role:
  - 기존 local 호환성을 깨지 않는다.

### GCP harness tests

- startup script syntax check
- role case가 `api`, `realtime`, `ai-worker`, invalid role을 구분하는지 확인
- 기존 dynamic ownership / rolling restart profile이 계속 `api + realtime`만 사용함을 확인
- role과 instance id가 artifact/log에 남는지 확인

### Local verification

- `./gradlew test --tests '*Role*'`
- `./gradlew test`
- `bash -n infra/gcp-loadtest/templates/app-startup.sh.tftpl`
- `terraform -chdir=infra/gcp-loadtest fmt -check`
- `terraform -chdir=infra/gcp-loadtest validate`
- `git diff --check`

### GCP validation

full load는 필요하지 않다. 기존 role split이 깨지지 않았는지만 smoke로 확인한다.

권장 run id:

- `20260511-runtime-role-contract-smoke`

성공 기준:

- API route 정상
- Realtime WebSocket 정상
- route failure/fallback/mismatch `0 / 0 / 0`
- sent == ack == DB rows
- cleanup RUN_ID VM/disk/network `0 / 0 / 0`

## Non-goals

- RAG/room summary 기능 구현
- embedding/vector DB 도입
- Docker image 물리 분리
- Gradle multi-module 분리
- API/Realtime endpoint 전체 재설계
- Phase 6 freshness SLO 정책 변경

## Decision Rationale

AI/RAG는 prompt, chunking, embedding model, summary policy, retry/idempotency, 비용 제어가 자주 바뀔 가능성이 높다. 이 변경이 Realtime WebSocket process를 흔들면 운영 리스크가 커진다.

하지만 지금 당장 image를 나누면 build/push/test matrix가 늘고, API/Realtime이 공유하는 DB entity, route contract, reconnect command contract의 version alignment 부담이 커진다.

따라서 Phase 6.9의 결정은 다음이다.

- 지금은 single image를 유지한다.
- runtime role contract를 명확히 한다.
- AI worker는 role로 먼저 준비한다.
- image 분리는 실제 배포 주기/의존성/보안 경계가 갈라진 뒤 진행한다.

이 순서가 현재 프로젝트의 검증 비용을 최소화하면서도 Phase 7 이후 AI 기능 확장을 막지 않는다.

## Update: Implementation and GCP Validation Result

작성일: 2026-05-11

Phase 6.9 구현은 `feat-phase6-9-runtime-role-contract` 브랜치에서 진행했다. 목표는 image를 분리하는 것이 아니라, single backend image 안에서 `api`, `realtime`, `ai-worker`, `combined` role이 어떤 runtime side effect를 가질 수 있는지 코드와 GCP startup contract로 고정하는 것이었다.

구현한 내용:

- `RuntimeRole`, `RuntimeCapability`, `@ConditionalOnRuntimeRole`을 추가해 string 기반 role condition을 capability 기반 condition으로 바꿨다.
- WebSocket, Redis subscriber, dynamic partition subscriber, realtime heartbeat, lifecycle scheduler, Kafka realtime consumer, graceful shutdown listener를 realtime capability에 묶었다.
- `api`와 `ai-worker` role에서도 의존성 연결은 깨지지 않게 하되, `RoomSessionRegistry`는 broadcast lane을 만들지 않는 disabled mode로 동작하게 했다.
- disabled registry에서 실수로 broadcast가 호출돼도 lane modulo 계산으로 실패하지 않도록 `WebSocketBroadcaster`가 no-op 처리와 low-cardinality metric을 남기게 했다.
- `infra/gcp-loadtest/templates/app-startup.sh.tftpl`에서 `APP_ROLE`을 normalize하고, `api`, `realtime`, `ai-worker`별 env override를 명시했다. 알 수 없는 role은 startup에서 실패한다.
- role별 Spring context 테스트와 startup shell contract 테스트를 추가했다.

검토한 trade-off:

- **즉시 image 분리**는 AI/RAG 의존성 분리와 보안 경계에는 유리하지만, 지금 단계에서는 build/push/test matrix와 GCP 검증 비용을 늘린다.
- **runtime role contract**는 같은 image를 유지하므로 배포 단순성이 유지되고, API/Realtime/AI worker의 side effect만 명확히 통제할 수 있다.
- 이번 단계에서는 runtime role contract를 선택했다. 실제 AI worker 의존성, 배포 주기, 보안 경계가 API/Realtime과 갈라지는 시점에 image split을 판단한다.

로컬 검증:

- `./gradlew test --tests '*Role*'` PASS
- `./gradlew test --tests '*RoomSessionRegistryTest' --tests '*Role*'` PASS
- `./gradlew test` PASS
- `bash scripts/test-runtime-role-startup-contract.sh` PASS
- `bash -n infra/gcp-loadtest/templates/app-startup.sh.tftpl` PASS
- `terraform -chdir=infra/gcp-loadtest fmt -check` PASS
- `terraform -chdir=infra/gcp-loadtest validate` PASS
- `git diff --check` PASS

GCP 검증:

- run id: `20260511-runtime-role-contract-smoke`
- profile: `room-partition-dynamic-ownership-smoke`
- result doc: `docs/results/gcp/GCP-smoke-결과-20260511-runtime-role-contract-smoke.md`
- status: PASS

결과:

- API VM startup role: `api`
- Realtime VM startup role: `realtime` 2대
- unsupported role fallback 로그: 없음
- assignment preflight: `activeNodes=2/2`, `readyAssignments=4/4`, `distinctOwners=2/2`
- k6 exit code: `0`
- HTTP error rate: `0%`
- WebSocket connect success: `100%`
- `/ws-route` success: `100/100`
- route failure/fallback/mismatch: `0/0/0`
- route node와 connected node mismatch: `0`
- sent/ack/DB rows: `16826 / 16826 / 16826`
- ACK p95/p99: `22ms / 35ms`
- latest freshness p95/p99: `43ms / 108ms`
- cleanup 후 RUN_ID VM/disk/network/firewall: `0/0/0/0`

해석:

- API와 Realtime VM을 같은 image로 실행하면서도 runtime role에 따라 WebSocket/subscriber/heartbeat side effect가 분리됨을 확인했다.
- Realtime node registry와 partition assignment는 role normalize 이후에도 정상 동작했다.
- Phase 7 RAG/room summary 작업은 이제 API/Realtime side effect를 오염시키지 않는 `ai-worker` role 위에 얹을 수 있다.
- 다만 아직 AI worker process를 실제 GCP profile로 띄운 것은 아니다. Phase 7에서 summary worker를 구현할 때 `ai-worker` role smoke를 별도 추가한다.
