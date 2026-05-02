package io.hyun424.openchat.infra.websocket.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.metrics.ChatPipelineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RoomSessionRegistryTest {

    private RoomSessionRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.shutdownExecutor();
        }
    }

    @Test
    @DisplayName("원본 세션으로 제거해도 decorator로 저장된 세션이 정리된다")
    void remove_originalSession_removesDecoratedSession() {
        registry = registry(8, 100);
        WebSocketSession session = mockSession("session-1");

        registry.add(1L, session);
        registry.remove(1L, session);

        assertEquals(0, registry.count(1L));
    }

    @Test
    @DisplayName("sendToRoom은 socket send 완료를 기다리지 않고 lane queue에 enqueue한 뒤 반환한다")
    void sendToRoom_returnsWithoutWaitingForSocketSend() throws Exception {
        registry = registry(1, 100);
        WebSocketSession session = mockSession("session-1");
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        doAnswer(invocation -> {
            sendStarted.countDown();
            releaseSend.await(1, TimeUnit.SECONDS);
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        registry.add(1L, session);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> registry.sendToRoom(1L, message("message-1")));

            assertDoesNotThrow(() -> future.get(100, TimeUnit.MILLISECONDS));
            assertTrue(sendStarted.await(1, TimeUnit.SECONDS));
            releaseSend.countDown();
            verify(session, timeout(1_000)).sendMessage(any(TextMessage.class));
        } finally {
            executor.shutdownNow();
            releaseSend.countDown();
        }
    }

    @Test
    @DisplayName("같은 sessionId는 항상 같은 lane으로 매핑된다")
    void laneIndexFor_mapsSameSessionToSameLane() {
        registry = registry(8, 100);

        assertThat(registry.laneIndexFor("session-1"))
                .isEqualTo(registry.laneIndexFor("session-1"));
    }

    @Test
    @DisplayName("같은 lane에 들어간 메시지는 enqueue 순서대로 전송된다")
    void sendToRoom_sameLane_sendsInEnqueueOrder() throws Exception {
        registry = registry(1, 100);
        WebSocketSession session = mockSession("session-1");
        registry.add(1L, session);

        registry.sendToRoom(1L, message("message-1"));
        registry.sendToRoom(1L, message("message-2"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, timeout(1_000).times(2)).sendMessage(captor.capture());
        ObjectMapper objectMapper = new ObjectMapper();
        List<String> messageIds = captor.getAllValues().stream()
                .map(TextMessage::getPayload)
                .map(payload -> readMessageId(objectMapper, payload))
                .toList();
        assertThat(messageIds).containsExactly("message-1", "message-2");
    }

    @Test
    @DisplayName("shutdown 이후 enqueue 실패 시 메시지를 버리지 않고 caller thread에서 전송한다")
    void sendToRoom_afterShutdown_fallsBackToCallerSend() throws Exception {
        registry = registry(1, 100);
        WebSocketSession session = mockSession("session-1");
        registry.add(1L, session);
        registry.shutdownExecutor();

        registry.sendToRoom(1L, message("message-1"));

        verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("닫힌 세션 또는 전송 실패 세션은 registry에서 정리된다")
    void sendToRoom_sendFailure_removesDeadSession() throws Exception {
        registry = registry(1, 100);
        WebSocketSession session = mockSession("session-1");
        doThrow(new RuntimeException("send failed"))
                .when(session).sendMessage(any(TextMessage.class));
        registry.add(1L, session);

        registry.sendToRoom(1L, message("message-1"));

        verify(session, timeout(1_000)).sendMessage(any(TextMessage.class));
        assertEventuallyRoomCount(1L, 0);
    }

    private RoomSessionRegistry registry(int laneCount, int queueCapacity) {
        return new RoomSessionRegistry(
                new ObjectMapper(),
                new ChatPipelineMetrics(new SimpleMeterRegistry()),
                laneCount,
                queueCapacity,
                5_000L);
    }

    private WebSocketSession mockSession(String sessionId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?roomId=1"));
        when(session.getAttributes()).thenReturn(Map.of());
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private ChatMessageDto message(String messageId) {
        return ChatMessageDto.builder()
                .messageId(messageId)
                .roomId(1L)
                .senderId("user1")
                .senderName("tester")
                .message("hello")
                .createdAt(1L)
                .build();
    }

    private String readMessageId(ObjectMapper objectMapper, String payload) {
        try {
            JsonNode jsonNode = objectMapper.readTree(payload);
            return jsonNode.get("messageId").asText();
        } catch (Exception e) {
            throw new AssertionError("Invalid JSON payload: " + payload, e);
        }
    }

    private void assertEventuallyRoomCount(Long roomId, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1_000;
        while (System.currentTimeMillis() < deadline) {
            if (registry.count(roomId) == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(registry.count(roomId)).isEqualTo(expected);
    }
}
