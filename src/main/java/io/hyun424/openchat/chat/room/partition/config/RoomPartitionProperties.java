package io.hyun424.openchat.chat.room.partition.config;

import io.hyun424.openchat.chat.room.hot.RoomScaleTier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Component
public class RoomPartitionProperties {

    private final boolean enabled;
    private final int partitionCount;
    private final Set<Integer> ownedPartitions;
    private final RoomScaleTier hotTierThreshold;
    private final int maxPartitionsPerRoom;

    @Autowired
    public RoomPartitionProperties(
            @Value("${app.room-partition.enabled:false}") boolean enabled,
            @Value("${app.room-partition.partition-count:1}") int partitionCount,
            @Value("${app.room-partition.owned-partitions:0}") String ownedPartitions,
            @Value("${app.room-partition.hot-tier-threshold:CRITICAL}") String hotTierThreshold,
            @Value("${app.room-partition.max-partitions-per-room:16}") int maxPartitionsPerRoom
    ) {
        this.enabled = enabled;
        this.partitionCount = Math.max(1, partitionCount);
        this.ownedPartitions = parseOwnedPartitions(ownedPartitions, this.partitionCount);
        this.hotTierThreshold = parseTier(hotTierThreshold);
        this.maxPartitionsPerRoom = Math.max(1, maxPartitionsPerRoom);
    }

    public RoomPartitionProperties(boolean enabled,
                                   int partitionCount,
                                   Set<Integer> ownedPartitions,
                                   RoomScaleTier hotTierThreshold,
                                   int maxPartitionsPerRoom) {
        this.enabled = enabled;
        this.partitionCount = Math.max(1, partitionCount);
        this.ownedPartitions = ownedPartitions == null || ownedPartitions.isEmpty()
                ? Set.of(0)
                : ownedPartitions.stream()
                .map(this::normalizePartitionId)
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(TreeSet::new),
                        Collections::unmodifiableSet));
        this.hotTierThreshold = hotTierThreshold == null ? RoomScaleTier.CRITICAL : hotTierThreshold;
        this.maxPartitionsPerRoom = Math.max(1, maxPartitionsPerRoom);
    }

    public boolean enabled() {
        return enabled;
    }

    public int partitionCount() {
        return partitionCount;
    }

    public Set<Integer> ownedPartitions() {
        return ownedPartitions;
    }

    public RoomScaleTier hotTierThreshold() {
        return hotTierThreshold;
    }

    public int maxPartitionsPerRoom() {
        return maxPartitionsPerRoom;
    }

    public int normalizePartitionId(Integer partitionId) {
        return Math.floorMod(partitionId == null ? 0 : partitionId, partitionCount);
    }

    public int normalizeForRoom(Integer partitionId, int roomPartitionCount) {
        return Math.floorMod(partitionId == null ? 0 : partitionId, Math.max(1, roomPartitionCount));
    }

    public boolean ownsPartition(int partitionId) {
        return ownedPartitions.contains(normalizePartitionId(partitionId));
    }

    private Set<Integer> parseOwnedPartitions(String rawOwnedPartitions, int resolvedPartitionCount) {
        if (rawOwnedPartitions == null || rawOwnedPartitions.isBlank()) {
            return Set.of(0);
        }
        Set<Integer> parsed = Arrays.stream(rawOwnedPartitions.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> {
                    try {
                        return Math.floorMod(Integer.parseInt(value), resolvedPartitionCount);
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                })
                .collect(Collectors.toCollection(TreeSet::new));
        return parsed.isEmpty() ? Set.of(0) : Collections.unmodifiableSet(parsed);
    }

    private RoomScaleTier parseTier(String value) {
        if (value == null || value.isBlank()) {
            return RoomScaleTier.CRITICAL;
        }
        try {
            return RoomScaleTier.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return RoomScaleTier.CRITICAL;
        }
    }
}
