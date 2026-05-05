package io.hyun424.openchat.chat.room.shard;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

@Component
public class RoomShardOverloadGuard {

    private final RoomShardProperties properties;
    private final LongSupplier clock;
    private final ConcurrentHashMap<Integer, ShardStateTracker> trackers = new ConcurrentHashMap<>();

    @Autowired
    public RoomShardOverloadGuard(RoomShardProperties properties) {
        this(properties, System::currentTimeMillis);
    }

    RoomShardOverloadGuard(RoomShardProperties properties, LongSupplier clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public RoomShardState evaluate(RoomShardStatsSnapshot snapshot) {
        long now = clock.getAsLong();
        int shardId = properties.normalizeShardId(snapshot.shardId());
        ShardStateTracker tracker = trackers.computeIfAbsent(shardId, ignored -> new ShardStateTracker(now));
        boolean overloadedSignal = isOverloadedSignal(snapshot);
        return tracker.evaluate(overloadedSignal, now, properties);
    }

    private boolean isOverloadedSignal(RoomShardStatsSnapshot snapshot) {
        long roomWorkLimit = Math.max(1, Math.round(
                properties.podWorkBudgetDeliveryPerSecond() * properties.overloadThresholdRatio()));
        long activeSessionLimit = Math.max(1L, properties.maxActiveSessionsPerPartition() * Math.max(1L, snapshot.roomCount()));
        return snapshot.roomWorkPerSecond() >= roomWorkLimit
                || snapshot.activeSessions() >= activeSessionLimit
                || snapshot.queueDepth() >= properties.queueDepthThreshold();
    }

    private static final class ShardStateTracker {
        private RoomShardState state = RoomShardState.NORMAL;
        private boolean lastSignal;
        private long lastSignalChangedAt;

        private ShardStateTracker(long now) {
            this.lastSignalChangedAt = now;
        }

        private RoomShardState evaluate(boolean overloadedSignal,
                                        long now,
                                        RoomShardProperties properties) {
            if (lastSignal != overloadedSignal) {
                lastSignal = overloadedSignal;
                lastSignalChangedAt = now;
            }
            if (state == RoomShardState.NORMAL && overloadedSignal
                    && now - lastSignalChangedAt >= properties.overloadStableMillis()) {
                state = RoomShardState.OVERLOADED;
            } else if (state == RoomShardState.OVERLOADED && !overloadedSignal
                    && now - lastSignalChangedAt >= properties.recoveryStableMillis()) {
                state = RoomShardState.NORMAL;
            }
            return state;
        }
    }
}
