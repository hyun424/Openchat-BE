# 부하테스트 기반 채팅 아키텍처 개선 경험 정리

## 한 줄 요약

실시간 채팅 서비스의 핫룸 성능 한계를 부하테스트로 검증하고, 단순 서버 증설이 아니라 병목 지점을 분리해 연결 수립, 메시지 저장, Pub/Sub, fanout 구조를 단계적으로 개선하는 방향을 도출했다.

## 최신 정리

2026-05-04 기준 최신 결론은 "최대 인원 수 홍보"보다 "측정 신뢰도와 병목 위치 분리"에 초점을 둔다.

초기에는 로컬과 GCP에서 200~500명 구간부터 결과가 흔들렸지만, 이후 GCP 부하테스트 환경을 분리하고 서버 metric, DB row count, k6 summary를 함께 수집하면서 해석이 바뀌었다. 특히 1800명 단일방 테스트에서는 서버 WebSocket fan-out/send 병목과 k6 관측 병목이 섞일 수 있음을 확인했고, k6 클라이언트를 `sender / observer / validator`로 나눠 재측정했다.

role-aware k6 재측정 결과, 1800명 단일방 shared room에서 DB rows와 ack count가 `201,538`건으로 일치했고, observer visible p95는 worker별 `191ms / 173ms`, 서버 `ws.broadcast.lane_done` p95는 worst node `142.5ms`, `ws.send.duration` p95는 `0.180ms`였다. 이 결과는 서버 처리 능력이 갑자기 달라졌다는 뜻이 아니라, 기존 full-parse k6 관측 방식이 결과를 오염시킬 수 있음을 분리해낸 것이다.

이후에는 남은 `ws.send.failed` 신호도 그냥 무시하지 않고, `closed_before_send`, `closed_during_send`, `send_time_limit`, `buffer_limit`, `io_exception`, `illegal_state`처럼 저카디널리티 reason으로 나눠 기록하도록 보강했다. 이로써 다음 부하테스트에서는 실패 총량뿐 아니라 정상적인 종료 경계인지 실제 WebSocket backpressure/전송 실패인지까지 설명할 수 있다.

그 다음 단계로는 Discord MaxJourney 사례에서 얻은 "필요 없는 fan-out 제거" 관점을 OpenChat에 적용했다. 사용자가 실제로 보고 있는 채팅방 세션만 full WebSocket payload를 받도록 `room.active`, `room.active.heartbeat`, `room.passive` control message를 추가했고, passive 세션은 full fan-out 대상에서 제외했다. 사용자가 다시 visible 상태로 돌아오면 `/messages/after`로 누락 메시지를 복구한다.

이 변경은 처리량 향상 수치 홍보가 아니라, fan-out 대상 축소와 메시지 복구 안정성을 검증한 작업이다. BE 단위 테스트로 control message가 저장/ack 경로를 타지 않는지, passive 세션이 fan-out에서 제외되는지 확인했고, FE 브라우저 E2E로 hidden 중 메시지가 즉시 표시되지 않고 visible 복귀 후 REST sync로 복구되는 흐름을 확인했다.

이후 1500명 단일방에서 active 30%, passive 70% 조건의 클라우드 부하테스트를 실행했다. 결과는 active/passive assigned `450 / 1050`, sent/ack/DB rows `50,870 / 50,870 / 50,870`, passive unexpected `0`, ack p95 worst `23ms`, visible p95 worst `117.5ms`, 서버 `ws.fanout.passive_omitted` `12,778,286`, `ws.send.failed` `0`이었다. 이 결과는 "더 많은 인원"이 아니라, 같은 방 인원에서 실제로 보고 있는 세션만 full fan-out 대상으로 남기는 구조가 동작한다는 근거로 기록한다.

이제 다음 확장 기준은 단순 방 인원수가 아니라 `room_work = input_msg_tps * active_sessions`로 잡는다. Realtime pod의 기본 단위를 `4 vCPU / 8GB`, pod budget을 `10,000 delivery/s`로 두고, 작은 방은 여러 개를 하나의 room shard에 묶고, 단일 pod budget을 넘는 hot room은 fan-out partition 대상으로 분류한다. v1에서는 라우팅을 바꾸지 않고 room tier와 partition 추천 수만 계측한다.

상세 정리: [k6 측정 신뢰도 개선과 1800명 단일방 재검증](../load-tests/role-aware-k6-measurement-reliability-20260504.md)

Active Room Fan-out 정리: [Active Room Fan-out v1과 브라우저 E2E 검증](../load-tests/active-room-fanout-e2e-20260505.md)

Active/Passive 부하테스트 결과: [1500명 Active/Passive Hot Room 측정 결과](../../infra/gcp-loadtest/results/2026-05-05-active-passive-hot-room-1500.md)

Room Work Sharding 설계: [4 vCPU 기준 Room Work Sharding 설계](../plans/room-work-sharding-plan-20260505.md)

관련 설계 메모: [Discord MaxJourney 사례에서 OpenChat에 가져갈 아이디어](../ideas/discord-maxjourney-openchat-ideas.md)

## 문제 상황

포트폴리오용 실시간 채팅 서비스에서 한 방에 수백 명 이상이 동시에 접속하고, 모든 사용자가 1초마다 메시지를 보내는 핫룸 상황을 목표로 잡았다. 초기에는 로컬과 GCP에서 부하테스트 결과가 크게 달랐고, 특히 GCP 환경에서는 300명 이상부터 RTT와 delivery ratio가 급격히 나빠졌다.

단순히 “서버 사양이 부족하다”로 결론 내리지 않고, 앱 서버, Redis, MySQL, k6 부하 생성기를 분리한 GCP 테스트 환경을 Terraform으로 구성했다. 이를 통해 부하 생성 리소스와 서버 리소스가 서로 영향을 주지 않도록 만들고, 테스트 종료 후 VM을 자동 삭제해 비용도 통제했다.

## 수행한 일

- Terraform으로 GCP 부하테스트 인프라를 자동화했다.
- Nginx LB, Spring Boot App, MySQL, Redis, k6 runner를 각각 별도 VM으로 분리했다.
- GCS에 k6 결과, Prometheus snapshot, 로그를 자동 업로드하도록 구성했다.
- 테스트 종료 후 VM을 자동 삭제해 부하테스트 비용 누수를 방지했다.
- 로컬 기준선과 GCP 기준선을 분리해 비교했다.
- 메시지 전송 경로에서 `rooms.last_message` DB update를 비동기 flush 구조로 분리해 채팅 전송 경로의 DB write 부담을 줄였다.
- 이후 300/500/1000명 핫룸 테스트를 반복하며 병목 위치를 재해석했다.

## 핵심 결과

GCP에서 58 vCPU 규모의 분리형 테스트 환경을 구성한 뒤 300/500/1000명 핫룸 테스트를 수행했다.

| 규모 | WebSocket 연결 성공률 | RTT p95 | Delivery ratio | 해석 |
|---:|---:|---:|---:|---|
| 300명 | 100% | 335ms | 99.85% | 배송은 안정적이나 목표 지연시간은 미달 |
| 500명 | 51.4% | 266ms | 51.31% | 메시지 처리보다 연결 수립 단계에서 병목 |
| 1000명 | 15.65% | 88ms | 12.08% | 대부분 접속 실패, RTT는 생존 연결만 반영 |

서버 내부 Prometheus 지표에서는 `fanout.total p95`와 `ws.broadcast.total p95`가 약 1~2ms 수준으로 낮게 나왔다. 반면 500명 이상에서는 WebSocket 연결 성공률 자체가 크게 떨어졌다. 즉, 문제는 메시지 fanout 코드만의 문제가 아니라 대량 접속 시 연결 수립, LB/Tomcat/socket 설정, 입장 API 처리 구간에 있었다.

## 배운 점

가장 중요한 배움은 부하테스트 결과를 단순히 평균 RTT만 보고 판단하면 잘못된 결론을 내릴 수 있다는 점이었다. 1000명 테스트에서 RTT p95는 낮게 보였지만, 실제로는 대부분의 사용자가 WebSocket 연결에 실패했기 때문에 살아남은 일부 연결만 측정된 결과였다.

또한 오토스케일링만으로는 모든 실시간 채팅 병목을 해결할 수 없다는 점도 확인했다. 현재 구조처럼 모든 앱 인스턴스가 Redis Pub/Sub 메시지를 모두 받아 각자 fanout하는 방식은 앱을 늘릴수록 중복 작업과 Redis egress가 증가한다. 여러 핫룸이 동시에 생기는 상황까지 고려하면 방 단위 fanout ownership, room worker, 연결 샤딩 같은 구조 개선이 필요하다.

## 향후 개선 방향

1. 연결 수립 병목 분리
   - k6 시나리오를 instant connection과 ramp-up connection으로 나눠 측정한다.
   - Nginx `worker_connections`, `worker_rlimit_nofile`, keepalive, WebSocket timeout을 조정한다.
   - VM `nofile`, TCP backlog, socket 관련 커널 파라미터를 점검한다.
   - Tomcat accept count, thread pool, WebSocket connection 처리 설정을 튜닝한다.

2. fanout 구조 개선
   - Redis subscriber thread와 fanout 실행을 분리한다.
   - `roomId` 기반 striped worker를 두어 방 단위 순서를 유지하면서 subscriber backlog를 줄인다.
   - 장기적으로는 모든 앱이 모든 방 메시지를 받는 구조가 아니라, 방 단위 fanout owner를 두는 방향을 검토한다.

3. 다중 핫룸 대응
   - 단일 핫룸 500명 목표를 먼저 안정화한다.
   - 이후 여러 핫룸이 동시에 존재하는 시나리오를 추가한다.
   - 서버 사양을 키우는 테스트와 코드 구조 개선 테스트를 분리해, 어떤 개선이 실제로 효과가 있었는지 명확히 측정한다.

## 자소서용 문장 예시

실시간 채팅 서비스의 성능 한계를 확인하기 위해 k6와 Terraform 기반의 GCP 부하테스트 환경을 직접 구축했습니다. 초기에는 단순히 서버 사양을 높이면 해결될 것으로 예상했지만, 로컬과 GCP 테스트 결과를 비교하면서 부하 생성기, 앱 서버, Redis, MySQL이 같은 리소스를 공유하면 병목 원인을 정확히 판단하기 어렵다는 점을 확인했습니다. 이에 LB, 앱 서버, DB, Redis, k6 runner를 각각 별도 VM으로 분리하고, 테스트 결과와 Prometheus 지표를 GCS에 자동 수집한 뒤 VM을 삭제하는 구조를 만들었습니다.

테스트 결과 300명 핫룸에서는 메시지 배송률 99.85%를 달성했지만, 500명 이상에서는 RTT보다 WebSocket 연결 성공률이 먼저 무너지는 것을 확인했습니다. 특히 1000명 테스트의 RTT p95가 낮게 보이는 상황에서도 실제로는 대부분의 사용자가 연결에 실패하고 일부 생존 연결만 측정된 것임을 지표로 검증했습니다. 이를 통해 평균 지연시간보다 연결 성공률, delivery ratio, 서버 내부 stage latency를 함께 봐야 한다는 점을 배웠습니다.

이후 단순 오토스케일링만으로 해결하기보다, 연결 수립 병목과 메시지 fanout 병목을 분리해 개선하기로 판단했습니다. 단기적으로는 Nginx, Tomcat, OS socket 설정과 연결 ramp-up 시나리오를 점검하고, 장기적으로는 모든 앱 인스턴스가 모든 Redis Pub/Sub 메시지를 처리하는 구조를 방 단위 fanout ownership 구조로 개선하는 방향을 설계했습니다. 이 경험을 통해 성능 문제를 감으로 판단하지 않고, 재현 가능한 실험 환경과 지표 기반 분석으로 아키텍처 개선 방향을 도출하는 역량을 기를 수 있었습니다.

## 면접에서 강조할 포인트

- 부하테스트 환경을 직접 자동화했다.
- 성능 저하 원인을 평균 RTT 하나로 판단하지 않았다.
- 로컬과 클라우드 결과 차이를 환경 차이로만 넘기지 않고, 테스트 구조 자체를 개선했다.
- 서버 증설, 오토스케일링, 구조 개선의 역할을 구분해서 판단했다.
- 실시간 서비스에서 연결 수립 병목과 메시지 fanout 병목은 서로 다른 문제라는 점을 실험으로 확인했다.
