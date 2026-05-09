package io.hyun424.openchat.chat.room.partition.commandlog;

import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionControlCommand;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionControlHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconnectCommandLogServiceTest {

    private final ReconnectCommandLogRepository commandRepository = mock(ReconnectCommandLogRepository.class);
    private final ReconnectCommandHandlingLogRepository handlingRepository = mock(ReconnectCommandHandlingLogRepository.class);
    private final ReconnectCommandLogService service = new ReconnectCommandLogService(
            commandRepository,
            handlingRepository,
            true,
            true
    );

    @Test
    @DisplayName("publish attempt를 commandId 단위로 durable audit row에 기록한다")
    void recordPublishAttempt_createsAttemptedCommandLog() {
        RoomPartitionControlCommand command = RoomPartitionControlCommand.nodeReconnect(
                "node-a",
                "node_drain",
                50,
                250
        );

        service.recordPublishAttempt(command, "node_drain:node-a", "publisher-a");

        ArgumentCaptor<ReconnectCommandLog> captor = forClass(ReconnectCommandLog.class);
        verify(commandRepository).upsertPublishAttempt(captor.capture());
        ReconnectCommandLog saved = captor.getValue();
        assertEquals(command.commandId(), saved.getCommandId());
        assertEquals("node_drain:node-a", saved.getOperationId());
        assertEquals("publisher-a", saved.getPublisherNodeId());
        assertEquals("node-a", saved.getTargetNodeId());
        assertEquals(ReconnectCommandPublishStatus.ATTEMPTED, saved.getPublishStatus());
    }

    @Test
    @DisplayName("publish success는 Redis receiver count와 함께 기록한다")
    void markPublishSucceeded_updatesPublishStatus() {
        service.markPublishSucceeded("reconnect-a", 2L);

        verify(commandRepository).markPublishSucceeded(org.mockito.ArgumentMatchers.eq("reconnect-a"), org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("subscriber handling result를 commandId + handlerNodeId 단위로 기록한다")
    void recordHandling_upsertsHandlingLog() {
        RoomPartitionControlCommand command = RoomPartitionControlCommand.nodeReconnect(
                "node-a",
                "node_drain",
                50,
                250
        );
        RoomPartitionControlHandler.ReconnectHandlingResult result =
                new RoomPartitionControlHandler.ReconnectHandlingResult(10, 8, 7, 1, 3);

        service.recordHandling(command, "handler-a", result);

        ArgumentCaptor<ReconnectCommandHandlingLog> captor = forClass(ReconnectCommandHandlingLog.class);
        verify(handlingRepository).upsertHandling(captor.capture());
        ReconnectCommandHandlingLog saved = captor.getValue();
        assertEquals(command.commandId(), saved.getCommandId());
        assertEquals("handler-a", saved.getHandlerNodeId());
        assertEquals(ReconnectCommandHandlingStatus.PARTIAL, saved.getStatus());
        assertEquals(10, saved.getOpenSessionsBefore());
        assertEquals(8, saved.getTargetedSessions());
        assertEquals(7, saved.getSentSessions());
        assertEquals(1, saved.getFailedSessions());
        assertEquals(3, saved.getRemainingOpenSessions());
    }

    @Test
    @DisplayName("subscriber handling status는 세션 전송 결과에 따라 계산된다")
    void recordHandling_calculatesHandlingStatus() {
        RoomPartitionControlCommand command = RoomPartitionControlCommand.nodeReconnect(
                "node-a",
                "node_drain",
                50,
                250
        );
        ReconnectCommandHandlingLog log = ReconnectCommandHandlingLog.handled(
                command.commandId(),
                "handler-a",
                new RoomPartitionControlHandler.ReconnectHandlingResult(10, 8, 0, 8, 10),
                100L,
                null
        );

        assertEquals(ReconnectCommandHandlingStatus.FAILED, log.getStatus());
        assertEquals(8, log.getFailedSessions());
    }

    @Test
    @DisplayName("기존 publish row에 attempt를 다시 적용해도 terminal status를 ATTEMPTED로 낮추지 않는다")
    void markAttempted_doesNotDowngradeTerminalStatus() {
        RoomPartitionControlCommand command = RoomPartitionControlCommand.nodeReconnect(
                "node-a",
                "node_drain",
                50,
                250
        );
        ReconnectCommandLog log = ReconnectCommandLog.attempted(
                command.commandId(),
                "node_drain:node-a",
                command,
                "publisher-a",
                100L
        );
        log.markSucceeded(2L, 200L);

        log.markAttempted("node_drain:node-a", command, "publisher-b", 300L);

        assertEquals(ReconnectCommandPublishStatus.SUCCEEDED, log.getPublishStatus());
        assertEquals(2L, log.getRedisReceivers());
        assertFalse(log.getPublishedAt() == null);
    }

    @Test
    @DisplayName("command log enabled는 command trace enabled를 요구한다")
    void validateConfiguration_failsWhenCommandTraceDisabled() {
        ReconnectCommandLogService misconfigured = new ReconnectCommandLogService(
                commandRepository,
                handlingRepository,
                true,
                false
        );

        assertThrows(IllegalStateException.class, misconfigured::validateConfiguration);
    }
}
