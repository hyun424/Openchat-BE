package io.hyun424.openchat.chat.websocket.config;

import io.hyun424.openchat.auth.jwt.JwtProvider;
import io.hyun424.openchat.auth.resolver.AuthUserResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtHandshakeInterceptorTest {

    private final JwtProvider jwtProvider = mock(JwtProvider.class);
    private final JwtHandshakeInterceptor interceptor = new JwtHandshakeInterceptor(jwtProvider, new AuthUserResolver(jwtProvider));

    @Test
    @DisplayName("JWT 없이 익명 id/nickname 쿼리로 WebSocket handshake 허용")
    void beforeHandshake_anonymousQueryAllowed() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        WebSocketHandler handler = mock(WebSocketHandler.class);
        Map<String, Object> attributes = new HashMap<>();
        when(request.getURI()).thenReturn(URI.create("ws://localhost/ws/chat?roomId=1&anonymousId=browser-123&nickname=Guest-123"));

        boolean allowed = interceptor.beforeHandshake(request, response, handler, attributes);

        assertTrue(allowed);
        assertEquals("anon:browser-123", attributes.get("userId"));
        assertEquals("Guest-123", attributes.get("nickname"));
        assertEquals(true, attributes.get("anonymous"));
        assertNull(attributes.get("token"));
        verifyNoInteractions(jwtProvider);
    }
}
