package io.hyun424.openchat.chat.room.workload.dto;

public record RealtimeWorkloadRecommendation(
        RealtimeWorkloadRecommendationType type,
        String reason,
        Long roomId,
        String nodeId,
        long observedValue,
        long threshold
) {
    public static RealtimeWorkloadRecommendation of(RealtimeWorkloadRecommendationType type,
                                                    String reason,
                                                    Long roomId,
                                                    String nodeId,
                                                    long observedValue,
                                                    long threshold) {
        return new RealtimeWorkloadRecommendation(type, reason, roomId, nodeId, observedValue, threshold);
    }
}
