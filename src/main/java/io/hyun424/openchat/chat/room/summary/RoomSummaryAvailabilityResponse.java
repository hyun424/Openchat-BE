package io.hyun424.openchat.chat.room.summary;

public record RoomSummaryAvailabilityResponse(
        boolean available,
        long unreadCount,
        int threshold,
        String reason,
        Long lastReadMessageId,
        Long rollingMemoryStartMessageId,
        Long rollingMemoryEndMessageId
) {
    static RoomSummaryAvailabilityResponse unavailable(long unreadCount,
                                                       int threshold,
                                                       String reason,
                                                       Long lastReadMessageId) {
        return new RoomSummaryAvailabilityResponse(false, unreadCount, threshold, reason, lastReadMessageId, null, null);
    }

    static RoomSummaryAvailabilityResponse available(long unreadCount,
                                                     int threshold,
                                                     Long lastReadMessageId,
                                                     RoomRollingMemory memory) {
        return new RoomSummaryAvailabilityResponse(
                true,
                unreadCount,
                threshold,
                "available",
                lastReadMessageId,
                memory.getStartMessageId(),
                memory.getEndMessageId()
        );
    }
}
