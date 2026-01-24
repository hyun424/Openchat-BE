# Race Condition in Room Member Service

## Severity: High

## Vulnerability Description

The `join()` method in `RoomMemberService` has a TOCTOU (Time-Of-Check-Time-Of-Use) race condition that allows duplicate room memberships.

## Current Code

```java
@Transactional
public JoinResult join(Long roomId, String userId) {
    // Check if already joined
    Optional<RoomMember> existing = roomMemberRepository
        .findByRoomIdAndUserIdAndLeftAtIsNull(roomId, userId);
    
    if (existing.isPresent()) {
        throw new ApiException(ErrorCode.ALREADY_JOINED);
    }
    
    // Race window: Another request could insert here
    
    // Insert new member
    RoomMember member = roomMemberRepository.save(
        RoomMember.join(roomId, userId, requiresApproval)
    );
}
```

## Attack Scenario

1. User sends two join requests simultaneously
2. Both requests pass the "already joined" check
3. Both requests insert new rows
4. User has duplicate memberships

## Remediation

### 1. Database Unique Constraint (Recommended)

```java
@Entity
@Table(
    name = "room_member",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_room_user_active",
            columnNames = {"room_id", "user_id", "left_at"}
        )
    }
)
public class RoomMember {
```

Note: Requires handling `NULL` in `left_at` specially for partial unique index.

### 2. Distributed Lock with Redis

```java
@Transactional
public JoinResult join(Long roomId, String userId) {
    String lockKey = "lock:room:join:" + roomId + ":" + userId;
    
    boolean locked = redisTemplate.opsForValue()
        .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));
    
    if (!locked) {
        throw new ApiException(ErrorCode.CONCURRENT_REQUEST);
    }
    
    try {
        // Existing join logic
    } finally {
        redisTemplate.delete(lockKey);
    }
}
```

### 3. Pessimistic Locking

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT rm FROM RoomMember rm WHERE rm.roomId = :roomId AND rm.userId = :userId AND rm.leftAt IS NULL")
Optional<RoomMember> findForUpdate(@Param("roomId") Long roomId, @Param("userId") String userId);
```

## Verification

```bash
# Send concurrent requests
for i in {1..10}; do
  curl -X POST "http://localhost:8080/api/rooms/1/join" \
    -H "Authorization: Bearer $TOKEN" &
done
wait

# Check member count - should be 1, not 10
curl http://localhost:8080/api/rooms/1/members | jq '.length'
```
