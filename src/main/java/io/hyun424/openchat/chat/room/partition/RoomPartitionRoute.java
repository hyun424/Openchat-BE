package io.hyun424.openchat.chat.room.partition;

public record RoomPartitionRoute(
        Long roomId,
        boolean partitioned,
        int partitionId,
        int partitionCount,
        int version,
        String wsUrl
) {
    public RoomPartitionRoute(Long roomId,
                              boolean partitioned,
                              int partitionId,
                              int partitionCount,
                              String wsUrl) {
        this(roomId, partitioned, partitionId, partitionCount, 0, wsUrl);
    }
}
