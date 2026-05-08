package io.hyun424.openchat.chat.room.partition.lifecycle;

import io.hyun424.openchat.chat.room.hot.RoomScaleTier;
import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionState;
import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionStatus;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.partition.repository.RoomPartitionStateRepository;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionStateService;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadClusterSummary;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadRecommendation;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadRecommendationType;
import io.hyun424.openchat.chat.room.workload.dto.RoomWorkloadCandidate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomPartitionLifecycleServiceTest {

    @Test
    void noScaleUpCandidateSkips() {
        TestContext context = context();

        boolean acted = context.service.tryScaleUp(summary(List.of(noAction()), List.of(candidate(1L, 12_000, 4))));

        assertFalse(acted);
        verify(context.stateService, never()).scaleUp(anyLong(), eq(4), eq("auto-partition-lifecycle"));
    }

    @Test
    void staleNodeSkipsScaleUp() {
        TestContext context = context();

        boolean acted = context.service.tryScaleUp(summary(List.of(
                recommendation(RealtimeWorkloadRecommendationType.INVESTIGATE_STALE_NODE, 1L),
                recommendation(RealtimeWorkloadRecommendationType.SCALE_UP_CANDIDATE, 1L)
        ), List.of(candidate(1L, 12_000, 4))));

        assertFalse(acted);
    }

    @Test
    void candidateMustSatisfyStableWindow() {
        TestContext context = context();
        when(context.stateRepository.findById(1L)).thenReturn(Optional.of(state(1L, 1, RoomPartitionStatus.ACTIVE)));

        boolean first = context.service.tryScaleUp(scaleUpSummary());
        context.clock.advanceMillis(1_000);
        boolean second = context.service.tryScaleUp(scaleUpSummary());

        assertFalse(first);
        assertTrue(second);
        verify(context.stateService).scaleUp(1L, 4, "auto-partition-lifecycle");
    }

    @Test
    void missingStateSkips() {
        TestContext context = context();
        context.clock.advanceMillis(1_000);
        context.service.tryScaleUp(scaleUpSummary());
        context.clock.advanceMillis(1_000);
        when(context.stateRepository.findById(1L)).thenReturn(Optional.empty());

        boolean acted = context.service.tryScaleUp(scaleUpSummary());

        assertFalse(acted);
    }

    @Test
    void nonActiveStateSkips() {
        TestContext context = context();
        context.clock.advanceMillis(1_000);
        context.service.tryScaleUp(scaleUpSummary());
        context.clock.advanceMillis(1_000);
        when(context.stateRepository.findById(1L)).thenReturn(Optional.of(state(1L, 2, RoomPartitionStatus.DRAINING)));

        boolean acted = context.service.tryScaleUp(scaleUpSummary());

        assertFalse(acted);
    }

    @Test
    void targetAtOrBelowCurrentSkips() {
        TestContext context = context();
        context.clock.advanceMillis(1_000);
        context.service.tryScaleUp(summary(List.of(recommendation(RealtimeWorkloadRecommendationType.SCALE_UP_CANDIDATE, 1L)),
                List.of(candidate(1L, 12_000, 2))));
        context.clock.advanceMillis(1_000);
        when(context.stateRepository.findById(1L)).thenReturn(Optional.of(state(1L, 2, RoomPartitionStatus.ACTIVE)));

        boolean acted = context.service.tryScaleUp(summary(List.of(recommendation(RealtimeWorkloadRecommendationType.SCALE_UP_CANDIDATE, 1L)),
                List.of(candidate(1L, 12_000, 2))));

        assertFalse(acted);
    }

    @Test
    void cooldownSkipsAction() {
        TestContext context = context();
        when(context.cooldowns.acquire("scale_up", 1L, 0)).thenReturn(false);
        when(context.stateRepository.findById(1L)).thenReturn(Optional.of(state(1L, 1, RoomPartitionStatus.ACTIVE)));

        context.service.tryScaleUp(scaleUpSummary());
        context.clock.advanceMillis(1_000);
        boolean acted = context.service.tryScaleUp(scaleUpSummary());

        assertFalse(acted);
    }

    @Test
    void scaleUpRedistributesThenCompletesScaleUp() {
        TestContext context = context();
        when(context.stateRepository.findById(1L)).thenReturn(Optional.of(state(1L, 1, RoomPartitionStatus.ACTIVE)));

        context.service.tryScaleUp(scaleUpSummary());
        context.clock.advanceMillis(1_000);
        boolean acted = context.service.tryScaleUp(scaleUpSummary());

        assertTrue(acted);
        verify(context.stateService).scaleUp(1L, 4, "auto-partition-lifecycle");
        verify(context.redistributionService).redistribute(1L, 1, 4, 2);
        verify(context.stateService).completeScaleUp(1L, "auto-partition-lifecycle");
    }

    private TestContext context() {
        RoomPartitionStateRepository stateRepository = mock(RoomPartitionStateRepository.class);
        RoomPartitionStateService stateService = mock(RoomPartitionStateService.class);
        RoomPartitionRedistributionService redistributionService = mock(RoomPartitionRedistributionService.class);
        RoomPartitionScaleDownService scaleDownService = mock(RoomPartitionScaleDownService.class);
        RoomPartitionLifecycleCooldowns cooldowns = mock(RoomPartitionLifecycleCooldowns.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-05-08T00:00:00Z"));
        when(cooldowns.acquire("scale_up", 1L, 0)).thenReturn(true);
        when(stateService.scaleUp(1L, 4, "auto-partition-lifecycle"))
                .thenReturn(state(1L, 4, RoomPartitionStatus.SCALING_UP));
        when(stateService.completeScaleUp(1L, "auto-partition-lifecycle"))
                .thenReturn(state(1L, 4, RoomPartitionStatus.ACTIVE));
        return new TestContext(
                new RoomPartitionLifecycleService(
                        stateRepository,
                        stateService,
                        redistributionService,
                        scaleDownService,
                        cooldowns,
                        properties(),
                        new RoomPartitionProperties(true, 4, Set.of(0, 1, 2, 3), RoomScaleTier.CRITICAL, 4),
                        new RoomPartitionMetrics(new SimpleMeterRegistry()),
                        clock
                ),
                stateRepository,
                stateService,
                redistributionService,
                cooldowns,
                clock
        );
    }

    private RealtimeWorkloadClusterSummary scaleUpSummary() {
        return summary(List.of(recommendation(RealtimeWorkloadRecommendationType.SCALE_UP_CANDIDATE, 1L)),
                List.of(candidate(1L, 12_000, 4)));
    }

    private RealtimeWorkloadClusterSummary summary(List<RealtimeWorkloadRecommendation> recommendations,
                                                   List<RoomWorkloadCandidate> candidates) {
        return new RealtimeWorkloadClusterSummary(
                1_000,
                1,
                0,
                List.of(),
                100,
                100,
                0,
                0,
                12_000,
                12_000,
                12_000,
                0,
                0,
                0,
                candidates,
                recommendations
        );
    }

    private RealtimeWorkloadRecommendation recommendation(RealtimeWorkloadRecommendationType type, Long roomId) {
        return RealtimeWorkloadRecommendation.of(type, "test", roomId, "node-1", 12_000, 10_000);
    }

    private RealtimeWorkloadRecommendation noAction() {
        return RealtimeWorkloadRecommendation.of(RealtimeWorkloadRecommendationType.NO_ACTION, "test", null, null, 0, 10_000);
    }

    private RoomWorkloadCandidate candidate(Long roomId, long work, int effectivePartitions) {
        return new RoomWorkloadCandidate(roomId, work, work, work, RoomScaleTier.CRITICAL,
                effectivePartitions, effectivePartitions, 100, false, "node-1");
    }

    private RoomPartitionState state(Long roomId, int partitions, RoomPartitionStatus status) {
        return new RoomPartitionState(roomId, partitions, status == RoomPartitionStatus.SCALING_UP ? 2 : 1,
                status, "", Instant.parse("2026-05-08T00:00:00Z"), "auto-partition-lifecycle");
    }

    private RoomPartitionLifecycleProperties properties() {
        return new RoomPartitionLifecycleProperties(
                true,
                5_000,
                15_000,
                1,
                "auto-partition-lifecycle",
                new RoomPartitionLifecycleProperties.ScaleUp(true, 1_000, 2, 0),
                new RoomPartitionLifecycleProperties.ScaleDown(true, 1_000, 1, 0, 0, true),
                new RoomPartitionLifecycleProperties.Redistribution(true, 50, 500),
                new RoomPartitionLifecycleProperties.Drain(1, 50, 500)
        );
    }

    private record TestContext(RoomPartitionLifecycleService service,
                               RoomPartitionStateRepository stateRepository,
                               RoomPartitionStateService stateService,
                               RoomPartitionRedistributionService redistributionService,
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
