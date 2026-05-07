package io.hyun424.openchat.chat.room.workload.service;

import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
import io.hyun424.openchat.chat.room.hot.RoomTrafficWorkloadSummary;
import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.shard.RoomShardProperties;
import io.hyun424.openchat.chat.room.workload.config.RealtimeWorkloadProperties;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import io.hyun424.openchat.infra.websocket.session.RoomSessionWorkloadSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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
}
