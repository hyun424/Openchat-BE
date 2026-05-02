package io.hyun424.openchat.chat.room.hot;

record RoomHotStateProperties(
        int windowSeconds,
        int maxLatencySamples,
        long inactiveTtlMillis,
        long upgradeStableMillis,
        long downgradeStableMillis,
        int watchedConnectedSessions,
        long watchedOutboundFanoutPerSecond,
        long warmJoinRatePerSecond,
        long warmDeliveryLagP95Millis,
        long warmOutboundFanoutPerSecond,
        long hotInboundMessagesPerSecond,
        long hotDeliveryLagP95Millis,
        long hotOutboundFanoutPerSecond,
        int superHotConnectedSessions,
        long superHotDeliveryLagP95Millis,
        long superHotOutboundFanoutPerSecond
) {
    static RoomHotStateProperties defaults() {
        return new RoomHotStateProperties(
                10,
                2048,
                5 * 60_000L,
                5_000L,
                60_000L,
                50,
                1_000L,
                5L,
                50L,
                5_000L,
                30L,
                100L,
                10_000L,
                500,
                300L,
                50_000L
        );
    }
}
