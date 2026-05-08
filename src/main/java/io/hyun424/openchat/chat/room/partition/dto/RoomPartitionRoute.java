package io.hyun424.openchat.chat.room.partition.dto;

public record RoomPartitionRoute(
        Long roomId,
        boolean partitioned,
        int partitionId,
        int partitionCount,
        int version,
        String wsUrl,
        String nodeId,
        String assignmentVersion,
        String fallbackReason
) {
    private static final String WS_CHAT_PATH = "/ws/chat";

    public RoomPartitionRoute(Long roomId,
                              boolean partitioned,
                              int partitionId,
                              int partitionCount,
                              String wsUrl) {
        this(roomId, partitioned, partitionId, partitionCount, 0, wsUrl);
    }

    public RoomPartitionRoute(Long roomId,
                              boolean partitioned,
                              int partitionId,
                              int partitionCount,
                              int version,
                              String wsUrl) {
        this(roomId, partitioned, partitionId, partitionCount, version, wsUrl, null, null, null);
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

    public static RoomPartitionRoute nodeAware(Long roomId,
                                               int partitionId,
                                               int partitionCount,
                                               int version,
                                               String wsBaseUrl,
                                               String nodeId,
                                               String assignmentVersion) {
        return new RoomPartitionRoute(
                roomId,
                true,
                partitionId,
                partitionCount,
                version,
                wsUrl(wsBaseUrl, roomId, partitionId, version, nodeId, assignmentVersion),
                nodeId,
                assignmentVersion,
                null
        );
    }

    public static RoomPartitionRoute fallback(Long roomId,
                                              int partitionId,
                                              int partitionCount,
                                              int version,
                                              String fallbackReason) {
        return new RoomPartitionRoute(
                roomId,
                true,
                partitionId,
                partitionCount,
                version,
                wsUrl(roomId, partitionId, version),
                null,
                null,
                fallbackReason
        );
    }

    private static String wsUrl(Long roomId, int partitionId, int version) {
        return WS_CHAT_PATH + "?roomId=" + roomId
                + "&partitionId=" + partitionId
                + "&routeVersion=" + version;
    }

    private static String wsUrl(String wsBaseUrl,
                                Long roomId,
                                int partitionId,
                                int version,
                                String nodeId,
                                String assignmentVersion) {
        String query = "?roomId=" + roomId
                + "&partitionId=" + partitionId
                + "&routeVersion=" + version
                + "&nodeId=" + nodeId
                + "&assignmentVersion=" + assignmentVersion;
        if (wsBaseUrl == null || wsBaseUrl.isBlank()) {
            return WS_CHAT_PATH + query;
        }
        String trimmed = wsBaseUrl.endsWith("/") ? wsBaseUrl.substring(0, wsBaseUrl.length() - 1) : wsBaseUrl;
        if (trimmed.endsWith(WS_CHAT_PATH)) {
            return trimmed + query;
        }
        return trimmed + WS_CHAT_PATH + query;
    }
}
