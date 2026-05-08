package io.hyun424.openchat.chat.room.hot;

public record RoomTrafficSnapshot(
        Long roomId,
        int connectedSessions,
        long joinRatePerSecond,
        long inboundMessagesPerSecond,
        long outboundFanoutPerSecond,
        long deliveryLagP95Millis,
        long laneQueueWaitP95Millis,
        RoomHotState state,
        int activeSessions,
        long roomWorkPerSecond,
        long actualDeliveryWorkPerSecond,
        long conceptualRoomWorkPerSecond,
        long scaleDecisionWorkPerSecond,
        RoomScaleTier scaleTier,
        int recommendedPartitions,
        int effectivePartitions
) {
    public boolean partitionRecommendationLimited() {
        return recommendedPartitions > effectivePartitions;
    }
}
