package io.hyun424.openchat.chat.room.partition;

import io.hyun424.openchat.chat.room.hot.RoomScaleTier;
import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
import io.hyun424.openchat.chat.room.hot.RoomTrafficSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.CRC32;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@Transactional
public class RoomPartitionStateService implements RoomPartitionStateOperations {

    private static final String SYSTEM_UPDATED_BY = "system";

    private final RoomPartitionStateRepository repository;
    private final RoomPartitionProperties properties;
    private final RoomTrafficMonitor roomTrafficMonitor;
    private final RoomPartitionMetrics metrics;
    private final Clock clock;

    @Autowired
    public RoomPartitionStateService(RoomPartitionStateRepository repository,
                                     RoomPartitionProperties properties,
                                     RoomTrafficMonitor roomTrafficMonitor,
                                     RoomPartitionMetrics metrics) {
        this(repository, properties, roomTrafficMonitor, metrics, Clock.systemUTC());
    }

    RoomPartitionStateService(RoomPartitionStateRepository repository,
                              RoomPartitionProperties properties,
                              RoomTrafficMonitor roomTrafficMonitor,
                              RoomPartitionMetrics metrics,
                              Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.roomTrafficMonitor = roomTrafficMonitor;
        this.metrics = metrics;
        this.clock = clock;
    }

    public RoomPartitionState getOrInitialize(Long roomId) {
        return repository.findById(roomId)
                .orElseGet(() -> {
                    RoomPartitionState initialized = RoomPartitionState.initialize(
                            roomId,
                            initialPartitionCount(roomId),
                            now(),
                            SYSTEM_UPDATED_BY
                    );
                    metrics.recordState(initialized.getStatus());
                    return repository.save(initialized);
                });
    }

    public int partitionCountForRoom(Long roomId) {
        if (!properties.enabled()) {
            return 1;
        }
        return getOrInitialize(roomId).getPartitionCount();
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

    public int routePartition(Long roomId, String userId) {
        RoomPartitionState state = getOrInitialize(roomId);
        int partitionCount = Math.max(1, state.getPartitionCount());
        if (partitionCount <= 1) {
            return 0;
        }

        int candidate = stablePartition(userId, partitionCount);
        Set<Integer> draining = drainingPartitions(state);
        if (!draining.contains(candidate)) {
            return candidate;
        }

        for (int offset = 1; offset < partitionCount; offset++) {
            int next = Math.floorMod(candidate + offset, partitionCount);
            if (!draining.contains(next)) {
                metrics.recordRouteDrainingAvoided();
                return next;
            }
        }

        return candidate;
    }

    public int versionForRoom(Long roomId) {
        if (!properties.enabled()) {
            return 0;
        }
        return getOrInitialize(roomId).getVersion();
    }

    @Override
    public RoomPartitionState scaleUp(Long roomId, int targetPartitionCount, String updatedBy) {
        RoomPartitionState state = getOrInitialize(roomId);
        int boundedTarget = boundPartitionCount(targetPartitionCount);
        if (boundedTarget <= state.getPartitionCount()) {
            metrics.recordScaleEvent("up", "noop");
            return state;
        }
        state.scaleUp(boundedTarget, now(), updatedBy);
        metrics.recordScaleEvent("up", "success");
        metrics.recordState(state.getStatus());
        return repository.save(state);
    }

    public RoomPartitionState scaleUp(Long roomId, String updatedBy) {
        RoomPartitionState state = getOrInitialize(roomId);
        int target = Math.max(2, state.getPartitionCount() * 2);
        return scaleUp(roomId, target, updatedBy);
    }

    public RoomPartitionState startDrain(Long roomId, Set<Integer> partitions, String updatedBy) {
        RoomPartitionState state = getOrInitialize(roomId);
        String normalized = normalizeDrainingPartitions(partitions, state.getPartitionCount());
        state.startDrain(normalized, now(), updatedBy);
        metrics.recordScaleEvent("down", "draining");
        metrics.recordState(state.getStatus());
        metrics.recordDrainingCount(drainingPartitions(state).size());
        return repository.save(state);
    }

    @Override
    public RoomPartitionState startDrain(Long roomId,
                                         int targetPartitionCount,
                                         Set<Integer> partitions,
                                         String updatedBy) {
        return startDrain(roomId, partitions, updatedBy);
    }

    public RoomPartitionState completeDrain(Long roomId, int targetPartitionCount, String updatedBy) {
        RoomPartitionState state = getOrInitialize(roomId);
        int boundedTarget = Math.max(1, Math.min(state.getPartitionCount(), targetPartitionCount));
        state.completeDrain(boundedTarget, now(), updatedBy);
        metrics.recordScaleEvent("down", "success");
        metrics.recordState(state.getStatus());
        metrics.recordDrainingCount(0);
        return repository.save(state);
    }

    @Override
    public RoomPartitionState completeDrain(Long roomId, String updatedBy) {
        RoomPartitionState state = getOrInitialize(roomId);
        int target = state.getPartitionCount() - drainingPartitions(state).size();
        return completeDrain(roomId, target, updatedBy);
    }

    private int initialPartitionCount(Long roomId) {
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

    private int boundPartitionCount(int partitionCount) {
        int configuredLimit = Math.min(properties.partitionCount(), properties.maxPartitionsPerRoom());
        return Math.max(1, Math.min(Math.max(1, partitionCount), configuredLimit));
    }

    private boolean isAtOrAboveThreshold(RoomScaleTier tier) {
        RoomScaleTier resolved = tier == null ? RoomScaleTier.SMALL : tier;
        return resolved.ordinal() >= properties.hotTierThreshold().ordinal();
    }

    private String normalizeDrainingPartitions(Set<Integer> partitions, int partitionCount) {
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

    private Set<Integer> drainingPartitions(RoomPartitionState state) {
        String raw = state.getDrainingPartitions();
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> {
                    try {
                        return Math.floorMod(Integer.parseInt(value), Math.max(1, state.getPartitionCount()));
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    private int stablePartition(String userId, int partitionCount) {
        CRC32 crc32 = new CRC32();
        byte[] bytes = (userId == null ? "" : userId).getBytes(StandardCharsets.UTF_8);
        crc32.update(bytes, 0, bytes.length);
        return Math.floorMod((int) crc32.getValue(), Math.max(1, partitionCount));
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
