package io.hyun424.openchat.chat.room.hot;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
    private final RoomHotStateClassifier classifier;
    private final LongSupplier clock;
    private final ScheduledExecutorService scheduler;
    private final Map<RoomHotState, AtomicInteger> roomCountsByState = new EnumMap<>(RoomHotState.class);
    private final AtomicLong maxDeliveryLagP95Millis = new AtomicLong(0);
    private final AtomicLong maxOutboundFanoutPerSecond = new AtomicLong(0);

    public RoomTrafficMonitor() {
        this(null, RoomHotStateProperties.defaults(), System::currentTimeMillis, false);
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
                              @Value("${app.room.hot-state.super-hot-outbound-fanout-per-sec:50000}") long superHotOutboundFanoutPerSecond) {
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
                System::currentTimeMillis,
                true
        );
    }

    RoomTrafficMonitor(MeterRegistry meterRegistry,
                       RoomHotStateProperties properties,
                       LongSupplier clock,
                       boolean startScheduler) {
        this.properties = properties;
        this.classifier = new RoomHotStateClassifier(properties);
        this.clock = clock;
        for (RoomHotState state : RoomHotState.values()) {
            roomCountsByState.put(state, new AtomicInteger(0));
        }
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
        stats(roomId).recordOutboundFanout(clock.getAsLong(), recipientCount);
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
            return new RoomTrafficSnapshot(roomId, 0, 0, 0, 0, 0, 0, RoomHotState.NORMAL);
        }
        return stats.snapshot(clock.getAsLong());
    }

    public List<RoomTrafficSnapshot> snapshots() {
        long nowMillis = clock.getAsLong();
        return rooms.values().stream()
                .map(stats -> stats.snapshot(nowMillis))
                .toList();
    }

    public void refresh() {
        long nowMillis = clock.getAsLong();
        resetGauges();

        List<Long> inactiveRooms = new ArrayList<>();
        long maxLag = 0;
        long maxFanout = 0;

        for (RoomTrafficStats stats : rooms.values()) {
            if (stats.isInactive(nowMillis, properties.inactiveTtlMillis())) {
                inactiveRooms.add(stats.snapshot(nowMillis).roomId());
                continue;
            }

            RoomTrafficSnapshot snapshot = stats.snapshot(nowMillis);
            RoomHotState nextState = classifier.classify(snapshot, stats.isMainExposed());
            transitionIfStable(stats, snapshot, nextState, nowMillis);

            RoomTrafficSnapshot updatedSnapshot = stats.snapshot(nowMillis);
            roomCountsByState.get(updatedSnapshot.state()).incrementAndGet();
            maxLag = Math.max(maxLag, updatedSnapshot.deliveryLagP95Millis());
            maxFanout = Math.max(maxFanout, updatedSnapshot.outboundFanoutPerSecond());
        }

        inactiveRooms.forEach(rooms::remove);
        maxDeliveryLagP95Millis.set(maxLag);
        maxOutboundFanoutPerSecond.set(maxFanout);
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
        Gauge.builder("openchat_room_hot_state_delivery_lag_p95_max_ms",
                        maxDeliveryLagP95Millis, AtomicLong::get)
                .description("Maximum room delivery lag p95 in milliseconds")
                .register(meterRegistry);
        Gauge.builder("openchat_room_hot_state_outbound_fanout_max_per_second",
                        maxOutboundFanoutPerSecond, AtomicLong::get)
                .description("Maximum room outbound fanout rate per second")
                .register(meterRegistry);
    }
}
