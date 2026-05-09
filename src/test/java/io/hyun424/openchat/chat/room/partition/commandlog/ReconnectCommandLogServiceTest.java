package io.hyun424.openchat.chat.room.partition.commandlog;

import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionControlCommand;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionControlHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconnectCommandLogServiceTest {

    private final ReconnectCommandLogRepository commandRepository = mock(ReconnectCommandLogRepository.class);
    private final ReconnectCommandHandlingLogRepository handlingRepository = mock(ReconnectCommandHandlingLogRepository.class);
    private final ReconnectCommandLogRetentionProperties retentionProperties = new ReconnectCommandLogRetentionProperties();
    private final ReconnectCommandLogService service = new ReconnectCommandLogService(
            commandRepository,
            handlingRepository,
            retentionProperties,
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
                retentionProperties,
                true,
                false
        );

        assertThrows(IllegalStateException.class, misconfigured::validateConfiguration);
    }

    @Test
    @DisplayName("delivery evidence는 target node handling 성공을 complete로 요약한다")
    void summarize_includesCompleteDeliveryEvidence() {
        ReconnectCommandLog commandLog = ReconnectCommandLog.fromRow(
                1L,
                "reconnect-a",
                "node_drain:node-a",
                "node_reconnect",
                null,
                null,
                "node-a",
                "publisher-a",
                "node_drain",
                null,
                50,
                250,
                ReconnectCommandPublishStatus.SUCCEEDED,
                1L,
                100L,
                110L,
                null,
                null
        );
        ReconnectCommandHandlingLog handlingLog = ReconnectCommandHandlingLog.fromRow(
                10L,
                "reconnect-a",
                "node-a",
                ReconnectCommandHandlingStatus.SENT,
                10,
                10,
                10,
                0,
                0,
                120L,
                null
        );
        when(commandRepository.findByCommandIdIn(List.of("reconnect-a"))).thenReturn(List.of(commandLog));
        when(handlingRepository.findByCommandIdIn(List.of("reconnect-a"))).thenReturn(List.of(handlingLog));

        ReconnectCommandLogService.Summary summary = service.summarize(List.of("reconnect-a"));

        assertTrue(summary.deliveryEvidence().complete());
        assertEquals(1, summary.deliveryEvidence().strictEligibleCommandCount());
        ReconnectCommandLogService.CommandDeliveryEvidence delivery = summary.records().get(0).deliveryEvidence();
        assertTrue(delivery.strictEligible());
        assertEquals("target_node", delivery.strictEligibilityReason());
        assertEquals(List.of("node-a"), delivery.expectedHandlers());
        assertEquals(List.of("node-a"), delivery.actualHandlers());
        assertEquals(List.of(), delivery.missingHandlers());
        assertEquals(List.of(), delivery.failedHandlers());
        assertTrue(delivery.complete());
    }

    @Test
    @DisplayName("delivery evidence는 expected handler 누락을 missing handler로 요약한다")
    void summarize_marksMissingExpectedHandler() {
        ReconnectCommandLog commandLog = ReconnectCommandLog.fromRow(
                1L,
                "reconnect-a",
                "node_drain:node-a",
                "node_reconnect",
                null,
                null,
                "node-a",
                "publisher-a",
                "node_drain",
                null,
                50,
                250,
                ReconnectCommandPublishStatus.SUCCEEDED,
                1L,
                100L,
                110L,
                null,
                null
        );
        when(commandRepository.findByCommandIdIn(List.of("reconnect-a"))).thenReturn(List.of(commandLog));
        when(handlingRepository.findByCommandIdIn(List.of("reconnect-a"))).thenReturn(List.of());

        ReconnectCommandLogService.Summary summary = service.summarize(List.of("reconnect-a"));

        assertFalse(summary.deliveryEvidence().complete());
        assertEquals(List.of("node-a"), summary.deliveryEvidence().missingHandlers());
        assertEquals(List.of("node-a"), summary.records().get(0).deliveryEvidence().missingHandlers());
    }

    @Test
    @DisplayName("delivery evidence는 PARTIAL/FAILED handling을 failed handler로 요약한다")
    void summarize_marksFailedHandlers() {
        ReconnectCommandLog commandLog = ReconnectCommandLog.fromRow(
                1L,
                "reconnect-a",
                "node_drain:node-a",
                "node_reconnect",
                null,
                null,
                "node-a",
                "publisher-a",
                "node_drain",
                null,
                50,
                250,
                ReconnectCommandPublishStatus.SUCCEEDED,
                1L,
                100L,
                110L,
                null,
                null
        );
        ReconnectCommandHandlingLog handlingLog = ReconnectCommandHandlingLog.fromRow(
                10L,
                "reconnect-a",
                "node-a",
                ReconnectCommandHandlingStatus.PARTIAL,
                10,
                10,
                8,
                2,
                2,
                120L,
                null
        );
        when(commandRepository.findByCommandIdIn(List.of("reconnect-a"))).thenReturn(List.of(commandLog));
        when(handlingRepository.findByCommandIdIn(List.of("reconnect-a"))).thenReturn(List.of(handlingLog));

        ReconnectCommandLogService.Summary summary = service.summarize(List.of("reconnect-a"));

        assertFalse(summary.deliveryEvidence().complete());
        assertEquals(List.of("node-a"), summary.deliveryEvidence().failedHandlers());
        assertEquals(List.of("node-a"), summary.records().get(0).deliveryEvidence().failedHandlers());
    }

    @Test
    @DisplayName("target node가 없는 command는 strict 대상에서 제외하지만 handling evidence는 보존한다")
    void summarize_preservesHandlingEvidenceForNonTargetedCommand() {
        ReconnectCommandLog commandLog = ReconnectCommandLog.fromRow(
                1L,
                "reconnect-a",
                "room_reconnect:1:0",
                "room_partition_reconnect",
                1L,
                0,
                null,
                "publisher-a",
                "partition_rebalance",
                3L,
                50,
                250,
                ReconnectCommandPublishStatus.SUCCEEDED,
                1L,
                100L,
                110L,
                null,
                null
        );
        ReconnectCommandHandlingLog handlingLog = ReconnectCommandHandlingLog.fromRow(
                10L,
                "reconnect-a",
                "node-b",
                ReconnectCommandHandlingStatus.NO_TARGET,
                0,
                0,
                0,
                0,
                0,
                120L,
                null
        );
        when(commandRepository.findByCommandIdIn(List.of("reconnect-a"))).thenReturn(List.of(commandLog));
        when(handlingRepository.findByCommandIdIn(List.of("reconnect-a"))).thenReturn(List.of(handlingLog));

        ReconnectCommandLogService.Summary summary = service.summarize(List.of("reconnect-a"));

        ReconnectCommandLogService.CommandDeliveryEvidence delivery = summary.records().get(0).deliveryEvidence();
        assertFalse(delivery.strictEligible());
        assertEquals("missing_target_node", delivery.strictEligibilityReason());
        assertEquals(List.of(), delivery.expectedHandlers());
        assertEquals(List.of("node-b"), delivery.actualHandlers());
        assertEquals(List.of(), delivery.missingHandlers());
        assertEquals(List.of(), delivery.failedHandlers());
        assertTrue(delivery.complete());
        assertEquals(0, summary.deliveryEvidence().strictEligibleCommandCount());
    }

    @Test
    @DisplayName("expected command row가 누락되면 recorded command가 성공해도 delivery complete가 아니다")
    void summarize_marksIncompleteWhenExpectedCommandRowMissing() {
        ReconnectCommandLog commandLog = ReconnectCommandLog.fromRow(
                1L,
                "reconnect-a",
                "node_drain:node-a",
                "node_reconnect",
                null,
                null,
                "node-a",
                "publisher-a",
                "node_drain",
                null,
                50,
                250,
                ReconnectCommandPublishStatus.SUCCEEDED,
                1L,
                100L,
                110L,
                null,
                null
        );
        ReconnectCommandHandlingLog handlingLog = ReconnectCommandHandlingLog.fromRow(
                10L,
                "reconnect-a",
                "node-a",
                ReconnectCommandHandlingStatus.SENT,
                10,
                10,
                10,
                0,
                0,
                120L,
                null
        );
        when(commandRepository.findByCommandIdIn(List.of("reconnect-a", "reconnect-b"))).thenReturn(List.of(commandLog));
        when(handlingRepository.findByCommandIdIn(List.of("reconnect-a"))).thenReturn(List.of(handlingLog));

        ReconnectCommandLogService.Summary summary = service.summarize(List.of("reconnect-a", "reconnect-b"));

        assertEquals(List.of("reconnect-b"), summary.missingCommandIds());
        assertFalse(summary.deliveryEvidence().complete());
    }

    @Test
    @DisplayName("handling summary 조회가 실패해도 publish command evidence는 보존한다")
    void summarize_preservesPublishEvidenceWhenHandlingLookupFails() {
        ReconnectCommandLog commandLog = ReconnectCommandLog.fromRow(
                1L,
                "reconnect-a",
                "node_drain:node-a",
                "node_reconnect",
                null,
                null,
                "node-a",
                "publisher-a",
                "node_drain",
                null,
                50,
                250,
                ReconnectCommandPublishStatus.SUCCEEDED,
                1L,
                100L,
                110L,
                null,
                null
        );
        when(commandRepository.findByCommandIdIn(List.of("reconnect-a"))).thenReturn(List.of(commandLog));
        when(handlingRepository.findByCommandIdIn(List.of("reconnect-a"))).thenThrow(new IllegalStateException("handling unavailable"));

        ReconnectCommandLogService.Summary summary = service.summarize(List.of("reconnect-a"));

        assertEquals(List.of("reconnect-a"), summary.recordedCommandIds());
        assertEquals(1, summary.recordCount());
        assertEquals(List.of(), summary.missingCommandIds());
        assertEquals(List.of("node-a"), summary.deliveryEvidence().missingHandlers());
        assertFalse(summary.deliveryEvidence().complete());
    }

    @Test
    @DisplayName("publish status가 SUCCEEDED가 아니면 handler row가 있어도 delivery complete가 아니다")
    void summarize_marksIncompleteWhenPublishDidNotSucceed() {
        ReconnectCommandLog commandLog = ReconnectCommandLog.fromRow(
                1L,
                "reconnect-a",
                "node_drain:node-a",
                "node_reconnect",
                null,
                null,
                "node-a",
                "publisher-a",
                "node_drain",
                null,
                50,
                250,
                ReconnectCommandPublishStatus.NO_RECEIVERS,
                0L,
                100L,
                null,
                110L,
                "no receivers"
        );
        ReconnectCommandHandlingLog handlingLog = ReconnectCommandHandlingLog.fromRow(
                10L,
                "reconnect-a",
                "node-a",
                ReconnectCommandHandlingStatus.SENT,
                10,
                10,
                10,
                0,
                0,
                120L,
                null
        );
        when(commandRepository.findByCommandIdIn(List.of("reconnect-a"))).thenReturn(List.of(commandLog));
        when(handlingRepository.findByCommandIdIn(List.of("reconnect-a"))).thenReturn(List.of(handlingLog));

        ReconnectCommandLogService.Summary summary = service.summarize(List.of("reconnect-a"));

        assertFalse(summary.records().get(0).deliveryEvidence().complete());
        assertFalse(summary.deliveryEvidence().complete());
    }

    @Test
    @DisplayName("command log가 disabled면 cleanup을 skip한다")
    void cleanupExpired_skipsWhenCommandLogDisabled() {
        ReconnectCommandLogService disabled = new ReconnectCommandLogService(
                commandRepository,
                handlingRepository,
                retentionProperties,
                false,
                false
        );
        retentionProperties.setEnabled(true);

        ReconnectCommandLogService.CleanupResult result = disabled.cleanupExpired(10_000L);

        assertFalse(result.enabled());
        assertTrue(result.retentionEnabled());
        assertEquals(0, result.deletedCommandRows());
        verify(commandRepository, never()).findExpiredCommandIds(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("retention이 disabled면 cleanup을 skip한다")
    void cleanupExpired_skipsWhenRetentionDisabled() {
        retentionProperties.setEnabled(false);

        ReconnectCommandLogService.CleanupResult result = service.cleanupExpired(10_000L);

        assertTrue(result.enabled());
        assertFalse(result.retentionEnabled());
        assertEquals(0, result.deletedCommandRows());
        verify(commandRepository, never()).findExpiredCommandIds(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("expired command가 없으면 delete를 호출하지 않는다")
    void cleanupExpired_doesNotDeleteWhenNoExpiredCommands() {
        retentionProperties.setEnabled(true);
        retentionProperties.setRetentionMs(3_600_000L);
        retentionProperties.setCleanupLimit(100);
        when(commandRepository.findExpiredCommandIds(6_400_000L, 100)).thenReturn(List.of());

        ReconnectCommandLogService.CleanupResult result = service.cleanupExpired(10_000_000L);

        assertEquals(6_400_000L, result.cutoffCreatedAt());
        assertEquals(0, result.candidateCommandCount());
        assertEquals(0, result.deletedHandlingRows());
        assertEquals(0, result.deletedCommandRows());
        verify(handlingRepository, never()).deleteByCommandIds(org.mockito.ArgumentMatchers.anyCollection());
        verify(commandRepository, never()).deleteByCommandIds(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    @DisplayName("cleanup은 handling log를 먼저 삭제한 뒤 command log를 삭제한다")
    void cleanupExpired_deletesHandlingBeforeCommandRows() {
        retentionProperties.setEnabled(true);
        retentionProperties.setRetentionMs(3_600_000L);
        retentionProperties.setCleanupLimit(2);
        List<String> expired = List.of("reconnect-a", "reconnect-b");
        when(commandRepository.findExpiredCommandIds(6_400_000L, 2)).thenReturn(expired);
        when(handlingRepository.deleteByCommandIds(expired)).thenReturn(3);
        when(commandRepository.deleteByCommandIds(expired)).thenReturn(2);

        ReconnectCommandLogService.CleanupResult result = service.cleanupExpired(10_000_000L);

        assertEquals(2, result.candidateCommandCount());
        assertEquals(3, result.deletedHandlingRows());
        assertEquals(2, result.deletedCommandRows());
        org.mockito.InOrder inOrder = inOrder(handlingRepository, commandRepository);
        inOrder.verify(handlingRepository).deleteByCommandIds(expired);
        inOrder.verify(commandRepository).deleteByCommandIds(expired);
    }

    @Test
    @DisplayName("retention properties는 최소값을 보정한다")
    void retentionProperties_normalizesMinimums() {
        ReconnectCommandLogRetentionProperties properties = new ReconnectCommandLogRetentionProperties();
        properties.setRetentionMs(1L);
        properties.setCleanupLimit(0);

        assertEquals(3_600_000L, properties.retentionMs());
        assertEquals(1, properties.cleanupLimit());
    }
}
