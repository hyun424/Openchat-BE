package io.hyun424.openchat.chat.room.partition.dto;

public record RoomPartitionRoute(
        Long roomId,
        boolean partitioned,
        int partitionId,
        int partitionCount,
        int version,
        String wsUrl
) {
    private static final String WS_CHAT_PATH = "/ws/chat";

    public RoomPartitionRoute(Long roomId,
                              boolean partitioned,
                              int partitionId,
                              int partitionCount,
                              String wsUrl) {
        this(roomId, partitioned, partitionId, partitionCount, 0, wsUrl);
    }

    public static RoomPartitionRoute legacy(Long roomId) {
        return new RoomPartitionRoute(roomId, false, 0, 1, 0, wsUrl(roomId, 0, 0));
    }

    public static RoomPartitionRoute partitioned(Long roomId, int partitionId, int partitionCount, int version) {
        return new RoomPartitionRoute(
                roomId,
                true,
                partitionId,
                partitionCount,
                version,
                wsUrl(roomId, partitionId, version)
        );
    }

    private static String wsUrl(Long roomId, int partitionId, int version) {
        return WS_CHAT_PATH + "?roomId=" + roomId
                + "&partitionId=" + partitionId
                + "&routeVersion=" + version;
    }
}
