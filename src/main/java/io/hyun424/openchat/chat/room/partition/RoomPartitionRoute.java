package io.hyun424.openchat.chat.room.partition;

public record RoomPartitionRoute(
        Long roomId,
        boolean partitioned,
        int partitionId,
        int partitionCount,
        String wsUrl
) {
}
