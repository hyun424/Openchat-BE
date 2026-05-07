package io.hyun424.openchat.chat.room.hot;

public record RoomTrafficWorkloadSummary(
        long maxActualDeliveryWorkPerSecond,
        long maxConceptualRoomWorkPerSecond,
        long maxScaleDecisionWorkPerSecond,
        int partitionRecommendationLimitedCount
) {
    public static RoomTrafficWorkloadSummary empty() {
        return new RoomTrafficWorkloadSummary(0, 0, 0, 0);
    }
}
