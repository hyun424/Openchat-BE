package io.hyun424.openchat.chat.room.partition.service;

import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionRoute;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RoomPartitionRoutingService {

    private final RoomPartitionProperties properties;
    private final RoomPartitionStateReader stateReader;
    private final RoomPartitionMetrics metrics;

    public RoomPartitionRoutingService(RoomPartitionProperties properties,
                                       RoomPartitionStateReader stateReader,
                                       RoomPartitionMetrics metrics) {
        this.properties = properties;
        this.stateReader = stateReader;
        this.metrics = metrics;
        this.metrics.updateConfig(properties);
    }

    public RoomPartitionRoute route(Long roomId, String userId) {
        int partitionCount = partitionCountForRoom(roomId);
        if (partitionCount <= 1) {
            metrics.recordRoute("legacy");
            return RoomPartitionRoute.legacy(roomId);
        }

        int partitionId = stateReader.routePartition(roomId, userId);
        int version = stateReader.versionForRoom(roomId);
        metrics.recordRoute("partitioned");
        return RoomPartitionRoute.partitioned(roomId, partitionId, partitionCount, version);
    }

    public boolean shouldPartition(Long roomId) {
        return partitionCountForRoom(roomId) > 1;
    }

    public int partitionCountForRoom(Long roomId) {
        if (!properties.enabled()) {
            return 1;
        }
        return stateReader.partitionCountForRoom(roomId);
    }

    public List<Integer> publishPartitions(Long roomId) {
        if (!properties.enabled()) {
            return List.of();
        }
        return stateReader.publishPartitions(roomId);
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
