package io.hyun424.openchat.chat.room.partition.lifecycle;

import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionState;
import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionStatus;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.partition.policy.RoomPartitionPolicy;
import io.hyun424.openchat.chat.room.partition.repository.RoomPartitionStateRepository;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionReconnectOperations;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionStateService;
import io.hyun424.openchat.chat.room.workload.config.RealtimeWorkloadProperties;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadClusterSummary;
import io.hyun424.openchat.chat.room.workload.dto.RoomPartitionDrainProgress;
import io.hyun424.openchat.chat.room.workload.dto.RoomWorkloadCandidate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomPartitionScaleDownServiceTest {

    @Test
    void autoManagedOnlySkipsManualStates() {
        TestContext context = context();
        when(context.repository.findByStatus(RoomPartitionStatus.DRAINING)).thenReturn(List.of());
        when(context.repository.findAutoManagedActiveScaleDownCandidates("auto-partition-lifecycle")).thenReturn(List.of());

        boolean acted = context.service.run(summary(List.of(), List.of()));

        assertFalse(acted);
        verify(context.repository, never()).findActiveScaleDownCandidates();
    }

    @Test
    void stableLowWorkloadStartsDrainWithHighPartitionIds() {
        TestContext context = context();
        RoomPartitionState state = state(1L, 4, RoomPartitionStatus.ACTIVE, "", "auto-partition-lifecycle",
                Instant.parse("2026-05-08T00:00:00Z"));
        when(context.repository.findByStatus(RoomPartitionStatus.DRAINING)).thenReturn(List.of());
        when(context.repository.findAutoManagedActiveScaleDownCandidates("auto-partition-lifecycle")).thenReturn(List.of(state));
        when(context.cooldowns.acquire("scale_down", 1L, 0)).thenReturn(true);
        when(context.cooldowns.acquire("drain_reconnect", 1L, 500)).thenReturn(true);
        when(context.stateService.startDrain(eq(1L), eq(2), eq(Set.of(2, 3)), eq("auto-partition-lifecycle")))
                .thenReturn(state(1L, 4, RoomPartitionStatus.DRAINING, "2,3", "auto-partition-lifecycle",
                        Instant.parse("2026-05-08T00:00:02Z")));
        when(context.reconnectOperations.reconnectDraining(1L, "scale_down", 500, 50))
                .thenReturn(new RoomPartitionReconnectOperations.RoomPartitionReconnectResult(1L, "scale_down", true, 2));

        boolean first = context.service.run(summary(List.of(), List.of()));
        context.clock.advanceMillis(1_000);
        boolean second = context.service.run(summary(List.of(), List.of()));

        assertFalse(first);
        assertTrue(second);
        verify(context.stateService).startDrain(1L, 2, Set.of(2, 3), "auto-partition-lifecycle");
    }

    @Test
    void partitionAgeMustBeSatisfiedBeforeDrain() {
        TestContext context = contextWithMinAge(10_000);
        RoomPartitionState state = state(1L, 4, RoomPartitionStatus.ACTIVE, "", "auto-partition-lifecycle",
                Instant.parse("2026-05-08T00:00:00Z"));
        when(context.repository.findByStatus(RoomPartitionStatus.DRAINING)).thenReturn(List.of());
        when(context.repository.findAutoManagedActiveScaleDownCandidates("auto-partition-lifecycle")).thenReturn(List.of(state));

        context.service.run(summary(List.of(), List.of()));
        context.clock.advanceMillis(1_000);
        boolean acted = context.service.run(summary(List.of(), List.of()));

        assertFalse(acted);
        verify(context.stateService, never()).startDrain(any(), anyInt(), any(), any());
    }

    @Test
    void sessionsRemainingRequestsReconnectButDoesNotCompleteDrain() {
        TestContext context = context();
        RoomPartitionState draining = state(1L, 2, RoomPartitionStatus.DRAINING, "1", "auto-partition-lifecycle",
                Instant.parse("2026-05-08T00:00:00Z"));
        when(context.repository.findByStatus(RoomPartitionStatus.DRAINING)).thenReturn(List.of(draining));
        when(context.policy.drainingPartitions(draining)).thenReturn(Set.of(1));
        when(context.cooldowns.acquire("drain_reconnect", 1L, 500)).thenReturn(true);
        when(context.reconnectOperations.reconnectDraining(1L, "scale_down", 500, 50))
                .thenReturn(new RoomPartitionReconnectOperations.RoomPartitionReconnectResult(1L, "scale_down", true, 1));

        boolean acted = context.service.run(summary(List.of(), List.of(new RoomPartitionDrainProgress(1L, 1, 3, "cluster"))));

        assertTrue(acted);
        verify(context.reconnectOperations).reconnectDraining(1L, "scale_down", 500, 50);
        verify(context.stateService, never()).completeDrain(1L, "auto-partition-lifecycle");
    }

    @Test
    void emptyDrainingSessionsMustBeObservedConsecutivelyBeforeComplete() {
        TestContext context = contextWithCompleteEmptyObservations(2);
        RoomPartitionState draining = state(1L, 2, RoomPartitionStatus.DRAINING, "1", "auto-partition-lifecycle",
                Instant.parse("2026-05-08T00:00:00Z"));
        when(context.repository.findByStatus(RoomPartitionStatus.DRAINING)).thenReturn(List.of(draining));
        when(context.policy.drainingPartitions(draining)).thenReturn(Set.of(1));
        when(context.cooldowns.acquire("drain_reconnect", 1L, 500)).thenReturn(true);
        when(context.reconnectOperations.reconnectDraining(1L, "scale_down", 500, 50))
                .thenReturn(new RoomPartitionReconnectOperations.RoomPartitionReconnectResult(1L, "scale_down", true, 1));

        boolean first = context.service.run(summary(List.of(), List.of(new RoomPartitionDrainProgress(1L, 1, 0, "cluster"))));
        boolean second = context.service.run(summary(List.of(), List.of(new RoomPartitionDrainProgress(1L, 1, 0, "cluster"))));

        assertTrue(first);
        assertTrue(second);
        verify(context.stateService).completeDrain(1L, "auto-partition-lifecycle");
    }

    private TestContext context() {
        return contextWithProperties(0, 1);
    }

    private TestContext contextWithMinAge(long minAgeMillis) {
        return contextWithProperties(minAgeMillis, 1);
    }

    private TestContext contextWithCompleteEmptyObservations(int observations) {
        return contextWithProperties(0, observations);
    }

    private TestContext contextWithProperties(long minAgeMillis, int emptyObservations) {
        RoomPartitionStateRepository repository = mock(RoomPartitionStateRepository.class);
        RoomPartitionStateService stateService = mock(RoomPartitionStateService.class);
        RoomPartitionReconnectOperations reconnectOperations = mock(RoomPartitionReconnectOperations.class);
        RoomPartitionPolicy policy = mock(RoomPartitionPolicy.class);
        RoomPartitionLifecycleCooldowns cooldowns = mock(RoomPartitionLifecycleCooldowns.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-05-08T00:00:02Z"));
        return new TestContext(
                new RoomPartitionScaleDownService(
                        repository,
                        stateService,
                        reconnectOperations,
                        policy,
                        cooldowns,
                        properties(minAgeMillis, emptyObservations),
                        new RealtimeWorkloadProperties(true, true, 5_000, 30_000, 10, 0.7, 10_000),
                        new RoomPartitionMetrics(new SimpleMeterRegistry()),
                        clock
                ),
                repository,
                stateService,
                reconnectOperations,
                policy,
                cooldowns,
                clock
        );
    }

    private RealtimeWorkloadClusterSummary summary(List<RoomWorkloadCandidate> topRooms,
                                                   List<RoomPartitionDrainProgress> drainProgress) {
        return new RealtimeWorkloadClusterSummary(
                1_000,
                1,
                0,
                List.of(),
                10,
                10,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                topRooms,
                List.of(),
                drainProgress
        );
    }

    private RoomPartitionState state(Long roomId,
                                     int partitions,
                                     RoomPartitionStatus status,
                                     String drainingPartitions,
                                     String updatedBy,
                                     Instant updatedAt) {
        return new RoomPartitionState(roomId, partitions, 1, status, drainingPartitions, updatedAt, updatedBy);
    }

    private RoomPartitionLifecycleProperties properties(long minAgeMillis, int emptyObservations) {
        return new RoomPartitionLifecycleProperties(
                true,
                5_000,
                15_000,
                1,
                "auto-partition-lifecycle",
                new RoomPartitionLifecycleProperties.ScaleUp(true, 1_000, 1, 0),
                new RoomPartitionLifecycleProperties.ScaleDown(true, 1_000, 2, 0, minAgeMillis, true),
                new RoomPartitionLifecycleProperties.Redistribution(true, 50, 500),
                new RoomPartitionLifecycleProperties.Drain(emptyObservations, 50, 500)
        );
    }

    private record TestContext(RoomPartitionScaleDownService service,
                               RoomPartitionStateRepository repository,
                               RoomPartitionStateService stateService,
                               RoomPartitionReconnectOperations reconnectOperations,
                               RoomPartitionPolicy policy,
                               RoomPartitionLifecycleCooldowns cooldowns,
                               MutableClock clock) {
    }

    private static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
