package io.hyun424.openchat.chat.room.workload.service;

public record RealtimeWorkloadSignalDelta(
        long sendFailedDelta,
        long reconnectSentDelta
) {
}
