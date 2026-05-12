package io.hyun424.openchat.chat.room.read;

import java.time.Instant;

public record RoomReadPositionResponse(
        Long roomId,
        String userId,
        Long lastReadMessageId,
        Instant lastReadAt,
        Instant updatedAt
) {
    public static RoomReadPositionResponse empty(Long roomId, String userId) {
        return new RoomReadPositionResponse(roomId, userId, null, null, null);
    }

    public static RoomReadPositionResponse from(RoomReadPosition position) {
        return new RoomReadPositionResponse(
                position.getRoomId(),
                position.getUserId(),
                position.getLastReadMessageId(),
                position.getLastReadAt(),
                position.getUpdatedAt()
        );
    }
}
