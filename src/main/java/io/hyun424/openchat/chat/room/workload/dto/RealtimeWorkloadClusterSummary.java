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
        List<RealtimeWorkloadRecommendation> recommendations
) {
}
