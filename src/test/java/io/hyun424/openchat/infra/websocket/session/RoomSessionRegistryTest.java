package io.hyun424.openchat.infra.websocket.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    @DisplayName("pool size보다 세션이 많아도 모든 열린 세션에 메시지를 전송한다")
    void sendToRoom_moreSessionsThanPool_sendsToEveryOpenSession() throws Exception {
        RoomSessionRegistry registry = new RoomSessionRegistry(new ObjectMapper(), 2, 16);
        List<WebSocketSession> sessions = List.of(
                mockOpenSession("session-1"),
                mockOpenSession("session-2"),
                mockOpenSession("session-3"),
                mockOpenSession("session-4"),
                mockOpenSession("session-5")
        );
        sessions.forEach(session -> registry.add(1L, session));

        registry.sendToRoom(1L, message());

        for (WebSocketSession session : sessions) {
            verify(session).sendMessage(any(TextMessage.class));
        }
        assertEquals(5, registry.count(1L));
        registry.shutdownExecutor();
    }

    @Test
    @DisplayName("닫힌 세션은 전송하지 않고 registry에서 제거한다")
    void sendToRoom_closedSession_removesWithoutSend() throws Exception {
        RoomSessionRegistry registry = new RoomSessionRegistry(new ObjectMapper(), 2, 16);
        WebSocketSession closedSession = mockSession("closed-session", false);
        registry.add(1L, closedSession);

        registry.sendToRoom(1L, message());

        verify(closedSession, never()).sendMessage(any(TextMessage.class));
        assertEquals(0, registry.count(1L));
        registry.shutdownExecutor();
    }

    private WebSocketSession mockSession(String sessionId) {
        return mockSession(sessionId, true);
    }

    private WebSocketSession mockOpenSession(String sessionId) {
        return mockSession(sessionId, true);
    }

    private WebSocketSession mockSession(String sessionId, boolean open) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?roomId=1"));
        when(session.getAttributes()).thenReturn(Map.of());
        when(session.isOpen()).thenReturn(open);
        return session;
    }

    private ChatMessageDto message() {
        return ChatMessageDto.builder()
                .id(1L)
                .messageId("message-1")
                .clientMessageId("client-message-1")
                .roomId(1L)
                .senderId("user-1")
                .senderName("User 1")
                .message("hello")
                .createdAt(System.currentTimeMillis())
                .build();
    }
}
