# 방 삭제 및 자동 종료 기능 구현

**작업일**: 2026-01-23

## 개요

모임 종료 시나리오(방장 삭제, 시간 경과)를 처리하고, 종료된 방에 대한 접근을 차단하는 기능을 구현했습니다.

---

## 구현 내용

### 1. Room 엔티티 수정

**파일**: `src/main/java/io/hyun424/openchat/chat/room/domain/Room.java`

#### 추가된 필드
```java
@Enumerated(EnumType.STRING)
@Column(nullable = false)
@Builder.Default
private RoomStatus status = RoomStatus.ACTIVE;

@Column(name = "deleted_at")
private LocalDateTime deletedAt;
```

#### 추가된 메서드
```java
// 방장이 방 삭제 시 호출
public void delete() {
    this.status = RoomStatus.ENDED;
    this.deletedAt = LocalDateTime.now();
}

// 스케줄러에 의한 자동 종료 시 호출
public void end() {
    this.status = RoomStatus.ENDED;
}

// 방 접근 가능 여부 확인
public boolean isAccessible() {
    return this.status == RoomStatus.ACTIVE;
}
```

---

### 2. RoomStatus Enum

**파일**: `src/main/java/io/hyun424/openchat/chat/room/domain/RoomStatus.java`

```java
public enum RoomStatus {
    ACTIVE,  // 정상 진행 중인 모임
    ENDED    // 종료된 모임 (시간 경과 또는 방장 삭제)
}
```

---

### 3. 방 삭제 API

**엔드포인트**: `DELETE /api/rooms/{roomId}`

**파일**: `src/main/java/io/hyun424/openchat/chat/room/controller/RoomController.java`

#### 특징
- 방장만 삭제 가능
- Soft Delete 방식 (status → ENDED, deletedAt 기록)
- 삭제 시 해당 방의 모든 WebSocket 세션 즉시 종료
- 응답: 204 No Content

#### 에러 케이스
| 상황 | HTTP Status | ErrorCode |
|------|-------------|-----------|
| 방이 존재하지 않음 | 404 | ROOM_NOT_FOUND |
| 이미 종료된 방 | 410 | ROOM_ENDED |
| 방장이 아님 | 403 | NOT_ROOM_OWNER |

---

### 4. 자동 종료 스케줄러

**파일**: `src/main/java/io/hyun424/openchat/chat/room/service/RoomScheduler.java`

```java
@Scheduled(fixedRate = 600_000) // 10분마다 실행
@Transactional
public void autoEndExpiredRooms() {
    // meetingDate가 지났거나
    // meetingDate가 오늘이고 meetingTime이 지난 경우
    List<Room> expiredRooms = roomRepository.findExpiredRooms(today, now);

    for (Room room : expiredRooms) {
        room.end();
        roomSessionRegistry.closeAllSessionsInRoom(room.getId());
    }
}
```

#### 종료 조건
- `meetingDate < 오늘`
- `meetingDate = 오늘 AND meetingTime < 현재시간`

#### 주의
- `@EnableScheduling` 애노테이션 추가됨 (`OpenchatApplication.java`)

---

### 5. ENDED 방 접근 차단

#### 영향받는 API

| API | 변경 내용 |
|-----|----------|
| `GET /api/rooms` | ACTIVE 상태만 반환 |
| `GET /api/rooms/map` | ACTIVE 상태만 반환 |
| `GET /api/rooms/my` | ACTIVE 상태만 반환 |
| `POST /api/rooms/{roomId}/enter` | ENDED 방이면 410 에러 |

#### Repository 변경

**파일**: `src/main/java/io/hyun424/openchat/chat/room/repository/RoomRepository.java`

```java
// ACTIVE 상태만 조회
List<Room> findAllByStatusOrderByCreatedAtDesc(RoomStatus status);

// 지도용: ACTIVE + 위치 정보 있는 방
List<Room> findAllByStatusAndLatIsNotNullAndLngIsNotNull(RoomStatus status);

// 자동 종료 대상 조회
@Query("SELECT r FROM Room r " +
       "WHERE r.status = 'ACTIVE' " +
       "AND r.meetingDate IS NOT NULL " +
       "AND (r.meetingDate < :today " +
       "     OR (r.meetingDate = :today AND r.meetingTime IS NOT NULL AND r.meetingTime < :now))")
List<Room> findExpiredRooms(@Param("today") LocalDate today, @Param("now") LocalTime now);
```

---

### 6. WebSocket 종료 처리

**파일**: `src/main/java/io/hyun424/openchat/infra/websocket/session/RoomSessionRegistry.java`

#### 추가된 메서드
```java
public void closeAllSessionsInRoom(Long roomId) {
    Set<WebSocketSession> sessions = roomSessions.remove(roomId);
    for (WebSocketSession session : sessions) {
        if (session.isOpen()) {
            session.close(new CloseStatus(4001, "Room has been ended"));
        }
    }
}
```

#### WebSocket Handler 변경

**파일**: `src/main/java/io/hyun424/openchat/infra/websocket/handler/ChatWebSocketHandler.java`

- 연결 시: 방 상태 체크, ENDED면 즉시 종료
- 메시지 전송 시: 방 상태 체크, ENDED면 세션 종료

---

### 7. ErrorCode 추가

**파일**: `src/main/java/io/hyun424/openchat/global/exception/ErrorCode.java`

```java
ROOM_ENDED(HttpStatus.GONE, "종료된 모임입니다.")  // 410 Gone
```

---

## 프론트엔드 연동 가이드

### 방 삭제
```javascript
// 방장만 호출 가능
const response = await fetch(`/api/rooms/${roomId}`, {
  method: 'DELETE',
  headers: { 'Authorization': `Bearer ${token}` }
});

if (response.status === 204) {
  // 삭제 성공 → 목록 페이지로 이동
}
```

### WebSocket 종료 처리
```javascript
socket.onclose = (event) => {
  if (event.code === 4001) {
    // 방이 종료됨 → 안내 메시지 표시 후 목록으로 이동
    alert('모임이 종료되었습니다.');
    router.push('/rooms');
  }
};
```

### 종료된 방 접근 시
```javascript
// 410 Gone 응답 처리
if (response.status === 410) {
  alert('종료된 모임입니다.');
  router.push('/rooms');
}
```

---

## 데이터베이스 변경사항

Room 테이블에 컬럼 추가 필요:

```sql
ALTER TABLE room ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE room ADD COLUMN deleted_at DATETIME NULL;
```

> JPA hibernate.ddl-auto=update 설정 시 자동 적용됨

---

## 테스트 시나리오

1. **방장 삭제**
   - 방장이 DELETE API 호출 → 204 응답
   - 해당 방 WebSocket 연결된 모든 유저 즉시 종료 (code: 4001)
   - 방 목록에서 해당 방 사라짐

2. **자동 종료**
   - meetingDate/Time이 지난 방 → 10분 내 자동 ENDED
   - WebSocket 세션 자동 종료

3. **종료된 방 접근 시도**
   - 입장 API → 410 에러
   - WebSocket 연결 → 즉시 종료 (code: 4001)
   - 방 목록/지도에서 미표시
