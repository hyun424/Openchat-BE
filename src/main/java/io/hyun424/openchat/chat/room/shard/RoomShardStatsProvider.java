package io.hyun424.openchat.chat.room.shard;

import io.hyun424.openchat.chat.room.domain.RoomStatus;
import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
import io.hyun424.openchat.chat.room.hot.RoomTrafficSnapshot;
import io.hyun424.openchat.chat.room.repository.RoomRepository;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RoomShardStatsProvider {

    private final RoomShardProperties properties;
    private final RoomShardResolver roomShardResolver;
    private final RoomTrafficMonitor roomTrafficMonitor;
    private final RoomSessionRegistry roomSessionRegistry;
    private final RoomRepository roomRepository;
    private final RoomShardOverloadGuard overloadGuard;
    private final RoomShardMetrics metrics;

    public RoomShardStatsProvider(RoomShardProperties properties,
                                  RoomShardResolver roomShardResolver,
                                  RoomTrafficMonitor roomTrafficMonitor,
                                  RoomSessionRegistry roomSessionRegistry,
                                  RoomRepository roomRepository,
                                  RoomShardOverloadGuard overloadGuard,
                                  RoomShardMetrics metrics) {
        this.properties = properties;
        this.roomShardResolver = roomShardResolver;
        this.roomTrafficMonitor = roomTrafficMonitor;
        this.roomSessionRegistry = roomSessionRegistry;
        this.roomRepository = roomRepository;
        this.overloadGuard = overloadGuard;
        this.metrics = metrics;
        this.metrics.updateConfig(properties);
    }

    public List<RoomShardStatsSnapshot> snapshots() {
        long[] workByShard = new long[properties.shardCount()];
        int[] activeSessionsByShard = new int[properties.shardCount()];
        for (RoomTrafficSnapshot roomSnapshot : roomTrafficMonitor.snapshots()) {
            int shardId = roomShardResolver.resolveShardId(roomSnapshot.roomId());
            workByShard[shardId] += roomSnapshot.roomWorkPerSecond();
            activeSessionsByShard[shardId] += roomSnapshot.activeSessions();
        }

        int queueDepth = roomSessionRegistry.totalBroadcastQueueDepth();
        List<RoomShardStatsSnapshot> snapshots = new ArrayList<>(properties.shardCount());
        for (int shardId = 0; shardId < properties.shardCount(); shardId++) {
            long roomCount = roomRepository.countActiveRoomsByResolvedShardId(RoomStatus.ACTIVE, shardId);
            RoomShardStatsSnapshot raw = new RoomShardStatsSnapshot(
                    shardId,
                    workByShard[shardId],
                    activeSessionsByShard[shardId],
                    queueDepth,
                    roomCount,
                    RoomShardState.NORMAL
            );
            snapshots.add(raw.withState(overloadGuard.evaluate(raw)));
        }
        metrics.updateSnapshots(snapshots);
        return snapshots;
    }
}
