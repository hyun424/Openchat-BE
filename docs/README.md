# OpenChat Docs

이 디렉토리는 OpenChat 작업 기록을 찾기 쉽게 모아두는 곳이다.

## Start Here

| Purpose | File |
| --- | --- |
| 전체 realtime ops 로드맵 | [OPENCHAT-REALTIME-OPS-ROADMAP.md](OPENCHAT-REALTIME-OPS-ROADMAP.md) |
| 지금 진행 중인 작업 | [OPENCHAT-CURRENT-WORK.md](OPENCHAT-CURRENT-WORK.md) |
| 시간순 의사결정 로그 | [OPENCHAT-BRANCH-DECISION-LOG.md](OPENCHAT-BRANCH-DECISION-LOG.md) |
| 포트폴리오/면접 경험 정리 | [OPENCHAT-EXPERIENCE-BANK.md](OPENCHAT-EXPERIENCE-BANK.md) |
| LLM 작업 규칙 | [LLM-WORKFLOW-RULES.md](LLM-WORKFLOW-RULES.md) |

## Directories

| Directory | Contents |
| --- | --- |
| [phases/](phases/README.md) | 현재 로드맵 phase별 계획과 결과 |
| [plans/](plans/) | 기능/운영 설계안 |
| [load-tests/](load-tests/) | 부하테스트 설계와 분석 |
| [architecture/](architecture/) | 아키텍처/포트폴리오 스토리 |
| [reports/](reports/) | 과거 구현 리포트와 worklog |
| [operations/](operations/) | GCP runner, overnight spike, 운영성 실험 |
| [portfolio-star/](portfolio-star/) | STAR 형식 포트폴리오 기록 |
| [ideas/](ideas/) | 제품/기능 아이디어 |
| [pr/](pr/) | PR 설명 초안/요약 |
| [mobile/](mobile/) | 모바일 확인용 작업 정리 |
| `results/gcp/` | 로컬 GCP 실행 결과 문서. Git에는 올리지 않는다. |

## Rules

- 기존 기록은 삭제하지 않고 append-only로 보강한다.
- 오래된 결론이 바뀌면 `Update`, `Correction`, `Follow-up` 섹션으로 현재 해석을 추가한다.
- GCP 실행 결과 원문은 `docs/results/gcp/`에 로컬 보관하고, Git에는 요약/의사결정만 올린다.
- 새 작업을 시작할 때는 roadmap, current work, 관련 phase 문서를 먼저 확인한다.
