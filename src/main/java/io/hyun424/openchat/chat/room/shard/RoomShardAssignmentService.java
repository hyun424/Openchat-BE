package io.hyun424.openchat.chat.room.shard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
public class RoomShardAssignmentService {

    private final RoomShardProperties properties;
    private final RoomShardStatsProvider statsProvider;
    private final RoomShardMetrics metrics;

    public RoomShardAssignmentService(RoomShardProperties properties,
                                      RoomShardStatsProvider statsProvider,
                                      RoomShardMetrics metrics) {
        this.properties = properties;
        this.statsProvider = statsProvider;
        this.metrics = metrics;
    }

    public int assignShardForNewRoom() {
        if (!properties.enabled()) {
            metrics.recordAssignment("disabled");
            return 0;
        }

        List<RoomShardStatsSnapshot> snapshots = statsProvider.snapshots();
        return snapshots.stream()
                .filter(snapshot -> snapshot.state() != RoomShardState.OVERLOADED)
                .min(Comparator.comparingLong(RoomShardStatsSnapshot::score))
                .map(snapshot -> {
                    metrics.recordAssignment("selected");
                    return snapshot.shardId();
                })
                .orElseGet(() -> fallbackToLeastLoadedShard(snapshots));
    }

    private int fallbackToLeastLoadedShard(List<RoomShardStatsSnapshot> snapshots) {
        metrics.recordAssignment("fallback_all_overloaded");
        RoomShardStatsSnapshot fallback = snapshots.stream()
                .min(Comparator.comparingLong(RoomShardStatsSnapshot::score))
                .orElse(new RoomShardStatsSnapshot(0, 0, 0, 0, 0, RoomShardState.NORMAL));
        log.warn("[ROOM SHARD ASSIGN FALLBACK] all shards overloaded, selected shardId={} score={}",
                fallback.shardId(), fallback.score());
        return fallback.shardId();
    }
}
