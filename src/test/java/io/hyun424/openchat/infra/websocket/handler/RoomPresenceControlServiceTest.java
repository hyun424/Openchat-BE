package io.hyun424.openchat.infra.websocket.handler;

import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.mockito.Mockito.*;

class RoomPresenceControlServiceTest {

    private final RoomSessionRegistry roomSessionRegistry = mock(RoomSessionRegistry.class);
    private final ChatPipelineMetrics chatPipelineMetrics = mock(ChatPipelineMetrics.class);
    private final RoomPresenceControlService service = new RoomPresenceControlService(roomSessionRegistry, chatPipelineMetrics);

    @Test
    void handle_roomActive_marksSessionActive() {
        WebSocketSession session = session("session-1");
        ChatWebSocketMessage message = new ChatWebSocketMessage(
                ChatWebSocketMessage.TYPE_ROOM_ACTIVE,
                null,
                null,
                null,
                1L,
                123L
        );

        service.handle(session, message, 1L, "user1");

        verify(roomSessionRegistry).markActive(1L, "session-1", 123L);
        verify(chatPipelineMetrics).incrementCounter("ws.control.room_active");
    }

    @Test
    void handle_roomActiveHeartbeat_recordsHeartbeatMetric() {
        WebSocketSession session = session("session-1");
        ChatWebSocketMessage message = new ChatWebSocketMessage(
                ChatWebSocketMessage.TYPE_ROOM_ACTIVE_HEARTBEAT,
                null,
                null,
                null,
                1L,
                124L
        );

        service.handle(session, message, 1L, "user1");

        verify(roomSessionRegistry).markActive(1L, "session-1", 124L);
        verify(chatPipelineMetrics).incrementCounter("ws.control.room_active_heartbeat");
    }

    @Test
    void handle_roomPassive_marksSessionPassive() {
        WebSocketSession session = session("session-1");
        ChatWebSocketMessage message = new ChatWebSocketMessage(
                ChatWebSocketMessage.TYPE_ROOM_PASSIVE,
                null,
                null,
                null,
                1L,
                125L
        );

        service.handle(session, message, 1L, "user1");

        verify(roomSessionRegistry).markPassive(1L, "session-1", 125L);
        verify(chatPipelineMetrics).incrementCounter("ws.control.room_passive");
    }

    @Test
    void handle_mismatchedRoom_doesNotMutateSessionState() {
        WebSocketSession session = session("session-1");
        ChatWebSocketMessage message = new ChatWebSocketMessage(
                ChatWebSocketMessage.TYPE_ROOM_ACTIVE,
                null,
                null,
                null,
                2L,
                126L
        );

        service.handle(session, message, 1L, "user1");

        verify(roomSessionRegistry, never()).markActive(any(), any(), any());
        verify(roomSessionRegistry, never()).markPassive(any(), any(), any());
        verify(chatPipelineMetrics).incrementCounter("ws.control.room_mismatch");
    }

    private WebSocketSession session(String sessionId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(sessionId);
        return session;
    }
}
