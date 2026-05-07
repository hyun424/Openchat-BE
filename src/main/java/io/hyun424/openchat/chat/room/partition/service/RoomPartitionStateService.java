package io.hyun424.openchat.chat.room.partition.service;

import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionState;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.partition.policy.RoomPartitionPolicy;
import io.hyun424.openchat.chat.room.partition.repository.RoomPartitionStateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

@Service
@Transactional
public class RoomPartitionStateService implements RoomPartitionStateOperations {

    private static final String SYSTEM_UPDATED_BY = "system";

    private final RoomPartitionStateRepository repository;
    private final RoomPartitionProperties properties;
    private final RoomPartitionPolicy policy;
    private final RoomPartitionMetrics metrics;
    private final Clock clock;

    @Autowired
    public RoomPartitionStateService(RoomPartitionStateRepository repository,
                                     RoomPartitionProperties properties,
                                     RoomPartitionPolicy policy,
                                     RoomPartitionMetrics metrics) {
        this(repository, properties, policy, metrics, Clock.systemUTC());
    }

    public RoomPartitionStateService(RoomPartitionStateRepository repository,
                                     RoomPartitionProperties properties,
                                     RoomPartitionPolicy policy,
                                     RoomPartitionMetrics metrics,
                                     Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.policy = policy;
        this.metrics = metrics;
        this.clock = clock;
    }

    public RoomPartitionState getOrInitialize(Long roomId) {
        return repository.findById(roomId)
                .orElseGet(() -> {
                    RoomPartitionState initialized = RoomPartitionState.initialize(
                            roomId,
                            policy.initialPartitionCount(roomId),
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

        RoomPartitionPolicy.RouteDecision decision = policy.routePartition(
                userId,
                partitionCount,
                policy.drainingPartitions(state)
        );
        if (decision.drainingAvoided()) {
            metrics.recordRouteDrainingAvoided();
        }
        return decision.partitionId();
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
        int boundedTarget = policy.boundPartitionCount(targetPartitionCount);
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
        String normalized = policy.normalizeDrainingPartitions(partitions, state.getPartitionCount());
        state.startDrain(normalized, now(), updatedBy);
        metrics.recordScaleEvent("down", "draining");
        metrics.recordState(state.getStatus());
        metrics.recordDrainingCount(policy.drainingPartitions(state).size());
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
        int target = state.getPartitionCount() - policy.drainingPartitions(state).size();
        return completeDrain(roomId, target, updatedBy);
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
