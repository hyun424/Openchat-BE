package io.hyun424.openchat.chat.room.summary;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RoomSummaryProperties {

    private final int unreadThreshold;
    private final int segmentSize;
    private final int rollingSegmentCount;
    private final int activeWindowMinutes;
    private final int maxActiveRoomsPerRun;

    public RoomSummaryProperties(
            @Value("${app.room-summary.unread-threshold:100}") int unreadThreshold,
            @Value("${app.room-summary.segment-size:100}") int segmentSize,
            @Value("${app.room-summary.rolling-segment-count:3}") int rollingSegmentCount,
            @Value("${app.room-summary.active-window-minutes:60}") int activeWindowMinutes,
            @Value("${app.room-summary.max-active-rooms-per-run:10}") int maxActiveRoomsPerRun
    ) {
        this.unreadThreshold = Math.max(1, unreadThreshold);
        this.segmentSize = Math.max(1, segmentSize);
        this.rollingSegmentCount = Math.max(1, rollingSegmentCount);
        this.activeWindowMinutes = Math.max(1, activeWindowMinutes);
        this.maxActiveRoomsPerRun = Math.max(1, maxActiveRoomsPerRun);
    }

    public int unreadThreshold() {
        return unreadThreshold;
    }

    public int segmentSize() {
        return segmentSize;
    }

    public int rollingSegmentCount() {
        return rollingSegmentCount;
    }

    public int activeWindowMinutes() {
        return activeWindowMinutes;
    }

    public int maxActiveRoomsPerRun() {
        return maxActiveRoomsPerRun;
    }
}
