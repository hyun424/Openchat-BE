package io.hyun424.openchat.chat.room.partition.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.room.partition.commandlog.ReconnectCommandLogService;
import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionControlCommand;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionControlHandler;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomPartitionControlSubscriberTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("target partition 세션에만 room.reconnect control payload를 전송한다")
    void reconnectCommand_sendsControlOnlyToTargetPartition() throws Exception {
        RoomSessionRegistry registry = new RoomSessionRegistry(objectMapper, 2, 16);
        RoomPartitionControlSubscriber subscriber = new RoomPartitionControlSubscriber(
                objectMapper,
                new RoomPartitionControlHandler(registry, new RoomPartitionMetrics(new SimpleMeterRegistry())),
                new RoomPartitionMetrics(new SimpleMeterRegistry())
        );
        WebSocketSession partition0 = mockOpenSession("partition-0");
        WebSocketSession partition1 = mockOpenSession("partition-1");
        registry.add(1L, 0, partition0);
        registry.add(1L, 1, partition1);

        RoomPartitionControlCommand command = RoomPartitionControlCommand.reconnect(
                1L,
                1,
                "scale_down",
                100,
                500,
                8
        );
        subscriber.onMessage(objectMapper.writeValueAsString(command), "openchat:room-partition-control:1");

        verify(partition0, never()).sendMessage(any(TextMessage.class));
        ArgumentCaptor<TextMessage> captor = forClass(TextMessage.class);
        verify(partition1).sendMessage(captor.capture());
        JsonNode payload = objectMapper.readTree(captor.getValue().getPayload());
        assertEquals("room.reconnect", payload.get("type").asText());
        assertEquals(1L, payload.get("roomId").asLong());
        assertEquals("scale_down", payload.get("reason").asText());
        assertEquals(500L, payload.get("retryAfterMs").asLong());
        assertEquals(8L, payload.get("routeVersion").asLong());
        assertEquals(command.commandId(), payload.get("commandId").asText());
        registry.shutdownExecutor();
    }

    @Test
    @DisplayName("limit까지만 reconnect control payload를 전송한다")
    void reconnectCommand_appliesLimit() throws Exception {
        RoomSessionRegistry registry = new RoomSessionRegistry(objectMapper, 2, 16);
        RoomPartitionControlSubscriber subscriber = new RoomPartitionControlSubscriber(
                objectMapper,
                new RoomPartitionControlHandler(registry, new RoomPartitionMetrics(new SimpleMeterRegistry())),
                new RoomPartitionMetrics(new SimpleMeterRegistry())
        );
        WebSocketSession first = mockOpenSession("partition-1-a");
        WebSocketSession second = mockOpenSession("partition-1-b");
        registry.add(1L, 1, first);
        registry.add(1L, 1, second);

        subscriber.onMessage(objectMapper.writeValueAsString(RoomPartitionControlCommand.reconnect(
                1L,
                1,
                "scale_down",
                1,
                500,
                8
        )), "openchat:room-partition-control:1");

        int sends = org.mockito.Mockito.mockingDetails(first).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("sendMessage"))
                .toList()
                .size()
                + org.mockito.Mockito.mockingDetails(second).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("sendMessage"))
                .toList()
                .size();
        assertEquals(1, sends);
        registry.shutdownExecutor();
    }

    @Test
    @DisplayName("malformed payload와 unknown type은 no-op 처리한다")
    void invalidCommand_isIgnored() throws Exception {
        RoomSessionRegistry registry = new RoomSessionRegistry(objectMapper, 2, 16);
        RoomPartitionControlSubscriber subscriber = new RoomPartitionControlSubscriber(
                objectMapper,
                new RoomPartitionControlHandler(registry, new RoomPartitionMetrics(new SimpleMeterRegistry())),
                new RoomPartitionMetrics(new SimpleMeterRegistry())
        );
        WebSocketSession session = mockOpenSession("partition-1");
        registry.add(1L, 1, session);

        subscriber.onMessage("{not-json", "openchat:room-partition-control:1");
        subscriber.onMessage("{\"type\":\"unknown\"}", "openchat:room-partition-control:1");

        verify(session, never()).sendMessage(any(TextMessage.class));
        registry.shutdownExecutor();
    }

    @Test
    @DisplayName("command log가 켜져 있으면 subscriber handling 결과를 node 단위로 기록한다")
    void reconnectCommand_recordsDurableHandlingLog() throws Exception {
        RoomSessionRegistry registry = new RoomSessionRegistry(objectMapper, 2, 16);
        ReconnectCommandLogService commandLogService = mock(ReconnectCommandLogService.class);
        RoomPartitionControlSubscriber subscriber = new RoomPartitionControlSubscriber(
                objectMapper,
                new RoomPartitionControlHandler(registry, new RoomPartitionMetrics(new SimpleMeterRegistry())),
                new RoomPartitionMetrics(new SimpleMeterRegistry()),
                commandLogService,
                "handler-a"
        );
        WebSocketSession session = mockOpenSession("partition-1");
        registry.add(1L, 1, session);
        RoomPartitionControlCommand command = RoomPartitionControlCommand.reconnect(
                1L,
                1,
                "scale_down",
                100,
                500,
                8
        );

        subscriber.onMessage(objectMapper.writeValueAsString(command), "openchat:room-partition-control:1");

        ArgumentCaptor<RoomPartitionControlHandler.ReconnectHandlingResult> captor =
                forClass(RoomPartitionControlHandler.ReconnectHandlingResult.class);
        verify(commandLogService).recordHandling(org.mockito.ArgumentMatchers.eq(command), org.mockito.ArgumentMatchers.eq("handler-a"), captor.capture());
        assertEquals(1, captor.getValue().openSessionsBefore());
        assertEquals(1, captor.getValue().targetedSessions());
        assertEquals(1, captor.getValue().sentSessions());
        assertEquals(0, captor.getValue().failedSessions());
        registry.shutdownExecutor();
    }

    @Test
    @DisplayName("command log 기록 실패는 reconnect control 전송 결과를 깨지 않는다")
    void reconnectCommand_ignoresCommandLogFailure() throws Exception {
        RoomSessionRegistry registry = new RoomSessionRegistry(objectMapper, 2, 16);
        ReconnectCommandLogService commandLogService = mock(ReconnectCommandLogService.class);
        RoomPartitionControlSubscriber subscriber = new RoomPartitionControlSubscriber(
                objectMapper,
                new RoomPartitionControlHandler(registry, new RoomPartitionMetrics(new SimpleMeterRegistry())),
                new RoomPartitionMetrics(new SimpleMeterRegistry()),
                commandLogService,
                "handler-a"
        );
        WebSocketSession session = mockOpenSession("partition-1");
        registry.add(1L, 1, session);
        RoomPartitionControlCommand command = RoomPartitionControlCommand.reconnect(
                1L,
                1,
                "scale_down",
                100,
                500,
                8
        );
        doThrow(new RuntimeException("db down"))
                .when(commandLogService)
                .recordHandling(org.mockito.ArgumentMatchers.eq(command), org.mockito.ArgumentMatchers.eq("handler-a"), any());

        subscriber.onMessage(objectMapper.writeValueAsString(command), "openchat:room-partition-control:1");

        verify(session).sendMessage(any(TextMessage.class));
        registry.shutdownExecutor();
    }

    private WebSocketSession mockOpenSession(String sessionId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?roomId=1"));
        when(session.getAttributes()).thenReturn(Map.of());
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
