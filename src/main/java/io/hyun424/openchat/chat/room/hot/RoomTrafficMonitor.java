package io.hyun424.openchat.chat.room.hot;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

@Slf4j
@Component
public class RoomTrafficMonitor {

    private final ConcurrentHashMap<Long, RoomTrafficStats> rooms = new ConcurrentHashMap<>();
    private final RoomHotStateProperties properties;
    private final RoomScaleProperties scaleProperties;
    private final RoomHotStateClassifier classifier;
    private final RoomScaleClassifier scaleClassifier;
    private final RoomPartitionAdvisor partitionAdvisor;
    private final LongSupplier clock;
    private final ScheduledExecutorService scheduler;
    private final Map<RoomHotState, AtomicInteger> roomCountsByState = new EnumMap<>(RoomHotState.class);
    private final Map<RoomScaleTier, AtomicInteger> roomCountsByScaleTier = new EnumMap<>(RoomScaleTier.class);
    private final AtomicLong maxDeliveryLagP95Millis = new AtomicLong(0);
    private final AtomicLong maxOutboundFanoutPerSecond = new AtomicLong(0);
    private final AtomicLong maxRoomWorkPerSecond = new AtomicLong(0);
    private final AtomicLong maxActualDeliveryWorkPerSecond = new AtomicLong(0);
    private final AtomicLong maxConceptualRoomWorkPerSecond = new AtomicLong(0);
    private final AtomicLong maxScaleDecisionWorkPerSecond = new AtomicLong(0);
    private final AtomicInteger maxRecommendedPartitionCount = new AtomicInteger(1);
    private final AtomicInteger maxEffectivePartitionCount = new AtomicInteger(1);
    private final AtomicInteger partitionRecommendationLimitedCount = new AtomicInteger(0);
    private final AtomicLong podWorkBudgetDeliveryPerSecond = new AtomicLong(0);

    public RoomTrafficMonitor() {
        this(null, RoomHotStateProperties.defaults(), RoomScaleProperties.defaults(), System::currentTimeMillis, false);
    }

    @Autowired
    public RoomTrafficMonitor(MeterRegistry meterRegistry,
                              @Value("${app.room.hot-state.window-seconds:10}") int windowSeconds,
                              @Value("${app.room.hot-state.max-latency-samples:2048}") int maxLatencySamples,
                              @Value("${app.room.hot-state.inactive-ttl-ms:300000}") long inactiveTtlMillis,
                              @Value("${app.room.hot-state.upgrade-stable-ms:5000}") long upgradeStableMillis,
                              @Value("${app.room.hot-state.downgrade-stable-ms:60000}") long downgradeStableMillis,
                              @Value("${app.room.hot-state.watched-connected-sessions:50}") int watchedConnectedSessions,
                              @Value("${app.room.hot-state.watched-outbound-fanout-per-sec:1000}") long watchedOutboundFanoutPerSecond,
                              @Value("${app.room.hot-state.warm-join-rate-per-sec:5}") long warmJoinRatePerSecond,
                              @Value("${app.room.hot-state.warm-delivery-lag-p95-ms:50}") long warmDeliveryLagP95Millis,
                              @Value("${app.room.hot-state.warm-outbound-fanout-per-sec:5000}") long warmOutboundFanoutPerSecond,
                              @Value("${app.room.hot-state.hot-inbound-messages-per-sec:30}") long hotInboundMessagesPerSecond,
                              @Value("${app.room.hot-state.hot-delivery-lag-p95-ms:100}") long hotDeliveryLagP95Millis,
                              @Value("${app.room.hot-state.hot-outbound-fanout-per-sec:10000}") long hotOutboundFanoutPerSecond,
                              @Value("${app.room.hot-state.super-hot-connected-sessions:500}") int superHotConnectedSessions,
                              @Value("${app.room.hot-state.super-hot-delivery-lag-p95-ms:300}") long superHotDeliveryLagP95Millis,
                              @Value("${app.room.hot-state.super-hot-outbound-fanout-per-sec:50000}") long superHotOutboundFanoutPerSecond,
                              @Value("${app.room.scale.upgrade-stable-ms:180000}") long scaleUpgradeStableMillis,
                              @Value("${app.room.scale.downgrade-stable-ms:600000}") long scaleDowngradeStableMillis,
                              @Value("${app.room.scale.medium-room-work-per-sec:1000}") long mediumRoomWorkPerSecond,
                              @Value("${app.room.scale.large-room-work-per-sec:5000}") long largeRoomWorkPerSecond,
                              @Value("${app.room.scale.hot-room-work-per-sec:10000}") long hotRoomWorkPerSecond,
                              @Value("${app.room.scale.critical-room-work-per-sec:20000}") long criticalRoomWorkPerSecond,
                              @Value("${app.room.scale.pod-work-budget-delivery-per-sec:10000}") long podWorkBudgetDeliveryPerSecond,
                              @Value("${app.room.scale.max-active-sessions-per-partition:500}") int maxActiveSessionsPerPartition,
                              @Value("${app.room.scale.max-partition-limit:16}") int maxPartitionLimit) {
        this(
                meterRegistry,
                new RoomHotStateProperties(
                        Math.max(1, windowSeconds),
                        Math.max(1, maxLatencySamples),
                        Math.max(1, inactiveTtlMillis),
                        Math.max(0, upgradeStableMillis),
                        Math.max(0, downgradeStableMillis),
                        Math.max(0, watchedConnectedSessions),
                        Math.max(0, watchedOutboundFanoutPerSecond),
                        Math.max(0, warmJoinRatePerSecond),
                        Math.max(0, warmDeliveryLagP95Millis),
                        Math.max(0, warmOutboundFanoutPerSecond),
                        Math.max(0, hotInboundMessagesPerSecond),
                        Math.max(0, hotDeliveryLagP95Millis),
                        Math.max(0, hotOutboundFanoutPerSecond),
                        Math.max(0, superHotConnectedSessions),
                        Math.max(0, superHotDeliveryLagP95Millis),
                        Math.max(0, superHotOutboundFanoutPerSecond)
                ),
                new RoomScaleProperties(
                        Math.max(0, scaleUpgradeStableMillis),
                        Math.max(0, scaleDowngradeStableMillis),
                        Math.max(0, mediumRoomWorkPerSecond),
                        Math.max(0, largeRoomWorkPerSecond),
                        Math.max(0, hotRoomWorkPerSecond),
                        Math.max(0, criticalRoomWorkPerSecond),
                        Math.max(1, podWorkBudgetDeliveryPerSecond),
                        Math.max(1, maxActiveSessionsPerPartition),
                        Math.max(1, maxPartitionLimit)
                ),
                System::currentTimeMillis,
                true
        );
    }

    RoomTrafficMonitor(MeterRegistry meterRegistry,
                       RoomHotStateProperties properties,
                       RoomScaleProperties scaleProperties,
                       LongSupplier clock,
                       boolean startScheduler) {
        this.properties = properties;
        this.scaleProperties = scaleProperties;
        this.classifier = new RoomHotStateClassifier(properties);
        this.scaleClassifier = new RoomScaleClassifier(scaleProperties);
        this.partitionAdvisor = new RoomPartitionAdvisor(scaleProperties);
        this.clock = clock;
        for (RoomHotState state : RoomHotState.values()) {
            roomCountsByState.put(state, new AtomicInteger(0));
        }
        for (RoomScaleTier tier : RoomScaleTier.values()) {
            roomCountsByScaleTier.put(tier, new AtomicInteger(0));
        }
        podWorkBudgetDeliveryPerSecond.set(scaleProperties.podWorkBudgetDeliveryPerSecond());
        registerGauges(meterRegistry);
        if (startScheduler) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "room-hot-state-monitor");
                t.setDaemon(true);
                return t;
            });
            this.scheduler.scheduleAtFixedRate(this::refresh, 1, 1, TimeUnit.SECONDS);
        } else {
            this.scheduler = null;
        }
    }

    public void recordJoin(Long roomId, int connectedSessions) {
        stats(roomId).recordJoin(clock.getAsLong(), connectedSessions);
    }

    public void recordLeave(Long roomId, int connectedSessions) {
        stats(roomId).recordLeave(clock.getAsLong(), connectedSessions);
    }

    public void recordInboundMessage(Long roomId) {
        stats(roomId).recordInbound(clock.getAsLong());
    }

    public void recordOutboundFanout(Long roomId, int recipientCount) {
        recordOutboundFanout(roomId, recipientCount, recipientCount);
    }

    public void recordOutboundFanout(Long roomId, int recipientCount, int activeSessions) {
        stats(roomId).recordOutboundFanout(clock.getAsLong(), recipientCount, activeSessions);
    }

    public void recordDeliveryLag(Long roomId, long createdAtMillis) {
        long nowMillis = clock.getAsLong();
        stats(roomId).recordDeliveryLag(nowMillis, nowMillis - createdAtMillis);
    }

    public void recordLaneQueueWait(Long roomId, long waitMillis) {
        stats(roomId).recordLaneQueueWait(clock.getAsLong(), waitMillis);
    }

    public void markMainExposed(Long roomId, boolean mainExposed) {
        stats(roomId).markMainExposed(clock.getAsLong(), mainExposed);
    }

    public RoomHotState state(Long roomId) {
        RoomTrafficStats stats = rooms.get(roomId);
        return stats != null ? stats.state() : RoomHotState.NORMAL;
    }

    public RoomTrafficSnapshot snapshot(Long roomId) {
        RoomTrafficStats stats = rooms.get(roomId);
        if (stats == null) {
            return new RoomTrafficSnapshot(
                    roomId, 0, 0, 0, 0, 0, 0, RoomHotState.NORMAL,
                    0, 0, 0, 0, 0, RoomScaleTier.SMALL, 1, 1
            );
        }
        return stats.snapshot(clock.getAsLong(), partitionAdvisor);
    }

    public List<RoomTrafficSnapshot> snapshots() {
        long nowMillis = clock.getAsLong();
        return rooms.values().stream()
                .map(stats -> stats.snapshot(nowMillis, partitionAdvisor))
                .toList();
    }

    public List<RoomTrafficSnapshot> topRoomsByScaleDecisionWork(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return snapshots().stream()
                .sorted(Comparator.comparingLong(RoomTrafficSnapshot::scaleDecisionWorkPerSecond).reversed())
                .limit(limit)
                .toList();
    }

    public RoomTrafficWorkloadSummary workloadSummary() {
        long maxActual = 0;
        long maxConceptual = 0;
        long maxDecision = 0;
        int limitedCount = 0;
        for (RoomTrafficSnapshot snapshot : snapshots()) {
            maxActual = Math.max(maxActual, snapshot.actualDeliveryWorkPerSecond());
            maxConceptual = Math.max(maxConceptual, snapshot.conceptualRoomWorkPerSecond());
            maxDecision = Math.max(maxDecision, snapshot.scaleDecisionWorkPerSecond());
            if (snapshot.partitionRecommendationLimited()) {
                limitedCount++;
            }
        }
        return new RoomTrafficWorkloadSummary(maxActual, maxConceptual, maxDecision, limitedCount);
    }

    public void refresh() {
        long nowMillis = clock.getAsLong();
        resetGauges();

        List<Long> inactiveRooms = new ArrayList<>();
        long maxLag = 0;
        long maxFanout = 0;
        long maxRoomWork = 0;
        long maxActualDeliveryWork = 0;
        long maxConceptualRoomWork = 0;
        long maxScaleDecisionWork = 0;
        int maxRecommendedPartitions = 1;
        int maxEffectivePartitions = 1;
        int limitedPartitionRecommendations = 0;

        for (RoomTrafficStats stats : rooms.values()) {
            if (stats.isInactive(nowMillis, properties.inactiveTtlMillis())) {
                inactiveRooms.add(stats.snapshot(nowMillis, partitionAdvisor).roomId());
                continue;
            }

            RoomTrafficSnapshot snapshot = stats.snapshot(nowMillis, partitionAdvisor);
            RoomHotState nextState = classifier.classify(snapshot, stats.isMainExposed());
            transitionIfStable(stats, snapshot, nextState, nowMillis);
            RoomScaleTier nextScaleTier = scaleClassifier.classify(snapshot.roomWorkPerSecond());
            transitionScaleTierIfStable(stats, snapshot, nextScaleTier, nowMillis);

            RoomTrafficSnapshot updatedSnapshot = stats.snapshot(nowMillis, partitionAdvisor);
            roomCountsByState.get(updatedSnapshot.state()).incrementAndGet();
            roomCountsByScaleTier.get(updatedSnapshot.scaleTier()).incrementAndGet();
            maxLag = Math.max(maxLag, updatedSnapshot.deliveryLagP95Millis());
            maxFanout = Math.max(maxFanout, updatedSnapshot.outboundFanoutPerSecond());
            maxRoomWork = Math.max(maxRoomWork, updatedSnapshot.roomWorkPerSecond());
            maxActualDeliveryWork = Math.max(maxActualDeliveryWork, updatedSnapshot.actualDeliveryWorkPerSecond());
            maxConceptualRoomWork = Math.max(maxConceptualRoomWork, updatedSnapshot.conceptualRoomWorkPerSecond());
            maxScaleDecisionWork = Math.max(maxScaleDecisionWork, updatedSnapshot.scaleDecisionWorkPerSecond());
            maxRecommendedPartitions = Math.max(maxRecommendedPartitions, updatedSnapshot.recommendedPartitions());
            maxEffectivePartitions = Math.max(maxEffectivePartitions, updatedSnapshot.effectivePartitions());
            if (updatedSnapshot.partitionRecommendationLimited()) {
                limitedPartitionRecommendations++;
            }
        }

        inactiveRooms.forEach(rooms::remove);
        maxDeliveryLagP95Millis.set(maxLag);
        maxOutboundFanoutPerSecond.set(maxFanout);
        updateMax(maxRoomWorkPerSecond, maxRoomWork);
        updateMax(maxActualDeliveryWorkPerSecond, maxActualDeliveryWork);
        updateMax(maxConceptualRoomWorkPerSecond, maxConceptualRoomWork);
        updateMax(maxScaleDecisionWorkPerSecond, maxScaleDecisionWork);
        maxRecommendedPartitionCount.set(maxRecommendedPartitions);
        maxEffectivePartitionCount.set(maxEffectivePartitions);
        partitionRecommendationLimitedCount.set(limitedPartitionRecommendations);
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private RoomTrafficStats stats(Long roomId) {
        long nowMillis = clock.getAsLong();
        return rooms.computeIfAbsent(roomId,
                id -> new RoomTrafficStats(id, properties.windowSeconds(), properties.maxLatencySamples(), nowMillis));
    }

    private void updateMax(AtomicLong target, long candidate) {
        target.accumulateAndGet(candidate, Math::max);
    }

    private void transitionIfStable(RoomTrafficStats stats,
                                    RoomTrafficSnapshot snapshot,
                                    RoomHotState nextState,
                                    long nowMillis) {
        RoomHotState currentState = snapshot.state();
        if (nextState == currentState) {
            stats.candidateStable(nextState, nowMillis, 0);
            return;
        }

        boolean upgrade = nextState.ordinal() > currentState.ordinal();
        long requiredMillis = upgrade ? properties.upgradeStableMillis() : properties.downgradeStableMillis();
        if (!stats.candidateStable(nextState, nowMillis, requiredMillis)) {
            return;
        }

        if (!upgrade && stats.millisSinceStateChange(nowMillis) < properties.downgradeStableMillis()) {
            return;
        }

        stats.transitionTo(nextState, nowMillis);
        log.info("[ROOM HOT STATE] roomId={} {} -> {} sessions={} inboundPerSec={} fanoutPerSec={} lagP95={}ms",
                snapshot.roomId(),
                currentState,
                nextState,
                snapshot.connectedSessions(),
                snapshot.inboundMessagesPerSecond(),
                snapshot.outboundFanoutPerSecond(),
                snapshot.deliveryLagP95Millis());
    }

    private void resetGauges() {
        roomCountsByState.values().forEach(count -> count.set(0));
        roomCountsByScaleTier.values().forEach(count -> count.set(0));
    }

    private void transitionScaleTierIfStable(RoomTrafficStats stats,
                                             RoomTrafficSnapshot snapshot,
                                             RoomScaleTier nextTier,
                                             long nowMillis) {
        RoomScaleTier currentTier = snapshot.scaleTier();
        if (nextTier == currentTier) {
            stats.scaleCandidateStable(nextTier, nowMillis, 0);
            return;
        }

        boolean upgrade = nextTier.ordinal() > currentTier.ordinal();
        long requiredMillis = upgrade
                ? scaleProperties.upgradeStableMillis()
                : scaleProperties.downgradeStableMillis();
        if (!stats.scaleCandidateStable(nextTier, nowMillis, requiredMillis)) {
            return;
        }

        if (!upgrade && stats.millisSinceScaleTierChange(nowMillis) < scaleProperties.downgradeStableMillis()) {
            return;
        }

        stats.transitionScaleTierTo(nextTier, nowMillis);
        log.info("[ROOM SCALE TIER] roomId={} {} -> {} roomWorkPerSec={} actualDeliveryWorkPerSec={} conceptualRoomWorkPerSec={} scaleDecisionWorkPerSec={} activeSessions={} recommendedPartitions={} effectivePartitions={} limitedByMaxPartition={}",
                snapshot.roomId(),
                currentTier,
                nextTier,
                snapshot.roomWorkPerSecond(),
                snapshot.actualDeliveryWorkPerSecond(),
                snapshot.conceptualRoomWorkPerSecond(),
                snapshot.scaleDecisionWorkPerSecond(),
                snapshot.activeSessions(),
                snapshot.recommendedPartitions(),
                snapshot.effectivePartitions(),
                snapshot.partitionRecommendationLimited());
    }

    private void registerGauges(MeterRegistry meterRegistry) {
        if (meterRegistry == null) {
            return;
        }
        for (RoomHotState state : RoomHotState.values()) {
            Gauge.builder("openchat_room_hot_state_rooms", roomCountsByState.get(state), AtomicInteger::get)
                    .description("Number of active rooms by hot state")
                    .tag("state", state.name().toLowerCase())
                    .register(meterRegistry);
        }
        for (RoomScaleTier tier : RoomScaleTier.values()) {
            Gauge.builder("openchat_room_scale_tier_count", roomCountsByScaleTier.get(tier), AtomicInteger::get)
                    .description("Number of active rooms by room work scale tier")
                    .tag("tier", tier.name().toLowerCase())
                    .register(meterRegistry);
        }
        Gauge.builder("openchat_room_hot_state_delivery_lag_p95_max_ms",
                        maxDeliveryLagP95Millis, AtomicLong::get)
                .description("Maximum room delivery lag p95 in milliseconds")
                .register(meterRegistry);
        Gauge.builder("openchat_room_hot_state_outbound_fanout_max_per_second",
                        maxOutboundFanoutPerSecond, AtomicLong::get)
                .description("Maximum room outbound fanout rate per second")
                .register(meterRegistry);
        Gauge.builder("openchat_room_work_max_per_second",
                        maxRoomWorkPerSecond, AtomicLong::get)
                .description("Maximum room work rate per second")
                .register(meterRegistry);
        Gauge.builder("openchat_room_actual_delivery_work_max_per_second",
                        maxActualDeliveryWorkPerSecond, AtomicLong::get)
                .description("Maximum actual WebSocket delivery work rate per second observed since process start")
                .register(meterRegistry);
        Gauge.builder("openchat_room_conceptual_work_max_per_second",
                        maxConceptualRoomWorkPerSecond, AtomicLong::get)
                .description("Maximum conceptual room work rate per second observed since process start, input messages multiplied by active sessions")
                .register(meterRegistry);
        Gauge.builder("openchat_room_scale_decision_work_max_per_second",
                        maxScaleDecisionWorkPerSecond, AtomicLong::get)
                .description("Maximum conservative room work rate observed since process start for scale decision analysis")
                .register(meterRegistry);
        Gauge.builder("openchat_room_partition_recommended_count_max",
                        maxRecommendedPartitionCount, AtomicInteger::get)
                .description("Maximum recommended fan-out partition count")
                .register(meterRegistry);
        Gauge.builder("openchat_room_partition_effective_count_max",
                        maxEffectivePartitionCount, AtomicInteger::get)
                .description("Maximum effective fan-out partition count after configured cap")
                .register(meterRegistry);
        Gauge.builder("openchat_room_partition_recommendation_limited_count",
                        partitionRecommendationLimitedCount, AtomicInteger::get)
                .description("Number of active rooms whose recommended partition count exceeds configured effective limit")
                .register(meterRegistry);
        Gauge.builder("openchat_room_pod_budget_delivery_per_second",
                        podWorkBudgetDeliveryPerSecond, AtomicLong::get)
                .description("Configured Realtime pod delivery budget per second")
                .register(meterRegistry);
    }
}
