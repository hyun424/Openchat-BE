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
- [GCP rolling restart gate split result](../results/gcp/GCP-load-결과-20260509-rolling-restart-gate-split.md)

## Update: Phase 6 SLO 기준 확정

작성일: 2026-05-11

이번 업데이트는 Phase 6 실행 전 기준을 고정하기 위한 문서화 작업이다. 코드, k6 scenario, Terraform profile은 아직 수정하지 않는다.

Phase 6의 핵심 판단은 "모든 visible message를 빠짐없이 낮은 latency로 받았는가"가 아니라, rolling restart와 drain이 있는 상황에서도 "observer가 방의 최신 상태를 충분히 빠르게 따라잡는가"로 둔다. 따라서 primary SLO는 `ws_visible_latest_freshness_ms`로 정의한다.

### Primary SLO

- primary metric: `ws_visible_latest_freshness_ms`
- 대상: active observer hot-room traffic
- hard gate: p95 `<= 1000ms`
- 의미:
  - observer가 수신한 batch 안에서 가장 최신 message의 `createdAt` 기준 freshness를 본다.
  - 사용자가 현재 방 상태를 따라잡고 있는지 판단하는 주 지표다.
  - live fanout cap 때문에 오래된 중간 message가 생략되더라도, 최신 상태 추적이 정상인지 분리해서 볼 수 있다.

### Supporting Metrics

다음 지표는 Phase 6에서 반드시 수집하고 결과 문서에 남기지만, v1 hard gate로는 사용하지 않는다.

- `ws_visible_latest_freshness_ms` p99
  - p95가 정상이어도 tail이 반복적으로 높으면 후속 개선 대상으로 본다.
- `ws_visible_freshness_ms` p95/p99
  - 수신한 visible message 각각의 freshness다.
  - backlog나 오래된 message가 batch에 섞이는 상황을 해석하기 위한 diagnostic metric이다.
- `ws_visible_gap_messages` p95/p99/max
  - live fanout cap으로 omitted된 message 수를 해석한다.
  - latest freshness가 낮더라도 gap이 너무 크면 사용자가 중간 흐름을 많이 놓치는 UX 문제가 남을 수 있다.
- ACK p95/p99
  - sender가 서버 처리 결과를 받는 round-trip 품질을 본다.
  - latest freshness와 함께 보면 send path와 observer path 중 어느 쪽 tail인지 분리할 수 있다.
- WebSocket handler/parse duration
  - k6 client 쪽 receive/parse pressure를 확인한다.
  - server-side freshness 병목과 load generator 병목을 구분하기 위한 supporting evidence다.

### Post-stop Freshness Policy

post-stop probe는 main rolling restart freshness SLO와 분리한다.

- main rolling restart freshness 검증에서는 post-stop freshness를 hard gate로 넣지 않는다.
- post-stop correctness hard gate는 유지한다.
  - stopped node로 신규 route되지 않아야 한다.
  - WebSocket connect가 성공해야 한다.
  - route failure/fallback/mismatch는 `0/0/0`이어야 한다.
  - sent == ack == DB rows가 유지되어야 한다.
- post-stop freshness는 settle delay가 있는 별도 profile에서 평가한다.
- settle window를 쓰는 경우, stop 직후 구간을 숨기지 않기 위해 "during stop"과 "after settle" freshness를 별도 기록한다.

### GCP Validation Plan

Phase 6 GCP 검증은 다음 순서로 진행한다.

1. Baseline
   - run id: `20260511-freshness-slo-baseline`
   - 목적: 현재 throttled drain 기본 정책에서 latest freshness p95가 `<= 1000ms`를 만족하는지 확인한다.
   - correctness gate는 Phase 5와 동일하게 유지한다.

2. Generator isolation
   - run id: `20260511-freshness-generator-isolation`
   - baseline이 freshness SLO를 만족하지 못하거나 k6 pressure가 의심될 때만 실행한다.
   - reconnect pacing, post-stop 설정, workload shape은 baseline과 맞추고, k6 VM capacity만 바꾼다.
   - 기존 `generator-check` 결과처럼 reconnect pacing까지 달라진 profile은 k6 pressure 격리 근거로 쓰지 않는다.

3. Improvement validation
   - run id: `20260511-freshness-slo-improvement`
   - baseline 또는 isolation 결과로 원인이 좁혀진 뒤, 최소 정책 변경 1개만 적용해 검증한다.
   - correctness, drain termination, cleanup gate는 완화하지 않는다.

### Decision Rationale

`ws_visible_freshness_ms`만 hard gate로 쓰면 live fanout cap, omitted message, backlog replay가 섞여 "최신 상태를 따라가는 능력"과 "모든 중간 message를 낮은 latency로 보는 능력"이 같은 실패로 합쳐진다. Phase 5에서 이미 correctness와 freshness를 분리한 것처럼, Phase 6에서도 freshness 내부의 의미를 분리한다.

`ws_visible_latest_freshness_ms`를 primary SLO로 둔 이유는 다음이다.

- 채팅방 observer에게 가장 중요한 1차 경험은 현재 대화 상태를 따라잡는 것이다.
- rolling restart/drain 중에도 최신 message가 빠르게 보이면 routing, reconnect, subscriber ownership은 사용자 관점에서 기본 동작을 유지한다.
- full visible freshness와 gap을 supporting metric으로 남기면, 최신 상태는 정상이어도 중간 흐름을 많이 놓치는 문제를 별도로 추적할 수 있다.

이번 결정의 trade-off는 명확하다.

- 장점:
  - 운영 correctness, 최신 상태 freshness, backlog/gap 문제를 분리해서 판단할 수 있다.
  - k6 exit code 하나로 서로 다른 문제를 섞어 실패시키지 않는다.
  - Phase 6에서 먼저 다룰 tail latency 범위가 명확해진다.
- 비용:
  - latest p95만 통과해도 p99나 visible gap 문제가 남을 수 있다.
  - full visible freshness를 hard gate에서 빼면, 중간 message 연속성 문제를 별도 후속 과제로 관리해야 한다.
  - post-stop freshness를 별도 profile로 분리하므로 GCP 검증 run이 추가될 수 있다.

### Plan Review Notes

계획 리뷰에서 지적된 보완점은 다음과 같다.

- 현재 k6 hard threshold는 `ws_visible_latest_freshness_ms`가 아니라 기존 `ws_visible_freshness_ms`를 보고 있어 Phase 6 의도와 충돌한다.
- 현재 `freshness-check` profile의 `k6_visible_freshness_p95_threshold_ms = 1000`은 full freshness threshold로 해석될 수 있으므로, 구현 단계에서 latest freshness 전용 threshold로 분리해야 한다.
- p99와 `ws_visible_gap_messages`를 숨기면 실제 UX tail을 과소평가할 수 있다. 따라서 v1 hard gate는 p95로 두더라도 p99/gap은 결과 문서에 반드시 남긴다.
- 기존 `generator-check` profile은 k6 VM capacity뿐 아니라 reconnect pacing과 post-stop 설정도 달라져 load generator pressure만 격리하지 못한다. Phase 6 isolation profile은 baseline과 동일한 조건에서 k6 capacity만 바꾸는 방식으로 다시 설계한다.
- post-stop freshness는 correctness probe와 섞지 않는다. 다만 settle window를 사용할 경우, stop 직후 지연이 감춰지지 않도록 during-stop metric과 after-settle metric을 모두 기록한다.

## Update: Phase 6 GCP PASS 및 Route Phase 해석 확정

작성일: 2026-05-11

`20260511-freshness-route-phase-metrics` GCP load run으로 Phase 6 primary SLO와 route phase 관측성을 검증했다.

결과:

- 최종 상태: `PASS`
- k6 exit code: `0`
- validation/correctness/drain/performance gate: `PASS`
- route failure/fallback/mismatch: `0 / 0 / 0`
- sent/ack/DB rows: `110233 / 110233 / 110233`
- rolling restart: `complete`
- `terminationAllowed=true`
- latest freshness p95/p99: `97ms / 612.84ms`
- ACK p95/p99: `107ms / 1547.68ms`
- full freshness p95/p99: `2245.65ms / 10251ms`
- visible gap p95/p99/max: `336 / 432 / 514`
- cleanup 후 RUN_ID VM/disk/network: `0 / 0 / 0`

이번 run의 핵심은 freshness 수치뿐 아니라 route 관측 해석을 바로잡은 것이다. 이전 `20260511-freshness-slo-baseline2`에서는 `ws_route_partition_id`가 모두 `1`로 보여 초기 route가 한 partition으로 몰린 것처럼 보였다. 확인 결과 기존 metric은 initial route가 아니라 reconnect route 위주로 기록되고 있었다. 이를 분리하기 위해 initial/reconnect route phase metric을 추가했고, 이번 GCP run에서 다음을 확인했다.

- initial route:
  - partition `0=100`, `1=100`
  - node `gcp-realtime-1=100`, `gcp-realtime-2=100`
- reconnect route:
  - partition `1=100`
  - node `gcp-realtime-3=100`

해석:

- 초기 연결은 partition/node 기준으로 균등 분산됐다.
- reconnect 집중은 drain 대상 `gcp-realtime-2`의 partition `1` 세션들이 replacement owner `gcp-realtime-3`로 이동한 결과다.
- 따라서 이전 `partitionId=1` 집중은 route hashing 실패가 아니라 관측 metric의 phase 구분 부족 때문에 생긴 해석 혼동이었다.

Phase 6 기준 판단:

- primary SLO인 hot active observer latest freshness p95 `<= 1000ms`는 `97ms`로 통과했다.
- latest p99도 `612.84ms`로 이번 run에서는 안정적이었다.
- full freshness와 visible gap은 아직 UX/stream continuity 관점에서 추적할 supporting metric이다. 다만 v1 hard gate는 latest freshness이므로 Phase 6 primary SLO는 통과로 본다.
- ACK p99 `1547.68ms`는 p95 `107ms` 대비 tail이 남아 있으므로 후속 성능 관찰 대상으로 둔다.

관련 결과 문서:

- [GCP freshness route phase metrics result](../results/gcp/GCP-load-결과-20260511-freshness-route-phase-metrics.md)
