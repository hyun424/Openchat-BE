# k6 측정 신뢰도 개선과 1800명 단일방 재검증

## 한 줄 요약

1800명 단일방 부하테스트에서 서버 병목과 k6 관측 병목이 섞이는 문제를 발견했고, k6 WebSocket 클라이언트를 `sender / observer / validator` 역할로 분리해 병목 판정 신뢰도를 높였다.

## 배경

OpenChat의 hot-room 테스트는 카카오톡 단체채팅처럼 한 방에 많은 사용자가 모이는 상황을 가정한다. 모든 사용자가 같은 방에서 1초에 1개씩 메시지를 보내면, 서버 입력 TPS는 사용자 수에 비례하지만 WebSocket delivery는 방 인원 수만큼 fan-out된다.

이전 1800명 단일방 테스트에서는 다음 현상이 동시에 나타났다.

- k6 ack p95와 visible freshness p95가 크게 상승했다.
- DB row count와 k6 ack count가 어긋나는 실행이 있었다.
- 서버 `ws.broadcast.lane_done`과 `ws.send.duration`은 상대적으로 낮았다.

이 상태에서 바로 서버 fan-out/send 병목으로 결론 내리면 위험했다. 실제 서버가 막힌 것인지, k6가 수신 payload를 너무 많이 파싱하면서 관측값을 오염시킨 것인지 분리해야 했다.

## 문제 정의

대규모 WebSocket fan-out 테스트에서는 부하 생성기 자체도 병목이 될 수 있다.

기존 k6 클라이언트는 많은 VU가 메시지를 보내면서 동시에 broadcast batch를 파싱하고 visible freshness, 중복, 누락, echo 검증까지 수행했다. 방 인원이 커질수록 한 frame에 포함되는 logical message 수가 늘고, k6의 message handler와 JSON parse 비용이 측정 결과에 섞일 수 있다.

따라서 이번 작업의 목표는 서버 성능을 바로 높이는 것이 아니라, 측정 경로를 분리해 다음 질문에 답하는 것이었다.

- 서버가 ack와 fan-out을 늦게 처리하는가?
- WebSocket send 호출 자체가 느려지는가?
- k6가 너무 많은 수신 payload를 처리하면서 visible freshness를 늦게 관측하는가?
- DB 저장과 ack count는 일치하는가?

## 접근 방식

k6 WebSocket 클라이언트를 역할별로 분리했다.

| 역할 | 목적 | 동작 |
| --- | --- | --- |
| `sender` | 서버 입력 부하 생성 | 메시지 전송과 ack 측정만 수행하고 broadcast batch full parse를 하지 않는다. |
| `observer` | 사용자 체감 지연 측정 | 메시지를 보내지 않거나 낮은 빈도로 보내고, broadcast batch를 파싱해 visible freshness를 측정한다. |
| `validator` | 정합성 검증 | 소수 VU만 기존처럼 full detail parse를 수행해 중복, 누락, echo 검증에 사용한다. |

추가한 k6 관측 비용 metric은 다음과 같다.

| metric | 의미 |
| --- | --- |
| `ws_message_handler_duration_ms` | `socket.on('message')` 전체 처리 시간 |
| `ws_json_parse_duration_ms` | JSON parse 시간 |
| `ws_batch_messages_per_frame` | frame 하나에 포함된 logical message 수 |
| `ws_observer_visible_samples_total` | observer가 visible freshness를 기록한 sample 수 |

1800명 기준 기본 분포는 `sender 94%`, `observer 5%`, `validator 1%`로 잡았다. 즉 대부분의 VU는 서버에 입력 부하를 만들고, 일부 VU만 사용자 체감 지연을 측정하며, 아주 소수만 상세 검증을 수행한다.

## 근거

이 접근은 단순히 임의로 만든 우회가 아니다.

- Grafana k6 공식 문서는 신규 WebSocket 테스트에는 `k6/websockets` 사용을 권장한다. 현재 코드는 기존 `k6/ws`를 유지하되, 1차로 관측 비용을 낮추는 방향을 선택했다.
  참고: https://grafana.com/docs/k6/latest/using-k6/protocols/websockets/
- Slack Koi Pond는 실제 Slack 클라이언트 전체를 복제하지 않고 API 요청과 WebSocket 메시지를 수행하는 가벼운 클라이언트로 대규모 부하를 모델링한다. 테스트 클라이언트 자체가 병목이 되지 않도록 만드는 관점이 이번 개선과 맞닿아 있다.
  참고: https://slack.engineering/load-testing-with-koi-pond/
- Slack Real-time Messaging은 입력 메시지와 broadcast delivery를 분리해서 설명한다. OpenChat 테스트도 입력 TPS, DB TPS, logical delivery TPS, physical frame TPS, visible freshness를 분리해 기록했다.
  참고: https://slack.engineering/real-time-messaging/

## 실행 조건

### Smoke

- run id: `20260504-roleaware-smoke2`
- shared room: `100명`
- k6 worker: `2대`, worker당 `50 VU`
- smoke 검증용 비율: `sender 80% / observer 10% / validator 10%`
- 목적: 역할 배정, shared room, validator detail metric, DB count 일치 여부 확인

### Main

- run id: `20260504-roleaware-hr1800`
- 앱 리소스: API `e2-standard-4 x1`, Realtime `e2-standard-8 x4`
- k6 리소스: `e2-standard-8 x2`, worker당 `900 VU`
- shared room: `1800명`
- role ratio: `sender 94% / observer 5% / validator 1%`
- 실행: `120초 ramp + 120초 chat`
- monitoring: off
- quota guard: Terraform output 기준 `62 vCPU / 195GB SSD`
- 결과 prefix: `gs://openchat-loadtest-openchat-495102/runs/20260504-roleaware-hr1800/`

## 결과

### Smoke 결과

| 항목 | worker-1 | worker-2 |
| --- | ---: | ---: |
| sender ack p95 / p99 | `32ms` / `42.69ms` | `32ms` / `42.13ms` |
| observer visible p95 / p99 | `120.2ms` / `123.88ms` | `118.6ms` / `122.84ms` |
| handler duration p95 | `1ms` | `1ms` |
| json parse p95 | `1ms` | `1ms` |
| validator broadcast / unique | `7128` / `7128` | `3113` / `3113` |
| sent / ack | `1232` / `1232` | `1230` / `1230` |

DB rows는 `2462`였고 worker 합산 ack도 `2462`로 일치했다.

### 1800명 main 결과

| 항목 | worker-1 | worker-2 |
| --- | ---: | ---: |
| sender ack p95 / p99 | `112ms` / `191ms` | `108ms` / `174ms` |
| observer visible p95 / p99 | `191ms` / `232ms` | `173ms` / `228.52ms` |
| handler duration p95 / p99 | `1ms` / `2ms` | `1ms` / `1ms` |
| json parse p95 / p99 | `1ms` / `3ms` | `1ms` / `2ms` |
| batch messages/frame p95 / p99 | `55` / `64` | `55` / `64` |
| observer samples | `5911` | `5549` |
| sent / ack | `100,517` / `100,517` | `101,021` / `101,021` |
| connect success | `100%` | `100%` |
| HTTP error rate | `0%` | `0%` |

서버와 DB 기준 결과는 다음과 같다.

| 항목 | 값 |
| --- | ---: |
| DB rows | `201,538` |
| k6 ack 합계 | `201,538` |
| 입력 TPS / DB TPS | chat window 환산 `1,679/s` |
| `ws.send.succeeded` | `22,160,849`, chat window 환산 `184.7k/s` |
| `ws.send.frame.succeeded` | `1,181,243`, chat window 환산 `9.8k/s` |
| `ws.send.bytes` | `7.20GB`, chat window 환산 `60.0MB/s`, 약 `480Mbps` |
| `ws.send.failed` | `27` |
| server ack p95 | worst node `19.4ms` |
| `ws.broadcast.lane_done` p95 | worst node `142.5ms` |
| `ws.send.duration` p95 | worst node `0.180ms` |

## 해석

이번 결과는 서버 성능이 갑자기 좋아졌다는 뜻이 아니다. 측정 방식을 바꿔 k6 full-parse 관측 비용을 줄였고, 그 상태에서 서버 pipeline과 사용자 체감 지연을 다시 분리해 본 것이다.

핵심 해석은 다음과 같다.

- DB rows와 k6 ack 합계가 `201,538`로 일치했다. 저장/ack 정합성 문제는 재현되지 않았다.
- observer visible p95가 `173~191ms`로 낮았다. 이전 shared k6 재실행에서 worker별 visible p95가 `399ms / 535ms`로 갈렸던 것과 다르다.
- handler duration p95와 JSON parse p95가 모두 `1ms`였다. 이번 실행에서는 k6 observer 처리 비용이 visible freshness를 크게 오염시키지 않았다.
- 서버 `lane_done` p95는 worst node `142.5ms`, `ws.send.duration` p95는 `0.180ms`였다. 서버 WebSocket lane/send가 막혔다고 볼 근거는 없다.
- 다만 role-aware 실행은 observer와 validator가 기본적으로 메시지를 보내지 않으므로, 이전 "전원 1초 1메시지" 실행과 입력 TPS를 1:1 비교하면 안 된다.

따라서 결론은 "성능 개선"이 아니라 "측정 신뢰도 개선"이다.

## 남은 send 실패 신호 분리

role-aware 재측정에서도 `ws.send.failed=27`이 남았다. 전체 delivery 대비 매우 작은 수치이고 서버 `lane_done`과 `send.duration`은 낮았지만, 이 값을 단순 종료 경계로 가정하고 넘어가면 운영 관점의 설명력이 부족하다.

그래서 후속 코드 작업에서 `ws.send.failed`를 reason별로 분리했다.

- `closed_before_send`: 전송 전에 이미 닫힌 세션
- `closed_during_send`: 전송 도중 닫힌 세션
- `send_time_limit`: WebSocket send time limit 초과
- `buffer_limit`: WebSocket buffer limit 초과
- `io_exception`: 네트워크/소켓 IO 예외
- `illegal_state`: 잘못된 세션 상태
- `unknown_exception`: 미분류 예외

이 작업의 목적은 성능 튜닝이 아니라 운영/측정 신뢰도 보강이다. 좋은 수치가 나왔더라도 남은 실패 신호를 무시하지 않고, 정상적인 연결 종료 경계와 실제 backpressure/전송 실패 신호를 구분할 수 있게 만드는 것이다.

## 포트폴리오용 결론

이번 경험에서 강조할 포인트는 단순히 1800명을 처리했다는 숫자가 아니다.

더 중요한 점은 부하테스트 결과가 악화됐을 때 서버 튜닝으로 바로 넘어가지 않고, 부하 생성기와 관측 경로가 결과를 오염시키는지 먼저 검증했다는 것이다.

대규모 WebSocket fan-out에서는 다음 지표를 분리해야 한다.

- 입력 TPS: 서버로 들어온 메시지 수
- DB TPS: 실제 저장된 메시지 row 수
- logical delivery TPS: 사용자에게 전달되어야 하는 논리 메시지 수
- physical frame TPS: 실제 WebSocket frame 전송 수
- visible freshness: 사용자가 메시지를 실제로 관측한 지연
- k6 handler/parse cost: 부하 생성기 내부 처리 비용

이 분리를 통해 1800명 단일방에서 k6 full-parse 관측 비용이 결과에 섞일 수 있음을 확인했고, sender/observer/validator 역할 분리로 병목 판정 신뢰도를 높였다.

## 이력서 문장

- k6와 Terraform 기반 GCP 부하테스트 환경에서 1800명 단일 WebSocket hot-room 시나리오를 검증하고, 입력 TPS, DB TPS, logical delivery TPS, physical frame TPS, visible freshness를 분리 측정했다.
- 부하테스트 결과 악화 시 서버 튜닝으로 바로 넘어가지 않고, k6 부하 생성기와 관측 경로가 결과를 오염시키는 문제를 발견해 sender/observer/validator 역할 분리 방식으로 측정 신뢰도를 개선했다.
- role-aware k6 재측정에서 DB rows와 ack count `201,538`건 일치, observer visible p95 `173~191ms`, server `lane_done` p95 `142.5ms`를 확인해 서버 fan-out/send 병목 여부를 근거 기반으로 판정했다.
- 이후 남은 `ws.send.failed` 신호를 reason별로 분리해, 종료 경계와 실제 WebSocket backpressure/전송 실패를 구분할 수 있도록 운영 관측성을 보강했다.

## 면접 답변 초안

Q. 1800명 단일방 테스트 결과가 좋았다고 보면 되나요?

A. "좋아졌다"보다는 "측정 신뢰도가 높아졌다"가 정확합니다. 이전에는 모든 k6 VU가 메시지를 보내면서 동시에 broadcast payload를 full parse했기 때문에, 서버 병목과 k6 관측 병목이 섞일 수 있었습니다. 그래서 k6 클라이언트를 sender, observer, validator로 나눴습니다. 대부분은 메시지 전송과 ack만 측정하고, 일부 observer만 visible freshness를 측정하게 했습니다. 그 결과 DB row와 ack count가 일치했고, observer visible p95는 173~191ms, 서버 lane_done p95는 142.5ms로 나왔습니다. 이 결과를 통해 당시 문제를 서버 fan-out/send 병목으로 바로 단정하기보다, 기존 full-parse 관측 방식이 결과를 흔들고 있었다고 판단했습니다.

Q. 왜 1800명인데 TPS가 이전보다 낮아졌나요?

A. role-aware 실행에서는 observer와 validator가 기본적으로 메시지를 보내지 않습니다. 목적이 최대 입력 TPS 측정이 아니라 관측 오염 제거였기 때문입니다. 그래서 이 결과는 이전 "전원 1초 1메시지" 실행과 입력 TPS를 직접 비교하면 안 됩니다. 대신 sender ack, observer visible, DB row count, server lane_done, k6 handler cost를 분리해 병목 위치를 더 신뢰성 있게 판단하는 용도입니다.

## 다음 단계

이번 작업 이후에는 무작정 인원 수를 더 늘리기보다 두 가지를 분리해서 진행한다.

1. 전원 송신 처리량 한계 측정
   - sender 비율을 다시 높이거나 observer send interval을 조정해 입력 TPS 한계를 별도로 측정한다.
   - 이때도 observer는 일부만 유지해 visible freshness 관측 경로를 분리한다.

2. 운영형 아키텍처 개선
   - 대형 단일방보다 여러 hot-room이 동시에 생기는 상황을 검증한다.
   - API/Realtime 분리 배포, room 단위 fan-out ownership, slow session isolation을 별도 실험으로 다룬다.
