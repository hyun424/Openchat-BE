# OpenChat Realtime Ops Phases

이 디렉토리는 realtime ops 로드맵의 phase별 상세 문서를 모아두는 곳이다.

상위 색인은 [Realtime Ops Roadmap](../OPENCHAT-REALTIME-OPS-ROADMAP.md)에서 관리한다. 진행 중인 세부 작업은 [Current Work](../OPENCHAT-CURRENT-WORK.md)에 남기고, phase가 닫히면 이 디렉토리의 해당 phase 문서에 결과를 append한다.

## Phase Index

| Phase | Status | Document | Purpose |
| --- | --- | --- | --- |
| Phase 1 | Done | Roadmap summary | Ownership, node-aware routing, node drain 기본 검증 |
| Phase 2 | Done | Roadmap summary | Drain orchestrator, termination decision, GCP VM stop adapter |
| Phase 3 | Done | Roadmap summary | Reconnect command traceability, durable audit log |
| Phase 4 | Done | Roadmap summary | Delivery evidence hardening, strict termination guard |
| Phase 5 | Done | [phase-05-node-drain-rolling-restart.md](phase-05-node-drain-rolling-restart.md) | Rolling restart, mini-soak, gate-based validation |
| Phase 6 | Planned | [phase-06-freshness-slo.md](phase-06-freshness-slo.md) | Freshness SLO and tail latency 개선 |
| Phase 6.9 | Planned | [phase-06-9-runtime-role-contract.md](phase-06-9-runtime-role-contract.md) | AI/RAG worker 도입 전 runtime role contract 고정 |
| Phase 7 | Planned | [phase-07-active-room-ai-memory.md](phase-07-active-room-ai-memory.md) | Active room rolling AI memory and unread recent summary |
| Phase 8 | Later | Roadmap summary | Optional infra lifecycle integration |

## Update Rule

- 기존 결과와 결론은 삭제하지 않는다.
- 후속 결과는 `Update`, `Follow-up`, `Result` 섹션으로 append한다.
- phase 번호나 의미가 바뀌면 [Realtime Ops Roadmap](../OPENCHAT-REALTIME-OPS-ROADMAP.md)에 먼저 이유를 남긴다.
