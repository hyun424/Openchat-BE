package io.hyun424.openchat.chat.room.summary;

public record RoomSegmentSignal(
        Long messageId,
        String type,
        double score,
        String reason
) {
}
