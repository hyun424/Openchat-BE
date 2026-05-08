package io.hyun424.openchat.infra.websocket.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.SessionLimitExceededException;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

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
    @DisplayName("닫힌 세션 전송 실패는 closed_before_send로 기록한다")
    void sendToRoom_closedSession_recordsClosedBeforeSendReason() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ChatPipelineMetrics metrics = new ChatPipelineMetrics(meterRegistry);
        RoomSessionRegistry registry = registryWithMetrics(metrics);
        WebSocketSession closedSession = mockSession("closed-session", false);
        registry.add(1L, closedSession);

        registry.sendToRoom(1L, message());

        awaitCounter(meterRegistry, "ws.send.fail.closed_before_send", 1);
        assertCounter(meterRegistry, "ws.send.failed", 1);
        assertCounter(meterRegistry, "ws.send.frame.failed", 1);
        awaitRoomCount(registry, 1L, 0);
        registry.shutdownExecutor();
        metrics.shutdown();
    }

    @Test
    @DisplayName("전송 중 IOException은 io_exception으로 기록한다")
    void sendToRoom_ioException_recordsIoExceptionReason() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ChatPipelineMetrics metrics = new ChatPipelineMetrics(meterRegistry);
        RoomSessionRegistry registry = registryWithMetrics(metrics);
        WebSocketSession session = mockOpenSession("session-1");
        doThrow(new IOException("write failed")).when(session).sendMessage(any(TextMessage.class));
        registry.add(1L, session);

        registry.sendToRoom(1L, message());

        awaitCounter(meterRegistry, "ws.send.fail.io_exception", 1);
        assertCounter(meterRegistry, "ws.send.failed", 1);
        assertCounter(meterRegistry, "ws.send.frame.failed", 1);
        awaitRoomCount(registry, 1L, 0);
        verify(session, timeout(500)).close(CloseStatus.SESSION_NOT_RELIABLE);
        registry.shutdownExecutor();
        metrics.shutdown();
    }

    @Test
    @DisplayName("전송 중 IllegalStateException은 illegal_state로 기록한다")
    void sendToRoom_illegalStateException_recordsIllegalStateReason() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ChatPipelineMetrics metrics = new ChatPipelineMetrics(meterRegistry);
        RoomSessionRegistry registry = registryWithMetrics(metrics);
        WebSocketSession session = mockOpenSession("session-1");
        doThrow(new IllegalStateException("bad lifecycle state")).when(session).sendMessage(any(TextMessage.class));
        registry.add(1L, session);

        registry.sendToRoom(1L, message());

        awaitCounter(meterRegistry, "ws.send.fail.illegal_state", 1);
        assertCounter(meterRegistry, "ws.send.failed", 1);
        assertCounter(meterRegistry, "ws.send.frame.failed", 1);
        awaitRoomCount(registry, 1L, 0);
        verify(session, timeout(500)).close(CloseStatus.SESSION_NOT_RELIABLE);
        registry.shutdownExecutor();
        metrics.shutdown();
    }

    @Test
    @DisplayName("send time limit 초과는 send_time_limit으로 기록한다")
    void sendToRoom_sendTimeLimit_recordsSendTimeLimitReason() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ChatPipelineMetrics metrics = new ChatPipelineMetrics(meterRegistry);
        RoomSessionRegistry registry = registryWithMetrics(metrics);
        WebSocketSession session = mockOpenSession("session-1");
        doThrow(new SessionLimitExceededException("Send time 5001 (ms) exceeded", CloseStatus.SESSION_NOT_RELIABLE))
                .when(session).sendMessage(any(TextMessage.class));
        registry.add(1L, session);

        registry.sendToRoom(1L, message());

        awaitCounter(meterRegistry, "ws.send.fail.send_time_limit", 1);
        assertCounter(meterRegistry, "ws.send.failed", 1);
        awaitRoomCount(registry, 1L, 0);
        registry.shutdownExecutor();
        metrics.shutdown();
    }

    @Test
    @DisplayName("buffer limit 초과는 buffer_limit으로 기록한다")
    void sendToRoom_bufferLimit_recordsBufferLimitReason() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ChatPipelineMetrics metrics = new ChatPipelineMetrics(meterRegistry);
        RoomSessionRegistry registry = registryWithMetrics(metrics);
        WebSocketSession session = mockOpenSession("session-1");
        doThrow(new SessionLimitExceededException("Buffer size 65537 bytes exceeds", CloseStatus.SESSION_NOT_RELIABLE))
                .when(session).sendMessage(any(TextMessage.class));
        registry.add(1L, session);

        registry.sendToRoom(1L, message());

        awaitCounter(meterRegistry, "ws.send.fail.buffer_limit", 1);
        assertCounter(meterRegistry, "ws.send.failed", 1);
        awaitRoomCount(registry, 1L, 0);
        registry.shutdownExecutor();
        metrics.shutdown();
    }

    @Test
    @DisplayName("broadcast lane queue full 시 대상 세션을 닫고 registry에서 제거한다")
    void sendToRoom_queueFull_closesAffectedSessionsForResync() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ChatPipelineMetrics metrics = new ChatPipelineMetrics(meterRegistry);
        RoomSessionRegistry registry = new RoomSessionRegistry(new ObjectMapper(), new RoomTrafficMonitor(), metrics,
                1, 0, 1, 5000);
        WebSocketSession blocking = mockOpenSession("blocking-session");
        WebSocketSession queued = mockOpenSession("queued-session");
        WebSocketSession overloaded = mockOpenSession("overloaded-session");
        CountDownLatch firstSendStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSend = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstSendStarted.countDown();
            releaseFirstSend.await(1, TimeUnit.SECONDS);
            return null;
        }).when(blocking).sendMessage(any(TextMessage.class));
        registry.add(1L, blocking);
        registry.add(1L, queued);
        registry.add(1L, overloaded);

        registry.sendToRoom(1L, message(1L, "blocking"));
        assertTrue(firstSendStarted.await(500, TimeUnit.MILLISECONDS));
        registry.sendToRoom(1L, message(2L, "queued"));
        registry.sendToRoom(1L, message(3L, "overloaded"));

        ArgumentCaptor<CloseStatus> closeCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(blocking, timeout(500)).close(any(CloseStatus.class));
        verify(queued, timeout(500)).close(any(CloseStatus.class));
        verify(overloaded, timeout(500)).close(closeCaptor.capture());
        assertEquals(1013, closeCaptor.getValue().getCode());
        assertEquals("broadcast_queue_overloaded", closeCaptor.getValue().getReason());
        awaitCounter(meterRegistry, "ws.broadcast.lane.overload.sessions_closed", 3);
        awaitCounter(meterRegistry, "ws.broadcast.lane.overload.resync_required", 3);
        awaitRoomCount(registry, 1L, 0);

        releaseFirstSend.countDown();
        registry.shutdownExecutor();
        metrics.shutdown();
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
        verify(monitor).recordOutboundFanout(1L, 1, 1);
        verify(monitor).recordDeliveryLag(1L, message.getCreatedAt());
        verify(monitor).recordLeave(1L, 0);
        registry.shutdownExecutor();
    }

    @Test
    @DisplayName("passive 세션은 full fan-out 대상에서 제외한다")
    void sendToRoom_passiveSession_omitsFullPayload() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ChatPipelineMetrics metrics = new ChatPipelineMetrics(meterRegistry);
        RoomSessionRegistry registry = registryWithMetrics(metrics);
        WebSocketSession activeSession = mockOpenSession("active-session");
        WebSocketSession passiveSession = mockOpenSession("passive-session");
        registry.add(1L, activeSession);
        registry.add(1L, passiveSession);
        registry.markPassive(1L, "passive-session", 10L);

        registry.sendToRoom(1L, message());

        verify(activeSession, timeout(500)).sendMessage(any(TextMessage.class));
        verify(passiveSession, never()).sendMessage(any(TextMessage.class));
        assertCounter(meterRegistry, "ws.fanout.passive_omitted", 1);
        registry.shutdownExecutor();
        metrics.shutdown();
    }

    @Test
    @DisplayName("passive 세션이 active로 복귀하면 다시 full fan-out 대상이 된다")
    void sendToRoom_activeAgain_sendsFullPayload() throws Exception {
        RoomSessionRegistry registry = registryWithMetrics(ChatPipelineMetrics.noop());
        WebSocketSession session = mockOpenSession("session-1");
        registry.add(1L, session);
        registry.markPassive(1L, "session-1", 10L);
        registry.markActive(1L, "session-1", 11L);

        registry.sendToRoom(1L, message());

        verify(session, timeout(500)).sendMessage(any(TextMessage.class));
        registry.shutdownExecutor();
    }

    @Test
    @DisplayName("partition fan-out은 같은 partition 세션에만 전송한다")
    void sendToRoom_partition_sendsOnlyMatchingPartition() throws Exception {
        RoomSessionRegistry registry = new RoomSessionRegistry(new ObjectMapper(), 2, 16);
        WebSocketSession partition0 = mockOpenSession("partition-0");
        WebSocketSession partition1 = mockOpenSession("partition-1");
        registry.add(1L, 0, partition0);
        registry.add(1L, 1, partition1);

        registry.sendToRoom(1L, 1, message());

        verify(partition0, never()).sendMessage(any(TextMessage.class));
        verify(partition1, timeout(500)).sendMessage(any(TextMessage.class));
        registry.shutdownExecutor();
    }

    @Test
    @DisplayName("partition별 열린 세션 id만 조회한다")
    void openSessionIds_filtersByPartitionAndOpenState() {
        RoomSessionRegistry registry = new RoomSessionRegistry(new ObjectMapper(), 2, 16);
        WebSocketSession partition0 = mockOpenSession("partition-0");
        WebSocketSession partition1 = mockOpenSession("partition-1");
        WebSocketSession closedPartition1 = mockSession("closed-partition-1", false);
        registry.add(1L, 0, partition0);
        registry.add(1L, 1, partition1);
        registry.add(1L, 1, closedPartition1);

        Set<String> sessionIds = new HashSet<>(registry.openSessionIds(1L, 1));

        assertEquals(Set.of("partition-1"), sessionIds);
        registry.shutdownExecutor();
    }

    @Test
    @DisplayName("partition 내부에서도 passive 세션은 full fan-out 대상에서 제외한다")
    void sendToRoom_partitionPassive_omitsFullPayload() throws Exception {
        RoomSessionRegistry registry = new RoomSessionRegistry(new ObjectMapper(), 2, 16);
        WebSocketSession active = mockOpenSession("active-partition");
        WebSocketSession passive = mockOpenSession("passive-partition");
        registry.add(1L, 1, active);
        registry.add(1L, 1, passive);
        registry.markPassive(1L, "passive-partition", 10L);

        registry.sendToRoom(1L, 1, message());

        verify(active, timeout(500)).sendMessage(any(TextMessage.class));
        verify(passive, never()).sendMessage(any(TextMessage.class));
        registry.shutdownExecutor();
    }

    @Test
    @DisplayName("active heartbeat가 TTL 안에 없으면 passive로 간주해 fan-out에서 제외한다")
    void sendToRoom_activeTtlExpired_omitsFullPayload() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ChatPipelineMetrics metrics = new ChatPipelineMetrics(meterRegistry);
        RoomSessionRegistry registry = registryWithActiveTtl(metrics, 20);
        WebSocketSession session = mockOpenSession("session-1");
        registry.add(1L, session);
        Thread.sleep(30);

        registry.sendToRoom(1L, message());

        verify(session, never()).sendMessage(any(TextMessage.class));
        assertCounter(meterRegistry, "ws.fanout.passive_omitted", 1);
        registry.shutdownExecutor();
        metrics.shutdown();
    }

    @Test
    @DisplayName("active heartbeat가 들어오면 TTL이 연장된다")
    void sendToRoom_activeHeartbeatExtendsTtl_sendsFullPayload() throws Exception {
        RoomSessionRegistry registry = registryWithActiveTtl(ChatPipelineMetrics.noop(), 100);
        WebSocketSession session = mockOpenSession("session-1");
        registry.add(1L, session);
        Thread.sleep(30);
        registry.markActive(1L, "session-1", 10L);
        Thread.sleep(30);

        registry.sendToRoom(1L, message());

        verify(session, timeout(500)).sendMessage(any(TextMessage.class));
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

    private void awaitCounter(SimpleMeterRegistry registry, String event, double expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 500;
        while (System.currentTimeMillis() < deadline) {
            if (counter(registry, event) == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertCounter(registry, event, expected);
    }

    private void assertCounter(SimpleMeterRegistry registry, String event, double expected) {
        assertEquals(expected, counter(registry, event));
    }

    private double counter(SimpleMeterRegistry registry, String event) {
        Counter counter = registry.find("openchat_pipeline_events")
                .tag("event", event)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private RoomSessionRegistry registryWithMetrics(ChatPipelineMetrics metrics) {
        return new RoomSessionRegistry(new ObjectMapper(), new RoomTrafficMonitor(), metrics,
                1, 0, 16, 5000);
    }

    private RoomSessionRegistry registryWithActiveTtl(ChatPipelineMetrics metrics, long activeTtlMillis) {
        return new RoomSessionRegistry(new ObjectMapper(), new RoomTrafficMonitor(), metrics,
                1, 0, 16, 5000, activeTtlMillis);
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
