package io.hyun424.openchat.chat.room.partition.lifecycle;

import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionState;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.partition.policy.RoomPartitionPolicy;
import io.hyun424.openchat.chat.room.partition.repository.RoomPartitionStateRepository;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionReconnectOperations;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionStateService;
import io.hyun424.openchat.chat.room.workload.config.RealtimeWorkloadProperties;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadClusterSummary;
import io.hyun424.openchat.chat.room.workload.dto.RoomPartitionDrainProgress;
import io.hyun424.openchat.chat.room.workload.dto.RoomWorkloadCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
public class RoomPartitionScaleDownService {

    private static final String SCALE_DOWN = "scale_down";
    private static final String DRAIN_RECONNECT = "drain_reconnect";
    private static final String DRAIN_COMPLETE = "drain_complete";

    private final RoomPartitionStateRepository stateRepository;
    private final RoomPartitionStateService stateService;
    private final RoomPartitionReconnectOperations reconnectOperations;
    private final RoomPartitionPolicy partitionPolicy;
    private final RoomPartitionLifecycleCooldowns cooldowns;
    private final RoomPartitionLifecycleProperties lifecycleProperties;
    private final RealtimeWorkloadProperties workloadProperties;
    private final RoomPartitionMetrics metrics;
    private final Clock clock;
    private final Map<Long, Observation> lowWorkObservations = new ConcurrentHashMap<>();
    private final Map<Long, Integer> emptyDrainObservations = new ConcurrentHashMap<>();

    @Autowired
    public RoomPartitionScaleDownService(RoomPartitionStateRepository stateRepository,
                                         RoomPartitionStateService stateService,
                                         RoomPartitionReconnectOperations reconnectOperations,
                                         RoomPartitionPolicy partitionPolicy,
                                         RoomPartitionLifecycleCooldowns cooldowns,
                                         RoomPartitionLifecycleProperties lifecycleProperties,
                                         RealtimeWorkloadProperties workloadProperties,
                                         RoomPartitionMetrics metrics) {
        this(
                stateRepository,
                stateService,
                reconnectOperations,
                partitionPolicy,
                cooldowns,
                lifecycleProperties,
                workloadProperties,
                metrics,
                Clock.systemUTC()
        );
    }

    public RoomPartitionScaleDownService(RoomPartitionStateRepository stateRepository,
                                         RoomPartitionStateService stateService,
                                         RoomPartitionReconnectOperations reconnectOperations,
                                         RoomPartitionPolicy partitionPolicy,
                                         RoomPartitionLifecycleCooldowns cooldowns,
                                         RoomPartitionLifecycleProperties lifecycleProperties,
                                         RealtimeWorkloadProperties workloadProperties,
                                         RoomPartitionMetrics metrics,
                                         Clock clock) {
        this.stateRepository = stateRepository;
        this.stateService = stateService;
        this.reconnectOperations = reconnectOperations;
        this.partitionPolicy = partitionPolicy;
        this.cooldowns = cooldowns;
        this.lifecycleProperties = lifecycleProperties;
        this.workloadProperties = workloadProperties;
        this.metrics = metrics;
        this.clock = clock;
    }

    public boolean run(RealtimeWorkloadClusterSummary summary) {
        if (!lifecycleProperties.scaleDown().enabled()) {
            record(SCALE_DOWN, "skip_disabled");
            return false;
        }
        if (summary.staleNodeCount() > 0) {
            record(SCALE_DOWN, "skip_stale_node");
            return false;
        }

        Optional<RoomPartitionState> draining = stateRepository.findByStatus(io.hyun424.openchat.chat.room.partition.domain.RoomPartitionStatus.DRAINING)
                .stream()
                .min(Comparator.comparing(RoomPartitionState::getUpdatedAt));
        if (draining.isPresent()) {
            return handleDraining(summary, draining.get());
        }

        List<RoomPartitionState> candidates = lifecycleProperties.scaleDown().autoManagedOnly()
                ? stateRepository.findAutoManagedActiveScaleDownCandidates(lifecycleProperties.updatedBy())
                : stateRepository.findActiveScaleDownCandidates();
        if (candidates.isEmpty()) {
            record(SCALE_DOWN, "skip_no_candidate");
            return false;
        }

        Map<Long, RoomWorkloadCandidate> topRoomById = summary.topRooms().stream()
                .collect(Collectors.toMap(RoomWorkloadCandidate::roomId, Function.identity(), this::busier));
        long threshold = watchThreshold();
        for (RoomPartitionState state : candidates) {
            long observed = Optional.ofNullable(topRoomById.get(state.getRoomId()))
                    .map(RoomWorkloadCandidate::scaleDecisionWorkPerSecond)
                    .orElse(0L);
            if (observed >= threshold) {
                lowWorkObservations.remove(state.getRoomId());
                continue;
            }
            if (!partitionAgeSatisfied(state)) {
                record(SCALE_DOWN, "skip_partition_age");
                continue;
            }
            Observation observation = observe(lowWorkObservations, state.getRoomId());
            if (!stable(observation, lifecycleProperties.scaleDown().minObservations(), lifecycleProperties.scaleDown().stableWindowMillis())) {
                record(SCALE_DOWN, "skip_not_stable");
                continue;
            }
            if (!cooldowns.acquire(SCALE_DOWN, state.getRoomId(), lifecycleProperties.scaleDown().cooldownMillis())) {
                record(SCALE_DOWN, "skip_cooldown");
                continue;
            }

            int target = Math.max(1, state.getPartitionCount() / 2);
            if (target >= state.getPartitionCount()) {
                record(SCALE_DOWN, "skip_no_target");
                continue;
            }
            Set<Integer> drainingPartitions = IntStream.range(target, state.getPartitionCount())
                    .boxed()
                    .collect(Collectors.toCollection(TreeSet::new));
            RoomPartitionState updated = stateService.startDrain(
                    state.getRoomId(),
                    target,
                    drainingPartitions,
                    lifecycleProperties.updatedBy()
            );
            record(SCALE_DOWN, "success");
            log.info("partition scale-down drain started roomId={} currentPartitions={} targetPartitions={} drainingPartitions={} observedWork={} threshold={}",
                    state.getRoomId(), state.getPartitionCount(), target, drainingPartitions, observed, threshold);
            requestDrainReconnect(updated);
            return true;
        }

        record(SCALE_DOWN, "skip_no_candidate");
        return false;
    }

    private boolean handleDraining(RealtimeWorkloadClusterSummary summary, RoomPartitionState state) {
        Set<Integer> drainingPartitions = partitionPolicy.drainingPartitions(state);
        int remaining = summary.drainProgress().stream()
                .filter(progress -> state.getRoomId().equals(progress.roomId()))
                .filter(progress -> drainingPartitions.contains(progress.partitionId()))
                .mapToInt(RoomPartitionDrainProgress::openSessions)
                .sum();
        if (remaining > 0) {
            emptyDrainObservations.remove(state.getRoomId());
            record(DRAIN_COMPLETE, "skip_sessions_remaining");
            requestDrainReconnect(state);
            return true;
        }

        int emptyCount = emptyDrainObservations.merge(state.getRoomId(), 1, Integer::sum);
        if (emptyCount < lifecycleProperties.drain().completeEmptyObservations()) {
            record(DRAIN_COMPLETE, "skip_not_stable");
            requestDrainReconnect(state);
            return true;
        }

        stateService.completeDrain(state.getRoomId(), lifecycleProperties.updatedBy());
        emptyDrainObservations.remove(state.getRoomId());
        lowWorkObservations.remove(state.getRoomId());
        record(DRAIN_COMPLETE, "success");
        log.info("partition drain completed roomId={} previousPartitions={} drainingPartitions={}",
                state.getRoomId(), state.getPartitionCount(), drainingPartitions);
        return true;
    }

    private void requestDrainReconnect(RoomPartitionState state) {
        if (!cooldowns.acquire(DRAIN_RECONNECT, state.getRoomId(), lifecycleProperties.drain().reconnectRetryAfterMillis())) {
            record(DRAIN_RECONNECT, "skip_cooldown");
            return;
        }
        RoomPartitionReconnectOperations.RoomPartitionReconnectResult result = reconnectOperations.reconnectDraining(
                state.getRoomId(),
                SCALE_DOWN,
                lifecycleProperties.drain().reconnectRetryAfterMillis(),
                lifecycleProperties.drain().reconnectLimitPerPartition()
        );
        record(DRAIN_RECONNECT, result.publishedCommands() > 0 ? "success" : "publish_failed");
    }

    private boolean partitionAgeSatisfied(RoomPartitionState state) {
        Instant updatedAt = state.getUpdatedAt();
        if (updatedAt == null) {
            return true;
        }
        long ageMillis = Duration.between(updatedAt, Instant.now(clock)).toMillis();
        return ageMillis >= lifecycleProperties.scaleDown().minPartitionAgeMillis();
    }

    private Observation observe(Map<Long, Observation> observations, Long roomId) {
        long now = Instant.now(clock).toEpochMilli();
        return observations.compute(roomId, (ignored, previous) -> {
            if (previous == null) {
                return new Observation(now, 1);
            }
            return new Observation(previous.firstObservedAtMillis(), previous.count() + 1);
        });
    }

    private boolean stable(Observation observation, int minObservations, long stableWindowMillis) {
        long now = Instant.now(clock).toEpochMilli();
        return observation.count() >= minObservations
                && now - observation.firstObservedAtMillis() >= stableWindowMillis;
    }

    private RoomWorkloadCandidate busier(RoomWorkloadCandidate left, RoomWorkloadCandidate right) {
        return left.scaleDecisionWorkPerSecond() >= right.scaleDecisionWorkPerSecond() ? left : right;
    }

    private long watchThreshold() {
        return Math.max(1L, Math.round(workloadProperties.podWorkBudgetDeliveryPerSecond() * workloadProperties.watchRatio()));
    }

    private void record(String operation, String result) {
        metrics.recordLifecycleEvent(operation, result);
    }

    private record Observation(long firstObservedAtMillis, int count) {
    }
}
