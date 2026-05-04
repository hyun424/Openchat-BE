package io.hyun424.openchat.chat.room.hot;

final class RoomHotStateClassifier {

    private final RoomHotStateProperties properties;

    RoomHotStateClassifier(RoomHotStateProperties properties) {
        this.properties = properties;
    }

    RoomHotState classify(RoomTrafficSnapshot snapshot, boolean mainExposed) {
        if (isSuperHot(snapshot)) {
            return RoomHotState.SUPER_HOT;
        }
        if (isHot(snapshot)) {
            return RoomHotState.HOT;
        }
        if (isWarm(snapshot, mainExposed)) {
            return RoomHotState.WARM;
        }
        if (isWatched(snapshot)) {
            return RoomHotState.WATCHED;
        }
        return RoomHotState.NORMAL;
    }

    private boolean isSuperHot(RoomTrafficSnapshot snapshot) {
        return snapshot.deliveryLagP95Millis() >= properties.superHotDeliveryLagP95Millis()
                || snapshot.outboundFanoutPerSecond() >= properties.superHotOutboundFanoutPerSecond()
                || (snapshot.connectedSessions() >= properties.superHotConnectedSessions()
                && snapshot.inboundMessagesPerSecond() >= properties.hotInboundMessagesPerSecond());
    }

    private boolean isHot(RoomTrafficSnapshot snapshot) {
        return snapshot.deliveryLagP95Millis() >= properties.hotDeliveryLagP95Millis()
                || snapshot.inboundMessagesPerSecond() >= properties.hotInboundMessagesPerSecond()
                || snapshot.outboundFanoutPerSecond() >= properties.hotOutboundFanoutPerSecond();
    }

    private boolean isWarm(RoomTrafficSnapshot snapshot, boolean mainExposed) {
        return mainExposed
                || snapshot.joinRatePerSecond() >= properties.warmJoinRatePerSecond()
                || snapshot.deliveryLagP95Millis() >= properties.warmDeliveryLagP95Millis()
                || snapshot.outboundFanoutPerSecond() >= properties.warmOutboundFanoutPerSecond();
    }

    private boolean isWatched(RoomTrafficSnapshot snapshot) {
        return snapshot.connectedSessions() >= properties.watchedConnectedSessions()
                || snapshot.outboundFanoutPerSecond() >= properties.watchedOutboundFanoutPerSecond()
                || snapshot.inboundMessagesPerSecond() > 0
                || snapshot.joinRatePerSecond() > 0;
    }
}
