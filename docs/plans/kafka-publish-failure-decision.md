# Kafka Publish 실패 처리 대안 정리

> 목적: 구현 자체보다, 왜 어떤 결정을 내렸고 어떤 trade-off를 감수했는지 나중에 설명하기 위해 남긴 의사결정 기록이다.

## 1. 배경

OpenChat의 메시지 흐름은 `DB 저장 -> publish -> subscribe -> fan-out` 순서다.

DB를 먼저 저장하는 이유는 명확하다. 실시간 전달이 실패하더라도 메시지 기록은 남아야 하기 때문이다. 즉, 최악의 경우에도 "보였던 메시지가 사라지는 문제"보다 "실시간 전달은 늦지만 새로고침하면 보이는 상태"가 낫다고 판단했다.

현재 publish 경로는 Redis와 Kafka를 함께 사용한다.

- Redis Pub/Sub: 빠른 실시간 전달
- Kafka: Redis 장애 시 fallback 및 내구성 보완

문제는 Kafka publish 실패가 비동기 callback에서만 로그로 남을 수 있다는 점이다. `ChatIngestService`는 `publisher.publish()`가 예외를 던질 때만 retry buffer에 넣는데, Kafka 실패가 나중에 callback에서 발생하면 ingest 계층은 실패를 알지 못한다.

```text
DB 저장 성공
-> publisher.publish() 호출
-> Kafka send 요청은 반환됨
-> ingest는 성공으로 판단
-> 이후 callback에서 Kafka 실패 로그만 기록
-> retryBuffer에 들어가지 않을 수 있음
```

이 상태로는 "Kafka fallback으로 메시지 전달 보장을 강화했다"고 말하기 어렵다. 실패를 관측하고 재시도 가능한 상태로 만들어야 한다.

## 2. 목표

Kafka publish 실패 처리의 목표는 다음과 같다.

1. DB에 저장된 메시지가 publish 실패로 조용히 유실되지 않게 한다.
2. 실패를 ingest 계층 또는 명확한 실패 처리 컴포넌트가 인지하게 한다.
3. 현재 프로젝트 규모를 고려해 구조를 과도하게 키우지 않는다.
4. Redis/Kafka/WebSocket 메시지 흐름 자체는 유지한다.
5. 이후 부하테스트에서 개선 전후를 수치로 비교할 수 있게 한다.

## 3. 대안 비교

### 대안 1. Kafka send를 짧은 timeout으로 동기 확인

Kafka publish 결과를 제한 시간 내 확인한다.

```java
kafkaTemplate.send(topic, key, message)
        .get(500, TimeUnit.MILLISECONDS);
```

성공하면 기존처럼 진행하고, 실패 또는 timeout이면 예외를 던진다. 그러면 `ChatIngestService`의 기존 `try-catch`가 실패를 잡아 `retryBuffer.enqueue(dto)`를 실행할 수 있다.

장점:

- 구현 범위가 작다.
- 기존 `ChatMessagePublisher.publish()` 인터페이스를 유지할 수 있다.
- 실패를 ingest 계층이 즉시 인지한다.
- 기존 `PublishRetryBuffer`를 그대로 활용할 수 있다.
- 포트폴리오에서 설명이 명확하다.

단점:

- WebSocket 메시지 처리 thread가 Kafka 응답을 기다린다.
- Kafka가 느리면 메시지 전송 지연이 증가한다.
- timeout 값을 잘못 잡으면 latency와 실패율이 모두 나빠질 수 있다.

적합한 경우:

- 현재 프로젝트처럼 구조를 크게 바꾸지 않고 실패 처리 구멍을 닫고 싶을 때.
- outbox까지 도입하기에는 과하지만, callback 로그만으로는 부족할 때.

### 대안 2. Publisher 인터페이스를 비동기로 변경

`publish()`가 `CompletionStage`나 `CompletableFuture`를 반환하도록 바꾼다.

```java
CompletionStage<Void> publish(ChatMessageDto message);
```

`ChatIngestService`는 future callback에서 실패를 받아 retry buffer에 넣는다.

장점:

- WebSocket 처리 thread를 오래 막지 않는다.
- publish 성공/실패가 타입으로 드러난다.
- 비동기 메시징 구조라는 의도가 코드에 잘 표현된다.

단점:

- 인터페이스 변경 범위가 크다.
- Redis publish 결과와 Kafka publish 결과를 어떻게 합칠지 정책이 필요하다.
- 실패 처리가 ingest 메서드 반환 이후에 일어나므로 추적이 복잡해진다.
- 테스트가 대안 1보다 복잡하다.

적합한 경우:

- publish 계층을 본격적으로 비동기 모델로 정리할 때.
- 메시지 처리 thread blocking을 반드시 피해야 할 때.

### 대안 3. Kafka callback에서 retry buffer에 직접 enqueue

Kafka send callback에서 실패를 감지하면 retry buffer에 넣는다.

```java
kafkaTemplate.send(topic, key, message)
        .whenComplete((result, ex) -> {
            if (ex != null) {
                retryBuffer.enqueue(message);
            }
        });
```

장점:

- 비동기 publish 방식을 유지한다.
- Kafka 실패가 발생한 실제 시점에 retry buffer로 보낼 수 있다.
- WebSocket 처리 thread를 막지 않는다.

단점:

- `ChatCompositePublisher`가 `PublishRetryBuffer`를 알게 되면 책임이 섞인다.
- 현재 구조에서는 `PublishRetryBuffer`가 `ChatMessagePublisher`를 의존하므로 순환 의존성이 생길 수 있다.
- 순환 의존성을 피하려면 `PublishFailureHandler` 같은 별도 컴포넌트가 필요하다.

적합한 경우:

- 비동기 흐름은 유지하되, 실패 처리 책임을 별도 컴포넌트로 분리할 의지가 있을 때.

### 대안 4. Outbox 패턴 도입

DB 저장과 함께 outbox 테이블에 publish 대상 이벤트를 저장하고, 별도 worker가 Kafka로 발행한다.

```text
1. chat_message 저장
2. message_outbox 저장
3. worker가 outbox를 읽어 Kafka publish
4. 성공 시 SENT 처리
5. 실패 시 재시도
```

장점:

- DB 저장과 publish 요청 기록을 같은 트랜잭션으로 묶을 수 있다.
- Kafka 장애가 길어져도 메시지 publish 요청이 DB에 남는다.
- 실서비스 수준의 메시지 전달 보장 스토리로 가장 강하다.

단점:

- 테이블, worker, 상태 관리가 추가된다.
- 중복 publish 가능성에 대비해 consumer idempotency가 더 중요해진다.
- 현재 프로젝트 규모에 비해 구조가 커질 수 있다.
- Redis 실시간 경로와 outbox Kafka 경로의 관계를 다시 정리해야 한다.

적합한 경우:

- 운영 수준의 내구성을 목표로 할 때.
- Kafka 장애가 장시간 이어지는 상황까지 명확히 보장해야 할 때.

### 대안 5. Kafka producer 설정 보강

코드 구조는 크게 바꾸지 않고 producer 설정을 강화한다.

```properties
spring.kafka.producer.acks=all
spring.kafka.producer.retries=3
spring.kafka.producer.properties.enable.idempotence=true
spring.kafka.producer.properties.delivery.timeout.ms=3000
spring.kafka.producer.properties.request.timeout.ms=1000
```

장점:

- 코드 변경이 작다.
- Kafka producer 자체의 전송 안정성이 올라간다.
- 메시지 순서와 중복 전송 위험을 줄이는 데 도움이 된다.

단점:

- 애플리케이션이 실패를 인지하는 문제는 남는다.
- retry buffer와 직접 연결되지 않는다.
- 단독으로는 "실패 처리"라기보다 "전송 안정성 설정"에 가깝다.

적합한 경우:

- 다른 대안과 함께 보조적으로 적용할 때.

## 4. 현재 선택 후보

현재 프로젝트 단계에서는 **대안 1 + 대안 5**가 가장 적절하다.

```text
DB 저장 성공
-> Redis publish 시도
-> Kafka publish 결과를 짧은 timeout으로 확인
-> 실패 또는 timeout이면 예외 발생
-> ChatIngestService가 retryBuffer.enqueue()
```

선택 이유:

- 현재 구조를 크게 바꾸지 않는다.
- 기존 retry buffer를 그대로 활용한다.
- Kafka 실패를 조용히 로그로만 남기지 않고, 재시도 가능한 상태로 만든다.
- outbox보다 약하지만 현재 프로젝트 규모에서는 충분히 설명 가능한 trade-off다.

감수하는 점:

- 정상 상황에서도 Kafka 응답 대기 시간만큼 latency가 늘 수 있다.
- Kafka가 느릴 때 WebSocket 처리 thread가 잠깐 점유된다.
- 장기 Kafka 장애까지 완벽하게 보장하려면 outbox가 더 적합하다.

초기 timeout 후보:

```text
500ms ~ 1s
```

timeout은 부하테스트로 조정한다. 정상 상황 p95 latency가 크게 흔들리면 더 짧게 잡고, 실패 오탐이 많으면 늘린다.

## 5. 수정 전 장애 재현 기록

수정 전 상태를 먼저 재현해 before/after 비교 기준으로 남긴다.

### 단위 테스트 관찰

추가한 테스트:

- `ChatCompositePublisherTest.publish_kafkaAsyncFailure_isNotPropagatedToCaller`
- `ChatIngestServiceTest.ingest_publisherReturnsNormally_retryBufferNotUsed`

관찰 결과:

```text
Kafka send future 실패
-> ChatCompositePublisher.publish()는 예외를 던지지 않음
-> ChatIngestService는 publish 실패를 알 수 없음
-> retryBuffer.enqueue() 호출 조건이 성립하지 않음
```

의미:

- `ChatIngestService`에는 publish 예외를 retry buffer로 넘기는 방어 로직이 있다.
- 하지만 실제 `ChatCompositePublisher`는 Kafka 실패를 비동기 callback 로그로만 처리한다.
- 따라서 현재 구조에서는 "Kafka publish 실패를 retry buffer로 재시도한다"고 말할 수 없다.

### 로컬 수동 재현 시나리오

절차:

1. `docker-compose up -d`로 MySQL, Redis, Kafka 실행
2. 애플리케이션 실행
3. WebSocket 메시지 정상 전송
4. `docker-compose stop kafka`
5. 메시지 재전송
6. 애플리케이션 로그 확인
7. `docker-compose start kafka`

수정 전 기대 로그:

```text
[KAFKA PUB FAIL] 발생
[PUBLISH FAIL] ... enqueuing for retry 미발생
[RETRY BUFFER] Drained ... 미발생
```

장애 조건:

- Kafka broker 중단
- Redis가 정상인 경우 같은 인스턴스/Redis 경로에서는 겉으로 메시지가 전달될 수 있음
- Redis 장애와 Kafka 장애가 겹치면 DB 저장 이후 cross-instance 전달이 조용히 누락될 수 있음

원인:

- `kafkaTemplate.send(...).whenComplete(...)`는 callback 안에서 실패를 관찰하지만, 호출자인 `ChatIngestService`로 실패를 전파하지 않는다.

### 실제 재현 결과

실행 일시:

- 2026-04-28 20:16~20:22 KST

실행 조건:

- 애플리케이션 프로필: `dev,loadtest`
- 인프라: `docker-compose.yml`의 MySQL, Redis, Kafka
- 부하: k6 WebSocket stress 시나리오를 짧게 축소 실행
- 명령:

```bash
k6 run \
  -e BASE_URL=http://127.0.0.1:8080 \
  -e WS_BASE_URL=ws://127.0.0.1:8080 \
  --stage 10s:5 \
  --stage 20s:5 \
  --stage 5s:0 \
  k6/scenarios/02-websocket-stress.js
```

정상 Kafka 상태 기준선:

| 지표 | 결과 |
| --- | ---: |
| HTTP error rate | 0.00% |
| WebSocket connect success | 100.00% |
| WebSocket connect p95 | 25.19ms |
| WebSocket message round-trip p50 | 11ms |
| WebSocket message round-trip p95 | 20.74ms |
| WebSocket message round-trip p99 | 31.94ms |
| WebSocket sent / received | 1407 / 1406 |

Kafka 중단 상태:

1. `docker-compose stop kafka`로 Kafka broker 중단
2. 같은 k6 WebSocket 부하 재실행
3. Redis가 살아 있어 클라이언트 관점 WebSocket 지표는 거의 정상 유지

| 지표 | 결과 |
| --- | ---: |
| HTTP error rate | 0.00% |
| WebSocket connect success | 100.00% |
| WebSocket connect p95 | 24.59ms |
| WebSocket message round-trip p50 | 14ms |
| WebSocket message round-trip p95 | 22ms |
| WebSocket message round-trip p99 | 27.94ms |
| WebSocket sent / received | 1406 / 1406 |

관찰 로그:

```text
20:19:33 Kafka broker stopped
20:19:46 Kafka 중단 상태 k6 WebSocket 부하 시작
20:21:56 [KAFKA PUB FAIL] 발생
20:21:56 TimeoutException: Expiring 48 record(s) for chat-message-0:120002 ms has passed since batch creation
```

해석:

- Kafka publish 실패는 실제로 발생했다.
- 실패 callback은 즉시 발생하지 않고, 현재 producer 기본 설정 기준 약 120초 뒤 timeout으로 관찰됐다.
- k6 실행 중 WebSocket 지표가 정상에 가깝게 나온 이유는 Redis Pub/Sub 경로가 살아 있었기 때문이다.
- 이 결과는 사용자 체감 경로와 내구성/fallback 경로가 다르게 깨질 수 있음을 보여준다.
- 현재 구조에서는 Kafka 실패가 callback 로그로만 남고, `ChatIngestService`의 `[PUBLISH FAIL] ... enqueuing for retry` 경로로 즉시 연결되지 않는다.
- 따라서 수정 전 상태는 "겉으로는 채팅이 정상처럼 보이지만 Kafka fallback 내구성은 조용히 깨질 수 있는 상태"다.

## 6. 실험 설계

### 실험 1. Kafka 정상 상태

목표:

- 동기 확인 추가 전후 latency 변화 확인

측정:

- WebSocket round-trip p50/p95/p99
- 메시지 송신 수
- 메시지 수신 수
- publish timeout 발생 수

예상:

- p50은 큰 변화가 없어야 한다.
- p95는 Kafka 응답 지연에 따라 소폭 증가할 수 있다.

### 실험 2. Kafka 중단 상태

목표:

- Kafka publish 실패가 retry buffer에 들어가는지 확인

절차:

1. 서버, DB, Redis, Kafka 정상 실행
2. WebSocket 메시지 전송
3. Kafka broker 중단
4. 메시지 전송 지속
5. retry buffer size 증가 확인
6. Kafka 복구
7. buffer drain 확인

측정:

- DB 저장 메시지 수
- retry buffer enqueue 수
- retry buffer drain 수
- Kafka 복구 후 fanout 성공 수
- 클라이언트 중복 표시 수

### 실험 3. Redis 장애 + Kafka fallback

목표:

- Redis가 죽었을 때 Kafka 경로가 fanout을 대행하는지 확인

측정:

- Redis down 구간 메시지 전달률
- Kafka consumer fanout 수
- dedupe hit 수
- 중복 표시 수

## 7. 포트폴리오 설명 문장

아래 문장을 기준으로 정리할 수 있다.

> 기존 구조에서는 Kafka publish 실패가 비동기 callback 로그로만 남아, 메시지를 저장한 ingest 계층이 실패를 인지하지 못했다. 이 상태에서는 Redis 장애 시 Kafka가 fallback 역할을 한다고 설명하기 어렵다고 판단했다. 대안으로 비동기 인터페이스 변경, callback 기반 retry, outbox 패턴, producer 설정 보강을 비교했고, 현재 프로젝트 규모에서는 짧은 timeout 기반 Kafka publish 확인과 retry buffer 연결을 선택했다. 이 선택은 outbox보다 보장 수준은 낮지만, 구조 복잡도를 크게 늘리지 않으면서 publish 실패를 관측 가능하고 재시도 가능한 상태로 만든다.

## 8. 남은 한계

- 짧은 timeout 기반 동기 확인은 장기 장애에 대한 완전한 해법은 아니다.
- retry buffer가 인메모리이므로 서버가 죽으면 buffer 안의 메시지는 사라질 수 있다.
- 운영 수준의 내구성이 필요해지면 outbox 패턴으로 확장해야 한다.
- Kafka publish 성공과 실제 consumer fanout 성공은 다른 문제다. consumer 실패/DLQ/재처리 정책은 별도 설계가 필요하다.
