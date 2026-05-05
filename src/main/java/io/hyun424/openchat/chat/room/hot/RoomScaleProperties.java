package io.hyun424.openchat.chat.room.hot;

record RoomScaleProperties(
        long upgradeStableMillis,
        long downgradeStableMillis,
        long mediumRoomWorkPerSecond,
        long largeRoomWorkPerSecond,
        long hotRoomWorkPerSecond,
        long criticalRoomWorkPerSecond,
        long podWorkBudgetDeliveryPerSecond,
        int maxActiveSessionsPerPartition,
        int maxPartitionLimit
) {
    static RoomScaleProperties defaults() {
        return new RoomScaleProperties(
                3 * 60_000L,
                10 * 60_000L,
                1_000L,
                5_000L,
                10_000L,
                20_000L,
                10_000L,
                500,
                16
        );
    }
}
