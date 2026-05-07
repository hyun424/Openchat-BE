package io.hyun424.openchat.chat.room.partition.policy;

import io.hyun424.openchat.chat.room.hot.RoomScaleTier;
import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
import io.hyun424.openchat.chat.room.hot.RoomTrafficSnapshot;
import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionState;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.CRC32;
import java.util.stream.Collectors;

@Component
public class RoomPartitionPolicy {

    private final RoomPartitionProperties properties;
    private final RoomTrafficMonitor roomTrafficMonitor;

    public RoomPartitionPolicy(RoomPartitionProperties properties,
                               RoomTrafficMonitor roomTrafficMonitor) {
        this.properties = properties;
        this.roomTrafficMonitor = roomTrafficMonitor;
    }

    public int initialPartitionCount(Long roomId) {
        if (!properties.enabled()) {
            return 1;
        }
        RoomTrafficSnapshot snapshot = roomTrafficMonitor.snapshot(roomId);
        if (!isAtOrAboveThreshold(snapshot.scaleTier())) {
            return 1;
        }
        int recommended = Math.max(2, snapshot.effectivePartitions());
        return Math.max(1, Math.min(recommended, configuredLimit()));
    }

    public int boundPartitionCount(int partitionCount) {
        return Math.max(1, Math.min(Math.max(1, partitionCount), configuredLimit()));
    }

    public RouteDecision routePartition(String userId, int partitionCount, Set<Integer> drainingPartitions) {
        int resolvedCount = Math.max(1, partitionCount);
        if (resolvedCount <= 1) {
            return new RouteDecision(0, false);
        }

        int candidate = stablePartition(userId, resolvedCount);
        if (!drainingPartitions.contains(candidate)) {
            return new RouteDecision(candidate, false);
        }

        for (int offset = 1; offset < resolvedCount; offset++) {
            int next = Math.floorMod(candidate + offset, resolvedCount);
            if (!drainingPartitions.contains(next)) {
                return new RouteDecision(next, true);
            }
        }

        return new RouteDecision(candidate, false);
    }

    public String normalizeDrainingPartitions(Set<Integer> partitions, int partitionCount) {
        if (partitions == null || partitions.isEmpty()) {
            return "";
        }
        return partitions.stream()
                .map(partition -> Math.floorMod(partition == null ? 0 : partition, Math.max(1, partitionCount)))
                .collect(Collectors.toCollection(TreeSet::new))
                .stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    public Set<Integer> drainingPartitions(RoomPartitionState state) {
        String raw = state.getDrainingPartitions();
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> parsePartition(value, state.getPartitionCount()))
                .collect(Collectors.toUnmodifiableSet());
    }

    private int parsePartition(String raw, int partitionCount) {
        try {
            return Math.floorMod(Integer.parseInt(raw), Math.max(1, partitionCount));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int configuredLimit() {
        return Math.min(properties.partitionCount(), properties.maxPartitionsPerRoom());
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

    public record RouteDecision(int partitionId, boolean drainingAvoided) {
    }
}
