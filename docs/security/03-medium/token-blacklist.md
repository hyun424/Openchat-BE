# Token Blacklist Implementation

## Severity: Medium

## Purpose

Enable immediate token invalidation for:
- User logout
- Password change
- Suspicious activity
- Admin forced logout

## Implementation

### Redis-based Blacklist

```java
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {
    
    private final StringRedisTemplate redisTemplate;
    
    private static final String BLACKLIST_PREFIX = "token:blacklist:";
    
    public void blacklist(String token) {
        String jti = extractJti(token);
        long ttl = getRemainingTtl(token);
        
        if (ttl > 0) {
            redisTemplate.opsForValue()
                .set(BLACKLIST_PREFIX + jti, "1", Duration.ofMillis(ttl));
        }
    }
    
    public boolean isBlacklisted(String token) {
        String jti = extractJti(token);
        return Boolean.TRUE.equals(
            redisTemplate.hasKey(BLACKLIST_PREFIX + jti)
        );
    }
}
```

### Integration with JwtProvider

```java
public boolean validateToken(String token) {
    if (tokenBlacklistService.isBlacklisted(token)) {
        return false;
    }
    // Existing validation...
}
```

### Logout Endpoint

```java
@PostMapping("/logout")
public void logout(@RequestHeader("Authorization") String authHeader) {
    String token = extractToken(authHeader);
    tokenBlacklistService.blacklist(token);
}
```

## Storage Considerations

- Use Redis SET with TTL matching token expiration
- Memory efficient: only stores token ID, not full token
- Auto-cleanup: TTL expires with token
