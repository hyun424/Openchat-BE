package io.hyun424.openchat.chat.room.shard;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Component
public class RoomShardProperties {

    private final boolean enabled;
    private final int shardCount;
    private final Set<Integer> ownedShards;
    private final boolean legacySubscribeEnabled;
    private final long podWorkBudgetDeliveryPerSecond;
    private final double overloadThresholdRatio;
    private final int maxActiveSessionsPerPartition;
    private final int queueDepthThreshold;
    private final long overloadStableMillis;
    private final long recoveryStableMillis;

    @Autowired
    public RoomShardProperties(
            @Value("${app.room-shard.enabled:false}") boolean enabled,
            @Value("${app.room-shard.shard-count:1}") int shardCount,
            @Value("${app.room-shard.owned-shards:0}") String ownedShards,
            @Value("${app.room-shard.legacy-subscribe-enabled:true}") boolean legacySubscribeEnabled,
            @Value("${app.room-shard.pod-work-budget-delivery-per-sec:10000}") long podWorkBudgetDeliveryPerSecond,
            @Value("${app.room-shard.overload-threshold-ratio:0.8}") double overloadThresholdRatio,
            @Value("${app.room-shard.max-active-sessions-per-partition:500}") int maxActiveSessionsPerPartition,
            @Value("${app.room-shard.queue-depth-threshold:5000}") int queueDepthThreshold,
            @Value("${app.room-shard.overload-stable-ms:30000}") long overloadStableMillis,
            @Value("${app.room-shard.recovery-stable-ms:180000}") long recoveryStableMillis
    ) {
        this.enabled = enabled;
        this.shardCount = Math.max(1, shardCount);
        this.ownedShards = parseOwnedShards(ownedShards, this.shardCount);
        this.legacySubscribeEnabled = legacySubscribeEnabled;
        this.podWorkBudgetDeliveryPerSecond = Math.max(1, podWorkBudgetDeliveryPerSecond);
        this.overloadThresholdRatio = Math.max(0.01, overloadThresholdRatio);
        this.maxActiveSessionsPerPartition = Math.max(1, maxActiveSessionsPerPartition);
        this.queueDepthThreshold = Math.max(1, queueDepthThreshold);
        this.overloadStableMillis = Math.max(0, overloadStableMillis);
        this.recoveryStableMillis = Math.max(0, recoveryStableMillis);
    }

    RoomShardProperties(boolean enabled,
                        int shardCount,
                        Set<Integer> ownedShards,
                        boolean legacySubscribeEnabled,
                        long podWorkBudgetDeliveryPerSecond,
                        double overloadThresholdRatio,
                        int maxActiveSessionsPerPartition,
                        int queueDepthThreshold,
                        long overloadStableMillis,
                        long recoveryStableMillis) {
        this.enabled = enabled;
        this.shardCount = Math.max(1, shardCount);
        this.ownedShards = ownedShards == null || ownedShards.isEmpty()
                ? Set.of(0)
                : ownedShards.stream()
                .map(this::normalizeShardId)
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(TreeSet::new),
                        Collections::unmodifiableSet));
        this.legacySubscribeEnabled = legacySubscribeEnabled;
        this.podWorkBudgetDeliveryPerSecond = Math.max(1, podWorkBudgetDeliveryPerSecond);
        this.overloadThresholdRatio = Math.max(0.01, overloadThresholdRatio);
        this.maxActiveSessionsPerPartition = Math.max(1, maxActiveSessionsPerPartition);
        this.queueDepthThreshold = Math.max(1, queueDepthThreshold);
        this.overloadStableMillis = Math.max(0, overloadStableMillis);
        this.recoveryStableMillis = Math.max(0, recoveryStableMillis);
    }

    public boolean enabled() {
        return enabled;
    }

    public int shardCount() {
        return shardCount;
    }

    public Set<Integer> ownedShards() {
        return ownedShards;
    }

    public boolean legacySubscribeEnabled() {
        return legacySubscribeEnabled;
    }

    public long podWorkBudgetDeliveryPerSecond() {
        return podWorkBudgetDeliveryPerSecond;
    }

    public double overloadThresholdRatio() {
        return overloadThresholdRatio;
    }

    public int maxActiveSessionsPerPartition() {
        return maxActiveSessionsPerPartition;
    }

    public int queueDepthThreshold() {
        return queueDepthThreshold;
    }

    public long overloadStableMillis() {
        return overloadStableMillis;
    }

    public long recoveryStableMillis() {
        return recoveryStableMillis;
    }

    public int normalizeShardId(Integer shardId) {
        return Math.floorMod(shardId == null ? 0 : shardId, shardCount);
    }

    public boolean ownsShard(int shardId) {
        return ownedShards.contains(normalizeShardId(shardId));
    }

    private Set<Integer> parseOwnedShards(String rawOwnedShards, int resolvedShardCount) {
        if (rawOwnedShards == null || rawOwnedShards.isBlank()) {
            return Set.of(0);
        }
        Set<Integer> parsed = Arrays.stream(rawOwnedShards.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> {
                    try {
                        return Math.floorMod(Integer.parseInt(value), resolvedShardCount);
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                })
                .collect(Collectors.toCollection(TreeSet::new));
        return parsed.isEmpty() ? Set.of(0) : Collections.unmodifiableSet(parsed);
    }
}
