package io.hyun424.openchat.chat.room.hot;

final class RoomScaleClassifier {

    private final RoomScaleProperties properties;

    RoomScaleClassifier(RoomScaleProperties properties) {
        this.properties = properties;
    }

    RoomScaleTier classify(long roomWorkPerSecond) {
        if (roomWorkPerSecond >= properties.criticalRoomWorkPerSecond()) {
            return RoomScaleTier.CRITICAL;
        }
        if (roomWorkPerSecond >= properties.hotRoomWorkPerSecond()) {
            return RoomScaleTier.HOT;
        }
        if (roomWorkPerSecond >= properties.largeRoomWorkPerSecond()) {
            return RoomScaleTier.LARGE;
        }
        if (roomWorkPerSecond >= properties.mediumRoomWorkPerSecond()) {
            return RoomScaleTier.MEDIUM;
        }
        return RoomScaleTier.SMALL;
    }
}
