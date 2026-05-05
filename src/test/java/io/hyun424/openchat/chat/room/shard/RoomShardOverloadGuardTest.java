package io.hyun424.openchat.chat.room.shard;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoomShardOverloadGuardTest {

    @Test
    void overloadRequiresStableDuration() {
        AtomicLong now = new AtomicLong(1_000L);
        RoomShardOverloadGuard guard = new RoomShardOverloadGuard(properties(), now::get);

        RoomShardStatsSnapshot overloaded = snapshot(8_000, 1, 0, 1);
        assertEquals(RoomShardState.NORMAL, guard.evaluate(overloaded));

        now.addAndGet(29_000);
        assertEquals(RoomShardState.NORMAL, guard.evaluate(overloaded));

        now.addAndGet(1_000);
        assertEquals(RoomShardState.OVERLOADED, guard.evaluate(overloaded));
    }

    @Test
    void recoveryRequiresStableDuration() {
        AtomicLong now = new AtomicLong(1_000L);
        RoomShardOverloadGuard guard = new RoomShardOverloadGuard(properties(), now::get);
        RoomShardStatsSnapshot overloaded = snapshot(8_000, 1, 0, 1);
        RoomShardStatsSnapshot normal = snapshot(1, 1, 0, 1);

        guard.evaluate(overloaded);
        now.addAndGet(30_000);
        assertEquals(RoomShardState.OVERLOADED, guard.evaluate(overloaded));

        assertEquals(RoomShardState.OVERLOADED, guard.evaluate(normal));

        now.addAndGet(179_000);
        assertEquals(RoomShardState.OVERLOADED, guard.evaluate(normal));

        now.addAndGet(1_000);
        assertEquals(RoomShardState.NORMAL, guard.evaluate(normal));
    }

    private RoomShardProperties properties() {
        return new RoomShardProperties(true, 2, Set.of(0, 1), true, 10_000, 0.8, 500, 5_000, 30_000, 180_000);
    }

    private RoomShardStatsSnapshot snapshot(long roomWork, int activeSessions, int queueDepth, long roomCount) {
        return new RoomShardStatsSnapshot(0, roomWork, activeSessions, queueDepth, roomCount, RoomShardState.NORMAL);
    }
}
