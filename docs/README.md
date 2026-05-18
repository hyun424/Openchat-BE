# OpenChat Docs

이 디렉터리는 OpenChat 백엔드 작업 기록을 찾기 쉽게 분류한 문서 인덱스입니다.
기존 기록은 삭제하지 않고, 현재 판단과 과거 증거를 구분해 보관합니다.

## Start Here

| Purpose | File |
| --- | --- |
| 시간순 의사결정 로그 | [OPENCHAT-BRANCH-DECISION-LOG.md](OPENCHAT-BRANCH-DECISION-LOG.md) |
| 포트폴리오/면접 경험 정리 | [OPENCHAT-EXPERIENCE-BANK.md](OPENCHAT-EXPERIENCE-BANK.md) |
| LLM 작업 규칙 | [LLM-WORKFLOW-RULES.md](LLM-WORKFLOW-RULES.md) |

## Directory Guide

| Directory | Contents |
| --- | --- |
| [architecture/](architecture/) | 아키텍처 정리와 포트폴리오용 시스템 설명 |
| [plans/](plans/) | 기능/운영 설계안과 의사결정 초안 |
| [load-tests/](load-tests/) | 부하테스트 설계, 결과, 병목 분석 |
| [reports/](reports/) | 구현 리포트, worklog, schema 실험 기록 |
| [operations/](operations/) | GCP runner, smoke runner, 운영 자동화 실험 |
| [portfolio-star/](portfolio-star/) | STAR 형식 포트폴리오 기록 |
| [ideas/](ideas/) | 제품/기능 아이디어 |
| [pr/](pr/) | PR 설명 초안/요약 |
| [mobile/](mobile/) | 모바일 확인용 작업 정리 |

## Rules

- 현재 작업에 필요한 문서는 root 또는 관련 디렉터리에 두고, 실행 로그 원문은 Git에 대량으로 올리지 않습니다.
- 오래된 결론이 바뀌면 기존 내용을 삭제하지 말고 `Update`, `Correction`, `Follow-up` 섹션을 추가합니다.
- 새 문서를 추가할 때는 위 디렉터리 중 가장 가까운 목적지에 넣고, 애매하면 `plans/`에 먼저 둔 뒤 나중에 승격합니다.
