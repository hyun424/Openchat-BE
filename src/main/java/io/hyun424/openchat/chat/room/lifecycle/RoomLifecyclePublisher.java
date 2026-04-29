package io.hyun424.openchat.chat.room.lifecycle;

public interface RoomLifecyclePublisher {
    void publishRoomEnded(Long roomId, String reason);
}
