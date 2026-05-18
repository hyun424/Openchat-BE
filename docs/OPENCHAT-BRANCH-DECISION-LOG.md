# OpenChat Branch Decision Log

> 작성일: 2026-05-08
> 목적: 자소서, 포트폴리오, 면접 답변에서 시간순으로 "왜 이 작업을 했고, 어떤 선택지를 비교했으며, 어떤 결과를 얻었는지"를 빠르게 확인하기 위한 의사결정 요약 문서다.
> 상세 설계와 실행 로그는 각 항목의 관련 문서 링크를 기준으로 본다.

## 읽는 방법

이 문서는 구현 상세가 아니라 **의사결정 흐름**을 기록한다.

각 항목은 다음 기준으로 정리한다.

- 발생한 상황
- 관측된 문제
- 검토한 선택지
- 결정과 이유
- 기대한 장점
- 감수한 트레이드오프
- 결과 수치
- 남은 한계
- 포트폴리오 문장

## 전체 흐름

OpenChat의 확장성 작업은 다음 순서로 진행됐다.

```text
단일 서버 WebSocket 채팅
-> 멀티 인스턴스 Redis Pub/Sub fan-out
-> DB 저장 우선 durability
-> hot-room 부하테스트와 측정 신뢰도 문제 발견
-> role-aware k6로 측정 경로 분리
-> active/passive fan-out으로 불필요한 delivery work 축소
-> room work와 pod budget 기준 수립
-> hot room partition으로 fan-out work 분산
-> partition state, drain, reconnect 기반 lifecycle
-> Redis control-plane으로 node-local session 한계 보강
-> workload summary와 자동 lifecycle smoke
-> dynamic realtime partition ownership
-> node drain으로 realtime node 종료 가능 상태 증명
```

핵심 메시지는 "서버를 많이 띄웠다"가 아니다.

> 대규모 WebSocket 채팅에서 병목은 사용자 수보다 active fan-out work와 ownership contract에 의해 결정된다. OpenChat은 이를 측정, fan-out 대상 축소, partition, reconnect/resync, node drain 순서로 풀어냈다.

---

## 1. GCP 부하테스트 환경 분리

### Branch / Work

- 작업 단위: GCP loadtest infrastructure
- 관련 문서:
  - [포트폴리오용 부하테스트/아키텍처 스토리](./architecture/portfolio-loadtest-architecture-story.md)
  - [초기 hot-room 부하테스트 요약](./load-tests/hot-room-loadtest-summary-for-portfolio.md)

### Situation

초기 hot-room 부하테스트는 로컬과 GCP 결과가 크게 달랐다. 200~500명 구간부터 RTT와 delivery ratio가 흔들렸고, 앱 서버 문제인지 Redis/MySQL 문제인지, 또는 k6 부하 생성기 리소스 문제인지 구분하기 어려웠다.

### Problem

앱, DB, Redis, k6가 같은 리소스 경계 안에서 실행되면 병목 위치를 잘못 판단할 수 있었다. 특히 WebSocket fan-out 테스트는 부하 생성기 자체도 많은 CPU와 네트워크를 사용하므로, 서버 병목과 테스트 환경 병목이 섞일 위험이 있었다.

### Options

- A. 로컬 docker-compose 기준으로만 테스트한다.
- B. 단일 GCP VM에 전체 스택을 올려 테스트한다.
- C. API, Realtime, Redis, MySQL, k6 runner를 GCP VM 단위로 분리하고 Terraform으로 재현 가능하게 만든다.

### Decision

C를 선택했다. 성능 수치보다 먼저 재현 가능한 실험 환경과 결과 수집 구조가 필요했기 때문이다.

### Expected Benefits

- 부하 생성기와 서버 리소스를 분리할 수 있다.
- 테스트 종료 후 VM cleanup으로 비용을 통제할 수 있다.
- GCS에 k6 summary, DB count, app log, metric snapshot을 모아 결과 재검토가 가능하다.

### Trade-offs

- Terraform, startup script, GCS 결과 수집 등 테스트 하네스 복잡도가 증가한다.
- 실행 시간이 길어지고 GCP quota/권한/cleanup 실패를 관리해야 한다.
- 테스트 인프라 문제와 앱 코드 문제를 구분하는 운영 절차가 필요해진다.

### Result

GCP에서 분리형 loadtest 환경을 만들고, 이후 모든 주요 WebSocket smoke/load 검증의 기준 환경으로 사용했다. 이 기반 위에서 1800명 role-aware hot-room, 1500명 active/passive, mixed-room, partition lifecycle, dynamic ownership, node drain smoke를 반복 검증했다.

### Portfolio Sentence

> 부하테스트 결과를 감으로 해석하지 않기 위해 API, Realtime, DB, Redis, k6 runner를 GCP VM 단위로 분리하고 Terraform으로 재현 가능한 테스트 환경을 만들었다. 이후 성능 수치뿐 아니라 DB row, ack, WebSocket 지표, cleanup 결과까지 함께 수집해 병목 분석의 신뢰도를 높였다.

---

## 2. Role-aware k6 측정 신뢰도 개선

### Branch / Work

- 작업 단위: role-aware k6 measurement reliability
- 관련 문서:
  - [k6 측정 신뢰도 개선과 1800명 단일방 재검증](./load-tests/role-aware-k6-measurement-reliability-20260504.md)

### Situation

1800명 단일방 hot-room 테스트에서 k6 ack p95와 visible freshness가 상승했지만, 서버 `ws.broadcast.lane_done`과 `ws.send.duration`은 상대적으로 낮았다. 이 상태에서는 서버 fan-out 병목인지 k6 관측 병목인지 단정하기 어려웠다.

### Problem

기존 k6 클라이언트는 많은 VU가 메시지를 보내면서 동시에 broadcast batch를 full parse하고, visible freshness, 중복, 누락, echo 검증까지 수행했다. 방 인원이 커질수록 k6 message handler와 JSON parse 비용이 측정 결과를 오염시킬 수 있었다.

### Options

- A. 서버 fan-out/send 튜닝부터 진행한다.
- B. k6 worker VM 사양만 올린다.
- C. k6 VU 역할을 sender, observer, validator로 분리해 입력 부하와 관측 비용을 분리한다.

### Decision

C를 선택했다. 결과가 나빠졌을 때 바로 서버 튜닝으로 들어가면 잘못된 병목을 최적화할 수 있기 때문이다.

### Expected Benefits

- 입력 TPS, DB TPS, logical delivery, physical frame, visible freshness를 분리해 볼 수 있다.
- k6 full parse 비용이 visible freshness를 오염시키는지 확인할 수 있다.
- 서버 병목인지 부하 생성기 병목인지 더 신뢰성 있게 판단할 수 있다.

### Trade-offs

- 전원 1초 1메시지 테스트와 입력 TPS를 직접 비교하기 어렵다.
- observer/validator 비율 설계가 필요하다.
- 최대 처리량 측정보다 병목 판정 신뢰도 개선에 초점이 맞춰진다.

### Result

1800명 main 실행에서 DB rows와 k6 ack 합계가 `201,538`건으로 일치했다. observer visible p95는 worker별 `191ms / 173ms`, sender ack p95는 `112ms / 108ms`였다. k6 handler duration p95와 JSON parse p95는 모두 `1ms`였고, 서버 `ws.broadcast.lane_done` p95 worst node는 `142.5ms`, `ws.send.duration` p95는 `0.180ms`였다.

이 결과는 "서버 성능이 갑자기 좋아졌다"가 아니라, 기존 full-parse 관측 방식이 결과를 흔들 수 있었고 role-aware 방식으로 병목 판정 신뢰도를 높였다는 의미다.

### Portfolio Sentence

> 1800명 WebSocket hot-room 결과가 흔들렸을 때 서버 튜닝으로 바로 넘어가지 않고, k6 클라이언트를 sender/observer/validator로 분리해 부하 생성과 관측 비용을 분리했다. 재측정에서 DB rows와 ack `201,538`건 일치, observer visible p95 `173~191ms`, server lane p95 `142.5ms`를 확인해 병목 위치를 근거 기반으로 판정했다.

---

## 3. Active Room Fan-out

### Branch / Work

- 작업 단위: active/passive fan-out
- 대표 근거:
  - PR `#5 perf: add active room fanout controls`
  - BE commit `49a495c`
  - FE E2E commit `83ef519`
- 관련 문서:
  - [Active Room Fan-out v1과 브라우저 E2E 검증](./load-tests/active-room-fanout-e2e-20260505.md)

### Situation

hot-room에서는 한 명이 보낸 메시지가 방의 모든 WebSocket 세션으로 fan-out된다. 하지만 1500명이 연결되어 있어도 모든 사용자가 실제로 해당 채팅방 화면을 보고 있는 것은 아니다.

### Problem

백그라운드 탭, 다른 화면, 잠시 이탈한 사용자에게도 full payload를 계속 보내면 서버를 늘리기 전에 줄일 수 있는 delivery work가 남는다. 반대로 passive 세션을 제외하면 사용자가 다시 돌아왔을 때 누락 메시지를 안전하게 복구해야 한다.

### Options

- A. 모든 WebSocket 세션에 계속 full payload를 보낸다.
- B. 서버가 임의로 비활성 세션을 추정한다.
- C. 클라이언트가 `room.active`, `room.passive`, heartbeat로 상태를 선언하고, passive 복귀는 `/messages/after`로 복구한다.

### Decision

C를 선택했다. 서버가 브라우저 화면 상태를 정확히 알 수 없으므로, 클라이언트 선언과 TTL을 조합하는 방식이 가장 명확했다.

### Expected Benefits

- 실제 보고 있는 세션만 full fan-out 대상으로 남길 수 있다.
- passive 세션은 DB catch-up으로 복구할 수 있다.
- 성능 최적화가 사용자 메시지 정합성을 깨지 않는지 E2E로 검증할 수 있다.

### Trade-offs

- 클라이언트가 active/passive control message를 보내야 한다.
- heartbeat와 TTL 정책이 필요하다.
- passive 세션에는 즉시 full message가 보이지 않으므로 복구 UX가 중요해진다.

### Result

1500명 active/passive GCP 실행에서 active/passive assigned는 `450 / 1050`이었다. sent/ack/DB rows는 `50,870 / 50,870 / 50,870`로 일치했고, passive unexpected message는 `0`이었다. ack p95 worst는 `23ms`, visible freshness p95 worst는 `117.5ms`, 서버 `ws.fanout.passive_omitted`는 `12,778,286`, `ws.send.failed`는 `0`이었다.

### Portfolio Sentence

> 서버를 더 늘리기 전에 사용자가 실제로 보고 있지 않은 세션으로 나가는 full payload를 줄였다. active/passive fan-out과 `/messages/after` 복구를 구현하고, 1500명 GCP 테스트에서 passive unexpected `0`, sent/ack/DB rows `50,870`건 일치, passive omitted `12,778,286`건을 확인했다.

---

## 4. Room Work와 Pod Budget 기준 수립

### Branch / Work

- 작업 단위: room work sharding model
- 관련 문서:
  - [4 vCPU 기준 Room Work Sharding 설계](./plans/room-work-sharding-plan-20260505.md)
  - [Realtime Chat Scalability Roadmap](./architecture/realtime-chat-scalability-roadmap-20260507.md)

### Situation

active/passive fan-out으로 불필요한 full delivery는 줄였지만, hot room 자체가 단일 pod가 처리하기 어려운 fan-out work를 만들 수 있다는 사실은 남아 있었다.

### Problem

방 인원수만으로 shard/partition 기준을 잡으면 실제 부하를 잘못 본다. 같은 1500명 방이라도 모두 active인지, 30%만 active인지에 따라 delivery work가 크게 달라진다.

### Options

- A. 방 인원수 기준으로만 hot room을 판단한다.
- B. 서버 CPU 사용률만 보고 scale-out한다.
- C. `room_work = input_msg_tps * active_sessions` 개념 모델과 pod budget을 기준으로 room tier와 partition recommendation을 정의한다.

### Decision

C를 선택했다. 실시간 채팅의 핵심 비용은 입력 TPS보다 fan-out delivery work에 의해 커지기 때문이다.

### Expected Benefits

- 작은 방 폭증과 단일 hot room 문제를 분리할 수 있다.
- K8s 전환 전에도 pod 하나의 처리 단위를 기준으로 설계를 설명할 수 있다.
- partition recommendation을 수치 기반으로 만들 수 있다.

### Trade-offs

- 현재 코드의 `roomWorkPerSecond`는 개념식 자체가 아니라 실제 outbound fan-out 관측치 proxy다.
- roomId를 metric tag로 넣지 않기 위해 summary/log 기반 관측 모델이 필요하다.
- pod budget 값은 실제 테스트를 통해 계속 보정해야 한다.

### Result

1500명 all-active는 약 `2,250,000 delivery/s`, 1500명 active/passive는 약 `189,000 delivery/s`로 재해석했다. active/passive로 work가 줄어도 4 vCPU/8GB pod 하나의 처리 단위는 아니므로 hot room partition 대상이라는 결론을 얻었다.

### Portfolio Sentence

> 단순 접속자 수가 아니라 `input_msg_tps * active_sessions`로 room work를 정의하고, 4 vCPU pod budget 기준으로 small/medium/hot room을 나눴다. 이 기준으로 1500명 active/passive도 약 `189,000 delivery/s` 수준이라 단일 pod가 아니라 hot room partition 대상임을 설명할 수 있게 됐다.

---

## 5. Hot Room Fan-out Partition

### Branch / Work

- 작업 단위: hot-room fan-out partition v3
- 관련 문서:
  - [Hot Room Fan-out Partition v3](./plans/hot-room-fanout-partition-v3-20260506.md)

### Situation

room work 기준으로 보면 단일 hot room은 한 realtime node의 fan-out lane을 독점할 수 있었다. 작은 방은 room shard ownership으로 묶을 수 있지만, 단일 hot room은 방 내부 fan-out 자체를 나눠야 했다.

### Problem

Redis Pub/Sub 기반 fan-out만으로는 hot room 메시지가 모든 realtime node 또는 특정 node에 과도한 work를 만들 수 있다. DB 저장/ack는 메시지당 1회여야 하지만, fan-out은 partition별로 나눠야 했다.

### Options

- A. hot room도 room shard 하나에 계속 둔다.
- B. 서버 수를 늘려 LB 분산에 맡긴다.
- C. `roomId + partitionId` 기준 Redis channel과 session partition assignment를 도입한다.

### Decision

C를 선택했다. WebSocket 세션은 long-lived connection이므로 LB만으로 기존 연결 fan-out work가 자동 분산되지 않는다. 애플리케이션이 partition 경계를 직접 가져야 했다.

### Expected Benefits

- hot room fan-out work를 여러 realtime node로 나눌 수 있다.
- DB 저장/ack는 메시지당 1회로 유지하고, delivery만 partition별로 분산할 수 있다.
- K8s 없이도 앱 레벨 scale-out 경계를 검증할 수 있다.

### Trade-offs

- route가 partitionId를 반환해야 한다.
- partition count와 session assignment 정책이 필요하다.
- 잘못된 partition count 결정은 일부 partition만 쓰는 문제를 만들 수 있다.

### Result

GCP 검증에서 DB rows와 k6 ack count는 small smoke `853`, 1500명 active/passive 실행 `50,853`으로 일치했다. 1500명 실행의 k6 worker별 ack p95는 `18ms / 17ms`, visible p95는 `163ms / 97.05ms`였다.

중요한 한계도 확인했다. `partition_count=4` 설정에도 접속 시점 traffic snapshot 때문에 실제로는 2개 partition만 활성화됐다. 이 결과가 다음 단계인 partition state와 lifecycle 설계의 근거가 됐다.

### Portfolio Sentence

> 단일 hot room의 fan-out work를 `roomId + partitionId` 기준 channel로 나눠 DB 저장/ack는 1회로 유지하면서 delivery work만 분산했다. GCP 검증에서는 ack/DB rows `50,853`건 일치를 확인했고, 동시에 4개 설정 중 2개 partition만 활성화되는 한계를 발견해 state 기반 partition lifecycle로 이어갔다.

---

## 6. Partition State, Drain, Redis Control-plane

### Branch / Work

- 작업 단위: hot-room partition autoscaling-aware v3.1/v3.2
- 관련 문서:
  - [Hot Room Partition Autoscaling v3.1 설계](./plans/hot-room-partition-autoscaling-v31-20260507.md)

### Situation

hot room partition v3는 fan-out을 나누는 기반을 만들었지만, 접속 시점 traffic snapshot으로 partition count를 결정하는 한계가 있었다. WebSocket은 long-lived connection이므로 Realtime node나 partition을 늘려도 기존 연결은 자동으로 이동하지 않는다.

### Problem

scale-up 후 신규 접속은 늘어난 partition으로 가야 하고, scale-down 대상 partition은 신규 접속에서 제외해야 한다. 또한 기존 연결은 무작정 끊는 것이 아니라 reconnect와 `/messages/after` 복구 경로로 이동해야 한다.

v3.1 GCP smoke에서는 더 중요한 한계도 확인했다. `drain/reconnect`를 API node 경유로 호출하면 API node의 in-memory `RoomSessionRegistry`만 조회하므로 `targetedSessions=0`이 나왔다. 실제 WebSocket 세션은 Realtime node에 있었다.

### Options

- A. traffic snapshot 기반 partition count를 유지한다.
- B. room별 `room_partition_state`를 두고 route/drain/complete를 state 기반으로 처리한다.
- C. Realtime owner node에 직접 internal API를 호출한다.
- D. Redis control-plane으로 reconnect command를 broadcast하고 각 realtime node가 자기 local session을 처리한다.

### Decision

B와 D를 선택했다. partition count와 drain 상태는 DB state로 관리하고, 실제 node-local session 조작은 Redis control-plane을 통해 realtime node가 직접 수행하게 했다.

### Expected Benefits

- route가 일시 traffic snapshot이 아니라 명시적 partition state를 따른다.
- draining partition에는 신규 접속을 보내지 않는다.
- API node와 Realtime node의 메모리 경계를 넘어서 reconnect를 전달할 수 있다.
- 메시지 누락은 DB sequence와 `/messages/after`로 복구할 수 있다.

### Trade-offs

- partition state, route version, drain 상태 관리가 필요하다.
- Redis Pub/Sub command는 durable command log가 아니므로 ack/retry는 후속 과제로 남는다.
- scale-down은 즉시 count를 줄이지 않고 drain 완료 조건을 기다려야 한다.

### Result

`20260507-v31-manual` GCP smoke에서 100 VU active/passive 조건으로 WebSocket connect success `100%`, HTTP error rate `0%`, sent/ack/DB rows `8,688 / 8,688 / 8,688`, ack p95 `18ms`, visible p95 `114ms`를 확인했다.

수동 operation API로 `/ws-route` 초기 `partitionCount=2`, scale-up `2 -> 4`, drain partition route 제외, drain complete 후 `partitionCount=3`까지 확인했다. 이후 Redis control-plane 보강으로 API node가 아니라 Realtime node가 자기 local session에 reconnect를 보내는 구조로 수정했다.

### Portfolio Sentence

> WebSocket scale-down은 pod를 바로 죽이는 문제가 아니라 `DRAINING -> reconnect -> catch-up -> complete` 순서의 운영 프로토콜 문제라고 판단했다. room partition state와 Redis control-plane을 도입해 route는 state를 따르고, 실제 reconnect는 세션을 가진 realtime node가 수행하게 했다.

---

## 7. Mixed-room Workload와 Workload Signal

### Branch / Work

- 작업 단위: mixed-room workload observer, workload signal delta
- 관련 문서:
  - [Room Workload Observer와 Rebalance Policy](./plans/room-workload-observer-and-rebalance-policy-20260507.md)
  - [Realtime Workload Cluster Summary](./plans/realtime-workload-cluster-summary-20260507.md)
  - [Mixed Room Service Workload](./load-tests/mixed-room-service-workload-20260507.md)

### Situation

단일 hot room 최대치만으로는 실제 서비스 부하를 설명하기 어렵다. 실제 서비스에서는 hot room 1개, medium room 여러 개, small room 다수가 섞이고, 작은 방 폭증과 hot room fan-out은 다른 방식으로 대응해야 한다.

### Problem

자동 scale-up/down을 바로 실행하기 전에, 클러스터 전체에서 어떤 room이 위험 후보인지 볼 수 있는 recommendation-only 관측 기반이 필요했다. 또한 send failure나 reconnect signal이 snapshot에는 있어도 cluster summary API에 노출되지 않으면 운영 판단 근거로 쓰기 어렵다.

### Options

- A. 단일 hot room 결과만 계속 개선한다.
- B. CPU/메모리 같은 node metric만 보고 scale 판단한다.
- C. Redis 기반 node workload snapshot을 모아 cluster summary, top rooms, recommendation, signal delta를 노출한다.

### Decision

C를 선택했다. 자동화 전에 사람이 검토 가능한 recommendation 근거를 먼저 고정해야 했기 때문이다.

### Expected Benefits

- small/medium/hot room이 섞인 상황에서 top room과 위험 후보를 구분할 수 있다.
- `actualDeliveryWork`, `conceptualRoomWork`, `scaleDecisionWork`를 분리해 볼 수 있다.
- send failure/reconnect delta를 summary에 노출해 자동화 금지 조건이나 위험 신호로 사용할 수 있다.

### Trade-offs

- 아직 자동 rebalance 실행이 아니라 recommendation-only 단계다.
- roomId는 metric tag가 아니라 summary/log로 다뤄야 한다.
- snapshot freshness와 stale node 처리가 필요하다.

### Result

`20260507-mixed-service-1500b` 실행에서 WebSocket connect success `100%`, sender ack p95/p99 `24ms / 30ms`, observer visible p95/p99 `116ms / 127ms`, sent/acked/DB rows `42,909 / 42,909 / 42,909`을 확인했다.

`20260507-signal-delta-smoke2`에서는 WebSocket connect `100/100`, HTTP error `0%`, sent/ack/DB rows `1,316 / 1,316 / 1,316`, `sendFailedDelta=0`, `reconnectSentDelta=0`을 확인했다.

### Portfolio Sentence

> 단일 hot room 숫자 경쟁에서 벗어나 hot/medium/small room이 섞인 workload를 만들고, Redis snapshot 기반 cluster summary로 top room, scale decision work, send failure/reconnect delta를 노출했다. mixed-room GCP 실행에서 ack/DB `42,909`건 일치와 visible p95 `116ms`를 확인해 자동화 전 판단 근거를 만들었다.

---

## 8. Auto Partition Lifecycle

### Branch / Work

- 작업 단위: auto partition lifecycle scaling
- 관련 문서:
  - [Dynamic Realtime Partition Ownership와 Node Drain STAR 기록](./portfolio-star/dynamic-realtime-partition-ownership-20260508.md)
  - 관련 GCP 결과 문서는 local ignored 결과 파일 기준으로 유지

### Situation

partition state, workload summary, Redis control-plane이 갖춰지자 hot room을 수동으로만 scale-up/down하는 한계가 남았다. 목표는 운영 기본값은 비활성으로 두되, GCP smoke profile에서는 hot room 자동 scale-up, reconnect redistribution, safe drain/scale-down을 검증하는 것이었다.

### Problem

자동 scale-up은 잘못 실행하면 partition churn을 만들 수 있고, scale-down은 기존 WebSocket 연결을 잃을 수 있다. 따라서 안정 조건, cooldown, stale node skip, send failure skip, drain session 0 확인 같은 보수적 조건이 필요했다.

### Options

- A. recommendation만 만들고 자동 실행은 하지 않는다.
- B. threshold 초과 시 즉시 partition count를 바꾼다.
- C. 안정 관측 window와 cooldown을 둔 lifecycle scheduler를 만들고, scale-down은 `DRAINING -> reconnect -> session 0 -> complete`로 처리한다.

### Decision

C를 선택했다. 자동화는 필요하지만 WebSocket long-lived connection 특성상 즉시 변경보다 안전한 lifecycle이 더 중요했기 때문이다.

### Expected Benefits

- hot room은 scale-up으로 partition count를 늘릴 수 있다.
- 기존 연결은 reconnect redistribution으로 새 route에 재분산할 수 있다.
- workload가 낮아지면 safe drain으로 partition count를 줄일 수 있다.

### Trade-offs

- scheduler, Redis lease/cooldown, drain progress, lifecycle metric이 필요해 복잡도가 커진다.
- scale-down은 즉시 완료되지 않고 세션이 비워질 때까지 기다려야 한다.
- 운영 기본값은 disabled로 두고 GCP smoke에서만 적극 활성화한다.

### Result

GCP lifecycle smoke에서 `1 -> 4` scale-up, reconnect redistribution, `4 -> 2` safe drain이 검증됐다. 이후 dynamic ownership smoke 안정화를 위해 k6 assignment preflight, GCP profile/env 전달, live publish queue timing metric, outbox mismatch marker metric을 보강했고 `20260508-dynamic-ownership-skill-smoke`에서 k6 exit code `0`, route failure/fallback/mismatch `0/0/0`, sent/ack/DB rows `16,826 / 16,826 / 16,826`, cleanup 후 RUN_ID VM 잔여 없음이 확인됐다.

### Portfolio Sentence

> hot room partition을 수동 operation으로만 두지 않고, 안정 window와 cooldown을 가진 lifecycle scheduler로 `1 -> 4` scale-up, reconnect redistribution, `4 -> 2` safe drain을 검증했다. 자동화는 운영 기본값 disabled로 두고, GCP smoke에서 route mismatch 0과 ack/DB 정합성을 확인해 안전 경계를 먼저 잡았다.

---

## 9. Dynamic Realtime Partition Ownership와 Node Drain

### Branch / Work

- branch: `feat-dynamic-realtime-partition-ownership`
- 대표 commit: `62ed5f0 feat: complete realtime node drain`
- 관련 문서:
  - [Dynamic Realtime Partition Ownership와 Node Drain STAR 기록](./portfolio-star/dynamic-realtime-partition-ownership-20260508.md)

### Situation

partition lifecycle까지 검증했지만, route가 partition count를 반환하는 것만으로는 충분하지 않았다. WebSocket 세션은 특정 realtime node 메모리에 있고, Redis subscriber도 node별로 존재한다. 따라서 route 결과, 실제 connected node, subscriber readiness, fan-out 경로가 같은 assignment를 따르는지 증명해야 했다.

### Problem

LB 뒤에서 단순히 여러 realtime node에 연결되는 것처럼 보여도, route node와 connected node가 다르거나 owner가 subscribe 준비 전이면 메시지 누락이나 drain 실패가 생길 수 있다. 또한 VM 종료나 rolling deploy를 고려하면 특정 realtime node를 안전하게 비우는 node drain 절차가 필요했다.

### Options

- A. 기존 LB 기반 route를 유지하고 node ownership 검증을 미룬다.
- B. GCP smoke v1에서는 VM direct `wsUrl`로 실제 connected node를 명확히 검증한다.
- C. EKS service discovery, rolling update, pod lifecycle까지 한 번에 구현한다.

### Decision

B를 선택했다. v1 목표는 최종 운영 플랫폼 도입이 아니라, 앱 레벨 assignment contract가 route, connection, subscriber readiness, reconnect, drain completion까지 일관되는지 증명하는 것이었기 때문이다.

### Expected Benefits

- route node와 실제 connected node 일치를 직접 확인할 수 있다.
- registry 기반 deterministic assignment로 테스트 가능성이 높다.
- node drain을 통해 "이 realtime node는 종료 가능하다"는 앱 레벨 신호를 만들 수 있다.
- 이후 EKS/MIG scale-in과 연결할 수 있는 선행 조건을 확보한다.

### Trade-offs

- VM direct `wsUrl`은 운영 LB 구조와 다르다.
- sorted modulo assignment는 최적 분산이나 최소 이동을 보장하지 않는다.
- EKS/MIG 자동 종료는 아직 구현 범위 밖이다.
- subscriber readiness는 registry/assignment로 관찰하고 검증하지만, durable command ack/retry까지 포함한 완전한 subscriber rebalance system은 후속 hardening이다.

### Result

`20260508-node-drain-smoke` GCP smoke에서 k6 exit code `0`, custom HTTP error rate `0.00%`, WebSocket connect success `145/145`, route failure/fallback/mismatch `0/0/0`, node drain reconnect controls `45`, sent/ack/DB rows `22,300 / 22,300 / 22,300`을 확인했다.

Drain 대상 node `gcp-realtime-1`은 active assignment에서 제외됐고, after-drain assignment는 4개 partition 모두 ready 상태로 `gcp-realtime-2` owner를 가리켰다. 최종적으로 drained node openSessions는 `0`이었고, cleanup 후 RUN_ID GCE VM 잔여도 없었다.

### Portfolio Sentence

> WebSocket scale-out을 단순 서버 증설이 아니라 ownership contract 문제로 보고, node registry 기반 assignment를 `/ws-route`, 실제 connected node, subscriber readiness, reconnect, drain completion에 일관되게 적용했다. GCP node drain smoke에서 route mismatch `0`, reconnect control `45`건, ack/DB `22,300`건 일치, drained node openSessions `0`을 확인해 realtime node 종료 가능 상태를 앱 레벨에서 증명했다.

### Update: Node Drain Status-only Hardening

node drain smoke 이후 남은 문제는 "drain이 된다"가 아니라, 운영자나 future orchestrator가 응답만 보고 다음 행동을 판단할 수 있는지였다.

검토한 선택지는 다음과 같았다.

- A. status만 명확히 하고 재시도/대기는 외부 runner가 판단한다.
- B. drain API가 내부에서 polling과 reconnect retry를 반복한다.
- C. background orchestrator를 만들고 operation state를 저장한다.
- D. grace timeout 이후 force close한다.

이번에는 A를 선택했다. HTTP 요청 안에서 긴 orchestration을 수행하거나 force close를 도입하기보다, EKS/MIG가 나중에 붙을 수 있는 앱 레벨 판단 계약을 먼저 고정하는 것이 안전하다고 봤다.

추가된 계약은 다음이다.

- `GET /api/internal/room-partition/nodes/{nodeId}/drain/status`
- `retryable`
- `nextAction`
- `readinessReason`
- `sessions_remaining`
- `not_draining`

또한 세션이 0이어도 replacement assignment가 준비되지 않았으면 `complete`로 보지 않도록 보강했다. 이로써 `complete`는 단순히 "현재 node 세션이 0"이 아니라 "replacement owner 상태까지 확인된 종료 가능 상태"에 가까워졌다.

trade-off는 남아 있다. 이 작업은 durable command log, background orchestrator, force-drain, EKS/MIG hook을 구현하지 않는다. 대신 현재 단계에서는 운영 판단을 명확하게 만들고, 후속 자동화가 붙을 수 있는 응답 계약을 준비하는 데 집중한다.

#### Validation Result

`20260508-node-drain-hardening-smoke` GCP smoke에서 status-only hardening의 응답 계약이 실제 node drain 흐름에서도 유지되는지 확인했다.

결과는 다음과 같다.

- k6 exit code `0`
- HTTP error rate `0.00%`
- WebSocket connect success `149/149`
- route failure/fallback/mismatch `0/0/0`
- node drain reconnect controls `49`
- sent/ack/DB rows `22,271 / 22,271 / 22,271`
- observer visible freshness p95 `122.45ms`
- target node `gcp-realtime-1`
- status transition `reconnect_published -> sessions_remaining -> complete`
- drained node openSessions `0`
- cleanup 후 RUN_ID GCE VM 잔여 없음

이 결과로 `retryable`, `nextAction`, `readinessReason`이 단순 API 필드가 아니라, 실제 GCP smoke에서 drain runner가 다음 행동을 판단할 수 있는 contract로 동작함을 확인했다. 특히 `POST drain` 응답은 `reconnect_published`, `nextAction=poll_status`, `readinessReason=ready`였고, 이후 status polling은 `sessions_remaining`, 마지막 progress는 `complete`, `nextAction=none`으로 수렴했다.

이번 결과의 의미는 "인프라 종료 자동화 완성"이 아니라 "인프라 종료 자동화를 붙이기 전에 앱이 종료 가능 상태를 명확히 판단할 수 있게 됐다"는 것이다. 다음 단계는 이 응답 계약을 사용하는 drain orchestrator 또는 운영 스크립트를 만들고, 그 다음에 MIG/EKS scale-in hook과 연결하는 것이다.

---

## 포트폴리오에서 사용할 최종 서사

OpenChat의 확장성 작업은 "몇 명을 처리했다"가 아니라 다음 판단 과정을 보여주는 프로젝트로 설명한다.

1. 먼저 부하테스트 환경을 분리해 측정값을 믿을 수 있게 만들었다.
2. 결과가 나빠졌을 때 서버 튜닝으로 바로 가지 않고 k6 관측 병목을 분리했다.
3. active/passive fan-out으로 실제 보고 있지 않은 세션의 delivery work를 제거했다.
4. 사용자 수가 아니라 `input_msg_tps * active_sessions` 기준으로 room work를 정의했다.
5. hot room은 partition으로 나누고, 작은 방은 shard ownership으로 다루는 방향을 잡았다.
6. WebSocket long-lived connection 특성 때문에 scale-down은 drain/reconnect/resync로 처리했다.
7. API node와 Realtime node의 메모리 경계를 Redis control-plane으로 넘겼다.
8. 마지막으로 dynamic ownership과 node drain을 검증해, 특정 realtime node를 안전하게 비운 뒤 종료 가능한 상태까지 증명했다.

## 자기소개서용 압축 문장

> 대규모 WebSocket 채팅에서 성능 문제를 단순 서버 증설로 해결하지 않고, 부하 생성기 관측 비용, active fan-out 대상, room work, partition ownership, reconnect/resync 경로를 단계적으로 분리했습니다. GCP 기반 검증에서 1800명 단일방 ack/DB `201,538`건 일치, 1500명 active/passive fan-out passive unexpected `0`, dynamic node drain route mismatch `0`, ack/DB `22,300`건 일치와 drained node session `0`을 확인하며, 측정 신뢰도와 운영 가능한 scale-out 구조를 함께 검증했습니다.

## 면접에서 강조할 경계

- "오토스케일링을 완성했다"보다 "오토스케일링이 안전하게 동작하기 위한 앱 레벨 protocol과 검증 기반을 만들었다"가 정확하다.
- K8s/EKS는 아직 도입하지 않았다. 대신 K8s가 맡을 node lifecycle 이전에 앱이 맡아야 할 route, ownership, reconnect, drain contract를 먼저 증명했다.
- 일부 결과는 성능 개선 수치가 아니라 측정 신뢰도 개선 또는 안전성 검증 수치다.
- Redis Pub/Sub control-plane은 v1에서 durable command log가 아니며, ack/retry/force-drain 정책은 후속 hardening이다.
