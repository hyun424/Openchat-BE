package io.hyun424.openchat.chat.room.hot;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoomTrafficMonitorTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final RoomTrafficMonitor monitor = new RoomTrafficMonitor(
            null,
            testProperties(),
            testScaleProperties(),
            now::get,
            false
    );

    @Test
    void inboundMessagePromotesRoomToWatched() {
        monitor.recordInboundMessage(1L);

        monitor.refresh();

        assertEquals(RoomHotState.WATCHED, monitor.state(1L));
        assertEquals(1, monitor.snapshot(1L).inboundMessagesPerSecond());
    }

    @Test
    void mainExposurePromotesRoomToWarmBeforeLagAppears() {
        monitor.markMainExposed(1L, true);

        monitor.refresh();

        assertEquals(RoomHotState.WARM, monitor.state(1L));
    }

    @Test
    void deliveryLagPromotesRoomToHotAndSuperHot() {
        monitor.recordDeliveryLag(1L, now.get() - 150);
        monitor.refresh();

        assertEquals(RoomHotState.HOT, monitor.state(1L));

        monitor.recordDeliveryLag(1L, now.get() - 350);
        monitor.refresh();

        assertEquals(RoomHotState.SUPER_HOT, monitor.state(1L));
    }

    @Test
    void outboundFanoutRateCanPromoteRoomToHot() {
        monitor.recordOutboundFanout(1L, 100_000);

        monitor.refresh();

        assertEquals(RoomHotState.SUPER_HOT, monitor.state(1L));
        assertEquals(10_000, monitor.snapshot(1L).outboundFanoutPerSecond());
    }

    @Test
    void roomWorkClassifiesScaleTierAtThresholds() {
        RoomTrafficMonitor scaleMonitor = scaleMonitor(0, 0);

        scaleMonitor.recordOutboundFanout(1L, 999);
        scaleMonitor.recordOutboundFanout(2L, 1_000);
        scaleMonitor.recordOutboundFanout(3L, 5_000);
        scaleMonitor.recordOutboundFanout(4L, 10_000);
        scaleMonitor.recordOutboundFanout(5L, 20_000);
        scaleMonitor.refresh();

        assertEquals(RoomScaleTier.SMALL, scaleMonitor.snapshot(1L).scaleTier());
        assertEquals(RoomScaleTier.MEDIUM, scaleMonitor.snapshot(2L).scaleTier());
        assertEquals(RoomScaleTier.LARGE, scaleMonitor.snapshot(3L).scaleTier());
        assertEquals(RoomScaleTier.HOT, scaleMonitor.snapshot(4L).scaleTier());
        assertEquals(RoomScaleTier.CRITICAL, scaleMonitor.snapshot(5L).scaleTier());
    }

    @Test
    void scaleTierUpgradeRequiresStableDuration() {
        RoomTrafficMonitor scaleMonitor = scaleMonitor(180_000, 600_000);

        scaleMonitor.recordOutboundFanout(1L, 20_000);
        scaleMonitor.refresh();

        assertEquals(RoomScaleTier.SMALL, scaleMonitor.snapshot(1L).scaleTier());

        now.addAndGet(179_000);
        scaleMonitor.recordOutboundFanout(1L, 20_000);
        scaleMonitor.refresh();

        assertEquals(RoomScaleTier.SMALL, scaleMonitor.snapshot(1L).scaleTier());

        now.addAndGet(1_000);
        scaleMonitor.recordOutboundFanout(1L, 20_000);
        scaleMonitor.refresh();

        assertEquals(RoomScaleTier.CRITICAL, scaleMonitor.snapshot(1L).scaleTier());
    }

    @Test
    void scaleTierDowngradeRequiresStableDuration() {
        RoomTrafficMonitor scaleMonitor = scaleMonitor(0, 600_000);

        scaleMonitor.recordOutboundFanout(1L, 20_000);
        scaleMonitor.refresh();
        assertEquals(RoomScaleTier.CRITICAL, scaleMonitor.snapshot(1L).scaleTier());

        now.addAndGet(1_000);
        scaleMonitor.refresh();
        assertEquals(RoomScaleTier.CRITICAL, scaleMonitor.snapshot(1L).scaleTier());

        now.addAndGet(600_000);
        scaleMonitor.refresh();
        assertEquals(RoomScaleTier.SMALL, scaleMonitor.snapshot(1L).scaleTier());
    }

    @Test
    void partitionRecommendationUsesWorkAndActiveSessionLimits() {
        RoomTrafficMonitor scaleMonitor = scaleMonitor(0, 0);

        scaleMonitor.recordOutboundFanout(1L, 189_000, 450);
        scaleMonitor.recordOutboundFanout(2L, 1_000, 1_001);
        scaleMonitor.recordOutboundFanout(3L, 300_000, 30_000);
        scaleMonitor.refresh();

        assertEquals(19, scaleMonitor.snapshot(1L).recommendedPartitions());
        assertEquals(16, scaleMonitor.snapshot(1L).effectivePartitions());
        assertEquals(3, scaleMonitor.snapshot(2L).recommendedPartitions());
        assertEquals(60, scaleMonitor.snapshot(3L).recommendedPartitions());
        assertEquals(16, scaleMonitor.snapshot(3L).effectivePartitions());
    }

    @Test
    void snapshotSeparatesActualConceptualAndDecisionWork() {
        RoomTrafficMonitor scaleMonitor = scaleMonitor(0, 0);

        scaleMonitor.recordOutboundFanout(1L, 20_000, 300);
        for (int i = 0; i < 100; i++) {
            scaleMonitor.recordInboundMessage(1L);
        }
        scaleMonitor.refresh();

        RoomTrafficSnapshot snapshot = scaleMonitor.snapshot(1L);
        assertEquals(20_000, snapshot.outboundFanoutPerSecond());
        assertEquals(20_000, snapshot.roomWorkPerSecond());
        assertEquals(20_000, snapshot.actualDeliveryWorkPerSecond());
        assertEquals(30_000, snapshot.conceptualRoomWorkPerSecond());
        assertEquals(30_000, snapshot.scaleDecisionWorkPerSecond());
        assertEquals(2, snapshot.recommendedPartitions());
    }

    @Test
    void limitedPartitionRecommendationIsExposedAsMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RoomTrafficMonitor scaleMonitor = scaleMonitor(registry, 0, 0);

        scaleMonitor.recordOutboundFanout(1L, 189_000, 450);
        scaleMonitor.recordOutboundFanout(2L, 1_000, 1_001);
        scaleMonitor.refresh();

        assertEquals(1.0,
                registry.get("openchat_room_partition_recommendation_limited_count").gauge().value(),
                0.0);
        assertEquals(189_000.0,
                registry.get("openchat_room_actual_delivery_work_max_per_second").gauge().value(),
                0.0);
        assertEquals(189_000.0,
                registry.get("openchat_room_scale_decision_work_max_per_second").gauge().value(),
                0.0);
    }


    @Test
    void workObserverMaxMetricsPreservePeakAfterRateWindowExpires() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RoomTrafficMonitor scaleMonitor = scaleMonitor(registry, 0, 0);

        scaleMonitor.recordOutboundFanout(1L, 20_000, 300);
        for (int i = 0; i < 100; i++) {
            scaleMonitor.recordInboundMessage(1L);
        }
        scaleMonitor.refresh();

        now.addAndGet(2_000);
        scaleMonitor.refresh();

        assertEquals(0, scaleMonitor.snapshot(1L).actualDeliveryWorkPerSecond());
        assertEquals(0, scaleMonitor.snapshot(1L).conceptualRoomWorkPerSecond());
        assertEquals(20_000.0,
                registry.get("openchat_room_work_max_per_second").gauge().value(),
                0.0);
        assertEquals(20_000.0,
                registry.get("openchat_room_actual_delivery_work_max_per_second").gauge().value(),
                0.0);
        assertEquals(30_000.0,
                registry.get("openchat_room_conceptual_work_max_per_second").gauge().value(),
                0.0);
        assertEquals(30_000.0,
                registry.get("openchat_room_scale_decision_work_max_per_second").gauge().value(),
                0.0);
    }

    @Test
    void exposesTopRoomsAndWorkloadSummaryForClusterObserver() {
        RoomTrafficMonitor scaleMonitor = scaleMonitor(0, 0);

        scaleMonitor.recordOutboundFanout(1L, 1_000, 10);
        scaleMonitor.recordOutboundFanout(2L, 20_000, 100);
        for (int i = 0; i < 50; i++) {
            scaleMonitor.recordInboundMessage(2L);
        }
        scaleMonitor.refresh();

        assertEquals(2L, scaleMonitor.topRoomsByScaleDecisionWork(1).get(0).roomId());
        RoomTrafficWorkloadSummary summary = scaleMonitor.workloadSummary();
        assertEquals(20_000, summary.maxActualDeliveryWorkPerSecond());
        assertEquals(5_000, summary.maxConceptualRoomWorkPerSecond());
        assertEquals(20_000, summary.maxScaleDecisionWorkPerSecond());
    }

    private RoomHotStateProperties testProperties() {
        return new RoomHotStateProperties(
                10,
                128,
                300_000,
                0,
                0,
                50,
                1_000,
                5,
                50,
                5_000,
                30,
                100,
                10_000,
                500,
                300,
                10_000
        );
    }

    private RoomScaleProperties testScaleProperties() {
        return new RoomScaleProperties(
                0,
                0,
                1_000,
                5_000,
                10_000,
                20_000,
                10_000,
                500,
                16
        );
    }

    private RoomTrafficMonitor scaleMonitor(long upgradeStableMillis, long downgradeStableMillis) {
        return scaleMonitor(null, upgradeStableMillis, downgradeStableMillis);
    }

    private RoomTrafficMonitor scaleMonitor(SimpleMeterRegistry registry,
                                            long upgradeStableMillis,
                                            long downgradeStableMillis) {
        return new RoomTrafficMonitor(
                registry,
                new RoomHotStateProperties(
                        1,
                        128,
                        2_000_000,
                        0,
                        0,
                        50,
                        1_000,
                        5,
                        50,
                        5_000,
                        30,
                        100,
                        10_000,
                        500,
                        300,
                        10_000
                ),
                new RoomScaleProperties(
                        upgradeStableMillis,
                        downgradeStableMillis,
                        1_000,
                        5_000,
                        10_000,
                        20_000,
                        10_000,
                        500,
                        16
                ),
                now::get,
                false
        );
    }
}
