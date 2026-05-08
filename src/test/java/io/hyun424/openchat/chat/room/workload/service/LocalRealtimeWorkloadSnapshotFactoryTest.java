package io.hyun424.openchat.chat.room.workload.service;

import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
import io.hyun424.openchat.chat.room.hot.RoomTrafficWorkloadSummary;
import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionState;
import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionStatus;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.partition.policy.RoomPartitionPolicy;
import io.hyun424.openchat.chat.room.partition.repository.RoomPartitionStateRepository;
import io.hyun424.openchat.chat.room.shard.RoomShardProperties;
import io.hyun424.openchat.chat.room.workload.config.RealtimeWorkloadProperties;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import io.hyun424.openchat.infra.websocket.session.RoomSessionWorkloadSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalRealtimeWorkloadSnapshotFactoryTest {

    @Test
    void snapshotIncludesSignalDeltasAfterFirstBaselineSnapshot() {
        RoomTrafficMonitor trafficMonitor = mock(RoomTrafficMonitor.class);
        RoomSessionRegistry sessionRegistry = mock(RoomSessionRegistry.class);
        RoomShardProperties shardProperties = mock(RoomShardProperties.class);
        RoomPartitionProperties partitionProperties = mock(RoomPartitionProperties.class);
        ChatPipelineMetrics chatMetrics = new ChatPipelineMetrics(new SimpleMeterRegistry());
        RoomPartitionMetrics partitionMetrics = new RoomPartitionMetrics(new SimpleMeterRegistry());
        LocalRealtimeWorkloadSnapshotFactory factory = new LocalRealtimeWorkloadSnapshotFactory(
                trafficMonitor,
                sessionRegistry,
                shardProperties,
                partitionProperties,
                new RealtimeWorkloadProperties(true, true, 5_000, 30_000, 10, 0.7, 10_000),
                chatMetrics,
                partitionMetrics,
                new RealtimeWorkloadSignalDeltaTracker(),
                "node-1",
                "realtime"
        );
        when(sessionRegistry.workloadSnapshot()).thenReturn(new RoomSessionWorkloadSnapshot(10, 7, 3, 2));
        when(trafficMonitor.workloadSummary()).thenReturn(new RoomTrafficWorkloadSummary(100, 80, 100, 0));
        when(trafficMonitor.topRoomsByScaleDecisionWork(10)).thenReturn(List.of());
        when(shardProperties.ownedShards()).thenReturn(Set.of(0));
        when(partitionProperties.ownedPartitions()).thenReturn(Set.of(0));

        var first = factory.create(1_000);
        chatMetrics.recordWebSocketSendFailure(3, "io_exception", System.nanoTime());
        partitionMetrics.recordReconnectControlSent("scale_down", "success");
        partitionMetrics.recordReconnectControlSent("scale_down", "success");
        var second = factory.create(6_000);

        assertEquals(0, first.sendFailedDelta());
        assertEquals(0, first.reconnectSentDelta());
        assertEquals(3, second.sendFailedDelta());
        assertEquals(2, second.reconnectSentDelta());
        assertEquals(10, second.totalSessions());
        assertEquals(100, second.maxScaleDecisionWorkPerSecond());

        chatMetrics.shutdown();
    }

    @Test
    void snapshotIncludesLocalDrainProgress() {
        RoomTrafficMonitor trafficMonitor = mock(RoomTrafficMonitor.class);
        RoomSessionRegistry sessionRegistry = mock(RoomSessionRegistry.class);
        RoomShardProperties shardProperties = mock(RoomShardProperties.class);
        RoomPartitionProperties partitionProperties = mock(RoomPartitionProperties.class);
        RoomPartitionStateRepository stateRepository = mock(RoomPartitionStateRepository.class);
        RoomPartitionPolicy partitionPolicy = mock(RoomPartitionPolicy.class);
        RoomPartitionState draining = new RoomPartitionState(
                1L,
                4,
                2,
                RoomPartitionStatus.DRAINING,
                "2,3",
                Instant.parse("2026-05-08T00:00:00Z"),
                "auto-partition-lifecycle"
        );
        LocalRealtimeWorkloadSnapshotFactory factory = new LocalRealtimeWorkloadSnapshotFactory(
                trafficMonitor,
                sessionRegistry,
                shardProperties,
                partitionProperties,
                new RealtimeWorkloadProperties(true, true, 5_000, 30_000, 10, 0.7, 10_000),
                new ChatPipelineMetrics(new SimpleMeterRegistry()),
                new RoomPartitionMetrics(new SimpleMeterRegistry()),
                stateRepository,
                partitionPolicy,
                new RealtimeWorkloadSignalDeltaTracker(),
                "node-1",
                "realtime"
        );
        when(sessionRegistry.workloadSnapshot()).thenReturn(new RoomSessionWorkloadSnapshot(10, 7, 3, 2));
        when(sessionRegistry.openSessionIds(1L, 2)).thenReturn(List.of("s1", "s2"));
        when(sessionRegistry.openSessionIds(1L, 3)).thenReturn(List.of());
        when(trafficMonitor.workloadSummary()).thenReturn(new RoomTrafficWorkloadSummary(100, 80, 100, 0));
        when(trafficMonitor.topRoomsByScaleDecisionWork(10)).thenReturn(List.of());
        when(shardProperties.ownedShards()).thenReturn(Set.of(0));
        when(partitionProperties.ownedPartitions()).thenReturn(Set.of(0));
        when(stateRepository.findByStatus(RoomPartitionStatus.DRAINING)).thenReturn(List.of(draining));
        when(partitionPolicy.drainingPartitions(draining)).thenReturn(Set.of(2, 3));

        var snapshot = factory.create(1_000);

        assertEquals(2, snapshot.drainProgress().size());
        var partitionTwo = snapshot.drainProgress().stream()
                .filter(progress -> progress.partitionId() == 2)
                .findFirst()
                .orElseThrow();
        assertEquals(2, partitionTwo.openSessions());
        assertEquals("node-1", partitionTwo.sourceNodeId());
    }
}
