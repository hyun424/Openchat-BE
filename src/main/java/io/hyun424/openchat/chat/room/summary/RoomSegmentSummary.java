package io.hyun424.openchat.chat.room.summary;

import java.util.List;

public record RoomSegmentSummary(
        String summaryText,
        List<Long> evidenceMessageIds,
        List<RoomSegmentSignal> signals
) {
}
