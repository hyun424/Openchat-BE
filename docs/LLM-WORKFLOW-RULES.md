# OpenChat LLM Workflow Rules

이 문서는 OpenChat 작업을 LLM 또는 agent에게 맡길 때 문서 기록을 누락하지 않기 위한 규칙이다.

## Goal

작업 시작 전에는 기존 문맥을 확인하고, 작업 종료 전에는 포트폴리오/의사결정 기록에 남길 내용이 있는지 판단한다.

문서의 기준은 단순 작업 로그가 아니라 다음 질문에 답하는 것이다.

- 어떤 상황이 발생했는가?
- 무엇이 문제였는가?
- 어떤 선택지를 비교했는가?
- 무엇을 선택했고 왜 선택했는가?
- 어떤 장점과 trade-off를 예상했는가?
- 결과는 어땠고 수치로 무엇을 확인했는가?
- 남은 한계와 다음 작업은 무엇인가?

## Before Work

중요한 작업을 시작하기 전에 다음을 확인한다.

- `docs/OPENCHAT-BRANCH-DECISION-LOG.md`
- 관련 topic-specific docs
- 현재 브랜치/커밋/PR 상태
- 이번 작업이 기존 의사결정 흐름의 어디에 붙는지
- 작업 종료 후 문서 갱신이 필요할 가능성

시작 시점에 반드시 문서를 수정할 필요는 없다. 다만 기존 기록을 읽고, 작업의 위치와 성공 기준을 이해한 뒤 진행한다.

## After Work

작업 종료 전 다음을 판단한다.

- 문서 갱신이 필요한가?
- 갱신한다면 decision log, experience bank, topic-specific doc 중 어디가 맞는가?
- 갱신하지 않는다면 이유가 명확한가?
- final response에 문서 갱신 여부를 명시했는가?

## When To Update Docs

다음 중 하나라도 해당하면 문서 갱신을 우선 검토한다.

- 새 브랜치 또는 큰 기능
- 설계 방향 변경
- GCP smoke/load/soak 결과
- 성능 수치 변화
- 중요한 trade-off
- 의미 있는 실패 원인 분석
- 포트폴리오나 자기소개서에 쓸 수 있는 판단 과정
- 기존 계획과 다르게 구현된 부분

## When Docs Are Usually Not Needed

다음은 보통 문서 갱신 대상이 아니다.

- 단순 오타 수정
- 작은 테스트 보강
- 의사결정 변화 없는 내부 리팩터링
- 이미 기록된 계획을 그대로 구현한 경우
- 로그 메시지나 이름 정리처럼 포트폴리오 흐름을 바꾸지 않는 변경

그래도 작업자가 판단했을 때 기록 가치가 있으면 문서를 갱신할 수 있다.

## Append-Only Rule

문서는 기본적으로 append-only로 관리한다.

- 사용자의 명시적 요청 없이 기존 기록을 삭제하지 않는다.
- 기존 기록을 축약하거나 다른 결론으로 대체하지 않는다.
- 오래된 결과가 후속 결과로 대체되더라도 원래 run id, 수치, 당시 결론을 보존한다.
- 틀렸거나 오래된 내용은 삭제하지 않고 새 섹션으로 보완한다.

사용할 섹션 이름 예시:

- `Update`
- `Correction`
- `Follow-up`
- `Current Interpretation`
- `2026-05-08 Update`
- `Later Result`

중복 정리, 큰 구조 개편, 오래된 문서 삭제는 사용자 승인 후 진행한다.

## Documentation Targets

### `docs/OPENCHAT-BRANCH-DECISION-LOG.md`

시간순 의사결정 요약 문서다.

새 항목이나 기존 항목의 `Update`로 다음을 기록한다.

- 상황
- 문제
- 선택지
- 결정
- 기대 효과
- trade-off
- 결과 수치
- 남은 한계
- 포트폴리오 문장

### `docs/OPENCHAT-EXPERIENCE-BANK.md`

자기소개서와 면접 답변용 경험 카드다.

큰 기능, 강한 포트폴리오 소재, 반복해서 설명할 만한 경험만 추가한다. 기존 경험을 삭제하거나 대체하지 말고, 새 경험 항목 또는 보강 문장으로 추가한다.

### Topic-specific Docs

상세 설계, GCP 결과, 구현 근거, 한계를 기록한다.

예:

- workload summary
- partition lifecycle
- dynamic ownership
- node drain
- k6/GCP smoke/load result

기존 결과가 후속 실행으로 바뀌면 기존 결과를 지우지 않고 새 결과를 날짜와 run id로 추가한다.

## Final Response Checklist

작업 종료 응답에는 가능하면 다음을 포함한다.

- 구현 또는 검증 요약
- 실행한 테스트
- 문서 갱신 여부
- 갱신한 문서 목록 또는 갱신하지 않은 이유
- 남은 위험 또는 다음 작업
