package io.hyun424.openchat.chat.room.workload.dto;

import java.util.List;

public record RealtimeWorkloadClusterSummary(
        long generatedAt,
        int activeNodeCount,
        int staleNodeCount,
        List<String> staleNodeIds,
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
        List<RealtimeWorkloadRecommendation> recommendations,
        List<RoomPartitionDrainProgress> drainProgress
) {
    public RealtimeWorkloadClusterSummary(long generatedAt,
                                          int activeNodeCount,
                                          int staleNodeCount,
                                          List<String> staleNodeIds,
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
                                          List<RealtimeWorkloadRecommendation> recommendations) {
        this(
                generatedAt,
                activeNodeCount,
                staleNodeCount,
                staleNodeIds,
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
                recommendations,
                List.of()
        );
    }

    public RealtimeWorkloadClusterSummary {
        staleNodeIds = staleNodeIds == null ? List.of() : List.copyOf(staleNodeIds);
        topRooms = topRooms == null ? List.of() : List.copyOf(topRooms);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        drainProgress = drainProgress == null ? List.of() : List.copyOf(drainProgress);
    }
}
