package io.hyun424.openchat.chat.room.summary;

import java.time.Instant;

public record RoomSummaryResponse(
        boolean available,
        String status,
        String summaryText,
        Long startMessageId,
        Long endMessageId,
        int segmentCount,
        Instant updatedAt
) {
    static RoomSummaryResponse notReady() {
        return new RoomSummaryResponse(false, "NOT_READY", null, null, null, 0, null);
    }

    static RoomSummaryResponse ready(RoomRollingMemory memory) {
        return new RoomSummaryResponse(
                true,
                "READY",
                memory.getSummaryText(),
                memory.getStartMessageId(),
                memory.getEndMessageId(),
                memory.getSegmentCount(),
                memory.getUpdatedAt()
        );
    }
}
