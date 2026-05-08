package io.hyun424.openchat.chat.room.workload.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RealtimeWorkloadSignalDeltaTrackerTest {

    @Test
    void firstSnapshotReturnsZeroDelta() {
        RealtimeWorkloadSignalDeltaTracker tracker = new RealtimeWorkloadSignalDeltaTracker();

        RealtimeWorkloadSignalDelta delta = tracker.delta(10, 7);

        assertEquals(0, delta.sendFailedDelta());
        assertEquals(0, delta.reconnectSentDelta());
    }

    @Test
    void computesDeltaFromPreviousSnapshot() {
        RealtimeWorkloadSignalDeltaTracker tracker = new RealtimeWorkloadSignalDeltaTracker();

        tracker.delta(10, 7);
        RealtimeWorkloadSignalDelta delta = tracker.delta(13, 11);

        assertEquals(3, delta.sendFailedDelta());
        assertEquals(4, delta.reconnectSentDelta());
    }

    @Test
    void counterResetUsesCurrentValueAsDelta() {
        RealtimeWorkloadSignalDeltaTracker tracker = new RealtimeWorkloadSignalDeltaTracker();

        tracker.delta(13, 11);
        RealtimeWorkloadSignalDelta delta = tracker.delta(2, 3);

        assertEquals(2, delta.sendFailedDelta());
        assertEquals(3, delta.reconnectSentDelta());
    }

    @Test
    void unchangedCountersReturnZeroDelta() {
        RealtimeWorkloadSignalDeltaTracker tracker = new RealtimeWorkloadSignalDeltaTracker();

        tracker.delta(5, 6);
        RealtimeWorkloadSignalDelta delta = tracker.delta(5, 6);

        assertEquals(0, delta.sendFailedDelta());
        assertEquals(0, delta.reconnectSentDelta());
    }
}
