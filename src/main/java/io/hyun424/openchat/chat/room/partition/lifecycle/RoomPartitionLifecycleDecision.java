package io.hyun424.openchat.chat.room.partition.lifecycle;

public record RoomPartitionLifecycleDecision(
        String operation,
        String result,
        Long roomId,
        int currentPartitions,
        int targetPartitions,
        long observedWork,
        long threshold
) {
    public static RoomPartitionLifecycleDecision of(String operation,
                                                    String result,
                                                    Long roomId,
                                                    int currentPartitions,
                                                    int targetPartitions,
                                                    long observedWork,
                                                    long threshold) {
        return new RoomPartitionLifecycleDecision(
                operation,
                result,
                roomId,
                Math.max(0, currentPartitions),
                Math.max(0, targetPartitions),
                Math.max(0L, observedWork),
                Math.max(0L, threshold)
        );
    }
}
