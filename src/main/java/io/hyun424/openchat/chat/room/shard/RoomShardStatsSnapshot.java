package io.hyun424.openchat.chat.room.shard;

public record RoomShardStatsSnapshot(
        int shardId,
        long roomWorkPerSecond,
        int activeSessions,
        int queueDepth,
        long roomCount,
        RoomShardState state
) {
    public long score() {
        return roomWorkPerSecond
                + activeSessions * 10L
                + queueDepth * 50L
                + roomCount * 100L;
    }

    RoomShardStatsSnapshot withState(RoomShardState nextState) {
        return new RoomShardStatsSnapshot(
                shardId,
                roomWorkPerSecond,
                activeSessions,
                queueDepth,
                roomCount,
                nextState
        );
    }
}
