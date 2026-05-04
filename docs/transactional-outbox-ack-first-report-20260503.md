# Transactional Outbox 기반 Ack-first 실험 보고서

## 실험 개요

- 브랜치: `perf-ack-first-transactional-outbox`
- 기준 브랜치/커밋: `perf-hot-room-controlled-realtime` / `7562eeb`
- GCP run id: `250503-2035-outbox`
- 시나리오: `target-500-ramped`
- 조건:
  - 500 VU
  - 60초 점진 입장
  - 120초 채팅 유지
  - 사용자당 1초 1메시지
  - App 4대, LB 1대, MySQL 1대, Redis 1대, k6 1대

## 변경 목표

기존 구조에서는 WebSocket 메시지 입력 후 request path에서 다음 작업을 한 번에 처리했다.

```text
메시지 수신
-> DB 저장
-> rooms.last_message 업데이트
-> Redis publish
-> hotchat 업데이트
-> sender ack
```

이 구조에서는 `chat.ack`가 DB 저장뿐 아니라 Redis/방 메타데이터/핫챗 업데이트 지연까지 함께 영향을 받았다.

이번 실험의 목표는 `chat.ack` 의미를 "DB commit 성공"으로 고정하고, Redis publish와 부가 업데이트를 outbox worker로 분리하는 것이다.

## 코드 변경 요약

### 1. outbox_event 테이블 추가

추가 파일:

- `src/main/java/io/hyun424/openchat/chat/outbox/OutboxEvent.java`
- `src/main/java/io/hyun424/openchat/chat/outbox/OutboxEventStatus.java`
- `src/main/java/io/hyun424/openchat/chat/outbox/OutboxEventRepository.java`
- `src/main/java/io/hyun424/openchat/chat/outbox/OutboxPayloadSerializer.java`

`chat_message` 저장과 같은 DB transaction 안에서 `outbox_event`를 같이 저장한다.

상태는 `PENDING`, `PROCESSING`, `PUBLISHED`, `FAILED`를 사용한다.

### 2. 메시지 저장 경로 분리

추가 파일:

- `src/main/java/io/hyun424/openchat/chat/ingest/ChatMessagePersistenceService.java`
- `src/main/java/io/hyun424/openchat/chat/ingest/PersistedChatMessage.java`

`ChatMessagePersistenceService.persistWithOutbox(...)`가 다음을 하나의 transaction으로 처리한다.

```text
chat_message insert
outbox_event insert
commit
```

이 메서드가 성공적으로 끝난 뒤에만 WebSocket handler가 `chat.ack`를 보낸다.

### 3. ChatIngestService request path 축소

수정 파일:

- `src/main/java/io/hyun424/openchat/chat/ingest/ChatIngestService.java`

request path에서 제거한 작업:

- `RoomService.updateLastMessage(...)`
- `ChatMessagePublisher.publish(...)`
- Redis hotchat bucket 업데이트
- publish retry buffer 직접 사용

현재 request path는 다음처럼 단순해졌다.

```text
메시지 수신
-> 중복 clientMessageId 확인
-> chat_message + outbox_event 저장
-> 저장된 DTO 반환
-> sender ack
```

### 4. Outbox worker 추가

추가 파일:

- `src/main/java/io/hyun424/openchat/chat/outbox/OutboxEventProcessor.java`
- `src/main/java/io/hyun424/openchat/chat/outbox/OutboxEventWorker.java`

worker 처리 순서:

```text
PENDING 이벤트 조회
-> PROCESSING claim
-> payloadJson 역직렬화
-> rooms.last_message 업데이트
-> Redis publish
-> hotchat 업데이트
-> PUBLISHED 처리
```

실패하면 retry backoff 후 다시 `PENDING`으로 돌린다. 최대 재시도 횟수를 넘으면 `FAILED`가 된다.

### 5. Redis publish 실패를 outbox가 인지하도록 변경

수정 파일:

- `src/main/java/io/hyun424/openchat/chat/publish/ChatRedisOnlyPublisher.java`
- `src/main/java/io/hyun424/openchat/chat/publish/ChatPublishException.java`

기존에는 Redis publish 실패가 내부에서 삼켜질 수 있었다. outbox에서는 실패를 알아야 재시도할 수 있으므로, Redis down 또는 publish 실패 시 예외를 던지도록 변경했다.

### 6. hotchat 업데이트 메서드 분리

수정 파일:

- `src/main/java/io/hyun424/openchat/hotchat/HotChatService.java`

outbox worker에서 호출할 수 있도록 `recordMessageActivity(roomId, messageId)`를 추가했다.

### 7. Ack metric 추가

수정 파일:

- `src/main/java/io/hyun424/openchat/infra/websocket/handler/ChatWebSocketHandler.java`

`chat.ack` 전송 구간을 `ack.after_commit`으로 측정한다.

## 검증 결과

### 로컬 검증

```text
./gradlew test
BUILD SUCCESSFUL
```

```text
terraform -chdir=infra/gcp-loadtest validate
Success! The configuration is valid.
```

### GCP 500 ramped 결과

| 지표 | controlled realtime 기준 | transactional outbox | 판단 |
| --- | ---: | ---: | --- |
| `chat_ack_roundtrip_ms p50` | 129ms | 14ms | 개선 |
| `chat_ack_roundtrip_ms p95` | 534ms | 20ms | 크게 개선 |
| `chat_ack_roundtrip_ms p99` | 760ms | 34ms | 크게 개선 |
| `ws_visible_freshness_ms p50` | 151ms | 43.4s | 악화 |
| `ws_visible_freshness_ms p95` | 498ms | 92.2s | 크게 악화 |
| WebSocket 연결 성공률 | 100% | 100% | 유지 |
| HTTP error rate | 0% | 0% | 유지 |
| HTTP 요청 수 | 1002 | 1002 | 폭증 없음 |
| 송신 메시지 수 | 59621 | 59612 | 유사 |
| 수신 ack 수 | 59500 | 59500 | 유사 |

### 서버 metric

대표적인 app metric:

```text
ingest.persist.total p95: 약 2.3~2.5ms
ingest.total p95: 약 13~14ms
ack.after_commit p95: 약 0.08~0.15ms
outbox.process.total p95: 약 20~22ms
outbox.room_update p95: 약 7.8~8.0ms
outbox.publish.redis p95: 약 1.0~1.2ms
outbox.hotchat_update p95: 약 1.4~1.7ms
ws.broadcast.lane.queue_wait p95: 약 0.1~1.9ms
```

outbox backlog:

```text
outbox.pending.count max: 약 42,127
outbox.oldest.pending.age max: 약 113초
outbox.process.success 합계: 약 18,342
outbox.process.fail 합계: 5
```

## 해석

Ack-first 자체는 효과가 매우 크다.

`chat.ack` p95가 `534ms`에서 `20ms`로 줄었다. 사용자가 "내 메시지가 서버에 저장됐다"는 피드백을 받는 시간은 크게 좋아졌다.

하지만 현재 outbox worker는 live publish 처리량을 따라가지 못했다. 테스트 중 약 59,612개 메시지를 보냈는데, Prometheus snapshot 기준 outbox worker가 publish 완료한 이벤트는 약 18,342개에 그쳤고 pending은 최대 약 42,127개까지 쌓였다.

그 결과 WebSocket lane 자체는 빠르게 비어 있었지만, lane 앞단으로 메시지가 늦게 도착했다. 그래서 `ws.broadcast.lane.queue_wait`는 낮게 유지됐는데도 `ws_visible_freshness_ms`는 p95 92초까지 악화됐다.

## 결론

이번 실험은 부분 성공이다.

- 성공한 부분: ack 병목 제거
- 실패한 부분: live stream freshness 유지

즉, request path에서 Redis publish와 부가 업데이트를 빼는 방향은 맞지만, 현재 polling outbox worker를 그대로 live fanout 경로로 쓰면 핫룸에서는 backlog가 너무 커진다.

## 다음 판단

이 상태를 그대로 채택하면 안 된다.

다음 실험은 둘 중 하나가 필요하다.

1. outbox worker를 live fanout용으로 훨씬 빠르게 만든다.
   - room별 latest metadata 업데이트와 Redis publish를 분리한다.
   - `lastMessage`는 coalescing해서 1초에 한 번만 flush한다.
   - Redis publish는 outbox에서 먼저 처리하고, room/hotchat 업데이트는 별도 저우선순위 worker로 분리한다.

2. live Redis publish는 commit 이후 즉시 비동기 실행하고, outbox는 실패 복구용으로만 사용한다.
   - ack는 DB commit 직후 보낸다.
   - Redis publish는 빠른 async executor로 바로 시도한다.
   - 실패한 경우에만 outbox worker가 재시도한다.

현재 결과만 보면 2번이 사용자 체감에는 더 맞다. outbox를 live path 자체로 쓰면 durable하긴 하지만, polling/DB claim 비용 때문에 핫룸 실시간성이 무너진다.
