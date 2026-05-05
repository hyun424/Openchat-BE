package io.hyun424.openchat.chat.room.partition;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RoomPartitionMetrics {

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Counter> publishCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> subscribeCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> routeCounters = new ConcurrentHashMap<>();
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

    private String safeTag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
