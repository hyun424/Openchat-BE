# OpenChat: 실시간 모임 채팅 플랫폼 개발기

> 이 문서는 프로젝트를 구현하면서 실제로 마주한 문제들과 그 해결 과정을 기록합니다.

---

## 1. 프로젝트 개요

### 1.1 무엇을 만들었는가

OpenChat은 위치와 시간이 정해진 오프라인 모임을 위한 채팅 플랫폼이다. 단순한 채팅방이 아니라 "2026년 1월 25일 저녁 7시, 강남역 2번 출구"처럼 구체적인 모임을 만들고, 참가자들이 실시간으로 소통하는 서비스다.

### 1.2 왜 단순 채팅 구조로는 부족했는가

처음에는 간단하게 생각했다:

```
클라이언트 → WebSocket → 서버 → DB 저장 → broadcast
```

로컬에서는 잘 동작했다. 문제는 "서버를 2대로 늘리면 어떻게 되지?"라는 질문에서 시작됐다.

User A가 Server 1에, User B가 Server 2에 연결되어 있으면, A가 보낸 메시지는 B에게 전달되지 않는다. 각 서버가 자신의 WebSocket 세션만 알고 있기 때문이다.

이 프로젝트는 그 질문에서 시작해서, 분산 환경에서도 안정적으로 동작하는 채팅 시스템을 만드는 과정이었다.

---

## 2. 메시지 유실 문제와 Durability 설계

### 문제 상황

초기 구현에서는 메시지를 Redis Pub/Sub으로 먼저 발행하고, 비동기로 DB에 저장하는 방식을 썼다. "실시간성이 중요하니까 빠르게 보내고 저장은 나중에"라는 생각이었다.

그런데 테스트 중 Redis 연결이 잠깐 끊겼다가 복구되는 상황에서, 발행은 됐는데 DB 저장이 실패한 케이스가 생겼다. 사용자 입장에서는 메시지를 보냈고 상대방도 받았는데, 새로고침하면 그 메시지가 없는 상황이 발생했다.

### 원인 분석

```
[기존 흐름]
Client → Redis 발행 (성공) → 상대방 수신 (성공) → DB 저장 (실패!)
                                                      ↓
                                              메시지가 사라짐
```

발행과 저장의 순서가 잘못됐다. "보여주기"를 먼저 하고 "기록하기"를 나중에 하니, 기록이 실패하면 증거가 없어지는 것과 같았다.

### 고려했던 대안

**1안: 발행 실패 시 재시도**
- Redis 발행이 실패하면 큐에 넣고 재시도
- 문제: 재시도해도 DB 저장 실패는 해결 안 됨

**2안: DB 저장을 먼저, 발행을 나중에 (Durability First)**
- DB 저장이 성공해야만 발행
- 발행 실패해도 DB에는 있으므로 재조회 가능

### 최종 선택

2안을 선택했다. 핵심 코드:

```java
public void ingest(Long roomId, String senderId, String content, ...) {
    // 1. DB 저장 - 실패하면 여기서 중단
    Message saved = messageService.save(roomId, senderId, content, ...);

    // 2. 발행 - 실패해도 메시지는 이미 저장됨
    try {
        publisher.publish(ChatMessageDto.from(saved));
    } catch (Exception e) {
        log.error("발행 실패, 하지만 메시지는 저장됨: {}", saved.getMessageId());
        // 클라이언트가 재조회하면 볼 수 있음
    }
}
```

### 결과 및 검증

- DB 저장 실패 → 전체 실패 → 클라이언트에 에러 반환 → 사용자가 재전송
- 발행 실패 → 메시지는 DB에 있음 → 클라이언트가 새로고침하면 보임

"메시지가 보였다가 사라지는" 현상이 완전히 해결됐다. 최악의 경우에도 "안 보이다가 새로고침하면 보이는" 수준으로 degradation됐다.

---

## 3. Redis Pub/Sub 한계와 Kafka 도입

### 문제 상황

Redis Pub/Sub을 도입해서 다중 서버 문제는 해결했다. 모든 서버가 같은 채널을 구독하면, 한 서버에서 발행한 메시지를 다른 서버들도 받을 수 있다.

```
Server A (발행) → Redis Pub/Sub → Server B (수신) ✓
                              → Server C (수신) ✓
```

그런데 Redis를 잠시 내렸다가 올리는 테스트를 하다가, 그 사이에 보낸 메시지들이 그냥 사라지는 걸 발견했다. Redis Pub/Sub은 "지금 연결된 구독자"에게만 메시지를 전달한다. 구독자가 없으면 메시지는 그냥 버려진다.

### 원인 분석

Redis Pub/Sub의 특성:
- 메시지를 저장하지 않음 (fire-and-forget)
- 구독자가 없으면 메시지 유실
- 네트워크 순단 시 그 사이 메시지 유실

실시간성은 좋지만, 내구성이 없었다.

### 고려했던 대안

**1안: Redis Streams 사용**
- Redis 5.0부터 지원하는 로그 기반 자료구조
- 메시지 영속성 있음
- 문제: Redis 자체가 죽으면 여전히 문제

**2안: Kafka 단독 사용**
- 디스크 기반, 메시지 영속성 보장
- 문제: Redis Pub/Sub보다 지연이 있음 (수십 ms)

**3안: Redis + Kafka 이중 발행**
- Redis: 실시간 배달 담당
- Kafka: 내구성 보장 + Redis 장애 시 백업
- 문제: 같은 메시지가 두 번 처리될 수 있음

### 최종 선택

3안을 선택했다. 실시간성과 내구성을 모두 잡기 위해서였다.

```java
public void publish(ChatMessageDto message) {
    // 1. Redis - 빠른 실시간 배달
    publishToRedis(message);

    // 2. Kafka - 느리지만 확실한 배달
    publishToKafka(message);
}
```

중복 문제는 별도로 해결해야 했다 (다음 섹션에서 설명).

### 결과 및 검증

Redis를 강제로 종료하는 테스트:
- 기존: 메시지 전달 완전 중단
- 변경 후: Kafka Consumer가 자동으로 fanout 대행, 메시지 전달 지속

지연 시간이 약간 늘어났지만 (Redis: ~5ms → Kafka: ~30ms), 서비스는 계속 동작했다.

---

## 4. Redis + Kafka 이중 전송으로 인한 중복 메시지 문제

### 문제 상황

Redis와 Kafka 둘 다에 발행하니까, 당연히 둘 다 메시지를 받는다.

```
Publisher ──┬── Redis ──→ RedisSubscriber ──→ fanout (1번째)
            │
            └── Kafka ──→ KafkaConsumer ──→ fanout (2번째) ← 중복!
```

채팅방에서 같은 메시지가 두 번 표시되는 현상이 발생했다.

### 원인 분석

처음에는 "Kafka Consumer에서 fanout을 안 하면 되지 않나?"라고 생각했다. 하지만 그러면 Redis 장애 시 Kafka가 백업 역할을 못 한다.

문제의 본질은 "같은 메시지인지 어떻게 알 것인가"였다.

### 고려했던 대안

**1안: Redis에서 처리했으면 Kafka에서 스킵**
- `RedisHealthState`로 Redis 상태 추적
- Redis가 살아있으면 Kafka Consumer는 fanout 안 함
- 문제: Redis가 "살아있지만 일부 메시지 누락"인 경우 대응 불가

**2안: 메시지마다 고유 ID 부여 + 중복 체크**
- 서버에서 UUID 생성
- fanout 전에 "이 ID 처리한 적 있나?" 체크
- 문제: 중복 체크를 어디서 할 것인가 (Redis? 인메모리?)

**3안: 1안 + 2안 조합**
- 기본적으로 Redis 상태로 판단
- 안전장치로 messageId 기반 중복 체크 추가

### 최종 선택

3안을 선택했다. 그리고 중복 체크는 **인메모리**로 했다.

```java
public class ChatFanoutService {
    private final ConcurrentHashMap<String, Long> dedupeCache = new ConcurrentHashMap<>();

    public void fanout(ChatMessageDto message) {
        String messageId = message.getMessageId();

        // 이미 처리한 메시지면 스킵
        Long previous = dedupeCache.putIfAbsent(messageId, System.currentTimeMillis());
        if (previous != null) {
            return;
        }

        outboundSender.send(message);
    }
}
```

**왜 Redis가 아닌 인메모리인가?**

처음에는 Redis에 중복 체크를 하려고 했다. 그런데 생각해보니, Redis 장애 때문에 Kafka를 쓰는 건데, 중복 체크를 Redis에 의존하면 Redis 장애 시 중복 체크도 안 된다.

인메모리 캐시의 한계도 있다:
- 서버 재시작 시 캐시 초기화
- 재시작 직후 잠깐 중복 가능

하지만 이 정도는 감수할 만했다. 1분 내로 캐시가 다시 쌓이고, 그 사이에 같은 메시지가 두 번 발행될 확률도 낮다.

### 결과 및 검증

- Redis 정상: Redis Subscriber가 fanout, Kafka Consumer는 스킵
- Redis 장애: Kafka Consumer가 fanout, 인메모리 캐시로 중복 방지
- 양쪽 모두 수신 (edge case): 먼저 처리한 쪽이 캐시에 등록, 늦은 쪽은 스킵

채팅방에서 메시지가 두 번 뜨는 현상이 사라졌다.

---

## 5. WebSocket Fan-out과 세션 관리 문제

초기 구현 단계부터 서버를 단일 인스턴스로 가정하지 않고,
두 개 이상의 서버로 스케일아웃하는 상황을 먼저 고려했다.

WebSocket 기반 채팅은 서버 메모리에 세션 상태를 가지기 때문에,
서버가 여러 대로 늘어나는 순간
메시지 fan-out, 세션 정합성, 방 종료 처리와 같은 문제가
자연스럽게 발생할 수 있기 때문이다.

이러한 문제를 실제로 확인하기 위해
서버를 2대로 띄운 상태에서 채팅과 방 삭제 시나리오를 테스트했고,
그 과정에서 Redis/Kafka 기반 fan-out 구조와
중복 메시지 처리의 필요성이 명확해졌다.


### 문제 상황

서버를 여러 대로 띄운 환경에서,
방이 삭제되었음에도 일부 WebSocket 세션이 살아있는 문제가 발생했다.

WebSocket 세션이 서버 메모리에 종속되어 있기 때문에,
한 서버에서 방 삭제 처리가 이루어져도
다른 서버에 연결된 세션에는 해당 상태 변경이 전파되지 않았기 때문이다.

이로 인해 사용자가 채팅을 시도하면 에러가 발생하거나,
더 심각하게는 이미 삭제된 방을 대상으로
메시지 전송이 시도되는 상황도 확인할 수 있었다.

또한, 서버를 여러 대로 구성했을 때
한 서버에서 방을 삭제해도
다른 서버에 연결된 사용자들은 이를 인지하지 못하는 문제가 있었다.


### 원인 분석

1. 방 삭제 시 WebSocket 세션을 정리하는 로직이 없었음
2. 다중 서버 환경에서 "방이 삭제됨" 이벤트를 전파하는 메커니즘이 없었음

### 고려했던 대안

**1안: 클라이언트 폴링**
- 클라이언트가 주기적으로 방 상태 확인
- 문제: 실시간성 떨어짐, 불필요한 트래픽

**2안: 방 삭제 시 해당 서버의 세션만 정리**
- 삭제 API를 처리한 서버에서 세션 종료
- 문제: 다른 서버의 세션은 그대로

**3안: 방 삭제 시 모든 서버에 이벤트 전파 + 세션 강제 종료**
- 삭제 이벤트를 Redis/Kafka로 발행
- 각 서버가 수신해서 해당 방 세션 종료

**4안: 메시지 전송 시마다 방 상태 체크 + 삭제 시 로컬 세션 종료**
- 매 메시지마다 방이 ACTIVE인지 확인
- 삭제 시 `roomSessionRegistry.closeAllSessionsInRoom(roomId)` 호출

### 최종 선택

4안을 선택했다. 3안이 더 정교하지만, 현재 규모에서는 과한 설계라고 판단했다.

```java
// 방 삭제 시
public void deleteRoom(Long roomId, String userId) {
    Room room = getRoomOrThrow(roomId);
    room.delete();

    // 이 서버에 연결된 세션 종료
    roomSessionRegistry.closeAllSessionsInRoom(roomId);
}

// 메시지 전송 시
protected void handleTextMessage(WebSocketSession session, TextMessage msg) {
    Room room = roomService.getRoomOrThrow(roomId);
    if (!room.isAccessible()) {
        session.close(new CloseStatus(4001, "Room has been ended"));
        return;
    }
    // ... 메시지 처리
}
```

다른 서버의 세션은 어떻게 되나? 메시지를 보내려고 하면 그때 방 상태를 체크해서 종료된다. 약간의 지연이 있지만, 치명적이지 않다.

### 결과 및 검증

- 방 삭제 즉시: 같은 서버의 세션은 바로 종료
- 다른 서버 세션: 다음 메시지 전송 시도 시 종료
- 클라이언트: CloseStatus 4001을 받으면 "모임이 종료되었습니다" 표시

커스텀 Close 코드(4001)를 쓴 이유는 프론트엔드가 "왜 끊겼는지" 알 수 있게 하기 위해서다. 일반적인 연결 끊김과 "방 종료로 인한 끊김"을 구분할 수 있다.

---

## 6. 방 삭제 vs 자동 종료 설계

### 문제 상황

처음에는 "방 삭제 = 데이터 삭제"로 구현하려고 했다. 그런데 몇 가지 문제가 생겼다:

1. 메시지 테이블에서 `room_id`를 FK로 참조하고 있어서, 방을 삭제하려면 메시지를 먼저 다 지워야 했다.
2. "어제 참여했던 모임 기록을 보고 싶다"는 요구사항이 있었다.
3. 방장이 실수로 삭제하면 복구할 방법이 없었다.

### 원인 분석

"삭제"와 "종료"를 같은 개념으로 봤던 게 문제였다.

- 삭제: 데이터를 없앤다
- 종료: 더 이상 활동할 수 없다 (데이터는 유지)

모임 앱의 맥락에서 "방이 끝났다"는 것은 "채팅을 더 이상 할 수 없다"는 의미지, "기록이 사라진다"는 의미가 아니었다.

### 고려했던 대안

**1안: 물리 삭제 (Hard Delete)**
- 방 삭제 시 메시지도 함께 삭제
- 장점: 깔끔함
- 단점: 복구 불가, FK 문제, 감사 로그 없음

**2안: 논리 삭제 (Soft Delete)**
- `status = ENDED`로 상태 변경
- 장점: 복구 가능, FK 문제 없음, 기록 유지
- 단점: 조회 시 항상 status 조건 필요

### 최종 선택

2안 (Soft Delete)를 선택하고, 추가로 "방장 삭제"와 "자동 종료"를 구분했다.

```java
// 방장이 직접 삭제
public void delete() {
    this.status = RoomStatus.ENDED;
    this.deletedAt = LocalDateTime.now();  // 삭제 시점 기록
}

// 모임 시간이 지나서 자동 종료
public void end() {
    this.status = RoomStatus.ENDED;
    // deletedAt은 null - 자동 종료는 "삭제"가 아님
}
```

왜 구분했는가?
- 삭제: 방장의 명시적 의사결정. 언제 삭제했는지 알아야 할 수 있음
- 자동 종료: 시스템에 의한 자연스러운 종료. "삭제"라고 부르기 어색함

### 결과 및 검증

- 방 목록 조회: `WHERE status = 'ACTIVE'` 조건 추가
- My Chats: 종료된 방도 표시 가능 (과거 기록)
- 복구: `deletedAt`이 있으면 관리자가 판단 후 복구 가능
- 메시지: FK 문제 없음, 과거 채팅 기록 유지

---

## 7. 자동 종료 스케줄러 설계

### 문제 상황

모임 시간이 지났는데도 채팅방이 계속 열려있는 게 어색했다. "2026년 1월 20일 저녁 모임"인데 1월 25일에도 채팅할 수 있으면, "모임"이라는 개념이 무의미해진다.

방장이 일일이 종료해야 한다면 UX가 나빠진다. 많은 방장들이 안 할 것이다.

### 원인 분석

모임은 시간 기반 이벤트인데, 시스템이 시간 경과에 반응하지 않았다. 방을 만들 때 `meetingDate`와 `meetingTime`을 받지만, 그 시간이 지나도 아무 일도 안 일어났다.

### 고려했던 대안

**1안: 클라이언트에서 만료 체크**
- 프론트엔드가 방에 입장할 때 시간 비교
- 문제: 클라이언트를 신뢰할 수 없음, 조작 가능

**2안: API 호출 시 만료 체크**
- 방 조회, 메시지 전송 시마다 체크
- 문제: 아무도 접근 안 하면 영원히 ACTIVE

**3안: 서버 스케줄러로 주기적 체크**
- 10분마다 만료된 방 찾아서 종료
- 단점: 서버가 여러 대면 중복 실행

### 최종 선택

3안을 선택했다.

```java
@Scheduled(fixedRate = 600_000) // 10분마다
@Transactional
public void autoEndExpiredRooms() {
    LocalDate today = LocalDate.now();
    LocalTime now = LocalTime.now();

    List<Room> expiredRooms = roomRepository.findExpiredRooms(today, now);

    for (Room room : expiredRooms) {
        room.end();
        roomSessionRegistry.closeAllSessionsInRoom(room.getId());
        log.info("Auto-ended room: {}", room.getId());
    }
}
```

**중복 실행 문제는 어떻게?**

현재 규모에서는 그냥 두기로 했다. `room.end()`가 idempotent하기 때문이다. 이미 ENDED인 방에 다시 `end()`를 호출해도 상태가 바뀌지 않는다.

트래픽이 많아지면 분산 락(Redisson 등)을 도입해야 하지만, 지금은 과한 설계라고 판단했다.

### 결과 및 검증

- 모임 시간 + 10분 이내에 방 자동 종료
- WebSocket 연결된 사용자들에게 종료 알림 (CloseStatus 4001)
- 방장이 별도 조치 없이도 자연스러운 생명주기

---

## 8. OAuth 도입 과정에서의 판단

### 문제 상황

사용자 인증을 어떻게 할 것인가가 문제였다. 자체 회원가입을 만들면:
- 이메일 인증 구현해야 함
- 비밀번호 저장/암호화 해야 함
- "비밀번호 찾기" 기능 필요

MVP 단계에서 이걸 다 만드는 건 배보다 배꼽이 컸다.

### 원인 분석

"로그인"과 "회원가입"이 별개의 복잡한 기능이 되어버렸다. 핵심 기능(채팅)에 집중하기 어려웠다.

### 고려했던 대안

**1안: 자체 회원가입**
- 완전한 통제권
- 개발 비용 높음

**2안: OAuth만 사용 (Google)**
- 개발 비용 낮음
- Google 의존성 생김

**3안: OAuth + 자체 회원가입 병행**
- 유연함
- 복잡도 증가

### 최종 선택

2안 (Google OAuth만)을 선택했다. 다만 한 가지 추가 요구사항이 있었다: **닉네임은 사용자가 직접 정해야 한다**.

Google에서 받아오는 이름을 그대로 쓰면 중복될 수 있고, 원하지 않는 이름이 노출될 수 있다.

그래서 "신규 유저 → 닉네임 설정 → 회원가입 완료" 플로우를 만들었다:

```
[신규 유저]
Google OAuth 성공 → 임시 토큰 발급 → /nickname-setup으로 리다이렉트
                   → 닉네임 입력 → 정식 JWT 발급

[기존 유저]
Google OAuth 성공 → 정식 JWT 즉시 발급 → /oauth/callback으로 리다이렉트
```

**임시 토큰에는 무엇을 담았는가?**

```java
public String createTempToken(String googleId, String email, String name, String picture) {
    return Jwts.builder()
            .setSubject(googleId)
            .claim("type", "temp")  // 임시 토큰임을 표시
            .claim("email", email)
            .claim("name", name)
            .claim("picture", picture)
            .setExpiration(new Date(now + 10분))
            .build();
}
```

닉네임 설정 API에서 이 토큰을 받아서 User를 생성한다.

### 결과 및 검증

- 로그인 UX: 버튼 하나로 끝
- 닉네임 중복: DB unique constraint로 보장
- 개발 비용: 자체 회원가입 대비 크게 절감

---

## 9. 사용자 식별: senderId vs 닉네임

### 문제 상황

메시지를 저장할 때 "누가 보냈는지"를 어떻게 기록할 것인가.

처음에는 닉네임만 저장했다. 그런데 "닉네임 변경" 기능을 추가하려고 보니 문제가 생겼다.

- 홍길동이 메시지 10개를 보냄
- 닉네임을 "이몽룡"으로 변경
- 과거 메시지 10개가 "이몽룡"으로 바뀌어야 하나?

### 원인 분석

닉네임은 "표시용"이지 "식별용"이 아니다. 닉네임으로 사용자를 식별하면 닉네임 변경 시 일관성이 깨진다.

### 최종 선택

둘 다 저장하기로 했다:

```java
// Message 엔티티
private String senderId;        // Google OAuth ID (불변, 식별용)
private String senderNickname;  // 전송 시점의 닉네임 (표시용)
```

- `senderId`: 불변. "이 사람의 모든 메시지"를 찾을 때 사용
- `senderNickname`: 전송 시점 스냅샷. 화면에 표시할 때 사용

**왜 senderNickname을 저장하는가?**

매번 User 테이블을 조인하면 성능이 떨어진다. 메시지 조회는 자주 일어나는 작업이다.

또한, "과거 메시지는 그때의 닉네임으로 보여줘야 한다"는 요구사항도 있을 수 있다. (트위터처럼)

### 결과 및 검증

- 닉네임 변경: 과거 메시지에 영향 없음
- 특정 사용자 메시지 검색: senderId로 조회
- 화면 표시: senderNickname 그대로 사용 (조인 불필요)

---

## 10. 위치/지도 기능 설계에서의 경계 설정

### 문제 상황

모임에 위치 정보가 필요했다. "어디서 만날지"를 지도에 표시하고 싶었다.

처음에는 서버에서 주소 → 좌표 변환(geocoding)까지 하려고 했다. 그런데:
- 지도 API 키를 서버에 넣어야 함
- 지도 SDK마다 API가 다름
- 프론트엔드가 지도 SDK를 바꾸면 서버도 바꿔야 함

### 원인 분석

서버가 지도 SDK에 종속되어 버렸다. 지도 UI는 100% 프론트엔드 영역인데, 서버가 거기에 관여하고 있었다.

### 최종 선택

**서버는 좌표(lat, lng)만 저장한다. 지도 관련 로직은 전부 프론트엔드.**

```java
// Room 엔티티
@Column(precision = 10, scale = 7)
private BigDecimal lat;

@Column(precision = 10, scale = 7)
private BigDecimal lng;

private String locationName;  // "강남역 2번 출구" (사용자 입력)
```

플로우:
1. 프론트엔드: 지도 SDK로 장소 검색 → 좌표 + 장소명 획득
2. 프론트엔드 → 서버: `{ lat, lng, locationName }` 전달
3. 서버: 그대로 저장
4. 조회 시: 서버가 좌표 반환 → 프론트엔드가 지도에 마커 표시

**지도용 API를 분리한 이유:**

```java
GET /api/rooms      // 일반 목록
GET /api/rooms/map  // 지도용 (위치 있는 것만, 좌표 중심)
```

- 지도 화면에서는 description, rules 같은 필드가 불필요
- 위치가 없는 방은 지도에 표시할 수 없으므로 필터링 필요
- 응답 크기 최적화

### 결과 및 검증

- 서버: 지도 SDK 의존성 없음
- 프론트엔드: 카카오맵, 네이버맵, 구글맵 자유롭게 선택 가능
- 역할 분리: 서버는 데이터 저장, 프론트엔드는 UI 렌더링

---

## 11. 한계와 개선 여지

### 현재 구조의 한계

**1. 스케줄러 중복 실행**

서버가 3대면 스케줄러도 3번 실행된다. 현재는 `room.end()`가 idempotent해서 문제가 안 되지만, 알림 발송 같은 기능이 추가되면 문제가 된다.

개선: Redisson 분산 락, 또는 별도의 배치 서버

**2. 단일 Redis 인스턴스**

Redis 하나가 죽으면 Kafka로 폴백되지만, 실시간성이 떨어진다.

개선: Redis Sentinel 또는 Redis Cluster

**3. 메시지 읽음 처리 없음**

누가 메시지를 읽었는지 추적하지 않는다.

개선: Redis 기반 읽음 위치 저장, 안 읽은 메시지 카운트

**4. 푸시 알림 없음**

앱이 백그라운드일 때 새 메시지 알림이 안 간다.

개선: FCM/APNs 연동

---

## 12. 이 프로젝트를 통해 얻은 교훈

### 기술적 교훈

**1. "서버 2대"를 항상 생각하라**

로컬에서 잘 되는 것과 프로덕션에서 잘 되는 것은 다르다. WebSocket 세션, 인메모리 캐시, 스케줄러 - 모두 다중 서버 환경에서 어떻게 동작하는지 고민해야 한다.

**2. Durability와 실시간성은 트레이드오프**

둘 다 잡으려면 복잡해진다 (Redis + Kafka). 하나만 선택하면 간단하지만 한계가 있다. 서비스 특성에 맞게 판단해야 한다.

**3. 장애 상황을 먼저 그려라**

"Redis가 죽으면?", "DB 저장이 실패하면?", "메시지가 두 번 발행되면?" - 이런 질문들을 먼저 던지고 설계해야 한다. 나중에 고치기 훨씬 어렵다.

### 설계 관점의 교훈

**1. 도메인을 이해해야 좋은 설계가 나온다**

"채팅"이 아니라 "모임"이라는 도메인을 이해하고 나서야 자동 종료, 승인 시스템 같은 기능이 자연스럽게 나왔다.

**2. 과한 설계는 독이다**

지금 당장 필요하지 않은 기능(분산 락, Redis Cluster)은 나중으로 미뤘다. 미리 만들어두면 유지보수만 늘어난다.

**3. 실패를 숨기지 말라**

발행 실패, 중복 발생, 세션 누락 - 이런 상황을 로그로 남기고 모니터링할 수 있게 해야 한다. 문제가 있다는 걸 아는 것 자체가 절반의 해결이다.

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| Backend | Spring Boot 3.5, Java 17 |
| Database | MySQL 8 |
| Message Broker | Redis Pub/Sub + Apache Kafka |
| Authentication | Google OAuth2 + JWT |
| Real-time | WebSocket (Spring WebSocket) |

---

*이 문서는 실제 개발 과정에서의 의사결정을 기록한 것입니다.*
