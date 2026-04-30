# Resource Comparison Load Test Summary

- Date: 2026-04-30 KST
- Machine: local Mac M4 Pro 24GB
- Scenario: `k6/scenarios/07-hot-room-fixed.js`
- Duration: 2 minutes per VU level
- Message interval: 1 message/user/second
- Shared infra: Docker Desktop, MySQL, Redis, nginx LB, k6 on same machine
- Profiles:
  - `scale-up-small`: 1 app, 2 CPU / 4GB
  - `scale-out-small`: 2 apps, each 1 CPU / 2GB, total 2 CPU / 4GB

## Results

| Profile | VU | Connect success | RTT p50 | RTT p95 | RTT p99 | Sent | Received | Receive rate |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| scale-up-small | 100 | 100% | 15 ms | 43 ms | 781 ms | 11,930 | 1,190,968 | 9,650/s |
| scale-up-small | 200 | 100% | 26 ms | 56 ms | 93 ms | 23,875 | 4,765,329 | 39,097/s |
| scale-up-small | 300 | 100% | 133 ms | 2.41 s | 4.90 s | 35,808 | 10,371,813 | 85,254/s |
| scale-out-small | 100 | 100% | 19 ms | 495 ms | 1.51 s | 11,934 | 1,191,258 | 9,674/s |
| scale-out-small | 200 | 100% | 18 ms | 112 ms | 402 ms | 23,853 | 4,764,922 | 38,973/s |
| scale-out-small | 300 | 100% | 4.59 s | 18.63 s | 23.42 s | 35,804 | 8,874,691 | 72,626/s |

## Interpretation

Same total app resource 기준에서는 이번 로컬 실험에서 `scale-up-small`이 `scale-out-small`보다 안정적이었다. 특히 300 VU에서 scale-out은 RTT p95가 18.63초까지 증가했고, 수신 처리량도 scale-up의 약 85k/s 대비 약 72k/s로 낮았다.

이 결과는 "서버 수를 늘리면 항상 좋아진다"가 아니라, hot room fan-out에서는 LB로 연결을 나눠도 전체 fan-out 작업량 자체는 사라지지 않는다는 점을 보여준다. Redis Pub/Sub 구조에서는 모든 앱 인스턴스가 같은 메시지를 받아 자기 로컬 세션에 전송하므로, 인스턴스가 늘면 로컬 세션 fan-out은 나뉘지만 Redis 수신, JVM, LB, 네트워크, 컨텍스트 전환 오버헤드도 늘어난다.

이번 조건에서는 1 CPU / 2GB 앱 2개로 쪼갠 구성이 2 CPU / 4GB 앱 1개보다 hot room tail latency에 불리했다. 단, 단일 로컬 머신에서 k6, DB, Redis, 앱, 모니터링을 함께 돌린 실험이므로 절대 처리량보다 같은 총 자원 내 상대 비교 결과로 해석해야 한다.

## Notes

- `hot_room_duplicate_received_total`은 발생하지 않았다.
- k6 raw samples는 InfluxDB에 적재하지 않았다. WebSocket message sample 양이 커서 InfluxDB request body limit을 초과했기 때문이다.
- Docker stats 파일은 각 VU run 종료 직후의 단일 snapshot이다. CPU는 순간값이므로 평균 사용률로 해석하면 안 되고, 메모리/네트워크 참고값으로만 사용한다.
