package io.hyun424.openchat.chat.room.workload.dto;

import io.hyun424.openchat.chat.room.hot.RoomScaleTier;
import io.hyun424.openchat.chat.room.hot.RoomTrafficSnapshot;

public record RoomWorkloadCandidate(
        Long roomId,
        long actualDeliveryWorkPerSecond,
        long conceptualRoomWorkPerSecond,
        long scaleDecisionWorkPerSecond,
        RoomScaleTier scaleTier,
        int recommendedPartitions,
        int effectivePartitions,
        int activeSessions,
        boolean partitionLimited,
        String sourceNodeId
) {
    public static RoomWorkloadCandidate from(RoomTrafficSnapshot snapshot, String sourceNodeId) {
        return new RoomWorkloadCandidate(
                snapshot.roomId(),
                snapshot.actualDeliveryWorkPerSecond(),
                snapshot.conceptualRoomWorkPerSecond(),
                snapshot.scaleDecisionWorkPerSecond(),
                snapshot.scaleTier(),
                snapshot.recommendedPartitions(),
                snapshot.effectivePartitions(),
                snapshot.activeSessions(),
                snapshot.partitionRecommendationLimited(),
                sourceNodeId
        );
    }
}
