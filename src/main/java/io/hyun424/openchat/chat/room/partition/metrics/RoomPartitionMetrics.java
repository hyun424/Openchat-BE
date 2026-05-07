package io.hyun424.openchat.chat.room.partition.metrics;

import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

@Component
public class RoomPartitionMetrics {

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Counter> publishCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> subscribeCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> routeCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> stateCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> scaleCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> controlPublishCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> controlReceivedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> controlIgnoredCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> reconnectRequestedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> reconnectTargetedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> reconnectControlSentCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> lifecycleCounters = new ConcurrentHashMap<>();
    private final LongAdder reconnectControlSentSuccessCount = new LongAdder();
    private final Counter routeDrainingAvoidedCounter;
    private final DistributionSummary drainingCountSummary;
    private final DistributionSummary activeSessionSummary;
    private final DistributionSummary fanoutDeliverySummary;
    private final AtomicInteger ownedPartitionCount = new AtomicInteger(0);

    public RoomPartitionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("openchat_room_partition_owned_count", ownedPartitionCount, AtomicInteger::get)
                .description("Room fan-out partitions owned by this realtime node")
                .register(meterRegistry);
        this.activeSessionSummary = DistributionSummary
                .builder("openchat_room_partition_active_sessions")
                .register(meterRegistry);
        this.fanoutDeliverySummary = DistributionSummary
                .builder("openchat_room_partition_fanout_deliveries")
                .register(meterRegistry);
        this.drainingCountSummary = DistributionSummary
                .builder("openchat_room_partition_draining_count")
                .register(meterRegistry);
        this.routeDrainingAvoidedCounter = Counter
                .builder("openchat_room_partition_route_draining_avoided_total")
                .register(meterRegistry);
    }

    public void updateConfig(RoomPartitionProperties properties) {
        ownedPartitionCount.set(properties.ownedPartitions().size());
    }

    public void recordPublish(String mode) {
        publishCounters.computeIfAbsent(safeTag(mode), key -> Counter
                .builder("openchat_room_partition_publish_total")
                .tag("mode", key)
                .register(meterRegistry))
                .increment();
    }

    public void recordSubscribe(String mode) {
        subscribeCounters.computeIfAbsent(safeTag(mode), key -> Counter
                .builder("openchat_room_partition_subscribe_total")
                .tag("mode", key)
                .register(meterRegistry))
                .increment();
    }

    public void recordRoute(String result) {
        routeCounters.computeIfAbsent(safeTag(result), key -> Counter
                .builder("openchat_room_partition_route_total")
                .tag("result", key)
                .register(meterRegistry))
                .increment();
    }

    public void recordState(RoomPartitionStatus status) {
        String state = status == null ? "unknown" : status.name().toLowerCase();
        stateCounters.computeIfAbsent(state, key -> Counter
                .builder("openchat_room_partition_state_total")
                .tag("state", key)
                .register(meterRegistry))
                .increment();
    }

    public void recordScaleEvent(String direction, String result) {
        String safeDirection = safeTag(direction);
        String safeResult = safeTag(result);
        String key = safeDirection + ":" + safeResult;
        scaleCounters.computeIfAbsent(key, ignored -> Counter
                .builder("openchat_room_partition_scale_event_total")
                .tag("direction", safeDirection)
                .tag("result", safeResult)
                .register(meterRegistry))
                .increment();
    }

    public void recordRouteDrainingAvoided() {
        routeDrainingAvoidedCounter.increment();
    }

    public void recordDrainingCount(int count) {
        drainingCountSummary.record(Math.max(0, count));
    }

    public void recordLegacyWebSocket() {
        Counter.builder("openchat_room_partition_legacy_ws_total")
                .register(meterRegistry)
                .increment();
    }

    public void recordActiveSessions(int count) {
        activeSessionSummary.record(Math.max(0, count));
    }

    public void recordFanoutDeliveries(long deliveries) {
        fanoutDeliverySummary.record(Math.max(0, deliveries));
    }

    public void recordControlPublish(String type, String result) {
        String safeType = safeTag(type);
        String safeResult = safeTag(result);
        String key = safeType + ":" + safeResult;
        controlPublishCounters.computeIfAbsent(key, ignored -> Counter
                        .builder("openchat_room_partition_control_publish_total")
                        .tag("type", safeType)
                        .tag("result", safeResult)
                        .register(meterRegistry))
                .increment();
    }

    public void recordControlReceived(String type, String result) {
        String safeType = safeTag(type);
        String safeResult = safeTag(result);
        String key = safeType + ":" + safeResult;
        controlReceivedCounters.computeIfAbsent(key, ignored -> Counter
                        .builder("openchat_room_partition_control_received_total")
                        .tag("type", safeType)
                        .tag("result", safeResult)
                        .register(meterRegistry))
                .increment();
    }

    public void recordControlIgnored(String reason) {
        String safeReason = safeTag(reason);
        controlIgnoredCounters.computeIfAbsent(safeReason, ignored -> Counter
                        .builder("openchat_room_partition_control_ignored_total")
                        .tag("reason", safeReason)
                        .register(meterRegistry))
                .increment();
    }

    public void recordReconnectRequested(String reason) {
        String safeReason = safeTag(reason);
        reconnectRequestedCounters.computeIfAbsent(safeReason, ignored -> Counter
                        .builder("openchat_room_reconnect_requested_total")
                        .tag("reason", safeReason)
                        .register(meterRegistry))
                .increment();
    }

    public void recordReconnectTargeted(String reason, int sessionCount) {
        if (sessionCount <= 0) {
            return;
        }
        String safeReason = safeTag(reason);
        reconnectTargetedCounters.computeIfAbsent(safeReason, ignored -> Counter
                        .builder("openchat_room_reconnect_sessions_targeted_total")
                        .tag("reason", safeReason)
                        .register(meterRegistry))
                .increment(sessionCount);
    }

    public void recordReconnectControlSent(String reason, String result) {
        String safeReason = safeTag(reason);
        String safeResult = safeTag(result);
        String key = safeReason + ":" + safeResult;
        reconnectControlSentCounters.computeIfAbsent(key, ignored -> Counter
                        .builder("openchat_room_reconnect_control_sent_total")
                        .tag("reason", safeReason)
                        .tag("result", safeResult)
                        .register(meterRegistry))
                .increment();
        if ("success".equals(safeResult)) {
            reconnectControlSentSuccessCount.increment();
        }
    }

    public long reconnectControlSentSuccessCount() {
        return reconnectControlSentSuccessCount.sum();
    }

    public void recordLifecycleEvent(String operation, String result) {
        String safeOperation = safeTag(operation);
        String safeResult = safeTag(result);
        String key = safeOperation + ":" + safeResult;
        lifecycleCounters.computeIfAbsent(key, ignored -> Counter
                        .builder("openchat_room_partition_lifecycle_event_total")
                        .tag("operation", safeOperation)
                        .tag("result", safeResult)
                        .register(meterRegistry))
                .increment();
    }

    private String safeTag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
