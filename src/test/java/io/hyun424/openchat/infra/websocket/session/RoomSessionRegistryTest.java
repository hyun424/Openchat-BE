package io.hyun424.openchat.infra.websocket.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

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
            verify(session, timeout(500)).sendMessage(any(TextMessage.class));
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
        awaitRoomCount(registry, 1L, 0);
        registry.shutdownExecutor();
    }

    @Test
    @DisplayName("입장/퇴장과 전송 시 방 단위 traffic metric을 기록한다")
    void roomTrafficMetricsRecorded() throws Exception {
        RoomTrafficMonitor monitor = mock(RoomTrafficMonitor.class);
        RoomSessionRegistry registry = new RoomSessionRegistry(new ObjectMapper(), monitor, 2, 16);
        WebSocketSession session = mockOpenSession("session-1");
        ChatMessageDto message = message();

        registry.add(1L, session);
        registry.sendToRoom(1L, message);
        registry.remove(1L, session);

        verify(monitor).recordJoin(1L, 1);
        verify(monitor).recordOutboundFanout(1L, 1);
        verify(monitor).recordDeliveryLag(1L, message.getCreatedAt());
        verify(monitor).recordLeave(1L, 0);
        registry.shutdownExecutor();
    }

    @Test
    @DisplayName("batch envelope을 한 번 직렬화해 모든 열린 세션에 전송한다")
    void sendBatchToRoom_multipleMessages_sendsBatchEnvelopeToEveryOpenSession() throws Exception {
        RoomSessionRegistry registry = new RoomSessionRegistry(new ObjectMapper(), 2, 16);
        List<WebSocketSession> sessions = List.of(
                mockOpenSession("session-1"),
                mockOpenSession("session-2")
        );
        sessions.forEach(session -> registry.add(1L, session));

        registry.sendBatchToRoom(1L, List.of(message(1L, "message-1"), message(2L, "message-2")));

        List<String> payloads = new ArrayList<>();
        for (WebSocketSession session : sessions) {
            ArgumentCaptor<TextMessage> captor = forClass(TextMessage.class);
            verify(session, timeout(500)).sendMessage(captor.capture());
            payloads.add(captor.getValue().getPayload());
        }
        for (String payload : payloads) {
            assertTrue(payload.contains("\"type\":\"chat.batch\""));
            assertTrue(payload.contains("\"firstSequence\":1"));
            assertTrue(payload.contains("\"lastSequence\":2"));
            assertTrue(payload.contains("\"messages\""));
        }
        registry.shutdownExecutor();
    }

    @Test
    @DisplayName("controlled realtime batch는 생략 정보와 원본 lastSequence를 envelope에 담는다")
    void sendBatchToRoom_incompleteRealtime_includesGapMetadata() throws Exception {
        RoomSessionRegistry registry = new RoomSessionRegistry(new ObjectMapper(), 1, 16);
        WebSocketSession session = mockOpenSession("session-1");
        registry.add(1L, session);

        registry.sendBatchToRoom(1L, List.of(message(1L, "message-1")), false, 3, 4L);

        ArgumentCaptor<TextMessage> captor = forClass(TextMessage.class);
        verify(session, timeout(500)).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertTrue(payload.contains("\"type\":\"chat.batch\""));
        assertTrue(payload.contains("\"realtimeComplete\":false"));
        assertTrue(payload.contains("\"omittedCount\":3"));
        assertTrue(payload.contains("\"lastSequence\":4"));
        registry.shutdownExecutor();
    }

    @Test
    @DisplayName("batch 전송은 실제 socket send 완료를 기다리지 않고 반환한다")
    void sendBatchToRoom_enqueuesLaneTaskWithoutWaitingForSocketSend() throws Exception {
        RoomSessionRegistry registry = new RoomSessionRegistry(new ObjectMapper(), 1, 16);
        WebSocketSession session = mockOpenSession("session-1");
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        AtomicBoolean sendCompleted = new AtomicBoolean(false);
        doAnswer(invocation -> {
            sendStarted.countDown();
            releaseSend.await(1, TimeUnit.SECONDS);
            sendCompleted.set(true);
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        registry.add(1L, session);

        registry.sendBatchToRoom(1L, List.of(message(1L, "message-1"), message(2L, "message-2")));

        assertTrue(sendStarted.await(500, TimeUnit.MILLISECONDS));
        assertTrue(!sendCompleted.get());
        releaseSend.countDown();
        verify(session, timeout(500)).sendMessage(any(TextMessage.class));
        registry.shutdownExecutor();
    }

    private void awaitRoomCount(RoomSessionRegistry registry, Long roomId, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 500;
        while (System.currentTimeMillis() < deadline) {
            if (registry.count(roomId) == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertEquals(expected, registry.count(roomId));
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
        return message(1L, "message-1");
    }

    private ChatMessageDto message(Long id, String messageId) {
        return ChatMessageDto.builder()
                .id(id)
                .sequence(id)
                .messageId(messageId)
                .clientMessageId("client-message-1")
                .roomId(1L)
                .senderId("user-1")
                .senderName("User 1")
                .message("hello")
                .createdAt(System.currentTimeMillis())
                .build();
    }
}
