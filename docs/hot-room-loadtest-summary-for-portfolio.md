# Hot Room Load Test Portfolio Summary

> 이 문서는 초기 로컬 hot-room 부하테스트 결과를 포트폴리오 형식으로 정리한 것이다.
> 최신 GCP 1800명 단일방 측정 신뢰도 개선 결과는 [k6 측정 신뢰도 개선과 1800명 단일방 재검증](./role-aware-k6-measurement-reliability-20260504.md)을 기준으로 본다.

## One-line Summary

HotChat으로 특정 방에 사용자가 몰리는 상황을 가정해 단일 방 WebSocket 부하테스트를 수행했고, 500 VU ramp 구간에서 p95 메시지 RTT가 46초까지 상승하는 fan-out 병목을 재현했다.

## Context

OpenChat은 사용자가 채팅방에 입장한 뒤 WebSocket으로 메시지를 주고받는 실시간 채팅 서비스다. HotChat 기능으로 특정 방이 상단에 노출되면 한 방에 사용자가 집중될 수 있으므로, 단일 인기 방에서 메시지 송수신 품질이 어디서 무너지는지 확인할 필요가 있었다.

이번 테스트의 목적은 단순히 최대 접속자 수를 확인하는 것이 아니라, 방 인원이 증가할 때 WebSocket RTT, 연결 성공률, broadcast 수신량, 중복 수신 여부를 측정해 다음 개선 우선순위를 정하는 것이었다.

## Load Test Setup

- Date: 2026-04-29 21:17~21:29 KST
- Environment: local macOS 15.5, OpenJDK 21.0.8
- Spring profile: `dev,loadtest`
- Infrastructure: Docker Compose MySQL, Redis, Kafka
- Application instances: 1
- Rooms: 1 hot room
- Scenario: all VUs enter the same room and send WebSocket messages
- Message rate: 1 message/user/sec
- Tool: k6
- Scenario file: `k6/scenarios/06-hot-room-fanout.js`

## Traffic Model

In a single chat room, one incoming message is broadcast to every connected session in that room. Therefore, if every user sends one message per second, output WebSocket sends grow roughly as `N * N`.

| VU | Inbound msg/sec | Expected fan-out sends/sec |
| ---: | ---: | ---: |
| 100 | 100 | 10,000 |
| 200 | 200 | 40,000 |
| 300 | 300 | 90,000 |
| 500 | 500 | 250,000 |

This was the main reason for testing a single hot room separately from normal REST or mixed traffic tests.

## Key Results

| Metric | Result |
| --- | ---: |
| Test duration | about 12 minutes |
| Max VU | 500 |
| Completed iterations | 886 |
| HTTP error rate | 0.03% |
| Check success rate | 99.97% |
| WebSocket connect success rate | 99.61% |
| WebSocket connect p50 | 4ms |
| WebSocket connect p95 | 6.6s |
| WebSocket connect p99 | 9.56s |
| Message RTT p50 | 120ms |
| Message RTT p95 | 46.0s |
| Message RTT p99 | 57.82s |
| Message RTT max | 71.32s |
| WebSocket messages sent | 129,567 |
| WebSocket messages received | 26,405,069 |
| Hot room unique received | 26,405,000 |
| Hot room duplicate received | 0 |
| Average broadcast receive rate | 36,670/sec |
| Received data | 7.1GB |

## Interpretation

The median latency was still acceptable, but tail latency collapsed. A p50 RTT of 120ms means many users could still experience normal delivery, while a p95 RTT of 46 seconds means a meaningful portion of users would see messages arrive extremely late.

The absence of duplicate messages suggests that Redis/Kafka duplicate delivery was not the primary issue in this run. The dominant symptom was delayed delivery, especially in p95/p99 latency.

The result points to a fan-out and WebSocket send bottleneck rather than an Outbox problem. Outbox can help with message event durability after DB commit, but this test showed that the first user-visible failure mode was real-time broadcast delay under a single hot room.

## Bottleneck Hypothesis

The current local fan-out path sends a message to every session in the room and waits until all send tasks complete. As room size grows, each incoming message creates work proportional to the number of sessions. When every user also sends messages, total send pressure grows close to `N * N`.

The observed p95/p99 latency increase is consistent with the broadcast executor and WebSocket send path becoming saturated. Once the broadcast queue fills up, slow sends can delay later messages and increase tail latency.

## What This Proves

This test proves that the project was not evaluated only through functional correctness. It was tested under a realistic failure mode for a chat service: many users entering one popular room and generating a high fan-out load.

The test also provided a concrete engineering decision point. Instead of prematurely prioritizing Outbox or Kafka durability work, the measured bottleneck showed that fan-out, backpressure, and slow session isolation should be improved first.

## Limitations

- The test was executed on a local single machine, so k6, Spring Boot, MySQL, Redis, Kafka, and the OS shared the same resources.
- The result is an overall k6 summary, not a precise per-stage time series.
- The test does not prove that 500 users is the exact breaking point.
- The test does not isolate DB, Redis, Kafka, JVM GC, and WebSocket send latency independently.
- The observed missed self-echo count should not be interpreted directly as message loss because messages still pending at connection close may not be recorded as RTT samples.

## Next Improvements

- Add metrics for broadcast executor queue depth, active worker count, send latency, send failures, and slow session closes.
- Replace synchronous all-session fan-out waiting with queue-based local fan-out.
- Add bounded session queues and slow client close/drop policy.
- Split large rooms into local fan-out shards.
- Re-run the same 500 VU scenario and compare p95/p99 RTT before and after.
- Run fixed-stage tests for 100, 200, 300, 500 VU to identify the first degradation point.

## Resume Bullet Draft

- Designed and executed a k6 WebSocket load test for a HotChat scenario where all users concentrate in a single room, modeling fan-out growth from 10,000 to 250,000 expected sends/sec.
- Reproduced a real-time delivery bottleneck at 500 VU, where median message RTT remained 120ms but p95 RTT increased to 46 seconds and p99 to 57.8 seconds.
- Analyzed the result as a fan-out/backpressure bottleneck rather than a message durability problem, prioritizing local fan-out queueing, slow session isolation, and WebSocket send metrics over premature Outbox adoption.

## Self-introduction Draft

OpenChat을 개발하면서 단순 기능 구현에서 멈추지 않고, 실시간 채팅이 실제로 언제 무너지는지 확인하기 위해 부하테스트를 직접 설계했습니다. HotChat으로 특정 방에 사용자가 몰리는 상황을 가정해 모든 VU가 하나의 방에 접속하고 초당 1개 메시지를 보내는 k6 WebSocket 시나리오를 만들었습니다.

테스트 결과 500 VU ramp 구간에서 메시지 RTT p50은 120ms로 비교적 안정적이었지만, p95는 46초, p99는 57.8초까지 상승했습니다. 이를 통해 평균 지표만으로는 사용자 경험을 판단하기 어렵고, 대형방에서는 tail latency가 먼저 무너진다는 점을 확인했습니다.

이 결과를 바탕으로 문제를 Outbox나 Kafka 내구성보다 WebSocket fan-out과 backpressure 문제로 판단했습니다. 이후 개선 방향을 메시지 저장 이후 이벤트 발행 안정성보다, room shard 기반 fan-out queue, bounded session queue, slow client 격리 정책으로 잡았습니다.

## Interview Answer Draft

Q. 부하테스트에서 무엇을 확인했나요?

A. HotChat으로 한 방에 사용자가 몰리는 상황을 가정해 단일 방 WebSocket 부하테스트를 진행했습니다. 모든 VU가 같은 방에 접속해 초당 1개 메시지를 보내도록 했고, 방 인원이 늘면서 fan-out send 수가 어떻게 증가하는지 확인했습니다. 500 VU 구간에서 메시지 RTT p50은 120ms였지만 p95는 46초, p99는 57.8초까지 올라가 tail latency가 크게 무너지는 것을 확인했습니다.

Q. 왜 Outbox보다 fan-out을 먼저 개선해야 한다고 판단했나요?

A. Outbox는 DB 저장 이후 발행 이벤트 유실을 줄이는 장애 복구 패턴입니다. 하지만 이번 테스트에서 먼저 드러난 문제는 메시지 유실보다 실시간 전달 지연이었습니다. duplicate는 0이었고, 핵심 증상은 WebSocket RTT p95/p99의 급격한 상승이었습니다. 그래서 사용자 체감 문제를 먼저 만드는 fan-out과 backpressure를 우선순위로 두는 것이 맞다고 판단했습니다.

Q. 서버를 늘리면 해결되나요?

A. 서버를 늘리면 각 인스턴스가 처리하는 local WebSocket 세션 수가 줄어들기 때문에 fan-out 부담은 줄어들 수 있습니다. 하지만 전체 fan-out 총량은 그대로이고, DB 저장과 Redis publish 같은 공유 병목은 여전히 남습니다. 그래서 scale-out과 함께 local fan-out queue, bounded buffer, slow session isolation이 같이 필요하다고 봤습니다.

## Portfolio Title Options

- HotChat 단일 방 fan-out 병목 재현 및 개선 방향 도출
- WebSocket 대형방 부하테스트를 통한 tail latency 병목 분석
- 실시간 채팅 fan-out 부하 모델링과 p95 RTT 병목 분석
- 단일 인기 채팅방 500 VU 부하에서 WebSocket fan-out 한계 측정
