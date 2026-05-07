# Realtime Chat Scalability Roadmap

작성일: 2026-05-07

## Summary

OpenChat의 대규모 채팅 확장 전략은 단순히 서버 수를 늘리는 방향이 아니라, 채팅방별 fan-out work를 정의하고 그 work가 특정 노드나 shard에 몰리지 않게 나누는 방향으로 간다.

지금까지의 부하테스트와 구조 개선은 다음 흐름으로 정리한다.

1. 단일 hot room의 최악 fan-out 기준선을 측정했다.
2. 실제로 보고 있지 않은 세션을 passive로 분리해 불필요한 full payload fan-out을 줄였다.
3. 작은 방 폭증이 전체 Realtime 노드로 번지지 않도록 room shard ownership 기반을 만들었다.
4. hot room은 room partition으로 fan-out work를 나눌 수 있게 했다.
5. autoscaling-aware partition state, drain, reconnect, resync 기반을 만들었고, 자동 적용은 아직 recommendation/manual 검증 단계로 둔다.

이 문서는 당장 구현할 상세 TODO가 아니라, 이후 작업과 포트폴리오 설명에서 사용할 큰 방향성 문서다.

## Current Foundation

### 1500 all-active hot room baseline

1500명이 모두 같은 방을 보고 있고, 모든 사용자가 1초에 1개 메시지를 보내는 조건은 OpenChat에서 매우 강한 최악 조건이다.

이 조건의 핵심 부하는 입력 TPS보다 WebSocket delivery work다.

```text
room_work = input_msg_tps * active_sessions
```

예를 들어 1500명이 모두 active이고 입력이 약 1500 msg/s라면, logical delivery work는 약 2,250,000 delivery/s 수준까지 커진다.

이 수치는 일반적인 "사용자 수"보다 채팅 서버가 실제로 처리해야 하는 fan-out 규모를 더 정확히 보여준다. 단, 이 식은 개념적 정의다. 현재 코드의 `roomWorkPerSecond`는 실제 fan-out 관측치인 `outboundFanoutPerSecond`를 proxy로 사용하고, 이후 필요하면 `input_msg_tps * active_sessions`를 별도 계산 지표로 분리한다.

### 1500 active/passive fan-out

Active Room Fan-out v1에서는 사용자가 실제로 보고 있는 채팅방에만 full WebSocket payload를 보낸다.

- active session: `room.active`, `room.active.heartbeat`를 보내고 full payload를 받는다.
- passive session: `room.passive` 이후 full payload를 받지 않는다.
- passive 상태에서 놓친 메시지는 다시 방을 볼 때 `/messages/after`로 복구한다.

1500명 방에서도 active 비율이 30%라면 full fan-out 대상은 전체 1500명이 아니라 약 450명으로 줄어든다.

이 작업의 의미는 "성능이 좋아졌다"가 아니라, 같은 방 인원에서도 실제 사용자 화면 상태에 따라 서버 delivery work를 줄일 수 있는 구조를 만든 것이다.

### v2 room shard ownership

기존 Redis Pub/Sub 구조에서는 작은 방이 많이 생겨도 모든 Realtime 노드가 모든 방 메시지를 받을 수 있었다.

v2는 방을 shard에 배정하고, Realtime 노드는 자신이 소유한 shard의 메시지만 처리할 수 있는 기반을 만들었다. 이 단계는 room shard routing 기반이며, 동적 rebalance 자동화까지 완료했다는 의미는 아니다.

- small/medium room은 여러 방을 하나의 room shard에 묶는다.
- 신규 room은 least-loaded shard에 배정한다.
- overloaded shard에는 신규 room 배정을 피한다.
- 기본값은 legacy 동작을 유지해 기존 기능을 깨지 않게 했다.

v2의 목적은 hot room을 쪼개는 것이 아니라, 작은 방 폭증이 전체 Realtime 노드로 번지는 것을 줄이는 것이다.

### v3 hot room fan-out partition

v3는 단일 hot room이 한 Realtime 인스턴스의 fan-out lane을 독점하지 않도록 `roomId + partitionId` 기준으로 fan-out을 나누는 구조다.

- DB 저장과 ack는 메시지당 1회만 수행한다.
- fan-out은 partition count만큼 Redis channel로 publish한다.
- 각 Realtime 노드는 자신이 소유한 partition channel만 subscribe한다.
- WebSocket session은 `partitionId`를 가진다.
- active/passive 필터는 partition 내부에서 그대로 적용한다.

이 구조는 K8s나 Swarm 없이도 애플리케이션 레벨에서 hot room fan-out work를 나눌 수 있게 한다.

### v3.1 autoscaling-aware partition state

v3.1은 scale-up, scale-down, drain 상황을 애플리케이션이 이해할 수 있게 `room_partition_state`를 추가했다. 이름은 autoscaling-aware지만, 현재 의미는 자동 확장 완료가 아니라 state 기반 route와 internal API를 통한 수동 검증 기반이다.

- route 응답은 현재 partition state를 기준으로 생성한다.
- 신규 접속자는 draining partition을 피한다.
- scale-down 중 기존 연결이 남아 있는 partition에는 publish를 유지한다.
- drain 대상 세션에는 `room.reconnect` control message를 보낸다.

핵심은 기존 연결을 갑자기 끊는 것이 아니라, 새 route로 이동시키고 `/messages/after`로 누락 메시지를 복구하게 만드는 것이다.

### v3.2 Redis control-plane and backpressure safety

GCP smoke에서 API node가 drain/reconnect 명령을 처리해도 실제 WebSocket session은 Realtime node의 local registry에 있다는 한계를 확인했다.

v3.2는 Redis Pub/Sub를 control-plane으로 사용해 API 명령을 모든 Realtime node에 broadcast하고, 각 Realtime node가 자기 local session에 대해서만 reconnect를 전송하게 했다.

또한 broadcast lane queue full 상황에서 조용히 메시지를 drop하지 않고 affected session을 `1013 broadcast_queue_overloaded`로 close하도록 했다.

이 정책의 의미는 다음과 같다.

- WebSocket 실시간 전송이 신뢰 불가능해진 세션을 계속 유지하지 않는다.
- 클라이언트는 reconnect 후 `/messages/after`로 복구한다.
- 최종 정합성은 DB 저장과 REST catch-up이 담당한다.
- queue full을 caller thread inline send로 우회해 순서를 깨지 않는다.

## Operating Model

### Small room

작은 방은 단일 Realtime partition을 독점하지 않는다.

여러 small room을 하나의 room shard에 묶고, room count, active sessions, room work, queue depth를 기준으로 신규 방 배정을 분산한다.

목표는 작은 방이 갑자기 많이 생겨도 모든 Realtime 노드가 모든 메시지를 받지 않게 하는 것이다.

### Medium room

Medium room은 room shard ownership으로 처리한다.

방 하나가 아직 partition 대상은 아니지만, 특정 shard의 room work가 증가하면 신규 room 배정을 다른 shard로 돌린다.

이 단계에서는 기존 연결 재배치보다 신규 방 배정 제어가 중요하다.

### Hot room

Hot room은 partition 후보가 된다.

판단 기준은 인원수가 아니라 `room_work`다.

```text
room_work = input_msg_tps * active_sessions
```

동일한 1000명 방이라도 실제 active 사용자가 100명인지 900명인지에 따라 서버가 처리해야 할 delivery work는 크게 다르다.

Hot room에서는 partition count를 늘려 fan-out work를 여러 Realtime 인스턴스에 분산할 수 있어야 한다. 현재는 state와 route 기반을 마련한 단계이며, 실제 자동 증감은 recommendation 정책과 mixed-room 검증 이후로 둔다.

### Critical room

Critical room은 단일 Realtime pod budget을 넘는 방이다.

이 단계에서는 다음 전제가 필요하다.

- partition route가 state 기반으로 동작한다.
- 신규 접속자는 overloaded/draining partition을 피한다.
- 기존 연결은 reconnect control로 이동시킬 수 있다.
- 이동 중 메시지는 `/messages/after`로 복구한다.
- queue full이나 send 불안정 상황은 silent drop이 아니라 reconnect/resync로 수렴한다.

Critical room을 안정적으로 운영하려면 단순 scale-out보다 재배치와 복구 경로가 더 중요하다. 현재 OpenChat은 이 경로를 검증할 수 있는 기반을 갖춘 상태이고, 완전 자동 rebalance 시스템으로 보지는 않는다.

## Design Principles

### DB is the source of final consistency

WebSocket은 빠른 전달 경로일 뿐 최종 정합성의 기준이 아니다.

메시지는 DB에 저장되고, 클라이언트는 `lastSeenSequence`와 `/messages/after`를 통해 누락분을 복구할 수 있어야 한다.

### WebSocket failure should become resync, not silent loss

과부하, partition drain, node drain, 네트워크 close는 모두 재접속과 catch-up으로 수렴해야 한다.

실시간 전송이 실패할 수 있다는 사실을 숨기지 않고, 복구 가능한 경로로 명시한다.

### Scale decisions should be based on work, not user count

사용자 수만으로 shard나 partition을 결정하면 부하를 잘못 본다.

중요한 값은 실제 입력 TPS와 active fan-out 대상 수다.

```text
room_work = input_msg_tps * active_sessions
```

다만 현재 구현의 `roomWorkPerSecond`는 이 개념식을 직접 계산한 값이 아니라, 실제 outbound fan-out rate 기반 proxy다. 문서와 포트폴리오에서는 개념 모델과 현재 관측 구현을 구분해서 설명한다.

### K8s or Swarm is not the first answer

K8s나 Swarm은 pod 배치와 lifecycle을 관리하는 도구다.

하지만 어떤 room을 어느 partition으로 보낼지, draining 중 기존 연결을 어떻게 이동시킬지, 이동 중 메시지를 어떻게 복구할지는 애플리케이션이 먼저 정의해야 한다.

따라서 현재 단계에서는 앱 레벨 partition, reconnect, resync 모델을 먼저 완성하고, 그 다음 운영 복잡도가 필요할 때 K8s/HPA를 검토한다.

## Next Direction

### 1. Mixed-room service workload 정의

단일 hot room만이 아니라 실제 서비스에 가까운 mixed-room 시나리오를 정의한다.

예시:

- hot room 1개
- medium room 여러 개
- small room 다수
- active/passive 비율 포함
- 방 생성이 갑자기 늘어나는 burst 포함

목표는 최대 인원 홍보가 아니라, shard ownership과 partition이 전체 서비스 부하를 어떻게 격리하는지 확인하는 것이다.

### 2. Room workload observer 정리

room별 workload를 직접 판단할 수 있는 관측 모델을 정리한다.

필요한 값:

- input msg/sec
- active sessions
- passive sessions
- delivery work/sec
- lane queue depth
- send failure reason
- reconnect/resync count 또는 이에 준하는 control-plane/close recovery 지표

고카디널리티 문제를 피하기 위해 metric tag에 `roomId`를 직접 넣지 않고, summary/max/count 중심으로 본다.

### 3. Rebalance decision 기준 문서화

자동화 전에 추천 기준을 먼저 문서화한다.

필요한 판단:

- 언제 SMALL에서 MEDIUM으로 보는가
- 언제 HOT으로 보는가
- 언제 partition count를 늘리는가
- 언제 drain을 시작하는가
- 언제 scale-down을 허용하는가
- 어떤 상태에서는 자동화하지 않고 operator 판단으로 남기는가

### 4. FE reconnect UX 완성

FE는 `room.reconnect`와 unexpected close를 조용히 처리하는 방향으로 구현했고, 이후 mixed-room/partition 상황에서 회귀 검증을 이어간다.

사용자에게 불필요하게 "서버 재배치 중" 같은 메시지를 노출하기보다, 일반적인 네트워크 지연처럼 보이게 하고 메시지는 `/messages/after`로 복구한다.

### 5. K8s/HPA 검토

K8s는 다음 조건이 생겼을 때 검토한다.

- Realtime 인스턴스 수가 수동 관리하기 어려울 정도로 늘어난다.
- partition owner 변경과 node lifecycle 관리가 반복 운영 문제가 된다.
- rolling deploy, node drain, health check, resource request/limit 관리가 필요해진다.
- HPA가 볼 수 있는 custom metric과 scale policy가 정리되어 있다.

그 전까지는 Docker Compose와 GCP VM 기반 smoke/loadtest로 앱 레벨 구조를 검증한다.

## Portfolio Message

이 프로젝트의 확장성 메시지는 "서버를 많이 띄워서 많은 인원을 받았다"가 아니다.

핵심은 다음이다.

> 대규모 WebSocket 채팅에서 병목은 입력 TPS보다 active fan-out work가 더 크게 지배한다는 점을 측정으로 확인하고, active/passive fan-out, room shard ownership, hot room partition, Redis control-plane, reconnect/resync 기반을 단계적으로 설계했다.

포트폴리오에서는 다음 흐름으로 설명한다.

1. 단일 hot room에서 fan-out work가 빠르게 커지는 문제를 측정했다.
2. 모든 접속자에게 full payload를 보내지 않고, 실제 보고 있는 세션만 active fan-out 대상으로 삼았다.
3. 작은 방 폭증이 전체 Realtime 노드로 번지지 않도록 room shard ownership 기반을 만들었다.
4. hot room은 room partition으로 나눠 여러 Realtime 인스턴스가 처리할 수 있게 했다.
5. scale-down, node drain, queue full 같은 운영 상황에서도 silent drop 대신 reconnect와 DB catch-up으로 복구하는 기반을 만들었다.

이 방향은 단순 성능 수치보다 3년차 백엔드 개발자에게 기대되는 판단, 즉 병목 정의, 장애 범위 축소, 복구 경로 설계, 운영 가능성까지 고려했다는 점을 보여준다.
