package io.hyun424.openchat.chat.room.workload.dto;

import java.util.List;
import java.util.Set;

public record RealtimeNodeWorkloadSnapshot(
        String nodeId,
        String role,
        long reportedAt,
        long expiresAt,
        Set<Integer> ownedShards,
        Set<Integer> ownedPartitions,
        int totalSessions,
        int activeSessions,
        int passiveSessions,
        int broadcastQueueDepth,
        long maxActualDeliveryWorkPerSecond,
        long maxConceptualRoomWorkPerSecond,
        long maxScaleDecisionWorkPerSecond,
        int partitionRecommendationLimitedCount,
        long sendFailedDelta,
        long reconnectSentDelta,
        List<RoomWorkloadCandidate> topRooms,
        List<RoomPartitionDrainProgress> drainProgress
) {
    public RealtimeNodeWorkloadSnapshot(String nodeId,
                                        String role,
                                        long reportedAt,
                                        long expiresAt,
                                        Set<Integer> ownedShards,
                                        Set<Integer> ownedPartitions,
                                        int totalSessions,
                                        int activeSessions,
                                        int passiveSessions,
                                        int broadcastQueueDepth,
                                        long maxActualDeliveryWorkPerSecond,
                                        long maxConceptualRoomWorkPerSecond,
                                        long maxScaleDecisionWorkPerSecond,
                                        int partitionRecommendationLimitedCount,
                                        long sendFailedDelta,
                                        long reconnectSentDelta,
                                        List<RoomWorkloadCandidate> topRooms) {
        this(
                nodeId,
                role,
                reportedAt,
                expiresAt,
                ownedShards,
                ownedPartitions,
                totalSessions,
                activeSessions,
                passiveSessions,
                broadcastQueueDepth,
                maxActualDeliveryWorkPerSecond,
                maxConceptualRoomWorkPerSecond,
                maxScaleDecisionWorkPerSecond,
                partitionRecommendationLimitedCount,
                sendFailedDelta,
                reconnectSentDelta,
                topRooms,
                List.of()
        );
    }

    public RealtimeNodeWorkloadSnapshot {
        topRooms = topRooms == null ? List.of() : List.copyOf(topRooms);
        drainProgress = drainProgress == null ? List.of() : List.copyOf(drainProgress);
    }

    public boolean stale(long nowMillis) {
        return expiresAt < nowMillis;
    }
}
