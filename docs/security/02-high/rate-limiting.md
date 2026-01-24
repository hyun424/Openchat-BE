# Rate Limiting Enhancement

## Severity: High

## Current Implementation

Basic rate limiting exists but lacks:
- Endpoint-specific limits
- Burst protection
- Proper distributed tracking

## Code Location

`src/main/java/io/hyun424/openchat/global/ratelimit/RateLimitFilter.java`

## Recommended Configuration

```properties
# HTTP API Rate Limits
ratelimit.api.default.limit=100
ratelimit.api.default.window-seconds=60

# Sensitive endpoints (lower limits)
ratelimit.api.auth.limit=10
ratelimit.api.auth.window-seconds=60

ratelimit.api.create-room.limit=5
ratelimit.api.create-room.window-seconds=60

# WebSocket Rate Limits
ratelimit.ws.messages-per-minute=30
ratelimit.ws.burst-per-second=5
```

## Implementation

### Endpoint-Specific Limits

```java
private int getLimitForPath(String path) {
    if (path.startsWith("/api/auth/")) {
        return authLimit;  // 10/min
    }
    if (path.equals("/api/rooms") && "POST".equals(method)) {
        return createRoomLimit;  // 5/min
    }
    return defaultLimit;  // 100/min
}
```

## Verification

```bash
# Default limit test (100/min)
for i in {1..110}; do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/rooms
done | grep 429
# Expected: Last 10 requests return 429

# Auth limit test (10/min)
for i in {1..15}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    "http://localhost:8080/api/auth/check-nickname?nickname=test$i"
done | grep 429
# Expected: Last 5 requests return 429
```
