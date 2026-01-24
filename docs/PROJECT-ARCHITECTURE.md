# OpenChat 프로젝트 아키텍처 문서

## 목차
1. [프로젝트 개요](#1-프로젝트-개요)
2. [기술 스택](#2-기술-스택)
3. [시스템 아키텍처](#3-시스템-아키텍처)
4. [핵심 기능별 상세 설계](#4-핵심-기능별-상세-설계)
5. [데이터베이스 설계](#5-데이터베이스-설계)
6. [메시지 파이프라인](#6-메시지-파이프라인)
7. [인증/인가 시스템](#7-인증인가-시스템)
8. [장애 대응 전략](#8-장애-대응-전략)
9. [API 설계](#9-api-설계)
10. [패키지 구조](#10-패키지-구조)

---

## 1. 프로젝트 개요

### 1.1 서비스 소개
OpenChat은 **위치 기반 실시간 모임 채팅 플랫폼**입니다. 사용자들이 오프라인 모임을 생성하고, 참가자들과 실시간으로 소통할 수 있는 서비스입니다.

### 1.2 핵심 기능
- **모임(Room) 관리**: 모임 생성, 참여, 삭제, 자동 종료
- **실시간 채팅**: WebSocket 기반 실시간 메시지 송수신
- **승인 시스템**: 방장 승인/거절 기반 입장 관리
- **위치 기반 서비스**: 모임 장소 지도 표시
- **Google OAuth 로그인**: 소셜 로그인 + 닉네임 설정

### 1.3 설계 원칙
- **Durability First**: 메시지는 반드시 DB에 먼저 저장 후 전송
- **Graceful Degradation**: Redis 장애 시 Kafka로 자동 폴백
- **Idempotency**: 서버 생성 messageId로 중복 처리 방지
- **Soft Delete**: 데이터 삭제 시 물리 삭제 대신 상태 변경

---

## 2. 기술 스택

### 2.1 Backend
| 분류 | 기술 | 버전 | 용도 |
|------|------|------|------|
| Framework | Spring Boot | 3.5.8 | 메인 프레임워크 |
| Language | Java | 17 | LTS 버전 |
| ORM | Spring Data JPA | - | DB 접근 |
| Security | Spring Security | - | 인증/인가 |
| OAuth | Spring OAuth2 Client | - | Google 로그인 |
| WebSocket | Spring WebSocket | - | 실시간 통신 |

### 2.2 Data Store
| 기술 | 용도 |
|------|------|
| MySQL 8 | 메인 데이터베이스 (영속성) |
| Redis | Pub/Sub (실시간 메시지 브로드캐스트), HotChat 메트릭 |
| Kafka | 메시지 내구성 보장, Redis 장애 시 폴백 |

### 2.3 인증
| 기술 | 용도 |
|------|------|
| JWT (jjwt 0.11.5) | Stateless 인증 토큰 |
| Google OAuth2 | 소셜 로그인 |

### 2.4 DevOps
- **환경변수 관리**: spring-dotenv (.env 파일 지원)
- **모니터링**: Spring Actuator

---

## 3. 시스템 아키텍처

### 3.1 전체 아키텍처
```
┌─────────────────────────────────────────────────────────────────────┐
│                           Client (React)                             │
│                    WebSocket + REST API + OAuth                      │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        Spring Boot Server                            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐  │
│  │ RoomController│  │MessageController│  │ ChatWebSocketHandler  │  │
│  └──────────────┘  └──────────────┘  └──────────────────────────┘  │
│           │                │                      │                  │
│           ▼                ▼                      ▼                  │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    ChatIngestService                          │   │
│  │        (메시지 유입 단일 진입점, 멱등성 보장)                    │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                              │                                       │
│         ┌────────────────────┼────────────────────┐                 │
│         ▼                    ▼                    ▼                  │
│  ┌────────────┐     ┌──────────────┐     ┌────────────────┐        │
│  │  MySQL     │     │    Redis     │     │     Kafka      │        │
│  │ (Durability)│     │  (Pub/Sub)   │     │  (Durability)  │        │
│  └────────────┘     └──────────────┘     └────────────────┘        │
│                              │                    │                  │
│                              ▼                    ▼                  │
│                     ┌──────────────┐     ┌──────────────┐           │
│                     │ Redis        │     │ Kafka        │           │
│                     │ Subscriber   │     │ Consumer     │           │
│                     └──────────────┘     └──────────────┘           │
│                              │                    │                  │
│                              └─────────┬─────────┘                  │
│                                        ▼                             │
│                              ┌──────────────────┐                   │
│                              │ ChatFanoutService │                   │
│                              │   (중복 제거)      │                   │
│                              └──────────────────┘                   │
│                                        │                             │
│                                        ▼                             │
│                              ┌──────────────────┐                   │
│                              │RoomSessionRegistry│                   │
│                              │  (WebSocket 전송) │                   │
│                              └──────────────────┘                   │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 메시지 흐름도
```
[Client]
    │ WebSocket SEND
    ▼
[ChatWebSocketHandler]
    │ 방 상태 체크, 권한 검증
    ▼
[ChatIngestService] ─────────────────────────────────┐
    │                                                 │
    │ 1. messageId 생성 (UUID)                       │
    │ 2. DB 저장 (MySQL) ← Durability First          │
    │ 3. Room lastMessage 업데이트                    │
    │ 4. Publisher 호출                               │
    ▼                                                 │
[ChatCompositePublisher]                              │
    │                                                 │
    ├─→ [Redis Pub/Sub] ───→ [ChatRedisSubscriber]   │
    │        (실시간)              │                   │
    │                              ▼                   │
    └─→ [Kafka Topic] ────→ [ChatKafkaConsumer]      │
             (내구성)              │                   │
                                   ▼                   │
                         [ChatFanoutService]          │
                              │ 중복 제거 (messageId)  │
                              ▼                       │
                    [RoomSessionRegistry]             │
                              │                       │
                              ▼                       │
                    [WebSocket Sessions]              │
                              │                       │
                              ▼                       │
                         [Client]                     │
```

---

## 4. 핵심 기능별 상세 설계

### 4.1 실시간 채팅 시스템

#### 4.1.1 메시지 Ingest (유입)
```java
// ChatIngestService.java
public void ingest(Long roomId, String senderId, String nickname,
                   String content, String clientMessageId) {
    // 1. 서버 측 messageId 생성 (멱등성 키)
    String messageId = UUID.randomUUID().toString();
    long createdAt = System.currentTimeMillis();

    // 2. DB 저장 (Durability First)
    Message saved = messageService.save(...);

    // 3. Room 메타데이터 업데이트
    roomService.updateLastMessage(roomId, createdAt, content, nickname);

    // 4. 메시지 버스로 발행
    publisher.publish(ChatMessageDto.from(saved));

    // 5. HotChat 메트릭 업데이트 (비필수)
    updateHotChatBucket(roomId, messageId);
}
```

**설계 포인트:**
- **Durability First**: DB 저장 실패 시 전체 플로우 중단 (메시지 유실 방지)
- **서버 측 messageId**: 클라이언트 전송 ID와 별개로 서버에서 생성하여 중복 처리 방지
- **HotChat 비필수**: Redis 장애 시에도 핵심 기능 동작

#### 4.1.2 메시지 Publish (발행)
```java
// ChatCompositePublisher.java (Redis + Kafka 이중 발행)
public void publish(ChatMessageDto message) {
    // 1. Redis Pub/Sub - 실시간 배달 (fire-and-forget)
    publishToRedis(message);

    // 2. Kafka - 내구성 보장
    publishToKafka(message);
}
```

**발행 전략:**
| 채널 | 목적 | 특성 |
|------|------|------|
| Redis Pub/Sub | 실시간 배달 | 빠르지만 비영속적 |
| Kafka | 내구성 + 장애 대비 | 느리지만 영속적 |

#### 4.1.3 메시지 Fanout (배포)
```java
// ChatFanoutService.java
public void fanout(ChatMessageDto message) {
    String messageId = message.getMessageId();

    // 인메모리 중복 체크 (인스턴스별)
    Long previous = dedupeCache.putIfAbsent(messageId, System.currentTimeMillis());
    if (previous != null) {
        return; // 이미 처리됨
    }

    outboundSender.send(message);
}
```

**중복 제거 메커니즘:**
- Redis와 Kafka 모두 같은 메시지를 수신할 수 있음
- `ConcurrentHashMap` 기반 인메모리 캐시로 중복 필터링
- TTL 1분 후 자동 정리 (메모리 누수 방지)

#### 4.1.4 Kafka Consumer 전략
```java
// ChatKafkaConsumer.java
@KafkaListener(topics = "chat-message")
public void consume(ChatMessageDto message, Acknowledgment ack) {
    try {
        if (redisHealthState.isUp()) {
            // Redis가 정상 → Kafka는 소비만 하고 fanout 생략
            log.debug("Redis UP, skip fanout");
        } else {
            // Redis 장애 → Kafka가 fanout 대행
            fanoutService.fanout(message);
        }
    } finally {
        ack.acknowledge();
    }
}
```

**Redis 장애 시 자동 폴백:**
```
정상 상태:
  Redis Pub/Sub → ChatRedisSubscriber → fanout ✓
  Kafka         → ChatKafkaConsumer   → skip (중복 방지)

Redis 장애:
  Redis Pub/Sub → (실패, 무시)
  Kafka         → ChatKafkaConsumer   → fanout ✓ (폴백)
```

### 4.2 모임(Room) 관리 시스템

#### 4.2.1 Room 상태 머신
```
┌──────────┐   방장 삭제 / 자동 종료   ┌──────────┐
│  ACTIVE  │ ───────────────────────→ │  ENDED   │
└──────────┘                          └──────────┘
     │                                      │
     │ 입장/채팅 가능                        │ 모든 접근 차단
     │                                      │ WebSocket 강제 종료
```

#### 4.2.2 자동 종료 스케줄러
```java
// RoomScheduler.java
@Scheduled(fixedRate = 600_000) // 10분마다
@Transactional
public void autoEndExpiredRooms() {
    LocalDate today = LocalDate.now();
    LocalTime now = LocalTime.now();

    // 만료 조건: meetingDate < today OR (meetingDate == today AND meetingTime < now)
    List<Room> expiredRooms = roomRepository.findExpiredRooms(today, now);

    for (Room room : expiredRooms) {
        room.end();
        roomSessionRegistry.closeAllSessionsInRoom(room.getId());
    }
}
```

#### 4.2.3 멤버 승인 시스템
```
[입장 요청]
     │
     ▼
┌─────────────────────┐
│ requiresApproval?   │
└─────────────────────┘
     │          │
    Yes        No
     │          │
     ▼          ▼
┌─────────┐  ┌──────────┐
│ PENDING │  │ APPROVED │
└─────────┘  └──────────┘
     │
     │ 방장 승인
     ▼
┌──────────┐
│ APPROVED │
└──────────┘
```

### 4.3 WebSocket 세션 관리

#### 4.3.1 연결 생명주기
```java
// ChatWebSocketHandler.java

// 연결 수립
public void afterConnectionEstablished(WebSocketSession session) {
    Long roomId = extractRoomId(session);

    // 1. 방 상태 검증 (ENDED면 거부)
    Room room = roomService.getRoomOrThrow(roomId);
    if (!room.isAccessible()) {
        session.close(new CloseStatus(4001, "Room has been ended"));
        return;
    }

    // 2. 멤버십 검증 (APPROVED만 허용)
    roomMemberService.getJoinedAtOrThrow(roomId, userId);

    // 3. 세션 등록
    roomSessionRegistry.add(roomId, session);
}

// 메시지 수신
protected void handleTextMessage(WebSocketSession session, TextMessage msg) {
    // 방 상태 재검증 (삭제된 방에 메시지 전송 방지)
    Room room = roomService.getRoomOrThrow(roomId);
    if (!room.isAccessible()) {
        session.close(new CloseStatus(4001, "Room has been ended"));
        return;
    }

    // ChatIngestService로 위임
    chatIngestService.ingest(roomId, senderId, nickname, content, clientMessageId);
}
```

#### 4.3.2 세션 레지스트리
```java
// RoomSessionRegistry.java
private final ConcurrentMap<Long, Set<WebSocketSession>> roomSessions;

// 방 전체 세션 종료 (방 삭제/종료 시)
public void closeAllSessionsInRoom(Long roomId) {
    Set<WebSocketSession> sessions = roomSessions.remove(roomId);
    for (WebSocketSession session : sessions) {
        session.close(new CloseStatus(4001, "Room has been ended"));
    }
}
```

---

## 5. 데이터베이스 설계

### 5.1 ERD
```
┌─────────────────────┐       ┌─────────────────────┐
│       users         │       │        room         │
├─────────────────────┤       ├─────────────────────┤
│ id (PK) - varchar   │       │ id (PK) - bigint    │
│ email (UK)          │       │ name                │
│ nickname (UK)       │       │ owner_id (FK)       │
│ profile_image       │       │ status (ENUM)       │
│ provider            │       │ max_members         │
│ created_at          │       │ requires_approval   │
│ last_login_at       │       │ meeting_date        │
└─────────────────────┘       │ meeting_time        │
         │                    │ lat, lng            │
         │                    │ created_at          │
         │                    │ deleted_at          │
         │                    └─────────────────────┘
         │                             │
         ▼                             ▼
┌─────────────────────┐       ┌─────────────────────┐
│    room_member      │       │    chat_message     │
├─────────────────────┤       ├─────────────────────┤
│ id (PK)             │       │ id (PK) - bigint    │
│ room_id (FK)        │       │ message_id (UK)     │
│ user_id (FK)        │       │ room_id (FK)        │
│ status (ENUM)       │       │ sender_id           │
│ joined_at           │       │ sender_nickname     │
│ left_at             │       │ content (TEXT)      │
└─────────────────────┘       │ created_at          │
                              └─────────────────────┘
```

### 5.2 테이블 상세

#### users
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | VARCHAR(255) | Google sub (OAuth ID) |
| email | VARCHAR(255) UK | 이메일 |
| nickname | VARCHAR(20) UK | 닉네임 (중복 불가) |
| provider | VARCHAR(50) | "google" |

#### room
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT | Auto Increment |
| status | ENUM('ACTIVE','ENDED') | 방 상태 |
| requires_approval | BOOLEAN | 승인 필요 여부 |
| max_members | INT | 최대 인원 (null=무제한) |
| meeting_date | DATE | 모임 날짜 |
| meeting_time | TIME | 모임 시간 |
| lat, lng | DECIMAL(10,7) | 위치 좌표 |

#### room_member
| 컬럼 | 타입 | 설명 |
|------|------|------|
| status | ENUM('PENDING','APPROVED') | 승인 상태 |
| joined_at | TIMESTAMP | 입장 시점 |
| left_at | TIMESTAMP | 퇴장 시점 (null=활성) |

#### chat_message
| 컬럼 | 타입 | 설명 |
|------|------|------|
| message_id | VARCHAR(36) UK | 서버 생성 UUID (멱등성) |
| created_at | BIGINT | epoch millis |

### 5.3 인덱스 전략
```sql
-- chat_message: 페이징 조회 최적화
INDEX idx_room_id (room_id, id)
INDEX idx_room_created (room_id, created_at)
UNIQUE INDEX uk_message_id (message_id)

-- room_member: 활성 멤버 조회
INDEX idx_room_user_active (room_id, user_id, left_at)
```

---

## 6. 메시지 파이프라인

### 6.1 Publish 전략 비교

| 구현체 | 조건 | 동작 |
|--------|------|------|
| ChatCompositePublisher | Kafka 설정 있음 | Redis + Kafka 이중 발행 |
| ChatRedisOnlyPublisher | Kafka 설정 없음 | Redis만 발행 |
| NoopChatMessagePublisher | 둘 다 없음 | 발행 안함 (로컬 전용) |

### 6.2 Subscribe 전략

```
┌─────────────────────────────────────────────────────────────┐
│                   Message Subscription                       │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Redis UP:                                                   │
│    [Redis Pub/Sub] ──→ ChatRedisSubscriber ──→ fanout()     │
│    [Kafka]         ──→ ChatKafkaConsumer  ──→ (skip)        │
│                                                              │
│  Redis DOWN:                                                 │
│    [Redis Pub/Sub] ──→ (fail, ignored)                      │
│    [Kafka]         ──→ ChatKafkaConsumer  ──→ fanout()      │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 6.3 중복 제거 전략

```java
// ChatFanoutService - 인스턴스별 인메모리 캐시
ConcurrentHashMap<String, Long> dedupeCache;

// 동작:
// 1. messageId가 캐시에 없으면 처리 + 캐시 저장
// 2. messageId가 캐시에 있으면 스킵
// 3. 1분마다 만료된 엔트리 정리
```

**왜 Redis 대신 인메모리?**
- Redis 장애 시에도 중복 제거 동작
- 같은 인스턴스 내에서만 중복 발생 가능
- 멀티 인스턴스 환경에서도 각 인스턴스가 독립적으로 처리

---

## 7. 인증/인가 시스템

### 7.1 OAuth + JWT 흐름

#### 신규 유저
```
[Frontend]                    [Backend]                      [Google]
    │                              │                              │
    │ GET /oauth2/authorization/google                            │
    │────────────────────────────→│                              │
    │                              │ 302 Redirect to Google       │
    │                              │─────────────────────────────→│
    │                              │                              │
    │                              │←──── Authorization Code ─────│
    │                              │                              │
    │                              │ Exchange code for user info  │
    │                              │─────────────────────────────→│
    │                              │                              │
    │                              │←──── User Info (sub, email)──│
    │                              │                              │
    │                              │ User not found               │
    │                              │ Create Temp Token            │
    │                              │                              │
    │←── 302 /nickname-setup?token=xxx ──│                        │
    │                              │                              │
    │ POST /api/auth/setup-nickname                               │
    │ { nickname: "홍길동" }      │                              │
    │────────────────────────────→│                              │
    │                              │ Create User                  │
    │                              │ Generate JWT                 │
    │←──────── { token: "..." } ──│                              │
```

#### 기존 유저
```
[Frontend]                    [Backend]                      [Google]
    │                              │                              │
    │ ... OAuth flow same as above ...                            │
    │                              │                              │
    │                              │ User found                   │
    │                              │ Generate JWT immediately     │
    │                              │                              │
    │←── 302 /oauth/callback?token=xxx ──│                        │
```

### 7.2 JWT 토큰 구조

#### Access Token (로그인 완료)
```json
{
  "sub": "google-oauth-id",
  "nickname": "홍길동",
  "type": "access",
  "iat": 1706000000,
  "exp": 1706043200
}
```

#### Temp Token (닉네임 설정 전)
```json
{
  "sub": "google-oauth-id",
  "type": "temp",
  "email": "user@gmail.com",
  "name": "Google Name",
  "picture": "https://...",
  "exp": 1706000600  // 10분
}
```

### 7.3 인증 필터 체인
```
[Request]
    │
    ▼
┌─────────────────────────────────────┐
│      JwtAuthenticationFilter        │
│  - Authorization 헤더에서 토큰 추출    │
│  - JWT 검증 및 파싱                   │
│  - SecurityContext에 인증 정보 설정    │
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────┐
│         Security Config             │
│  - /api/** → 인증 필요               │
│  - /oauth2/** → 허용                 │
│  - /ws/** → WebSocket 별도 처리       │
└─────────────────────────────────────┘
```

---

## 8. 장애 대응 전략

### 8.1 Redis 장애 대응

#### RedisHealthState
```java
@Component
public class RedisHealthState {
    private final AtomicBoolean available = new AtomicBoolean(true);

    public void markDown() {
        if (available.compareAndSet(true, false)) {
            log.error("[REDIS DOWN] switching to degraded mode");
        }
    }

    public void markUp() {
        if (available.compareAndSet(false, true)) {
            log.info("[REDIS UP] recovered");
        }
    }
}
```

#### 장애 시 동작
| 기능 | 정상 | Redis 장애 |
|------|------|------------|
| 메시지 발행 | Redis + Kafka | Kafka만 |
| 메시지 수신 | Redis Subscriber | Kafka Consumer |
| HotChat 메트릭 | 업데이트 | 스킵 |

### 8.2 Graceful Degradation 원칙

```
┌─────────────────────────────────────────────────────────────┐
│                    기능별 중요도                              │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Critical (장애 시 서비스 중단):                               │
│    - MySQL 연결                                              │
│    - JWT 검증                                                │
│                                                              │
│  Important (장애 시 성능 저하):                                │
│    - Redis Pub/Sub → Kafka 폴백                             │
│    - WebSocket 재연결                                        │
│                                                              │
│  Non-Critical (장애 시 기능 비활성화):                          │
│    - HotChat 메트릭                                          │
│    - OAuth (기본 로그인으로 대체 가능)                          │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 9. API 설계

### 9.1 REST API

#### Room APIs
| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | /api/rooms | 방 생성 |
| GET | /api/rooms | 방 목록 (ACTIVE만) |
| GET | /api/rooms/{id} | 방 상세 |
| DELETE | /api/rooms/{id} | 방 삭제 (방장만) |
| GET | /api/rooms/map | 지도용 목록 |
| GET | /api/rooms/my | 내 채팅방 목록 |
| POST | /api/rooms/{id}/enter | 방 입장 |

#### Member APIs
| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | /api/rooms/{id}/members/{userId}/approve | 승인 |
| POST | /api/rooms/{id}/members/{userId}/reject | 거절 |
| GET | /api/rooms/{id}/members/pending | 대기 목록 |
| GET | /api/rooms/{id}/members/count | 현재 인원 |
| GET | /api/rooms/{id}/membership | 내 상태 |

#### Message APIs
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | /api/rooms/{id}/messages | 메시지 조회 (커서 페이징) |

#### Auth APIs
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | /api/auth/check-nickname | 닉네임 중복 체크 |
| POST | /api/auth/setup-nickname | 닉네임 설정 (회원가입) |
| PATCH | /api/users/me/nickname | 닉네임 변경 |

### 9.2 WebSocket API

#### 연결
```
ws://host/ws/chat?roomId={roomId}&token={jwt}
```

#### 메시지 송신 (Client → Server)
```json
{
  "content": "안녕하세요",
  "clientMessageId": "client-uuid-123"
}
```

#### 메시지 수신 (Server → Client)
```json
{
  "messageId": "server-uuid-456",
  "roomId": 1,
  "senderId": "user-id",
  "senderNickname": "홍길동",
  "content": "안녕하세요",
  "createdAt": 1706000000000,
  "clientMessageId": "client-uuid-123"
}
```

#### 종료 코드
| Code | 의미 |
|------|------|
| 4001 | 방 종료됨 (삭제/자동종료) |

---

## 10. 패키지 구조

```
io.hyun424.openchat
├── OpenchatApplication.java          # @EnableScheduling
│
├── auth/                             # 인증 도메인
│   ├── controller/
│   │   ├── AuthController.java       # 닉네임 설정 API
│   │   └── LoginController.java      # 개발용 로그인
│   ├── dto/
│   ├── entity/
│   │   └── User.java
│   ├── jwt/
│   │   ├── JwtProvider.java          # 토큰 생성/파싱
│   │   └── JwtAuthenticationFilter.java
│   ├── oauth/
│   │   ├── OAuth2Config.java         # 조건부 OAuth 설정
│   │   └── OAuth2SuccessHandler.java
│   ├── repository/
│   └── service/
│
├── chat/                             # 채팅 도메인
│   ├── room/                         # 방 관리
│   │   ├── controller/
│   │   ├── domain/
│   │   │   ├── Room.java
│   │   │   └── RoomStatus.java
│   │   ├── dto/
│   │   ├── repository/
│   │   └── service/
│   │       ├── RoomService.java
│   │       └── RoomScheduler.java    # 자동 종료
│   │
│   ├── member/                       # 멤버 관리
│   │   ├── controller/
│   │   ├── entity/
│   │   │   ├── RoomMember.java
│   │   │   └── MemberStatus.java
│   │   ├── repository/
│   │   └── service/
│   │
│   ├── message/                      # 메시지 도메인
│   │   ├── controller/
│   │   ├── dto/
│   │   ├── entity/
│   │   │   └── Message.java
│   │   ├── repository/
│   │   └── service/
│   │
│   ├── ingest/                       # 메시지 유입
│   │   ├── ChatIngestService.java    # 단일 진입점
│   │   └── ChatMessageValidator.java
│   │
│   ├── publish/                      # 메시지 발행
│   │   ├── ChatMessagePublisher.java # 인터페이스
│   │   ├── ChatCompositePublisher.java
│   │   ├── ChatRedisOnlyPublisher.java
│   │   └── NoopChatMessagePublisher.java
│   │
│   ├── subscribe/                    # 메시지 구독
│   │   ├── ChatRedisSubscriber.java
│   │   └── ChatKafkaConsumer.java
│   │
│   ├── fanout/                       # 메시지 배포
│   │   ├── ChatFanoutService.java    # 중복 제거
│   │   ├── ChatOutboundSender.java
│   │   └── ws/
│   │       └── WebSocketOutboundSender.java
│   │
│   └── websocket/                    # WebSocket 설정
│       ├── config/
│       │   ├── WebSocketConfig.java
│       │   └── JwtHandshakeInterceptor.java
│       └── listener/
│
├── infra/                            # 인프라 계층
│   ├── redis/
│   │   ├── config/
│   │   │   ├── RedisConfig.java
│   │   │   └── RedisSubscriberConfig.java
│   │   └── health/
│   │       └── RedisHealthState.java
│   │
│   ├── kafka/
│   │   └── config/
│   │
│   ├── websocket/
│   │   ├── handler/
│   │   │   └── ChatWebSocketHandler.java
│   │   └── session/
│   │       └── RoomSessionRegistry.java
│   │
│   └── time/
│       └── BucketKeyUtil.java        # HotChat 버킷 키 생성
│
└── global/                           # 공통
    └── exception/
        ├── ErrorCode.java
        └── ApiException.java
```

---

## 면접 예상 질문

### 아키텍처 관련
1. **Q: 왜 Redis와 Kafka를 둘 다 사용하나요?**
   - Redis: 실시간 배달에 최적화 (Pub/Sub)
   - Kafka: 메시지 내구성 보장 + Redis 장애 시 폴백

2. **Q: Redis 장애 시 어떻게 대응하나요?**
   - RedisHealthState로 상태 추적
   - Kafka Consumer가 자동으로 fanout 대행
   - HotChat 등 비필수 기능은 스킵

3. **Q: 메시지 중복 전송은 어떻게 방지하나요?**
   - 서버 측 UUID messageId 생성
   - ChatFanoutService의 인메모리 dedupeCache

### 설계 관련
4. **Q: 왜 DB 저장을 먼저 하나요?**
   - Durability First 원칙
   - 메시지 브로커 장애 시에도 데이터 보존
   - 재전송 가능

5. **Q: WebSocket 세션은 어떻게 관리하나요?**
   - RoomSessionRegistry: roomId → Set<Session> 매핑
   - 방 종료 시 일괄 세션 종료

6. **Q: 승인 시스템은 왜 필요한가요?**
   - 모임 특성상 무분별한 입장 방지
   - 방장의 커뮤니티 관리 권한 부여

### 확장성 관련
7. **Q: 서버가 여러 대일 때 메시지는 어떻게 전달되나요?**
   - Redis Pub/Sub: 모든 서버가 구독하여 수신포틒
   - Kafka: 컨슈머 그룹으로 파티션별 분산 처리

8. **Q: HotChat 기능은 무엇인가요?**
   - Redis ZSet으로 최근 5분간 메시지 수 집계
   - 인기 채팅방 실시간 순위

---

*이 문서는 2026-01-23 기준으로 작성되었습니다.*
