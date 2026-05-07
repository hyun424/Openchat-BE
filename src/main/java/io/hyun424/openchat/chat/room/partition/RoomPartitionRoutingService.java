package io.hyun424.openchat.chat.room.partition;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RoomPartitionRoutingService {

    private final RoomPartitionProperties properties;
    private final RoomPartitionStateService stateService;
    private final RoomPartitionMetrics metrics;

    public RoomPartitionRoutingService(RoomPartitionProperties properties,
                                       RoomPartitionStateService stateService,
                                       RoomPartitionMetrics metrics) {
        this.properties = properties;
        this.stateService = stateService;
        this.metrics = metrics;
        this.metrics.updateConfig(properties);
    }

    public RoomPartitionRoute route(Long roomId, String userId) {
        int partitionCount = partitionCountForRoom(roomId);
        boolean partitioned = partitionCount > 1;
        int partitionId = partitioned ? stateService.routePartition(roomId, userId) : 0;
        int version = partitioned ? stateService.versionForRoom(roomId) : 0;
        metrics.recordRoute(partitioned ? "partitioned" : "legacy");
        return new RoomPartitionRoute(
                roomId,
                partitioned,
                partitionId,
                partitionCount,
                version,
                "/ws/chat?roomId=" + roomId + "&partitionId=" + partitionId + "&routeVersion=" + version
        );
    }

    public boolean shouldPartition(Long roomId) {
        return partitionCountForRoom(roomId) > 1;
    }

    public int partitionCountForRoom(Long roomId) {
        if (!properties.enabled()) {
            return 1;
        }
        return stateService.partitionCountForRoom(roomId);
    }

    public List<Integer> publishPartitions(Long roomId) {
        if (!properties.enabled()) {
            return List.of();
        }
        return stateService.publishPartitions(roomId);
    }

    public Integer partitionIdForChannel(String channel) {
        if (channel == null || !channel.startsWith("chat:room-partition:")) {
            return null;
        }
        String[] parts = channel.split(":");
        if (parts.length != 4) {
            return null;
        }
        try {
            return Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public int normalizePartitionId(Integer partitionId, Long roomId) {
        return properties.normalizeForRoom(partitionId, partitionCountForRoom(roomId));
    }
}
