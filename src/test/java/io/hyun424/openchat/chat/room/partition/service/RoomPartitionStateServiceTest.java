package io.hyun424.openchat.chat.room.partition.service;

import io.hyun424.openchat.chat.room.hot.RoomHotState;
import io.hyun424.openchat.chat.room.hot.RoomScaleTier;
import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
import io.hyun424.openchat.chat.room.hot.RoomTrafficSnapshot;
import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionState;
import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionStatus;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.partition.policy.RoomPartitionPolicy;
import io.hyun424.openchat.chat.room.partition.repository.RoomPartitionStateRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoomPartitionStateServiceTest {

    @Test
    void missingState_initializesFromTrafficAdvisor() {
        TestContext context = context(snapshot(RoomScaleTier.CRITICAL, 4));

        RoomPartitionState state = context.service.getOrInitialize(1L);

        assertEquals(4, state.getPartitionCount());
        assertEquals(1, state.getVersion());
        assertEquals(RoomPartitionStatus.ACTIVE, state.getStatus());
        assertEquals("", state.getDrainingPartitions());
    }

    @Test
    void missingState_readsStateAfterConcurrentInsertNoop() {
        RoomPartitionStateRepository repository = mock(RoomPartitionStateRepository.class);
        RoomTrafficMonitor monitor = mock(RoomTrafficMonitor.class);
        RoomPartitionProperties properties = properties();
        RoomPartitionState concurrentState = RoomPartitionState.initialize(
                1L,
                4,
                Instant.parse("2026-05-07T00:00:00Z"),
                "system"
        );

        when(repository.findById(1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concurrentState));
        when(repository.insertIfAbsent(anyLong(), anyInt(), any(), anyString()))
                .thenReturn(0);
        when(monitor.snapshot(1L)).thenReturn(snapshot(RoomScaleTier.CRITICAL, 4));

        RoomPartitionStateService service = new RoomPartitionStateService(
                repository,
                properties,
                new RoomPartitionPolicy(properties, monitor),
                new RoomPartitionMetrics(new SimpleMeterRegistry()),
                Clock.fixed(Instant.parse("2026-05-07T00:00:00Z"), ZoneOffset.UTC)
        );

        RoomPartitionState state = service.getOrInitialize(1L);

        assertEquals(4, state.getPartitionCount());
        assertEquals(RoomPartitionStatus.ACTIVE, state.getStatus());
    }

    @Test
    void scaleUp_increasesCountAndVersion() {
        TestContext context = context(snapshot(RoomScaleTier.CRITICAL, 2));
        context.service.getOrInitialize(1L);

        RoomPartitionState scaled = context.service.scaleUp(1L, 4, "test");

        assertEquals(4, scaled.getPartitionCount());
        assertEquals(2, scaled.getVersion());
        assertEquals(RoomPartitionStatus.SCALING_UP, scaled.getStatus());
    }

    @Test
    void completeScaleUp_returnsActiveWithoutVersionIncrement() {
        TestContext context = context(snapshot(RoomScaleTier.CRITICAL, 2));
        context.service.getOrInitialize(1L);
        RoomPartitionState scaled = context.service.scaleUp(1L, 4, "test");

        RoomPartitionState completed = context.service.completeScaleUp(1L, "test");

        assertEquals(4, completed.getPartitionCount());
        assertEquals(scaled.getVersion(), completed.getVersion());
        assertEquals(RoomPartitionStatus.ACTIVE, completed.getStatus());
        assertEquals("", completed.getDrainingPartitions());
    }

    @Test
    void scaleUp_missingState_initializesBeforeLocking() {
        TestContext context = context(snapshot(RoomScaleTier.CRITICAL, 2));

        RoomPartitionState scaled = context.service.scaleUp(1L, 4, "test");

        assertEquals(4, scaled.getPartitionCount());
        assertEquals(2, scaled.getVersion());
        assertEquals(RoomPartitionStatus.SCALING_UP, scaled.getStatus());
    }

    @Test
    void routePartition_avoidsDrainingPartition() {
        TestContext context = context(snapshot(RoomScaleTier.CRITICAL, 4));
        context.service.getOrInitialize(1L);
        String userId = userForPartition(context.service, 1L, 1);

        context.service.startDrain(1L, Set.of(1), "test");

        int routed = context.service.routePartition(1L, userId);
        assertNotEquals(1, routed);
        assertEquals(4, context.service.partitionCountForRoom(1L));
    }

    @Test
    void publishPartitions_includesDrainingUntilDrainComplete() {
        TestContext context = context(snapshot(RoomScaleTier.CRITICAL, 4));
        context.service.getOrInitialize(1L);

        context.service.startDrain(1L, Set.of(3), "test");

        assertEquals(java.util.List.of(0, 1, 2, 3), context.service.publishPartitions(1L));
    }

    @Test
    void completeDrain_lowersCountAndClearsDrainingPartitions() {
        TestContext context = context(snapshot(RoomScaleTier.CRITICAL, 4));
        context.service.getOrInitialize(1L);
        context.service.startDrain(1L, Set.of(2, 3), "test");

        RoomPartitionState completed = context.service.completeDrain(1L, 2, "test");

        assertEquals(2, completed.getPartitionCount());
        assertEquals(3, completed.getVersion());
        assertEquals(RoomPartitionStatus.ACTIVE, completed.getStatus());
        assertEquals("", completed.getDrainingPartitions());
        assertEquals(java.util.List.of(0, 1), context.service.publishPartitions(1L));
    }

    private String userForPartition(RoomPartitionStateService service, Long roomId, int expectedPartition) {
        for (int i = 0; i < 1000; i++) {
            String userId = "user-" + i;
            if (service.routePartition(roomId, userId) == expectedPartition) {
                return userId;
            }
        }
        throw new IllegalStateException("No test user matched partition " + expectedPartition);
    }

    private TestContext context(RoomTrafficSnapshot snapshot) {
        Map<Long, RoomPartitionState> states = new HashMap<>();
        RoomPartitionStateRepository repository = mock(RoomPartitionStateRepository.class);
        when(repository.findById(any())).thenAnswer(invocation -> Optional.ofNullable(states.get(invocation.getArgument(0))));
        when(repository.findByIdForUpdate(any())).thenAnswer(invocation -> Optional.ofNullable(states.get(invocation.getArgument(0))));
        when(repository.insertIfAbsent(anyLong(), anyInt(), any(), anyString())).thenAnswer(invocation -> {
            Long roomId = invocation.getArgument(0);
            if (states.containsKey(roomId)) {
                return 0;
            }
            RoomPartitionState state = RoomPartitionState.initialize(
                    roomId,
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3)
            );
            states.put(roomId, state);
            return 1;
        });
        when(repository.save(any())).thenAnswer(invocation -> {
            RoomPartitionState state = invocation.getArgument(0);
            states.put(state.getRoomId(), state);
            return state;
        });

        RoomTrafficMonitor monitor = mock(RoomTrafficMonitor.class);
        when(monitor.snapshot(1L)).thenReturn(snapshot);

        RoomPartitionProperties properties = properties();
        RoomPartitionStateService service = new RoomPartitionStateService(
                repository,
                properties,
                new RoomPartitionPolicy(properties, monitor),
                new RoomPartitionMetrics(new SimpleMeterRegistry()),
                Clock.fixed(Instant.parse("2026-05-07T00:00:00Z"), ZoneOffset.UTC)
        );
        return new TestContext(service);
    }

    private RoomPartitionProperties properties() {
        return new RoomPartitionProperties(
                true,
                4,
                Set.of(0, 1, 2, 3),
                RoomScaleTier.CRITICAL,
                16
        );
    }

    private RoomTrafficSnapshot snapshot(RoomScaleTier tier, int effectivePartitions) {
        return new RoomTrafficSnapshot(
                1L,
                0,
                0,
                0,
                0,
                0,
                0,
                RoomHotState.NORMAL,
                0,
                0,
                0,
                0,
                0,
                tier,
                effectivePartitions,
                effectivePartitions
        );
    }

    private record TestContext(RoomPartitionStateService service) {
    }
}
