package io.hyun424.openchat.chat.room.shard;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RoomShardMetrics {

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Counter> assignmentCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> publishCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> subscribeCounters = new ConcurrentHashMap<>();
    private final AtomicInteger shardCount = new AtomicInteger(0);
    private final AtomicInteger ownedShardCount = new AtomicInteger(0);
    private final AtomicInteger overloadedShardCount = new AtomicInteger(0);
    private final AtomicLong maxShardWorkPerSecond = new AtomicLong(0);

    public RoomShardMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("openchat_room_shard_count", shardCount, AtomicInteger::get)
                .description("Configured room shard count")
                .register(meterRegistry);
        Gauge.builder("openchat_room_shard_owned_count", ownedShardCount, AtomicInteger::get)
                .description("Room shards owned by this realtime node")
                .register(meterRegistry);
        Gauge.builder("openchat_room_shard_overloaded_count", overloadedShardCount, AtomicInteger::get)
                .description("Number of overloaded room shards")
                .register(meterRegistry);
        Gauge.builder("openchat_room_shard_work_max_per_second", maxShardWorkPerSecond, AtomicLong::get)
                .description("Maximum room work rate across owned room shards")
                .register(meterRegistry);
    }

    public void updateConfig(RoomShardProperties properties) {
        shardCount.set(properties.shardCount());
        ownedShardCount.set(properties.ownedShards().size());
    }

    public void updateSnapshots(List<RoomShardStatsSnapshot> snapshots) {
        int overloaded = 0;
        long maxWork = 0;
        for (RoomShardStatsSnapshot snapshot : snapshots) {
            if (snapshot.state() == RoomShardState.OVERLOADED) {
                overloaded++;
            }
            maxWork = Math.max(maxWork, snapshot.roomWorkPerSecond());
        }
        overloadedShardCount.set(overloaded);
        maxShardWorkPerSecond.set(maxWork);
    }

    public void recordAssignment(String result) {
        assignmentCounters.computeIfAbsent(safeTag(result), key -> Counter
                .builder("openchat_room_shard_assignment_total")
                .tag("result", key)
                .register(meterRegistry))
                .increment();
    }

    public void recordPublish(String mode) {
        publishCounters.computeIfAbsent(safeTag(mode), key -> Counter
                .builder("openchat_room_shard_publish_total")
                .tag("mode", key)
                .register(meterRegistry))
                .increment();
    }

    public void recordSubscribe(String mode) {
        subscribeCounters.computeIfAbsent(safeTag(mode), key -> Counter
                .builder("openchat_room_shard_subscribe_total")
                .tag("mode", key)
                .register(meterRegistry))
                .increment();
    }

    private String safeTag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
