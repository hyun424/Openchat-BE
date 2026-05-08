package io.hyun424.openchat.chat.room.partition.lifecycle;

import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionState;
import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionStatus;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.partition.repository.RoomPartitionStateRepository;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionStateService;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadClusterSummary;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadRecommendation;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadRecommendationType;
import io.hyun424.openchat.chat.room.workload.dto.RoomWorkloadCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class RoomPartitionLifecycleService {

    private static final String SCALE_UP = "scale_up";

    private final RoomPartitionStateRepository stateRepository;
    private final RoomPartitionStateService stateService;
    private final RoomPartitionRedistributionService redistributionService;
    private final RoomPartitionScaleDownService scaleDownService;
    private final RoomPartitionLifecycleCooldowns cooldowns;
    private final RoomPartitionLifecycleProperties lifecycleProperties;
    private final RoomPartitionProperties partitionProperties;
    private final RoomPartitionMetrics metrics;
    private final Clock clock;
    private final Map<Long, Observation> scaleUpObservations = new ConcurrentHashMap<>();

    @Autowired
    public RoomPartitionLifecycleService(RoomPartitionStateRepository stateRepository,
                                         RoomPartitionStateService stateService,
                                         RoomPartitionRedistributionService redistributionService,
                                         RoomPartitionScaleDownService scaleDownService,
                                         RoomPartitionLifecycleCooldowns cooldowns,
                                         RoomPartitionLifecycleProperties lifecycleProperties,
                                         RoomPartitionProperties partitionProperties,
                                         RoomPartitionMetrics metrics) {
        this(
                stateRepository,
                stateService,
                redistributionService,
                scaleDownService,
                cooldowns,
                lifecycleProperties,
                partitionProperties,
                metrics,
                Clock.systemUTC()
        );
    }

    public RoomPartitionLifecycleService(RoomPartitionStateRepository stateRepository,
                                         RoomPartitionStateService stateService,
                                         RoomPartitionRedistributionService redistributionService,
                                         RoomPartitionScaleDownService scaleDownService,
                                         RoomPartitionLifecycleCooldowns cooldowns,
                                         RoomPartitionLifecycleProperties lifecycleProperties,
                                         RoomPartitionProperties partitionProperties,
                                         RoomPartitionMetrics metrics,
                                         Clock clock) {
        this.stateRepository = stateRepository;
        this.stateService = stateService;
        this.redistributionService = redistributionService;
        this.scaleDownService = scaleDownService;
        this.cooldowns = cooldowns;
        this.lifecycleProperties = lifecycleProperties;
        this.partitionProperties = partitionProperties;
        this.metrics = metrics;
        this.clock = clock;
    }

    public int run(RealtimeWorkloadClusterSummary summary) {
        int actions = 0;
        if (tryScaleUp(summary)) {
            actions++;
        }
        while (actions < lifecycleProperties.maxActionsPerRun()) {
            if (!scaleDownService.run(summary)) {
                break;
            }
            actions++;
        }
        return actions;
    }

    public boolean tryScaleUp(RealtimeWorkloadClusterSummary summary) {
        if (!lifecycleProperties.scaleUp().enabled()) {
            record(SCALE_UP, "skip_disabled");
            return false;
        }
        if (hasRecommendation(summary, RealtimeWorkloadRecommendationType.INVESTIGATE_STALE_NODE)) {
            record(SCALE_UP, "skip_stale_node");
            return false;
        }
        if (hasRecommendation(summary, RealtimeWorkloadRecommendationType.INVESTIGATE_SEND_FAILURE)) {
            record(SCALE_UP, "skip_send_failure");
            return false;
        }

        Optional<RoomWorkloadCandidate> candidate = scaleUpCandidate(summary);
        if (candidate.isEmpty()) {
            record(SCALE_UP, "skip_no_candidate");
            return false;
        }

        RoomWorkloadCandidate hotRoom = candidate.get();
        Observation observation = observe(hotRoom.roomId());
        if (!stable(observation)) {
            record(SCALE_UP, "skip_not_stable");
            return false;
        }

        Optional<RoomPartitionState> currentState = stateRepository.findById(hotRoom.roomId());
        if (currentState.isEmpty()) {
            record(SCALE_UP, "skip_missing_state");
            return false;
        }
        RoomPartitionState state = currentState.get();
        if (state.getStatus() != RoomPartitionStatus.ACTIVE) {
            record(SCALE_UP, "skip_not_active");
            return false;
        }

        int currentPartitions = state.getPartitionCount();
        int target = targetPartitions(hotRoom, currentPartitions);
        if (target <= currentPartitions) {
            record(SCALE_UP, "skip_no_target");
            return false;
        }
        if (!cooldowns.acquire(SCALE_UP, hotRoom.roomId(), lifecycleProperties.scaleUp().cooldownMillis())) {
            record(SCALE_UP, "skip_cooldown");
            return false;
        }

        RoomPartitionState scaled = stateService.scaleUp(hotRoom.roomId(), target, lifecycleProperties.updatedBy());
        redistributionService.redistribute(
                hotRoom.roomId(),
                currentPartitions,
                scaled.getPartitionCount(),
                scaled.getVersion()
        );
        RoomPartitionState completed = stateService.completeScaleUp(hotRoom.roomId(), lifecycleProperties.updatedBy());
        record(SCALE_UP, "success");
        log.info("partition scale-up completed roomId={} currentPartitions={} targetPartitions={} routeVersion={} observedWork={} threshold={}",
                hotRoom.roomId(),
                currentPartitions,
                completed.getPartitionCount(),
                completed.getVersion(),
                hotRoom.scaleDecisionWorkPerSecond(),
                scaleUpThreshold(summary, hotRoom.roomId()));
        return true;
    }

    private Optional<RoomWorkloadCandidate> scaleUpCandidate(RealtimeWorkloadClusterSummary summary) {
        return summary.recommendations().stream()
                .filter(recommendation -> recommendation.type() == RealtimeWorkloadRecommendationType.SCALE_UP_CANDIDATE)
                .map(RealtimeWorkloadRecommendation::roomId)
                .flatMap(roomId -> summary.topRooms().stream()
                        .filter(candidate -> candidate.roomId().equals(roomId)))
                .max(Comparator.comparingLong(RoomWorkloadCandidate::scaleDecisionWorkPerSecond));
    }

    private boolean hasRecommendation(RealtimeWorkloadClusterSummary summary, RealtimeWorkloadRecommendationType type) {
        return summary.recommendations().stream().anyMatch(recommendation -> recommendation.type() == type);
    }

    private int targetPartitions(RoomWorkloadCandidate candidate, int currentPartitions) {
        if (candidate.effectivePartitions() <= currentPartitions) {
            return currentPartitions;
        }
        int candidateTarget = Math.max(Math.max(candidate.effectivePartitions(), currentPartitions + 1), 2);
        return Math.min(candidateTarget, Math.min(partitionProperties.maxPartitionsPerRoom(), partitionProperties.partitionCount()));
    }

    private Observation observe(Long roomId) {
        long now = Instant.now(clock).toEpochMilli();
        return scaleUpObservations.compute(roomId, (ignored, previous) -> {
            if (previous == null) {
                return new Observation(now, 1);
            }
            return new Observation(previous.firstObservedAtMillis(), previous.count() + 1);
        });
    }

    private boolean stable(Observation observation) {
        long now = Instant.now(clock).toEpochMilli();
        return observation.count() >= lifecycleProperties.scaleUp().minObservations()
                && now - observation.firstObservedAtMillis() >= lifecycleProperties.scaleUp().stableWindowMillis();
    }

    private long scaleUpThreshold(RealtimeWorkloadClusterSummary summary, Long roomId) {
        return summary.recommendations().stream()
                .filter(recommendation -> recommendation.type() == RealtimeWorkloadRecommendationType.SCALE_UP_CANDIDATE)
                .filter(recommendation -> roomId.equals(recommendation.roomId()))
                .mapToLong(RealtimeWorkloadRecommendation::threshold)
                .findFirst()
                .orElse(0L);
    }

    private void record(String operation, String result) {
        metrics.recordLifecycleEvent(operation, result);
    }

    private record Observation(long firstObservedAtMillis, int count) {
    }
}
