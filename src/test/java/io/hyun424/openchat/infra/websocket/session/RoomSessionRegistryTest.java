package io.hyun424.openchat.infra.websocket.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoomSessionRegistryTest {

    @Test
    @DisplayName("원본 세션으로 제거해도 decorator로 저장된 세션이 정리된다")
    void remove_originalSession_removesDecoratedSession() {
        RoomSessionRegistry registry = new RoomSessionRegistry(new ObjectMapper());
        WebSocketSession session = mockSession("session-1");

        registry.add(1L, session);
        registry.remove(1L, session);

        assertEquals(0, registry.count(1L));
        registry.shutdownExecutor();
    }

    private WebSocketSession mockSession(String sessionId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?roomId=1"));
        when(session.getAttributes()).thenReturn(Map.of());
        return session;
    }
}
