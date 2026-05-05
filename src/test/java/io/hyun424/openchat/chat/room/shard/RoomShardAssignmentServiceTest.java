package io.hyun424.openchat.chat.room.shard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomShardAssignmentServiceTest {

    @Test
    void assignShardForNewRoom_disabledUsesZero() {
        RoomShardMetrics metrics = mock(RoomShardMetrics.class);
        RoomShardAssignmentService service = new RoomShardAssignmentService(
                properties(false),
                mock(RoomShardStatsProvider.class),
                metrics
        );

        assertEquals(0, service.assignShardForNewRoom());
        verify(metrics).recordAssignment("disabled");
    }

    @Test
    void assignShardForNewRoom_selectsLowestScoreNonOverloadedShard() {
        RoomShardStatsProvider provider = mock(RoomShardStatsProvider.class);
        RoomShardMetrics metrics = mock(RoomShardMetrics.class);
        when(provider.snapshots()).thenReturn(List.of(
                snapshot(0, 1_000, 10, 0, 5, RoomShardState.NORMAL),
                snapshot(1, 100, 1, 0, 1, RoomShardState.NORMAL),
                snapshot(2, 1, 1, 0, 1, RoomShardState.OVERLOADED)
        ));
        RoomShardAssignmentService service = new RoomShardAssignmentService(properties(true), provider, metrics);

        assertEquals(1, service.assignShardForNewRoom());
        verify(metrics).recordAssignment("selected");
    }

    @Test
    void assignShardForNewRoom_fallbackWhenAllShardsOverloaded() {
        RoomShardStatsProvider provider = mock(RoomShardStatsProvider.class);
        RoomShardMetrics metrics = mock(RoomShardMetrics.class);
        when(provider.snapshots()).thenReturn(List.of(
                snapshot(0, 5_000, 1, 0, 1, RoomShardState.OVERLOADED),
                snapshot(1, 100, 1, 0, 1, RoomShardState.OVERLOADED)
        ));
        RoomShardAssignmentService service = new RoomShardAssignmentService(properties(true), provider, metrics);

        assertEquals(1, service.assignShardForNewRoom());
        verify(metrics).recordAssignment("fallback_all_overloaded");
    }

    private RoomShardProperties properties(boolean enabled) {
        return new RoomShardProperties(enabled, 3, Set.of(0, 1, 2), true, 10_000, 0.8, 500, 5_000, 30_000, 180_000);
    }

    private RoomShardStatsSnapshot snapshot(int shardId,
                                            long roomWork,
                                            int activeSessions,
                                            int queueDepth,
                                            long roomCount,
                                            RoomShardState state) {
        return new RoomShardStatsSnapshot(shardId, roomWork, activeSessions, queueDepth, roomCount, state);
    }
}
