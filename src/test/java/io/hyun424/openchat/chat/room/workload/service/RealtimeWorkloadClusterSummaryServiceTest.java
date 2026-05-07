package io.hyun424.openchat.chat.room.workload.service;

import io.hyun424.openchat.chat.room.hot.RoomScaleTier;
import io.hyun424.openchat.chat.room.workload.config.RealtimeWorkloadProperties;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeNodeWorkloadSnapshot;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadRecommendationType;
import io.hyun424.openchat.chat.room.workload.dto.RoomPartitionDrainProgress;
import io.hyun424.openchat.chat.room.workload.dto.RoomWorkloadCandidate;
import io.hyun424.openchat.chat.room.workload.infra.RealtimeWorkloadSnapshotRepository;
import io.hyun424.openchat.chat.room.workload.metrics.RealtimeWorkloadMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RealtimeWorkloadClusterSummaryServiceTest {

    private final RealtimeWorkloadProperties properties = new RealtimeWorkloadProperties(
            true, true, 5_000, 30_000, 10, 0.7, 10_000);
    private final RealtimeWorkloadMetrics metrics = new RealtimeWorkloadMetrics(new SimpleMeterRegistry());

    @Test
    void summaryAggregatesActiveSnapshotsAndSeparatesStaleNodes() {
        RealtimeWorkloadSnapshotRepository repository = mock(RealtimeWorkloadSnapshotRepository.class);
        long now = System.currentTimeMillis();
        when(repository.readAll()).thenReturn(List.of(
                snapshot("node-1", now - 1_000, now + 20_000, 10, 7, 3, 500, 2, 1),
                snapshot("node-2", now - 60_000, now - 1, 99, 99, 0, 9_000, 99, 99)
        ));
        RealtimeWorkloadClusterSummaryService service = new RealtimeWorkloadClusterSummaryService(
                repository,
                new RealtimeWorkloadRecommendationService(properties, metrics),
                properties,
                metrics
        );

        var summary = service.summary();

        assertEquals(1, summary.activeNodeCount());
        assertEquals(1, summary.staleNodeCount());
        assertEquals(List.of("node-2"), summary.staleNodeIds());
        assertEquals(10, summary.totalSessions());
        assertEquals(7, summary.activeSessions());
        assertEquals(3, summary.passiveSessions());
        assertEquals(500, summary.maxScaleDecisionWorkPerSecond());
        assertEquals(2, summary.sendFailedDelta());
        assertEquals(1, summary.reconnectSentDelta());
        assertEquals(1, summary.topRooms().size());
        assertEquals("node-1", summary.topRooms().get(0).sourceNodeId());
        assertTrue(summary.recommendations().stream()
                .anyMatch(r -> r.type() == RealtimeWorkloadRecommendationType.INVESTIGATE_STALE_NODE));
    }

    @Test
    void topRoomsAreSortedByDecisionWorkAcrossNodes() {
        RealtimeWorkloadSnapshotRepository repository = mock(RealtimeWorkloadSnapshotRepository.class);
        long now = System.currentTimeMillis();
        when(repository.readAll()).thenReturn(List.of(
                snapshot("node-1", now, now + 20_000, 10, 7, 3, 100, 2, 3),
                snapshot("node-2", now, now + 20_000, 20, 15, 5, 900, 5, 7)
        ));
        RealtimeWorkloadClusterSummaryService service = new RealtimeWorkloadClusterSummaryService(
                repository,
                new RealtimeWorkloadRecommendationService(properties, metrics),
                properties,
                metrics
        );

        var summary = service.summary();

        assertEquals(2, summary.activeNodeCount());
        assertEquals(30, summary.totalSessions());
        assertEquals(7, summary.sendFailedDelta());
        assertEquals(10, summary.reconnectSentDelta());
        assertEquals(900, summary.topRooms().get(0).scaleDecisionWorkPerSecond());
        assertEquals("node-2", summary.topRooms().get(0).sourceNodeId());
    }

    @Test
    void drainProgressIsAggregatedByRoomAndPartition() {
        RealtimeWorkloadSnapshotRepository repository = mock(RealtimeWorkloadSnapshotRepository.class);
        long now = System.currentTimeMillis();
        when(repository.readAll()).thenReturn(List.of(
                snapshotWithDrain("node-1", now, now + 20_000, List.of(
                        new RoomPartitionDrainProgress(1L, 2, 3, "node-1"),
                        new RoomPartitionDrainProgress(1L, 3, 1, "node-1")
                )),
                snapshotWithDrain("node-2", now, now + 20_000, List.of(
                        new RoomPartitionDrainProgress(1L, 2, 5, "node-2")
                ))
        ));
        RealtimeWorkloadClusterSummaryService service = new RealtimeWorkloadClusterSummaryService(
                repository,
                new RealtimeWorkloadRecommendationService(properties, metrics),
                properties,
                metrics
        );

        var summary = service.summary();

        assertEquals(2, summary.drainProgress().size());
        assertEquals(8, summary.drainProgress().get(0).openSessions());
        assertEquals(2, summary.drainProgress().get(0).partitionId());
        assertEquals(1, summary.drainProgress().get(1).openSessions());
        assertEquals("cluster", summary.drainProgress().get(0).sourceNodeId());
    }

    private RealtimeNodeWorkloadSnapshot snapshot(String nodeId,
                                                  long reportedAt,
                                                  long expiresAt,
                                                  int totalSessions,
                                                  int activeSessions,
                                                  int passiveSessions,
                                                  long decisionWork,
                                                  long sendFailedDelta,
                                                  long reconnectSentDelta) {
        return new RealtimeNodeWorkloadSnapshot(
                nodeId,
                "realtime",
                reportedAt,
                expiresAt,
                Set.of(0),
                Set.of(0),
                totalSessions,
                activeSessions,
                passiveSessions,
                2,
                decisionWork,
                decisionWork / 2,
                decisionWork,
                0,
                sendFailedDelta,
                reconnectSentDelta,
                List.of(new RoomWorkloadCandidate(
                        nodeId.equals("node-1") ? 1L : 2L,
                        decisionWork,
                        decisionWork / 2,
                        decisionWork,
                        RoomScaleTier.SMALL,
                        1,
                        1,
                        activeSessions,
                        false,
                        nodeId
                ))
        );
    }

    private RealtimeNodeWorkloadSnapshot snapshotWithDrain(String nodeId,
                                                           long reportedAt,
                                                           long expiresAt,
                                                           List<RoomPartitionDrainProgress> drainProgress) {
        return new RealtimeNodeWorkloadSnapshot(
                nodeId,
                "realtime",
                reportedAt,
                expiresAt,
                Set.of(0),
                Set.of(0),
                1,
                1,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                drainProgress
        );
    }
}
