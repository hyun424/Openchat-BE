# WebSocket Security Limits

## Severity: High

## Required Limits

1. **Message Size**: Max 10KB per message
2. **Connection Count**: Max 5 connections per user
3. **Message Rate**: 30 messages/minute, burst of 5/second
4. **Session Duration**: Max 4 hours

## Implementation

### Message Size Limit

```java
private static final int MAX_MESSAGE_SIZE = 10 * 1024;  // 10KB

@Override
protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    if (message.getPayloadLength() > MAX_MESSAGE_SIZE) {
        sendError(session, "Message too large (max 10KB)");
        return;
    }
    // ...
}
```

### Connection Limit

```java
public boolean canConnect(String userId) {
    int currentConnections = getUserConnectionCount(userId);
    return currentConnections < MAX_CONNECTIONS_PER_USER;
}
```

### Configure in Spring

```java
@Configuration
public class WebSocketConfig implements WebSocketConfigurer {
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatHandler, "/ws")
            .setAllowedOrigins(allowedOrigins)
            .addInterceptors(jwtInterceptor);
    }
    
    @Bean
    public ServletServerContainerFactoryBean containerFactory() {
        ServletServerContainerFactoryBean factory = new ServletServerContainerFactoryBean();
        factory.setMaxTextMessageBufferSize(10 * 1024);
        factory.setMaxBinaryMessageBufferSize(10 * 1024);
        factory.setMaxSessionIdleTimeout(60000L);
        return factory;
    }
}
```

## Verification

```bash
# Message size test (>10KB should fail)
wscat -c "ws://localhost:8080/ws?roomId=1&token=$TOKEN"
> {"type":"CHAT","content":"<15KB of data>"}
# Expected: Error message about size limit

# Connection limit test
for i in {1..10}; do
  wscat -c "ws://localhost:8080/ws?roomId=1&token=$TOKEN" &
done
# Expected: Only first 5 connections succeed
```
