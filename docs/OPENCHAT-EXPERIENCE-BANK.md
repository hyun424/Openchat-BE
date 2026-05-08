# OpenChat 경험 정리

> OpenChat 프로젝트에서 자소서, 이력서, 면접 답변으로 재사용할 수 있도록 경험 단위로 정리한 문서다.
> 정리 기준은 `상황 -> 문제 -> 행동 -> 결과 -> 배운 점`이며, 각 항목은 독립적으로 꺼내 쓸 수 있게 구성했다.

---

## [EXP-OC-01]

- 프로젝트: OpenChat
- 유형: 분산 실시간 채팅 아키텍처 설계
- 역할: 백엔드 설계 및 핵심 메시지 흐름 구현
- 사용 문항: 직무역량 / 문제해결 / 도전경험
- 키워드: `[WebSocket, Redis Pub/Sub, Kafka, 멀티 인스턴스, fan-out, 분산 시스템]`

### 상황

오프라인 모임 기반 채팅 서비스를 만들면서, 처음부터 단일 서버가 아니라 서버가 여러 대로 늘어나는 상황을 같이 고려했다. WebSocket 기반 채팅은 연결 세션이 서버 메모리에 묶이기 때문에, 서버를 나누는 순간 메시지 전달 구조가 바로 복잡해진다.

### 문제

로컬 단일 서버에서는 잘 동작했지만, 서버가 2대로 늘어나면 한 서버에 연결된 사용자가 보낸 메시지가 다른 서버에 연결된 사용자에게 전달되지 않는 구조적 한계가 있었다. 단순히 채팅 기능을 만드는 것보다, 세션이 분산된 환경에서도 fan-out이 되도록 메시지 전달 구조를 다시 잡는 것이 핵심 문제였다.

### 행동

메시지 흐름을 `ingest -> publish -> subscribe -> fanout`으로 분리하고, WebSocket 세션 관리와 메시지 전달 경로를 분리된 책임으로 설계했다. 실시간 배달은 Redis Pub/Sub이 담당하고, 다중 서버에서도 각 서버가 같은 채널을 구독해 fan-out할 수 있도록 구조를 잡았다. 이후 Redis의 비영속성 한계를 보완하기 위해 Kafka를 durability/fallback 경로로 추가하는 방향으로 아키텍처를 확장했다.

### 결과

단순한 "단일 서버 채팅"이 아니라, 멀티 인스턴스 환경에서 발생하는 메시지 fan-out 문제를 전제로 설계한 채팅 구조를 만들 수 있었다. 이 경험을 통해 실시간 시스템에서는 기능 구현보다 먼저 "상태가 어디에 있고, 이벤트가 어느 경로로 전파되는가"를 정의해야 한다는 점을 배웠다.

### 배운 점

실시간 서비스는 WebSocket만 붙인다고 끝나지 않는다. 서버가 여러 대가 되는 순간 세션 지역성, 이벤트 전파, 장애 시 전달 보장까지 함께 설계해야 한다는 점을 체감했다.

---

## [EXP-OC-02]

- 프로젝트: OpenChat
- 유형: 메시지 유실 방지를 위한 Durability 설계
- 역할: 메시지 ingest 흐름 재설계
- 사용 문항: 문제해결 / 직무역량 / 실패경험
- 키워드: `[Durability First, DB 선저장, 메시지 유실 방지, 데이터 일관성, 복구 가능성]`

### 상황

초기에는 메시지를 빠르게 보여주는 데 초점을 맞춰, Redis로 먼저 발행하고 DB에는 이후 저장하는 흐름을 생각했다. 실시간 채팅에서는 체감 속도가 중요하다고 판단했기 때문이다.

### 문제

테스트 중 Redis 연결이 불안정한 상황에서, 상대방은 메시지를 받았지만 DB 저장이 실패해 새로고침 후 메시지가 사라지는 문제가 발생했다. 즉 "보여주기"는 성공했지만 "기록"이 남지 않는 상태였고, 사용자 입장에서는 보냈던 메시지가 사라지는 심각한 신뢰성 문제가 됐다.

### 행동

메시지 흐름을 `DB 저장 -> 발행` 순서로 바꾸는 durability-first 구조로 재설계했다. 메시지 유입을 담당하는 ingest 계층에서 먼저 DB에 저장하고, 그 다음 Redis/Kafka로 발행하도록 바꿨다. 발행이 실패하더라도 최소한 메시지는 DB에 남아 재조회로 복구할 수 있게 했다.

### 결과

최악의 상황에서도 메시지가 "보였다가 사라지는" 문제가 아니라 "실시간 전달은 늦을 수 있지만 기록은 남는" 방향으로 장애 특성을 바꿀 수 있었다. 실시간성보다 데이터 신뢰성이 우선인 구간을 명확히 분리한 경험이었다.

### 배운 점

실시간 시스템에서는 빠르게 보이는 것보다, 실패했을 때 어떤 상태가 남는지가 더 중요할 수 있다. 특히 채팅처럼 사용자 신뢰가 중요한 기능은 durability 우선 설계가 필요하다는 점을 배웠다.

---

## [EXP-OC-03]

- 프로젝트: OpenChat
- 유형: Redis와 Kafka를 함께 사용하는 이중 전송 구조 설계
- 역할: 장애 대응형 메시지 전달 구조 설계
- 사용 문항: 문제해결 / 도전경험 / 직무역량
- 키워드: `[Redis Pub/Sub, Kafka, 실시간성, 내구성, fallback, graceful degradation]`

### 상황

Redis Pub/Sub을 적용해 멀티 인스턴스 fan-out 문제는 해결했지만, Redis는 구독자가 없거나 연결이 잠시 끊기면 메시지를 저장하지 않는 구조다. 실시간성은 좋지만 내구성이 약하다는 점이 한계였다.

### 문제

Redis를 잠시 내렸다가 올리는 테스트에서, 장애 구간 동안 발행된 메시지가 그대로 사라지는 문제를 확인했다. 채팅 서비스에서 일시적인 인프라 장애가 곧 메시지 유실로 이어지는 구조는 감당하기 어려웠다.

### 행동

Redis는 빠른 실시간 배달, Kafka는 영속성과 fallback 역할을 맡는 이중 발행 구조를 설계했다. Redis가 정상일 때는 낮은 지연으로 배달하고, Redis가 불안정할 때는 Kafka consumer가 fan-out을 대행하는 방향으로 구조를 잡았다. 단일 기술로 모든 요구사항을 해결하려 하기보다, 서로 다른 특성을 가진 시스템을 역할별로 분리해 사용했다.

### 결과

실시간성만 보장하는 구조에서, 장애 시에도 복구 가능한 구조로 설계 관점을 확장할 수 있었다. 단순한 기술 도입 경험이 아니라, `실시간성 vs 내구성`이라는 trade-off를 서비스 특성에 맞게 조합한 경험으로 정리할 수 있게 됐다.

### 배운 점

분산 시스템 설계에서는 "무엇이 가장 빠른가"보다 "무엇을 어느 실패 조건까지 보장할 것인가"가 더 중요하다. 한 기술에 모든 역할을 맡기기보다, 특성에 맞게 역할을 나누는 판단이 필요하다는 점을 배웠다.

---

## [EXP-OC-04]

- 프로젝트: OpenChat
- 유형: 중복 메시지 방지와 idempotency 설계
- 역할: messageId 기반 중복 처리 로직 설계
- 사용 문항: 문제해결 / 직무역량 / 꼼꼼함
- 키워드: `[messageId, dedupe, idempotency, 중복 처리, 이벤트 정합성, fan-out]`

### 상황

Redis와 Kafka에 동시에 메시지를 발행하는 구조를 도입하자, 같은 메시지가 두 번 fan-out되는 문제가 생겼다. 실시간성과 내구성을 함께 잡으려다, 중복 처리라는 새로운 정합성 문제가 생긴 것이다.

### 문제

하나의 메시지가 Redis subscriber와 Kafka consumer를 통해 각각 처리되면서, 채팅방에서 같은 메시지가 두 번 보이는 현상이 발생했다. 장애 대응을 위해 도입한 구조가 오히려 사용자 경험을 해치는 부작용을 만든 셈이었다.

### 행동

서버에서 메시지별 고유 `messageId`를 생성하고, fan-out 직전에 해당 ID를 처리한 적이 있는지 확인하는 dedupe 흐름을 설계했다. Redis 장애 시에도 동작해야 했기 때문에 dedupe 저장소를 Redis가 아니라 인메모리 캐시 중심으로 두고, 빠른 중복 차단에 초점을 맞췄다. 즉 메시지 전달 경로가 여러 개여도 최종 소비 단계에서 한 번만 반영되도록 했다.

### 결과

Redis와 Kafka를 함께 쓰는 구조에서 생길 수 있는 중복 표시 문제를 제어할 수 있었고, "장애 대응 구조를 넣을수록 정합성 리스크도 같이 생긴다"는 점을 직접 경험했다. 이 경험은 이후 이벤트 기반 구조를 볼 때도 idempotency를 먼저 떠올리는 기준이 됐다.

### 배운 점

분산 메시징에서는 전송 성공 자체보다, 동일 이벤트가 여러 번 들어왔을 때도 시스템이 안전하게 동작하는지가 중요하다. 중복은 예외가 아니라 전제 조건으로 다뤄야 한다는 점을 배웠다.

---

## [EXP-OC-05]

- 프로젝트: OpenChat
- 유형: 방 라이프사이클과 세션 정합성 설계
- 역할: Room 상태 모델링 및 종료 처리 흐름 구현
- 사용 문항: 문제해결 / 직무역량 / 서비스 이해
- 키워드: `[Room Lifecycle, Soft Delete, 상태 머신, 자동 종료, WebSocket 세션 정합성, 도메인 모델링]`

### 상황

OpenChat은 일반 채팅방이 아니라 시간과 장소가 있는 오프라인 모임 채팅 서비스였다. 따라서 방은 영구적으로 열려 있는 개념이 아니라, 생성되고 종료되는 생명주기를 가져야 했다.

### 문제

처음에는 "방 삭제"를 단순 데이터 삭제로 생각했지만, 메시지 기록 유지, FK 관계, 복구 가능성, 과거 모임 기록 조회 요구사항이 충돌했다. 또 방이 종료됐는데도 일부 WebSocket 세션이 남아 있는 문제가 있어, 단순 CRUD 방식으로는 도메인을 설명할 수 없었다.

### 행동

`삭제`와 `종료`를 분리해 Room 상태를 `ACTIVE / ENDED` 기반으로 관리하고, 방장에 의한 종료와 시스템 스케줄러에 의한 자동 종료를 구분했다. 방 종료 시 로컬 세션을 정리하고, 메시지 전송 시에도 방 상태를 다시 확인하도록 설계해 이미 종료된 방에 대한 메시지 전송을 차단했다. 또한 soft delete 방식으로 과거 기록과 FK 정합성을 유지할 수 있도록 모델을 조정했다.

### 결과

단순한 방 CRUD가 아니라, 서비스 특성에 맞는 라이프사이클 중심 모델을 만들 수 있었다. 이 경험을 통해 데이터 삭제보다 도메인 상태 전이가 더 중요한 경우가 많다는 점을 배웠고, 기능 요구사항을 엔티티 상태로 풀어내는 감각을 얻었다.

### 배운 점

서비스 도메인을 잘못 이해하면 삭제/조회/API 설계가 모두 어긋난다. 특히 모임 서비스처럼 시간 개념이 있는 도메인은 CRUD보다 상태 전이 관점으로 모델링해야 한다는 점을 배웠다.

---

## [EXP-OC-06]

- 프로젝트: OpenChat
- 유형: OAuth 기반 온보딩 흐름 설계
- 역할: 인증/회원가입 플로우 설계 및 구현
- 사용 문항: 문제해결 / 서비스 관점 / 직무역량
- 키워드: `[Google OAuth, JWT, 온보딩, temp token, nickname setup, 인증 설계]`

### 상황

MVP 단계에서 자체 회원가입을 만들 경우 이메일 인증, 비밀번호 저장, 재설정 기능까지 같이 설계해야 했다. 핵심 기능인 채팅보다 인증 시스템 구축에 개발 리소스가 과하게 들어갈 가능성이 컸다.

### 문제

로그인 기능은 빨리 붙여야 했지만, Google 이름을 그대로 닉네임으로 쓰는 방식은 중복과 사용자 의도 문제를 일으킬 수 있었다. 즉 로그인 간소화와 사용자 식별 정책을 동시에 만족시켜야 했다.

### 행동

Google OAuth만 사용하되, 신규 사용자는 곧바로 서비스 사용자가 되지 않고 `임시 토큰 -> 닉네임 설정 -> 정식 JWT 발급` 흐름을 거치도록 설계했다. 기존 사용자는 즉시 로그인시키고, 신규 사용자는 필요한 최소한의 프로필 정보만 담은 temp token을 사용해 닉네임 입력 과정을 분리했다.

### 결과

인증 기능 개발 비용을 줄이면서도, 사용자 닉네임을 서비스 정책에 맞게 직접 설정하게 하는 온보딩 흐름을 만들 수 있었다. 단순히 OAuth를 붙인 것이 아니라, MVP의 속도와 서비스 정책을 같이 만족시키는 인증 흐름을 설계한 경험으로 정리할 수 있다.

### 배운 점

초기 서비스에서는 모든 기능을 직접 구현하는 것이 항상 좋은 선택은 아니다. 핵심 기능에 집중하기 위해 외부 인증을 활용하되, 서비스 정책이 필요한 지점은 별도 온보딩 단계로 분리하는 식의 절충이 중요하다는 점을 배웠다.

---

## [EXP-OC-07]

- 프로젝트: OpenChat
- 유형: 부하 테스트 기반 병목 분석과 성능 개선
- 역할: 성능 측정, 병목 분석, 쿼리/설정 개선
- 사용 문항: 문제해결 / 성취경험 / 직무역량
- 키워드: `[k6, 부하 테스트, p95, 병목 분석, 페이지네이션, N+1, 성능 개선]`

### 상황

실시간 채팅 서비스는 구현만으로 끝낼 수 없다고 생각해, REST API와 WebSocket 시나리오를 k6로 직접 부하 테스트했다. 단순 기능 확인이 아니라, 실제로 어느 구간에서 병목이 생기는지를 수치로 확인하고 싶었다.

### 문제

테스트 과정에서 `GET /api/rooms`가 N+1 쿼리와 페이지네이션 부재 때문에 전체 시스템 병목으로 작동했다. 방 수가 누적될수록 응답 시간이 급격히 늘어나고, DB 커넥션풀 포화가 Tomcat 스레드 블로킹과 WebSocket 연결 실패까지 연쇄적으로 유발했다.

### 행동

부하 테스트 결과를 바탕으로 방 목록 조회를 페이지네이션 기반으로 바꾸고, LEFT JOIN 집계 쿼리와 인덱스 설계를 통해 N+1 패턴을 줄이는 방향으로 개선했다. 동시에 HikariCP와 Tomcat 설정도 조정해 병목이 한 계층에서 전체 시스템으로 번지는 구조를 완화했다. 개선 전후 결과를 따로 기록해 단순 추정이 아니라 측정값으로 비교했다.

### 결과

비교 리포트 기준으로 REST API p95는 `25.36s -> 2.33s`로 약 90% 감소했고, WebSocket 연결 p95는 `38.44s -> 1.89s`로 약 95% 개선됐다. 반면 WebSocket roundtrip p95는 악화된 지표도 확인했기 때문에, 성능 최적화가 항상 전반적 개선으로 이어지지 않는다는 점까지 함께 해석했다.

### 배운 점

성능 문제는 "느리다"는 감각으로 풀 수 없고, 병목이 어디에서 시작해 어떤 연쇄 장애를 만드는지 측정으로 봐야 한다. 또한 일부 지표가 개선됐더라도 다른 지표가 악화될 수 있기 때문에, 최적화는 항상 trade-off를 동반한다는 점을 배웠다.

---

## [EXP-OC-08]

- 프로젝트: OpenChat
- 유형: 운영 관점 요소를 코드에 포함한 경험
- 역할: 헬스체크, rate limit, graceful shutdown, 세션 관리 구성
- 사용 문항: 직무역량 / 협업 / 장단점
- 키워드: `[운영성, Health Check, Rate Limit, Graceful Shutdown, WebSocket Session Registry, Actuator]`

### 상황

채팅 서비스는 기능이 동작하는 것만으로 충분하지 않고, 장애 시 어떻게 느려지고 어떻게 종료되는지도 중요하다고 생각했다. 특히 WebSocket 연결은 HTTP보다 종료 처리와 세션 관리가 까다롭기 때문에 운영 관점 요소를 함께 넣고 싶었다.

### 문제

실시간 연결은 서버 종료, Redis 장애, 방 종료, 과도한 요청 등 다양한 운영 이벤트에 영향을 받는다. 이런 상황을 코드 레벨에서 다루지 않으면 개발 단계에서는 정상처럼 보여도 실제 환경에서는 원인 파악이 어려워진다.

### 행동

WebSocket 세션 레지스트리, graceful shutdown listener, Redis/Kafka/WS health indicator, HTTP/WS rate limiting, actuator 노출 제한 등을 코드에 포함했다. 즉 기능 구현과 별개로, 서비스가 느려지거나 멈출 때 최소한 어떤 상태인지 알 수 있고 통제할 수 있도록 기본 운영 장치를 같이 설계했다.

### 결과

이 프로젝트를 통해 단순한 기능 구현 경험을 넘어서, "운영 중 어떤 일이 일어날 수 있는가"를 코드와 설정 단계에서 같이 고민한 경험을 쌓을 수 있었다. 완벽하진 않지만, 운영성과 관측성을 의식한 백엔드 개발 경험으로 정리할 수 있다.

### 배운 점

백엔드 개발은 API를 만드는 일에서 끝나지 않는다. 서비스가 느려질 때, 종료될 때, 장애가 날 때 어떤 행동을 하는지까지 설계해야 운영 가능한 시스템에 가까워진다는 점을 배웠다.

---

## [EXP-OC-09]

- 프로젝트: OpenChat
- 유형: 부하 생성기 병목 분리와 측정 신뢰도 개선
- 역할: k6 WebSocket 시나리오 개선, GCP 부하테스트 실행, 결과 분석
- 사용 문항: 문제해결 / 성능 개선 / 직무역량 / 포트폴리오 설명
- 키워드: `[k6, WebSocket, 측정 신뢰도, fan-out, TPS 분리, Grafana, GCP]`

### 상황

OpenChat hot-room 부하테스트에서 1800명 단일방 결과가 흔들렸다. k6 visible freshness와 ack p95는 높아졌지만, 서버 `ws.broadcast.lane_done`과 `ws.send.duration`은 상대적으로 낮게 나왔다. 이 상태에서는 서버 fan-out/send 병목인지, k6가 너무 많은 수신 payload를 파싱하면서 관측값을 늦게 기록한 것인지 단정하기 어려웠다.

### 문제

대규모 WebSocket fan-out 테스트에서는 서버뿐 아니라 부하 생성기 자체도 병목이 될 수 있다. 기존 k6 클라이언트는 많은 VU가 메시지를 보내면서 broadcast batch를 full parse하고, visible freshness, 중복, 누락, echo 검증까지 동시에 수행했다. 즉 서버 성능 저하와 k6 관측 비용이 같은 지표에 섞일 수 있었다.

### 행동

k6 WebSocket 클라이언트를 `sender / observer / validator` 역할로 분리했다. 대부분의 VU는 sender로 메시지 전송과 ack 측정만 수행하게 하고, 일부 observer만 broadcast batch를 파싱해 visible freshness를 측정하게 했다. validator는 소수만 유지해 기존처럼 full detail parse로 정합성을 검증했다. 또한 `ws_message_handler_duration_ms`, `ws_json_parse_duration_ms`, `ws_batch_messages_per_frame`, `ws_observer_visible_samples_total` metric을 추가해 k6 내부 처리 비용도 함께 기록했다.

### 결과

1800명 단일방 shared room을 k6 worker 2대로 재측정한 결과, DB rows와 k6 ack count가 `201,538`건으로 일치했다. sender ack p95는 worker별 `112ms / 108ms`, observer visible p95는 `191ms / 173ms`였다. 서버 지표도 `ws.broadcast.lane_done` p95 worst node `142.5ms`, `ws.send.duration` p95 `0.180ms`로 낮게 나왔다. k6 handler duration p95와 JSON parse p95도 모두 `1ms`라서, role-aware 실행에서는 관측 비용이 visible freshness를 크게 오염시키지 않는다는 점을 확인했다.

### 배운 점

부하테스트 결과가 나빠졌을 때 곧바로 서버 튜닝으로 들어가면 잘못된 병목을 최적화할 수 있다. 특히 WebSocket fan-out처럼 수신량이 큰 테스트에서는 입력 TPS, DB TPS, logical delivery TPS, physical frame TPS, visible freshness, 부하 생성기 처리 비용을 분리해야 한다. 성능 개선만큼 측정 신뢰도 개선도 중요한 엔지니어링 작업이라는 점을 배웠다.

상세 문서: [k6 측정 신뢰도 개선과 1800명 단일방 재검증](./role-aware-k6-measurement-reliability-20260504.md)

---

## [EXP-OC-10]

- 프로젝트: OpenChat
- 유형: Active Room Fan-out과 브라우저 E2E 검증
- 역할: WebSocket 수신 상태 프로토콜 설계, 서버 fan-out 대상 축소, FE E2E 자동화
- 사용 문항: 문제해결 / 성능 개선 / 서비스 안정성 / 직무역량
- 키워드: `[WebSocket, fan-out, active/passive, E2E, messages/after]`

### 상황

OpenChat은 hot-room에서 한 명이 보낸 메시지를 같은 방의 모든 WebSocket 세션에 fan-out한다. 1500명 단일방이 안정적으로 동작하더라도, 모든 사용자가 실제로 같은 화면을 보고 있는 것은 아니다. 백그라운드 탭, 다른 화면, 잠시 이탈한 사용자의 세션까지 full payload를 계속 보내면 서버를 늘리기 전에 줄일 수 있는 delivery work가 남는다.

### 문제

서버는 사용자가 실제로 채팅방을 보고 있는지 직접 알 수 없다. 단순히 WebSocket이 연결되어 있다는 이유만으로 모든 세션에 full message를 보내면, active 사용자와 passive 사용자를 구분하지 못한다. 반대로 passive 세션을 전송 대상에서 제외하면, 사용자가 다시 돌아왔을 때 누락 메시지를 안전하게 복구해야 한다.

### 행동

WebSocket control message로 `room.active`, `room.active.heartbeat`, `room.passive`를 추가했다. Web 클라이언트는 현재 route, `document.visibilityState`, WebSocket 연결 상태를 기준으로 active 여부를 선언하고, 서버는 active TTL을 둬 passive 이벤트 누락에도 안전하게 동작하도록 했다. 서버 fan-out은 active 세션에만 full payload를 보내고, visible 복귀 시 FE가 `/messages/after`로 누락 메시지를 복구하도록 했다.

### 결과

BE 단위 테스트로 control message가 DB 저장, ack, publish 경로를 타지 않는지 확인했고, passive 또는 TTL 만료 세션이 full fan-out 대상에서 제외되는지 검증했다. FE 브라우저 E2E에서는 hidden 중 다른 세션이 보낸 메시지가 즉시 표시되지 않고, visible 복귀 후 `/messages/after` sync로 복구되는 흐름을 확인했다. 이 결과는 단순 성능 수치가 아니라, fan-out 대상 축소와 메시지 복구 안정성을 검증한 근거로 남겼다.

### 배운 점

실시간 채팅의 확장성은 서버를 더 띄우는 것만으로 설명하기 어렵다. 사용자가 실제로 보고 있지 않은 세션에 full payload를 계속 보내는 구조라면, 먼저 delivery work 자체를 줄일 수 있는지 봐야 한다. 또한 최적화는 사용자 경험을 깨뜨리면 안 되므로, passive 전환과 visible 복구를 브라우저 E2E로 확인하는 과정이 중요하다는 점을 배웠다.

상세 문서: [Active Room Fan-out v1과 브라우저 E2E 검증](./active-room-fanout-e2e-20260505.md)

---

## [EXP-OC-11]

- 프로젝트: OpenChat
- 유형: pod budget 기준 room work sharding 설계
- 역할: Realtime pod 기준 수립, room tier 계측, fan-out partition 추천 공식 설계
- 사용 문항: 시스템 설계 / 성능 개선 / 확장성 / 직무역량
- 키워드: `[WebSocket, fan-out, sharding, room work, pod budget, K8s]`

### 상황

OpenChat은 1500명 all-active hot room과 1500명 active/passive hot room을 측정하면서, 단순히 "몇 명까지 된다"보다 "어떤 리소스 단위에서 어떤 fan-out work를 처리하는가"가 더 중요해졌다. 특히 K8s로 전환한다면 pod 하나의 리소스 budget을 기준으로 작은 방과 hot room을 다르게 다뤄야 한다.

### 문제

방 인원수만으로 shard 기준을 잡으면 실제 부하를 잘못 볼 수 있다. 같은 1500명 방이어도 모두 active이면 full delivery work가 매우 크고, active/passive가 적용되면 실제 full fan-out 대상은 줄어든다. 따라서 스케일 아웃 기준은 전체 인원수가 아니라 `input_msg_tps * active_sessions`로 봐야 한다.

### 행동

Realtime pod의 기본 단위를 `4 vCPU / 8GB`로 두고, pod work budget을 `10,000 delivery/s`로 정의했다. `room_work = input_msg_tps * active_sessions`를 기준으로 `SMALL / MEDIUM / LARGE / HOT / CRITICAL` tier를 나누고, `ceil(room_work / pod_budget)`과 `ceil(active_sessions / max_sessions_per_partition)` 중 큰 값으로 partition 추천 수를 계산하도록 설계했다. v1에서는 실제 라우팅을 바꾸지 않고 room tier와 partition recommendation metric만 추가했다.

### 결과

1500명 all-active는 `약 2,250,000 delivery/s`, 1500명 active/passive는 `약 189,000 delivery/s`로 재해석했다. active/passive로 full fan-out work가 줄었지만, 둘 다 `4 vCPU / 8GB` pod 하나의 처리 단위는 아니므로 hot room fan-out partition 대상이라는 결론을 얻었다. 이로써 이후 K8s 전환이나 room shard 설계를 단순 증설이 아니라 pod budget 기준으로 설명할 수 있게 됐다.

### 배운 점

확장성은 서버 수를 늘리는 이야기만으로 부족하다. 작은 방은 하나의 shard에 효율적으로 묶고, hot room은 단일 pod budget을 넘는 순간 fan-out partition으로 나누는 기준이 필요하다. 특히 실시간 채팅에서는 입력 TPS, active sessions, delivery work를 분리해서 봐야 리소스 사용량과 확장 전략을 설득력 있게 설명할 수 있다는 점을 배웠다.

상세 문서: [4 vCPU 기준 Room Work Sharding 설계](./room-work-sharding-plan-20260505.md)

---

## [EXP-OC-12]

- 프로젝트: OpenChat
- 유형: Dynamic Realtime Partition Ownership와 Node Drain 검증
- 역할: node registry 기반 assignment 설계, node-aware routing, reconnect 기반 drain, GCP smoke 검증
- 사용 문항: 시스템 설계 / 문제해결 / 확장성 / 운영 안정성 / 포트폴리오 설명
- 키워드: `[WebSocket, dynamic ownership, node drain, reconnect, Redis Pub/Sub, GCP, k6]`

### 상황

OpenChat은 hot room fan-out을 partition으로 나누는 단계까지 발전했지만, partition을 여러 개로 나누는 것만으로는 충분하지 않았다. WebSocket 세션은 특정 realtime node 메모리에 있고, Redis subscriber도 node마다 다르기 때문에 `/ws-route` 결과, 실제 connected node, subscriber owner, fan-out 경로가 같은 기준을 따라야 했다.

### 문제

단순히 서버를 여러 대 띄우면 route는 분산된 것처럼 보여도 실제 연결 node나 Redis subscriber ownership이 맞지 않으면 메시지 누락, 중복 fan-out, drain 실패가 생길 수 있다. 또한 rolling deploy나 VM 종료를 고려하면 특정 realtime node를 안전하게 비우는 node drain 절차가 필요했다.

### 행동

`app.instance-id`를 node identity로 통일하고, Redis node registry를 routing/assignment의 source of truth로 두었다. active node 목록을 기준으로 partition owner를 deterministic하게 계산하고, `/ws-route` 응답에 `nodeId`, `assignmentVersion`, `wsUrl`을 optional로 추가했다. 이후 node drain command를 별도로 구현해 target node를 `draining=true`로 표시하고, replacement owner readiness 확인 후 기존 WebSocket sessions에 reconnect control을 보내도록 했다. drain 진행 상태는 registry heartbeat의 `openSessions`로 확인했다.

### 결과

GCP node drain smoke에서 WebSocket connect success `145/145`, route failure/fallback/mismatch `0/0/0`, node drain reconnect control `45`건, sent/ack/DB rows `22300/22300/22300` 일치를 확인했다. drain 대상 node `gcp-realtime-1`은 assignment owner에서 제외됐고, 최종 open session count가 `0`이 되었다. 이 결과로 "node를 안전하게 비운 뒤 종료 가능 상태로 만들 수 있다"는 앱 레벨 근거를 확보했다.

### 배운 점

실시간 시스템의 scale-out은 서버 수 증가가 아니라 ownership contract를 맞추는 문제다. route, 실제 연결, subscriber, reconnect, drain completion signal이 모두 같은 기준을 따라야 운영 가능한 구조가 된다. 또한 EKS나 MIG 자동 종료를 붙이기 전에, 애플리케이션이 먼저 "이 node는 안전하게 비워졌다"는 상태를 증명할 수 있어야 한다.

상세 문서: [Dynamic Realtime Partition Ownership와 Node Drain STAR 기록](./2026-05-08-dynamic-realtime-partition-ownership-star.md)

---

## 활용 가이드

### 자소서에서 강하게 쓰기 좋은 경험

1. `EXP-OC-01` 분산 실시간 채팅 아키텍처 설계
2. `EXP-OC-02` Durability-first 메시지 유실 방지
3. `EXP-OC-09` 부하 생성기 병목 분리와 측정 신뢰도 개선
4. `EXP-OC-10` Active Room Fan-out과 브라우저 E2E 검증
5. `EXP-OC-12` Dynamic Realtime Partition Ownership와 Node Drain 검증

### 면접에서 기술적으로 풀기 좋은 경험

1. `EXP-OC-03` Redis + Kafka 이중 전송 구조
2. `EXP-OC-04` 중복 메시지 방지와 idempotency
3. `EXP-OC-07` 부하 테스트 기반 병목 분석과 성능 개선
4. `EXP-OC-10` Active Room Fan-out과 브라우저 E2E 검증
5. `EXP-OC-12` Dynamic Realtime Partition Ownership와 Node Drain 검증

### 협업/서비스 이해 관점으로 풀기 좋은 경험

1. `EXP-OC-06` OAuth 온보딩 설계
2. `EXP-OC-08` 운영 관점 요소를 코드에 포함한 경험
