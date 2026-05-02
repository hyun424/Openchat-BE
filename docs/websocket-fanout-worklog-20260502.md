# WebSocket Fanout Performance Worklog

## Current Focus

OpenChat hot room에서 WebSocket fanout 병목을 줄이는 작업을 진행 중이다.

현재 목표는 한 방에 많은 사용자가 동시에 접속하고 메시지를 보낼 때, 서버가 어떤 구간에서 밀리는지 계측하고 개선 방향을 검증하는 것이다.

## Work In Progress

- GCP 기반 부하테스트 환경 구성
- Redis subscriber와 fanout 실행 분리 실험
- WebSocket fanout lane 병렬화 실험
- 500명 ramped hot room 테스트 결과 비교
- room-local batching/coalescing 적용 가능성 검토
- room hot-state classification 설계 검토

## Findings So Far

- 단순히 WebSocket broadcast lane 수를 늘리는 것만으로는 500명 hot room 병목을 해결하지 못했다.
- Redis subscriber 분리 후 병목은 WebSocket broadcast queue 쪽으로 더 명확하게 이동했다.
- 다음 개선 방향은 방 단위 상태 판단, adaptive batching, slow session isolation 쪽으로 보는 것이 적절하다.

## Next Step

다음 단계는 전송 정책을 바로 바꾸기 전에 방 단위 지표를 수집하는 것이다.

- connected sessions
- join rate
- inbound messages per second
- outbound fanout per second
- delivery lag
- lane queue wait

이 지표를 기준으로 `NORMAL`, `WATCHED`, `WARM`, `HOT`, `SUPER_HOT` 상태를 나누고, 이후 상태별 전송 정책을 실험한다.
