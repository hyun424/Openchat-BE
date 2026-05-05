package io.hyun424.openchat.chat.room.partition;

import io.hyun424.openchat.chat.room.hot.RoomScaleTier;
import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
import io.hyun424.openchat.chat.room.hot.RoomTrafficSnapshot;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import java.util.stream.IntStream;

@Service
public class RoomPartitionRoutingService {

    private final RoomPartitionProperties properties;
    private final RoomTrafficMonitor roomTrafficMonitor;
    private final RoomPartitionMetrics metrics;

    public RoomPartitionRoutingService(RoomPartitionProperties properties,
                                       RoomTrafficMonitor roomTrafficMonitor,
                                       RoomPartitionMetrics metrics) {
        this.properties = properties;
        this.roomTrafficMonitor = roomTrafficMonitor;
        this.metrics = metrics;
        this.metrics.updateConfig(properties);
    }

    public RoomPartitionRoute route(Long roomId, String userId) {
        int partitionCount = partitionCountForRoom(roomId);
        boolean partitioned = partitionCount > 1;
        int partitionId = partitioned ? stablePartition(userId, partitionCount) : 0;
        metrics.recordRoute(partitioned ? "partitioned" : "legacy");
        return new RoomPartitionRoute(
                roomId,
                partitioned,
                partitionId,
                partitionCount,
                "/ws/chat?roomId=" + roomId + "&partitionId=" + partitionId
        );
    }

    public boolean shouldPartition(Long roomId) {
        return partitionCountForRoom(roomId) > 1;
    }

    public int partitionCountForRoom(Long roomId) {
        if (!properties.enabled()) {
            return 1;
        }
        RoomTrafficSnapshot snapshot = roomTrafficMonitor.snapshot(roomId);
        if (!isAtOrAboveThreshold(snapshot.scaleTier())) {
            return 1;
        }
        int recommended = Math.max(2, snapshot.effectivePartitions());
        int configuredLimit = Math.min(properties.partitionCount(), properties.maxPartitionsPerRoom());
        int bounded = Math.min(recommended, configuredLimit);
        return Math.max(1, bounded);
    }

    public List<Integer> publishPartitions(Long roomId) {
        int partitionCount = partitionCountForRoom(roomId);
        if (partitionCount <= 1) {
            return List.of();
        }
        return IntStream.range(0, partitionCount)
                .boxed()
                .toList();
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

    private boolean isAtOrAboveThreshold(RoomScaleTier tier) {
        RoomScaleTier resolved = tier == null ? RoomScaleTier.SMALL : tier;
        return resolved.ordinal() >= properties.hotTierThreshold().ordinal();
    }

    private int stablePartition(String userId, int partitionCount) {
        CRC32 crc32 = new CRC32();
        byte[] bytes = (userId == null ? "" : userId).getBytes(StandardCharsets.UTF_8);
        crc32.update(bytes, 0, bytes.length);
        return Math.floorMod((int) crc32.getValue(), Math.max(1, partitionCount));
    }
}
