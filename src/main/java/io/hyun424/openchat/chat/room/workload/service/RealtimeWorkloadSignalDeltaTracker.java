package io.hyun424.openchat.chat.room.workload.service;

import org.springframework.stereotype.Component;

@Component
public class RealtimeWorkloadSignalDeltaTracker {

    private boolean initialized;
    private long previousSendFailedCount;
    private long previousReconnectSentCount;

    public synchronized RealtimeWorkloadSignalDelta delta(long currentSendFailedCount, long currentReconnectSentCount) {
        long safeSendFailedCount = Math.max(0, currentSendFailedCount);
        long safeReconnectSentCount = Math.max(0, currentReconnectSentCount);

        if (!initialized) {
            initialized = true;
            previousSendFailedCount = safeSendFailedCount;
            previousReconnectSentCount = safeReconnectSentCount;
            return new RealtimeWorkloadSignalDelta(0, 0);
        }

        long sendFailedDelta = deltaSincePrevious(previousSendFailedCount, safeSendFailedCount);
        long reconnectSentDelta = deltaSincePrevious(previousReconnectSentCount, safeReconnectSentCount);
        previousSendFailedCount = safeSendFailedCount;
        previousReconnectSentCount = safeReconnectSentCount;
        return new RealtimeWorkloadSignalDelta(sendFailedDelta, reconnectSentDelta);
    }

    private long deltaSincePrevious(long previous, long current) {
        return current >= previous ? current - previous : current;
    }
}
