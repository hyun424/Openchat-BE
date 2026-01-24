# WebSocket Token Validation

## Severity: Critical

## Vulnerability Description

WebSocket connections validate JWT tokens only at connection time. Once connected, expired tokens continue to work until the connection is closed. This allows:
- Long-lived sessions after token expiration
- Continued access after logout
- Session hijacking persistence

## Current Code Location

`src/main/java/io/hyun424/openchat/infra/websocket/handler/ChatWebSocketHandler.java`

Token is validated in `JwtHandshakeInterceptor` during WebSocket handshake, but never re-validated during the session.

## Attack Scenario

1. Attacker obtains valid JWT token
2. Establishes WebSocket connection
3. Token is revoked/expires
4. Connection remains active indefinitely
5. Attacker continues to send/receive messages

## Remediation

### 1. Periodic Token Validation

```java
@Override
protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    // Re-validate token periodically
    String token = (String) session.getAttributes().get("token");
    Long lastValidation = (Long) session.getAttributes().get("lastTokenValidation");
    
    long now = System.currentTimeMillis();
    if (lastValidation == null || now - lastValidation > TOKEN_VALIDATION_INTERVAL_MS) {
        if (!jwtProvider.validateToken(token)) {
            session.close(new CloseStatus(4001, "Token expired"));
            return;
        }
        session.getAttributes().put("lastTokenValidation", now);
    }
    
    // ... rest of message handling
}
```

### 2. Connection Timeout

```java
@Value("${websocket.max-session-duration-ms:3600000}")  // 1 hour
private long maxSessionDuration;

@Override
protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    Long connectedAt = (Long) session.getAttributes().get("connectedAt");
    if (System.currentTimeMillis() - connectedAt > maxSessionDuration) {
        session.close(new CloseStatus(4002, "Session timeout"));
        return;
    }
    // ...
}
```

### 3. Token Refresh Requirement

Require clients to send refresh heartbeat with new token:
```json
{"type": "REFRESH", "token": "new-jwt-token"}
```

## Verification Test

```bash
# 1. Get short-lived token (1 minute)
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"userId":"test"}' | jq -r .token)

# 2. Connect WebSocket
wscat -c "ws://localhost:8080/ws?roomId=1&token=$TOKEN"

# 3. Wait for token expiration (>1 minute)
# 4. Try sending message
> {"type":"CHAT","content":"test"}
# Expected: Connection closed with error 4001
```

## References

- OWASP: WebSocket Security
- RFC 6455: WebSocket Protocol Security Considerations
