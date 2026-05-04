package io.hyun424.openchat.chat.room.hot;

public record RoomTrafficSnapshot(
        Long roomId,
        int connectedSessions,
        long joinRatePerSecond,
        long inboundMessagesPerSecond,
        long outboundFanoutPerSecond,
        long deliveryLagP95Millis,
        long laneQueueWaitP95Millis,
        RoomHotState state
) {
}
