# Phase 7 Active Room AI Memory / Unread Recent Summary

작성일: 2026-05-12

## Summary

Phase 7의 목표는 단순히 "최근 300개 메시지를 LLM에 던지는 요약 기능"이 아니라, **활성화된 방의 최근 대화 맥락을 AI가 미리 압축해두고 사용자가 필요할 때 바로 보여주는 Room Memory 구조**를 만드는 것이다.

사용자에게 보여줄 문구는 다음으로 고정한다.

```text
읽지 않은 최근 메시지를 요약했어요.
```

이 요약은 완전한 회의록이나 메시지 정합성 기능이 아니다. 사용자가 읽지 않은 최근 대화의 흐름을 빠르게 회복하기 위한 UX 보조 기능이다.

## Core Decision

처음에는 사용자별 `lastReadMessageId` 이후 최신 300개를 on-demand로 요약하는 안을 검토했다. 하지만 한 방에서는 대부분 같은 대화가 공유되므로, 사용자마다 AI가 다시 일하는 구조는 비용과 부하가 커질 수 있다.

따라서 Phase 7 v1은 다음 방향으로 잡는다.

```text
모든 사용자별 요약을 미리 만들지 않는다.
모든 방을 요약하지도 않는다.
최근 활동이 충분한 active room에 대해서만 room-level rolling memory를 유지한다.
사용자별 lastRead는 어떤 room memory segment를 보여줄지 고르는 필터로 사용한다.
```

## Why Active Rooms Only

전체 방을 대상으로 AI memory를 유지하면 다음 문제가 생긴다.

- inactive room까지 AI 비용이 발생한다.
- 사용자 수가 많을수록 on-demand 요약 요청이 폭증할 수 있다.
- AI worker가 DB/RAG 서버를 압박할 수 있다.
- 실제 UX 가치가 낮은 방에도 요약 리소스를 쓰게 된다.

반대로 active room만 처리하면 비용이 메시지 증가량과 active room 수에 비례한다.

```text
비용 기준:
나쁜 구조 = 사용자 수 * 요약 요청 수
선택한 구조 = active room 수 * message segment 수
```

이 구조는 AI 비용을 제어하면서도 활발한 방에서 가장 필요한 "놓친 흐름 요약"을 빠르게 제공할 수 있다.

## Active Room Policy v1

초기 기준은 단순하게 둔다.

```text
active room:
최근 1시간 메시지 수 >= 100
```

후속으로 다음 조건을 추가할 수 있다.

```text
최근 10분 메시지 수 >= 50
요약 요청이 최근에 발생한 방
participant 수가 높은 방
```

v1에서는 active room 후보 수와 AI worker 처리량을 제한한다.

```text
max active rooms per scheduler run = N
ai-worker concurrency = 2~4
segment size = 100 messages
rolling memory = recent 3 segments
```

## Room Memory Model

### Segment Summary

방 메시지를 일정 단위로 압축한다.

```text
room_summary_segment
- roomId
- startMessageId
- endMessageId
- messageCount
- summaryText
- evidenceMessageIds
- signals
- status
- createdAt
- completedAt
```

segment size v1:

```text
100 messages
```

고정 segment는 사용자에게 그대로 노출하기 위한 주제 단위가 아니다. AI 처리와 재사용을 위한 물리 단위다.

### Rolling Room Memory

최근 segment들을 합쳐 현재 방의 맥락을 유지한다.

```text
room_rolling_memory
- roomId
- startMessageId
- endMessageId
- segmentCount
- summaryText
- currentTopics
- decisions
- openQuestions
- actionItems
- updatedAt
```

rolling memory v1:

```text
최근 3개 segment
최대 약 300 messages
```

## Runtime Responsibilities

### API Role

- 요약 가능 여부 조회
- 사용자 `lastReadMessageId`와 unread count 확인
- `unreadCount >= 100`이고 room memory가 있으면 요약 버튼 노출
- room memory segment를 조회해 사용자에게 반환
- LLM/RAG 서버를 직접 호출하지 않는다.

### Realtime Role

- 관여하지 않는다.
- WebSocket fanout, partition ownership, drain/reconnect만 담당한다.
- 요약 실패나 RAG 장애가 realtime message delivery에 영향을 주면 안 된다.

### AI Worker Role

- active room 후보를 찾는다.
- 새 segment summary가 필요한지 판단한다.
- OpenChat DB에서 권한 있는 범위의 메시지 window를 조회한다.
- 내부 RAG 서버에 요약 요청을 보낸다.
- segment summary와 rolling memory를 저장한다.
- worker concurrency와 retry/backoff를 제어한다.

### Python RAG Server

- Java/OpenChat DB를 직접 조회하지 않는다.
- Java ai-worker가 전달한 message window만 처리한다.
- token-aware chunking을 수행한다.
- 중요 신호 추출, topic segmentation, importance scoring, evidence selection을 수행한다.
- 최종 segment summary와 signal metadata를 반환한다.
- 원문 메시지를 장기 저장하지 않는다.

## Java to Python Contract

Java ai-worker는 "어떤 메시지를 요약할지"를 결정한다. Python RAG 서버는 "그 메시지들에서 무엇이 중요한지"를 결정한다.

요청 예시:

```json
{
  "requestId": "room-segment-10-301-400",
  "summaryType": "ROOM_SEGMENT",
  "language": "ko",
  "room": {
    "roomId": 10,
    "roomType": "GROUP"
  },
  "range": {
    "startMessageId": 301,
    "endMessageId": 400,
    "messageCount": 100
  },
  "policy": {
    "includeSignals": true,
    "includeEvidence": true,
    "maxOutputBullets": 5
  },
  "messages": [
    {
      "messageId": 301,
      "senderId": 7,
      "senderDisplayName": "user-7",
      "sentAt": "2026-05-12T10:01:03+09:00",
      "type": "TEXT",
      "text": "..."
    }
  ]
}
```

응답 예시:

```json
{
  "requestId": "room-segment-10-301-400",
  "summaryText": "- drain reconnect pacing 정책을 논의했어요.\n- 최신 freshness SLO는 별도 gate로 분리하기로 했어요.",
  "evidenceMessageIds": [318, 344, 389],
  "signals": [
    {
      "messageId": 344,
      "type": "DECISION",
      "score": 0.91,
      "reason": "rolling restart 검증 기준을 gate-based로 분리하기로 결정함"
    }
  ],
  "chunkStats": {
    "inputMessages": 100,
    "chunksCreated": 2,
    "truncatedMessages": 0
  }
}
```

## User Summary View

사용자가 방에 들어왔을 때:

```text
unreadCount >= 100
AND rolling memory exists
```

이면 요약 버튼을 보여준다.

버튼 문구:

```text
읽지 않은 최근 메시지 요약
```

결과 문구:

```text
읽지 않은 최근 메시지를 요약했어요.
```

사용자별 `lastReadMessageId` 이후와 겹치는 최신 segment summary를 조합해 보여준다. v1에서는 segment 경계 때문에 사용자가 이미 읽은 일부 메시지가 섞이거나, 오래된 unread 일부가 제외될 수 있음을 허용한다.

이유:

- 이 기능은 정합성 기능이 아니라 맥락 회복 보조 기능이다.
- 사용자가 원하면 원본 메시지를 직접 스크롤해 확인할 수 있다.
- 정확한 개인별 재요약보다 공용 room memory 재사용이 비용/응답성 면에서 유리하다.

## Security / Privacy Rules

- RAG 서버는 내부 서비스로 본다.
- RAG 서버는 OpenChat DB 권한을 갖지 않는다.
- Java ai-worker가 권한 검증과 message window 제한을 끝낸 뒤에만 payload를 보낸다.
- message text를 application log에 남기지 않는다.
- RAG 서버는 원문을 장기 저장하지 않는다.
- Java DB에는 summary, evidence message ids, signal metadata만 저장한다.
- 외부 LLM provider로 원문이 나가는 경우에는 PII/secret masking, provider retention policy, 사용자/운영 정책을 별도로 정한다.

## Non-goals

- 모든 방의 전체 메시지 요약
- 모든 사용자별 요약을 미리 생성
- Realtime 서버에서 AI/RAG 호출
- 완전한 회의록 생성
- message delivery 정합성과 같은 수준의 보장
- message edit/delete까지 반영한 memory invalidation
- vector DB 기반 장기 검색
- 멘션 기반 개인화 요약

멘션 기능은 아직 없으므로 v1 범위에 넣지 않는다.

## Acceptance Criteria

- active room 기준을 만족한 방만 segment summary 생성 대상이 된다.
- inactive room은 AI memory를 만들지 않는다.
- segment size `100 messages`, rolling memory `recent 3 segments` 기준으로 동작한다.
- `unreadCount >= 100`이고 rolling memory가 있는 경우에만 요약 버튼을 노출한다.
- API role은 요약 조회/노출만 담당하고 LLM/RAG 서버를 직접 호출하지 않는다.
- Realtime role은 summary 기능을 모른다.
- AI worker role이 active room detection, segment build, rolling memory update를 담당한다.
- Python RAG 서버는 전달받은 message window만 처리하고 OpenChat DB를 직접 조회하지 않는다.
- summary result에는 evidence message ids와 signal metadata가 포함된다.
- 요약 실패가 채팅 송수신, ACK, DB message write에 영향을 주지 않는다.

## Portfolio Story

이 phase의 핵심 서사는 다음이다.

```text
모든 사용자 요청마다 LLM을 호출하지 않고,
활성 방에 대해서만 rolling room memory를 유지해
AI 비용을 사용자 수가 아니라 active room/message segment 수에 비례하도록 설계했다.
```

그리고 realtime 운영성과의 연결은 다음이다.

```text
AI 요약은 WebSocket realtime path에서 분리하고,
API/AI worker/RAG 서버의 비동기 경로로 처리해
fanout latency와 reconnect/drain 안정성에 영향을 주지 않도록 설계했다.
```

## Open Questions

- active room 기준을 `최근 1시간 100개`로 시작할지, `최근 10분 50개`를 함께 둘지 결정해야 한다.
- segment summary 생성 주기를 scheduler polling으로 할지, message count threshold event로 만들지 결정해야 한다.
- rolling memory merge를 Python RAG 서버가 할지, Java ai-worker가 segment summary를 단순 조합할지 결정해야 한다.
- summary signal type enum을 어디까지 둘지 결정해야 한다.
  - 후보: `QUESTION`, `DECISION`, `ACTION_ITEM`, `ISSUE`, `LINK`, `SCHEDULE`, `OPERATIONS`
- Phase 7 v1에서 실제 Python RAG 서버를 만들지, mock RAG adapter로 먼저 계약을 검증할지 결정해야 한다.

## Update: Phase 7-1 Java Contract Slice

Phase 7-1은 Python RAG 서버를 붙이기 전에 Java 쪽 계약을 먼저 고정하는 작은 구현 단위로 진행한다.

이번 slice의 목적은 다음이다.

- 사용자별 마지막 읽음 위치를 서버 DB에 보관한다.
- 요약 노출 여부를 `unreadCount >= 100 AND rolling memory exists` 기준으로 API에서 판단한다.
- active room summary job, segment, rolling memory 테이블 계약을 만든다.
- `ai-worker` role에서만 summary job planner/worker/mock summarizer가 동작하게 한다.
- Realtime role은 summary 기능을 모르고, API role은 read position/summary 조회 API만 담당하게 한다.

구현 기준은 다음으로 고정했다.

- `room_read_position.lastReadMessageId`는 `chat_message.id` cursor 기준이다.
- update는 monotonic max 정책이다. 더 작은 cursor가 들어와도 기존 read position을 뒤로 되돌리지 않는다.
- 다른 방 메시지, 없는 메시지, join 이전 메시지, non-member 요청은 거부한다.
- active room 기준은 최근 `1시간` 메시지 `100개 이상`이다.
- segment size는 `100 messages`다.
- rolling memory는 최근 `3 segments`를 조합한다.
- Phase 7-1 summarizer는 Java `RoomSegmentSummarizer` interface와 mock implementation이다.
- 실제 Python RAG HTTP 호출, vector DB, embedding, 개인별 재요약은 Phase 7-2 이후로 미룬다.

Phase 7-1에서 생긴 API는 다음이다.

- `PUT /api/rooms/{roomId}/read-position`
- `GET /api/rooms/{roomId}/read-position`
- `GET /api/rooms/{roomId}/summary/availability`
- `GET /api/rooms/{roomId}/summary`

검증할 핵심은 사용자 경험 자체보다 경계다.

- read position은 기존 cursor pagination과 같은 `chat_message.id` 기준을 사용한다.
- summary worker는 realtime process에 섞이지 않는다.
- 요약 실패가 채팅 송수신, ACK, fanout, reconnect/drain 경로에 영향을 주지 않는다.
- Phase 7-2에서 Python RAG 서버를 붙이더라도 Java API contract는 그대로 유지한다.
