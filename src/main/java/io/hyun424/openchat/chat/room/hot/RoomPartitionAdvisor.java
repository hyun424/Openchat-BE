package io.hyun424.openchat.chat.room.hot;

final class RoomPartitionAdvisor {

    private final RoomScaleProperties properties;

    RoomPartitionAdvisor(RoomScaleProperties properties) {
        this.properties = properties;
    }

    int recommendedPartitionCount(long roomWorkPerSecond, int activeSessions) {
        long byWork = ceilDiv(Math.max(0, roomWorkPerSecond), properties.podWorkBudgetDeliveryPerSecond());
        long bySessions = ceilDiv(Math.max(0, activeSessions), properties.maxActiveSessionsPerPartition());
        long recommended = Math.max(byWork, bySessions);
        return Math.max(1, recommended > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) recommended);
    }

    int effectivePartitionCount(long roomWorkPerSecond, int activeSessions) {
        return Math.min(recommendedPartitionCount(roomWorkPerSecond, activeSessions), properties.maxPartitionLimit());
    }

    private long ceilDiv(long value, long divisor) {
        if (value <= 0) {
            return 0;
        }
        return (value + divisor - 1) / divisor;
    }
}
