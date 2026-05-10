# Ack-first + Async Live Publish + Outbox Recovery 실험 보고서

작성일: 2026-05-03
브랜치: `perf-ack-first-async-live-outbox-recovery`
기준: `perf-ack-first-transactional-outbox` 체크포인트 이후 실험

## 1. 문제 정의

기존 실험에서 문제는 채팅 전송 경로에 여러 책임이 섞여 있다는 점이었다.

```text
WebSocket message
-> DB 저장
-> lastMessage 업데이트
-> hotchat 업데이트
-> Redis publish
-> fanout
-> ack
```

이 구조에서는 사용자가 메시지를 보냈을 때 받는 피드백이 `DB 저장`, `Redis publish`, `방 메타데이터 갱신`, `실시간 fanout` 지연에 같이 묶인다. 그래서 핫룸에서 부하가 커지면 사용자는 "내 메시지가 전송됐는지"조차 늦게 느낀다.

직전 `Transactional Outbox Ack-first` 실험은 ack를 빠르게 만들었지만, live 표시가 outbox worker 뒤로 밀리면서 실패했다.

- ack p95: `20ms`
- visible freshness p95: `92.2s`
- outbox pending max: 약 `42,127`

즉, 저장 보장은 좋아졌지만 사용자가 보는 채팅 화면은 사실상 멈췄다. 실제 서비스 관점에서는 실패다.

## 2. 이번 실험 목표

이번 실험의 목표는 책임을 다음처럼 분리하는 것이다.

```text
DB/outbox
  메시지 저장 보장

ack
  DB commit 직후 sender에게 빠른 피드백

async live publish
  commit 이후 Redis publish를 즉시 시도해 화면에 빠르게 표시

outbox worker
  live publish 실패 시 복구용 재시도

room metadata
  lastMessage/hotchat을 저우선순위로 1초 coalescing 갱신
```

핵심은 outbox를 live 주 경로로 쓰지 않는 것이다. outbox는 메시지 저장과 복구 보장을 위한 장치이고, 사용자 화면에 빠르게 보여주는 경로는 commit 직후 async live publish가 담당한다.

## 3. 변경한 코드

### 3.1 `ChatWebSocketHandler`

파일: `src/main/java/io/hyun424/openchat/infra/websocket/handler/ChatWebSocketHandler.java`

변경 전에는 메시지 저장 이후 후처리가 같은 흐름에 묶였다. 변경 후에는 다음 순서로 분리했다.

```text
ingest
-> chat.ack 전송
-> 새 메시지인 경우 async live publish enqueue
-> 새 메시지인 경우 room metadata buffer enqueue
```

핵심 코드 흐름:

```java
ChatIngestResult ingestResult = chatIngestService.ingest(...);
sendAck(session, ingestResult.message());
if (ingestResult.newMessage()) {
    postCommitLivePublishService.publishAsync(ingestResult.message());
    roomMetadataUpdateBuffer.enqueue(ingestResult.message());
}
```

ack 의미는 명확히 바꿨다.

- `chat.ack` = DB commit 성공
- Redis publish 성공은 ack 의미에 포함하지 않음
- 모든 사용자 fanout 완료도 ack 의미에 포함하지 않음

중복 `clientMessageId` 요청은 기존 메시지에 대한 ack만 다시 보내고, live publish/outbox를 새로 만들지 않는다.

### 3.2 `ChatIngestService` / `ChatIngestResult`

파일:

- `src/main/java/io/hyun424/openchat/chat/ingest/ChatIngestService.java`
- `src/main/java/io/hyun424/openchat/chat/ingest/ChatIngestResult.java`

`ingest()` 반환값을 `ChatMessageDto`에서 `ChatIngestResult`로 바꿨다.

```java
public record ChatIngestResult(ChatMessageDto message, boolean newMessage) {
}
```

이유는 중복 메시지와 신규 메시지를 구분하기 위해서다.

- 신규 메시지: DB + outbox 저장 후 `newMessage=true`
- 중복 메시지: 기존 메시지 조회 후 `newMessage=false`

이 구분이 없으면 중복 요청에서도 Redis publish가 다시 발생할 수 있다.

### 3.3 `ChatMessagePersistenceService`

파일: `src/main/java/io/hyun424/openchat/chat/ingest/ChatMessagePersistenceService.java`

`chat_message`와 `outbox_event`는 계속 같은 DB transaction 안에 저장한다.

```text
chat_message 저장
outbox_event 저장
commit
```

추가로 `outbox_event.nextRetryAt`을 생성 시점보다 기본 1초 뒤로 잡았다.

```java
nextRetryAt = outboxCreatedAt + initialRetryDelayMs
```

이유는 commit 직후 async live publish가 먼저 Redis publish를 시도할 시간을 주기 위해서다. 이 값이 없으면 outbox worker가 방금 저장된 이벤트를 바로 잡아가서 async live publisher와 경쟁할 수 있다.

### 3.4 `PostCommitLivePublishService`

파일: `src/main/java/io/hyun424/openchat/chat/outbox/PostCommitLivePublishService.java`

새로 추가한 컴포넌트다. DB commit 이후 별도 executor에서 Redis publish를 즉시 수행한다.

최종 구조는 아래처럼 바꿨다.

```text
executor enqueue
-> Redis publish
-> 성공 시 outbox PUBLISHED mark
-> 실패 시 outbox PENDING 유지 또는 retry 상태 기록
```

중간 실험에서는 `outbox claim -> Redis publish -> PUBLISHED` 순서로 구현했는데, 이게 live 표시를 망쳤다. Redis publish 자체는 빠른데, outbox claim/update가 publish 전에 들어가면서 live path를 DB update 지연에 묶어버렸기 때문이다.

최종 구현은 Redis publish를 먼저 하고, 성공한 뒤 outbox 상태를 `PUBLISHED`로 바꾼다.

트레이드오프:

- Redis publish 성공 후 PUBLISHED mark가 실패하면 outbox worker가 나중에 중복 publish할 수 있다.
- 이 중복은 기존 `messageId` dedupe로 방어한다.
- 대신 사용자 화면에 보이는 live path는 훨씬 빨라진다.

### 3.5 `OutboxEventProcessor`

파일: `src/main/java/io/hyun424/openchat/chat/outbox/OutboxEventProcessor.java`

outbox worker 역할을 복구 전용으로 축소했다.

변경 후 worker가 하는 일:

```text
PENDING 조회
-> PROCESSING claim
-> payload deserialize
-> Redis publish 재시도
-> 성공 시 PUBLISHED
-> 실패 시 backoff 후 PENDING 또는 FAILED
```

제거한 일:

- `RoomService.updateLastMessage(...)`
- `HotChatService.recordMessageActivity(...)`

이 둘은 live publish 복구와 성격이 다르다. outbox worker가 메타데이터 업데이트까지 같이 하면 복구 작업이 무거워지고, live 표시 지연 원인을 다시 섞게 된다.

### 3.6 `RoomMetadataUpdateBuffer`

파일: `src/main/java/io/hyun424/openchat/chat/room/metadata/RoomMetadataUpdateBuffer.java`

새로 추가한 저우선순위 메타데이터 갱신 버퍼다.

동작:

- room별 최신 메시지 1건만 메모리에 보관
- 기본 1초마다 flush
- flush 시 `lastMessage`, `hotchat` 업데이트
- 실패하면 해당 room의 최신값을 다시 pending에 넣고 다음 주기에 재시도

이렇게 한 이유는 핫룸에서 메시지마다 `rooms.last_message*` update와 hotchat update를 하지 않기 위해서다. 방 목록에 보이는 마지막 메시지는 1초 정도 늦어져도 채팅 본문 저장/표시보다 우선순위가 낮다.

주의점:

- 현재 hotchat도 room별 최신 메시지 기준으로 coalescing된다.
- 만약 hotchat 랭킹이 "메시지 개수" 정확도에 민감하다면, 이후에는 최신 메시지와 activity count를 분리해서 누적 flush해야 한다.

### 3.7 `OutboxEvent` 인덱스

파일: `src/main/java/io/hyun424/openchat/chat/outbox/OutboxEvent.java`

추가한 인덱스:

```java
@Index(name = "idx_outbox_message_id", columnList = "messageId")
```

이 인덱스가 이번 실험의 핵심 수정 중 하나였다.

`PostCommitLivePublishService`는 Redis publish 성공 후 `messageId`로 outbox row를 찾아 `PUBLISHED` 처리한다. 인덱스가 없을 때는 이 조회가 outbox table scan에 가까워져 `live_publish.total` p95가 약 `0.77s`까지 올라갔다.

인덱스 추가 후:

- `live_publish.redis` p95: 약 `0.8~1.0ms`
- `live_publish.total` p95: 약 `12.8~13.4ms`
- outbox pending max: app별 `27~44`

즉 Redis가 느린 것이 아니라, outbox mark를 위한 DB 조회가 병목이었다.

## 4. 최종 흐름

```text
Client
  -> WebSocket message

ChatWebSocketHandler
  -> ChatIngestService

ChatMessagePersistenceService
  -> chat_message + outbox_event transaction 저장
  -> commit

ChatWebSocketHandler
  -> sender에게 chat.ack 전송
  -> PostCommitLivePublishService.publishAsync()
  -> RoomMetadataUpdateBuffer.enqueue()

PostCommitLivePublishService
  -> Redis publish 즉시 시도
  -> 성공 시 outbox_event PUBLISHED
  -> 실패 시 outbox_event PENDING 유지

OutboxEventProcessor
  -> 남은 PENDING 이벤트만 Redis publish 재시도

RoomMetadataUpdateBuffer
  -> 1초마다 room별 최신 lastMessage/hotchat flush

Redis Subscriber / Fanout
  -> 기존 live 표시 경로
```

## 5. 부하테스트 결과

테스트 조건:

- GCP profile: `target-500-ramped`
- VUs: `500`
- connect ramp: `60s`
- chat duration: `120s`
- send interval: `1000ms`
- app: `4 x e2-standard-8`
- k6: `e2-standard-16`
- MySQL/Redis/LB 분리 VM

### 5.1 k6 결과 비교

| 실험 | run id | ack p95 | visible freshness p95 | 연결 성공률 | HTTP error | 비고 |
|---|---:|---:|---:|---:|---:|---|
| Controlled realtime 기준 | `250503-1800-crealtime` | `534ms` | `498ms` | `100%` | `0%` | ack가 후처리에 끌림 |
| Pure outbox 실패 | `250503-2035-outbox` | `20ms` | `92.219s` | `100%` | `0%` | live 표시가 outbox worker 뒤로 밀림 |
| Async live 1차 실패 | `250503-2113-async` | `28ms` | `107.063s` | `100%` | `0%` | publish 전 outbox claim이 live path를 막음 |
| Publish-before-mark, 인덱스 없음 | `250503-2127-fix` | `54ms` | `85.724s` | `100%` | `0%` | Redis는 빠르나 messageId 조회가 느림 |
| 최종: messageId 인덱스 추가 | `250503-2139-idx` | `61ms` | `208ms` | `100%` | `0%` | 목표 달성 |

최종 run 상세:

- exit code: `0`
- `chat_ack_roundtrip_ms`: p50 `15ms`, p95 `61ms`, p99 `139ms`
- `ws_visible_freshness_ms`: p50 `101ms`, p95 `208ms`, p99 `289ms`, max `460ms`
- WebSocket connect success: `500 / 500`
- sent: `59,636`
- ack received: `59,500`
- received logical messages: `6,080,338`
- received frames: `439,608`
- HTTP requests: `1,002`
- HTTP error: `0`

### 5.2 Prometheus 지표

최종 run `250503-2139-idx` 앱별 핵심 지표:

| app | live queue max | outbox pending max | outbox oldest max | live publish p95 | Redis publish p95 | lane queue wait p95 | ingest p95 |
|---|---:|---:|---:|---:|---:|---:|---:|
| app-1 | `5` | `29` | `91ms` | `13.37ms` | `1.03ms` | `1.51ms` | `16.52ms` |
| app-2 | `8` | `44` | `89ms` | `12.85ms` | `0.87ms` | `1.31ms` | `15.47ms` |
| app-3 | `9` | `27` | `40ms` | `12.85ms` | `0.91ms` | `1.24ms` | `15.47ms` |
| app-4 | `8` | `40` | `73ms` | `13.37ms` | `0.84ms` | `1.44ms` | `15.47ms` |

해석:

- Redis publish 자체는 병목이 아니다.
- live publish queue가 거의 쌓이지 않았다.
- outbox pending도 app별 최대 `27~44` 수준으로 바로 줄었다.
- WebSocket lane queue wait도 p95 약 `1~1.5ms`라 fanout lane은 이번 run에서 병목이 아니다.
- 최종 사용자 체감 지표인 visible freshness p95가 `208ms`로 들어왔다.

## 6. 개선점

이번 변경으로 얻은 개선은 세 가지다.

첫째, sender ack가 후처리 지연에서 분리됐다.

- 기준: ack p95 `534ms`
- 최종: ack p95 `61ms`
- 약 `8.8배` 개선

둘째, pure outbox 방식의 live 표시 지연을 제거했다.

- 실패 outbox: visible freshness p95 `92.219s`
- 최종: visible freshness p95 `208ms`
- live path를 outbox worker에서 빼낸 효과가 크다.

셋째, 병목 위치를 명확히 잡았다.

- Redis publish p95는 약 `1ms` 이하
- 인덱스 없는 outbox PUBLISHED mark가 실제 병목
- `messageId` 인덱스 추가 후 live publish total p95가 약 `13ms`로 감소

## 7. 트레이드오프와 주의점

### 7.1 ack 의미 변경

`chat.ack`는 이제 "DB commit 성공"이다.

장점:

- 사용자는 내 메시지가 서버에 저장됐다는 피드백을 빠르게 받는다.
- Redis/fanout 지연 때문에 입력 경험이 느려지지 않는다.

단점:

- ack를 받았다고 해서 모든 사용자에게 실시간 표시가 끝난 것은 아니다.
- 클라이언트/문서에서 ack 의미를 명확히 해야 한다.

### 7.2 중복 publish 가능성

Redis publish 성공 후 outbox `PUBLISHED` mark가 실패하면, worker가 나중에 같은 메시지를 다시 publish할 수 있다.

대응:

- 기존 `messageId` dedupe가 fanout 중복 전송을 방어한다.
- 이 정책은 "사용자 live 표시 속도"를 위해 의도적으로 선택한 트레이드오프다.

### 7.3 hotchat coalescing 정확도

현재 `RoomMetadataUpdateBuffer`는 room별 최신 메시지 1건만 유지한다. `lastMessage`에는 적합하지만, hotchat 랭킹이 메시지 개수 기반이라면 활동량이 과소 반영될 수 있다.

후속 개선:

- `lastMessage`: 최신 1건 coalescing
- `hotchat`: room별 count 누적 후 주기 flush

이렇게 분리하는 편이 더 정확하다.

### 7.4 운영 DB schema

현재 loadtest/dev 환경은 `ddl-auto=update`를 활용한다. 운영 환경에서 이 구조를 쓰려면 별도 migration이 필요하다.

필수 인덱스:

```sql
CREATE INDEX idx_outbox_message_id ON outbox_event (message_id);
```

실제 컬럼명이 JPA naming strategy에 따라 `message_id` 또는 `messageId`일 수 있으므로 운영 DDL은 실제 생성 스키마를 확인한 뒤 작성해야 한다.

## 8. 결론

이번 실험은 성공으로 보는 것이 맞다.

핫룸에서 모든 메시지를 모든 사용자에게 즉시 push하는 대신, 다음 기준으로 책임을 나눈 구조가 효과를 냈다.

- 메시지 저장 보장: DB transaction + outbox
- sender 피드백: DB commit 직후 ack
- 사용자 화면 흐름: async live publish
- Redis publish 실패 복구: outbox worker
- 방 목록/인기방 부가 업데이트: low-priority coalescing buffer

최종 결과는 500명 ramped hot room 기준으로:

- ack p95 `61ms`
- visible freshness p95 `208ms`
- WebSocket 연결 성공률 `100%`
- HTTP error `0%`
- outbox pending max app별 `27~44`
- live publish p95 약 `13ms`

이제 다음 단계는 기능을 더 섞는 것이 아니라, 이 구조를 기준선으로 고정하고 다음 두 가지를 정리하는 것이다.

1. 운영용 outbox schema migration 문서화
2. hotchat activity count와 lastMessage coalescing 분리

