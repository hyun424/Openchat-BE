# Phase 6 Freshness SLO / Tail Latency Plan

작성일: 2026-05-11

## Summary

Phase 6의 목표는 node drain, rolling restart, termination safety를 더 확장하는 것이 아니라, 그 위에서 사용자 체감 freshness와 tail latency를 별도 SLO로 정의하고 개선하는 것이다.

Phase 5에서 확인한 결론은 다음이다.

- rolling restart correctness는 통과했다.
- drain/termination safety는 통과했다.
- route failure/fallback/mismatch는 `0/0/0`이었다.
- sent/ack/DB rows는 일치했다.
- ACK/freshness는 canonical rolling restart gate에서 `COLLECTED`로 분리됐다.

따라서 Phase 6는 "node를 안전하게 비우고 종료할 수 있는가"가 아니라 "그 과정과 고부하 상황에서도 사용자가 최신 채팅 상태를 충분히 빠르게 보는가"를 다룬다.

## Problem

이전 GCP run에서 correctness는 정상인데 freshness tail이 흔들리는 구간이 있었다.

대표적으로 `20260509-rolling-restart-throttled-default`에서는 다음이 모두 정상이었다.

- route failure/fallback/mismatch `0/0/0`
- main sent/ack/DB rows `110220 / 110220 / 110220`
- post-stop sent/ack/DB rows `3026 / 3026 / 3026`
- rolling restart `complete`
- terminationAllowed `true`
- GCP stop `RUNNING -> TERMINATED`
- cleanup RUN_ID VM/disk/network `0/0/0`

하지만 latest freshness p99와 post-stop freshness threshold가 높아 단일 k6 exit code는 실패했다. 이후 Phase 5에서는 이를 correctness 실패가 아니라 validation 기준 결합 문제로 보고 gate-based validation으로 분리했다.

Phase 6는 그 다음 단계다. 이제 validation 기준은 분리됐으므로, 실제 freshness SLO를 어떻게 정의하고 어디서 tail이 생기는지 좁혀야 한다.

## Key Questions

- 사용자 체감 freshness SLO는 어떤 metric으로 볼 것인가?
- `ws_visible_freshness_ms`와 `ws_visible_latest_freshness_ms` 중 어떤 지표가 "최신 상태를 따라가는가"에 더 적합한가?
- `ws_visible_gap_messages`가 높을 때 full visible freshness를 실패로 볼 것인가, live fanout cap에 따른 의도된 생략으로 볼 것인가?
- rolling restart 중 freshness와 일반 hot-room freshness를 같은 threshold로 볼 것인가?
- post-stop probe freshness는 correctness probe의 일부인가, 별도 settle-window 성능 probe인가?
- tail latency가 서버 fanout, Redis, WebSocket send queue, reconnect burst, client receive backlog, k6 generator pressure 중 어디에서 생기는가?

## Metrics

기존 metric:

- `ws_visible_freshness_ms`
  - 수신한 visible message 각각의 `createdAt` 기준 freshness.
  - 오래된 visible message가 섞이면 p95/p99가 커질 수 있다.

추가된 metric:

- `ws_visible_latest_freshness_ms`
  - 수신 batch 안에서 가장 최신 `createdAt` 기준 freshness.
  - observer가 최신 room state를 따라가는지 보기 위한 지표다.

- `ws_visible_gap_messages`
  - incomplete realtime batch의 `omittedCount`.
  - live fanout cap 때문에 얼마나 많은 중간 메시지가 생략됐는지 해석하기 위한 지표다.

Phase 6에서는 이 세 지표를 함께 본다. 하나의 freshness 숫자로 결론 내리지 않는다.

## Work Plan

### Phase 6-1. SLO Definition

목표:

- freshness SLO를 metric별로 정의한다.
- canonical correctness profile과 strict freshness profile의 역할을 분리한다.

결정할 것:

- hot-room 최신 상태 SLO는 `ws_visible_latest_freshness_ms` 중심으로 볼지.
- full visible stream SLO는 `ws_visible_freshness_ms`로 별도 추적할지.
- `ws_visible_gap_messages`가 클 때 full freshness threshold를 어떻게 해석할지.
- post-stop probe는 route exclusion/message correctness만 hard gate로 두고 freshness는 settle 이후 별도 probe로 볼지.

출력:

- freshness SLO 표.
- canonical profile과 freshness-check profile의 acceptance 차이.

### Phase 6-2. Baseline Reproduction

목표:

- 현재 `room-partition-rolling-restart-freshness-check` profile로 freshness-only baseline을 얻는다.
- Phase 5 correctness gate와 독립적으로 성능 지표만 본다.

볼 것:

- ACK p95/p99
- latest freshness p95/p99
- full visible freshness p95/p99
- visible gap p95/p99
- sent/ack/DB rows
- route failure/fallback/mismatch

성공/실패 해석:

- route/data가 깨지면 correctness 회귀다.
- route/data는 정상인데 latest freshness만 높으면 delivery freshness 병목이다.
- latest freshness는 낮고 full freshness/gap만 높으면 SLO/metric 해석 문제 또는 live cap 정책 문제다.

### Phase 6-3. Bottleneck Isolation

목표:

- tail이 생기는 위치를 좁힌다.

후보:

- reconnect burst 이후 client receive backlog
- live fanout batch cap과 omitted message 정책
- WebSocket send queue
- Redis publish/subscriber 처리 지연
- DB/outbox/live publish timing
- k6 observer parse/receive pressure
- post-stop probe와 main workload 동시 실행 간섭

방법:

- generator pressure profile과 freshness-check profile을 비교한다.
- post-stop probe를 main workload와 분리하거나 settle window를 둔다.
- observer 수, send interval, reconnect batch limit을 바꿔 metric 변화를 본다.
- server-side send duration과 k6-side handler duration을 함께 본다.

### Phase 6-4. Policy Improvement

목표:

- 병목 원인에 맞는 최소 정책 변경을 한다.

가능한 선택지:

- reconnect pacing adaptive tuning
- post-stop probe settle window
- latest-first visible delivery policy
- observer/resync path 보강
- live fanout cap 기준 조정
- client-side reconnect resync guidance

원칙:

- correctness gate를 통과한 rolling restart 정책을 성급히 바꾸지 않는다.
- freshness 개선은 SLO와 원인 분리가 끝난 뒤에 한다.
- 운영 안전성과 사용자 체감 품질을 같은 실패로 섞지 않는다.

### Phase 6-5. GCP Validation

목표:

- strict freshness profile로 개선 전/후를 비교한다.

권장 run:

- baseline: `20260511-freshness-slo-baseline`
- improvement: `20260511-freshness-slo-improvement`

Acceptance 후보:

- route failure/fallback/mismatch `0/0/0`
- sent == ack == DB rows
- ACK p95/p99 목표치 충족
- latest freshness p95/p99 목표치 충족
- full freshness와 gap은 별도 표로 해석
- cleanup RUN_ID VM/disk/network `0/0/0`

## Non-goals

- Phase 5 rolling restart correctness를 다시 증명하는 것.
- MIG/EKS lifecycle hook 구현.
- Redis Streams 전환.
- reconnect command durable delivery 보장.
- UX 기능 추가.
- 앱 전체 아키텍처 재설계.

## Current Links

- [Realtime Ops Roadmap](../OPENCHAT-REALTIME-OPS-ROADMAP.md)
- [Phase 5 Node Drain / Rolling Restart](phase-05-node-drain-rolling-restart.md)
- [Current Work](../OPENCHAT-CURRENT-WORK.md)
- [Decision Log](../OPENCHAT-BRANCH-DECISION-LOG.md)
- [GCP rolling restart gate split result](../GCP-load-결과-20260509-rolling-restart-gate-split.md)
