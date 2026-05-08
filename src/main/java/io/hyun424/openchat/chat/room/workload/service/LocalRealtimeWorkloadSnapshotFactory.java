package io.hyun424.openchat.chat.room.workload.service;

import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
import io.hyun424.openchat.chat.room.hot.RoomTrafficWorkloadSummary;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.shard.RoomShardProperties;
import io.hyun424.openchat.chat.room.workload.config.RealtimeWorkloadProperties;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeNodeWorkloadSnapshot;
import io.hyun424.openchat.chat.room.workload.dto.RoomWorkloadCandidate;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import io.hyun424.openchat.infra.websocket.session.RoomSessionWorkloadSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalRealtimeWorkloadSnapshotFactory {

    private final RoomTrafficMonitor roomTrafficMonitor;
    private final RoomSessionRegistry roomSessionRegistry;
    private final RoomShardProperties roomShardProperties;
    private final RoomPartitionProperties roomPartitionProperties;
    private final RealtimeWorkloadProperties workloadProperties;
    private final ChatPipelineMetrics chatPipelineMetrics;
    private final RoomPartitionMetrics roomPartitionMetrics;
    private final RealtimeWorkloadSignalDeltaTracker signalDeltaTracker;
    private final String nodeId;
    private final String role;

    public LocalRealtimeWorkloadSnapshotFactory(RoomTrafficMonitor roomTrafficMonitor,
                                                RoomSessionRegistry roomSessionRegistry,
                                                RoomShardProperties roomShardProperties,
                                                RoomPartitionProperties roomPartitionProperties,
                                                RealtimeWorkloadProperties workloadProperties,
                                                ChatPipelineMetrics chatPipelineMetrics,
                                                RoomPartitionMetrics roomPartitionMetrics,
                                                RealtimeWorkloadSignalDeltaTracker signalDeltaTracker,
                                                @Value("${app.instance-id:local}") String nodeId,
                                                @Value("${app.role:combined}") String role) {
        this.roomTrafficMonitor = roomTrafficMonitor;
        this.roomSessionRegistry = roomSessionRegistry;
        this.roomShardProperties = roomShardProperties;
        this.roomPartitionProperties = roomPartitionProperties;
        this.workloadProperties = workloadProperties;
        this.chatPipelineMetrics = chatPipelineMetrics;
        this.roomPartitionMetrics = roomPartitionMetrics;
        this.signalDeltaTracker = signalDeltaTracker;
        this.nodeId = nodeId == null || nodeId.isBlank() ? "local" : nodeId;
        this.role = role == null || role.isBlank() ? "combined" : role;
    }

    public RealtimeNodeWorkloadSnapshot create(long nowMillis) {
        RoomSessionWorkloadSnapshot sessionSnapshot = roomSessionRegistry.workloadSnapshot();
        RoomTrafficWorkloadSummary trafficSummary = roomTrafficMonitor.workloadSummary();
        RealtimeWorkloadSignalDelta signalDelta = signalDeltaTracker.delta(
                chatPipelineMetrics.counterValue("ws.send.failed"),
                roomPartitionMetrics.reconnectControlSentSuccessCount()
        );
        return new RealtimeNodeWorkloadSnapshot(
                nodeId,
                role,
                nowMillis,
                nowMillis + workloadProperties.snapshotTtlMillis(),
                roomShardProperties.ownedShards(),
                roomPartitionProperties.ownedPartitions(),
                sessionSnapshot.totalSessions(),
                sessionSnapshot.activeSessions(),
                sessionSnapshot.passiveSessions(),
                sessionSnapshot.broadcastQueueDepth(),
                trafficSummary.maxActualDeliveryWorkPerSecond(),
                trafficSummary.maxConceptualRoomWorkPerSecond(),
                trafficSummary.maxScaleDecisionWorkPerSecond(),
                trafficSummary.partitionRecommendationLimitedCount(),
                signalDelta.sendFailedDelta(),
                signalDelta.reconnectSentDelta(),
                roomTrafficMonitor.topRoomsByScaleDecisionWork(workloadProperties.topRoomLimit()).stream()
                        .map(snapshot -> RoomWorkloadCandidate.from(snapshot, nodeId))
                        .toList()
        );
    }
}
