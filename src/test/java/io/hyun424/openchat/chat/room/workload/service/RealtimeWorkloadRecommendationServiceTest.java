package io.hyun424.openchat.chat.room.workload.service;

import io.hyun424.openchat.chat.room.hot.RoomScaleTier;
import io.hyun424.openchat.chat.room.workload.config.RealtimeWorkloadProperties;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeNodeWorkloadSnapshot;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadRecommendationType;
import io.hyun424.openchat.chat.room.workload.dto.RoomWorkloadCandidate;
import io.hyun424.openchat.chat.room.workload.metrics.RealtimeWorkloadMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealtimeWorkloadRecommendationServiceTest {

    private final RealtimeWorkloadRecommendationService service = new RealtimeWorkloadRecommendationService(
            new RealtimeWorkloadProperties(true, true, 5_000, 30_000, 10, 0.7, 10_000),
            new RealtimeWorkloadMetrics(new SimpleMeterRegistry())
    );

    @Test
    void staleNodeCreatesInvestigateRecommendation() {
        var recommendations = service.recommend(List.of(), List.of("node-stale"), List.of(), 0);

        assertEquals(RealtimeWorkloadRecommendationType.INVESTIGATE_STALE_NODE, recommendations.get(0).type());
    }

    @Test
    void capLimitedCreatesCapRecommendation() {
        var recommendations = service.recommend(List.of(), List.of(), List.of(), 2);

        assertEquals(RealtimeWorkloadRecommendationType.CAP_LIMITED, recommendations.get(0).type());
    }

    @Test
    void decisionWorkAtBudgetCreatesScaleUpCandidate() {
        var recommendations = service.recommend(List.of(), List.of(), List.of(candidate(10_000)), 0);

        assertTrue(recommendations.stream().anyMatch(r -> r.type() == RealtimeWorkloadRecommendationType.SCALE_UP_CANDIDATE));
    }

    @Test
    void decisionWorkAtWatchThresholdCreatesWatch() {
        var recommendations = service.recommend(List.of(), List.of(), List.of(candidate(7_000)), 0);

        assertEquals(RealtimeWorkloadRecommendationType.WATCH, recommendations.get(0).type());
    }

    @Test
    void sendFailureCreatesInvestigateRecommendation() {
        var recommendations = service.recommend(List.of(snapshotWithSendFailure()), List.of(), List.of(), 0);

        assertEquals(RealtimeWorkloadRecommendationType.INVESTIGATE_SEND_FAILURE, recommendations.get(0).type());
    }

    @Test
    void reconnectDeltaAloneDoesNotCreateRecommendation() {
        var recommendations = service.recommend(List.of(snapshotWithReconnectOnly()), List.of(), List.of(), 0);

        assertEquals(RealtimeWorkloadRecommendationType.NO_ACTION, recommendations.get(0).type());
    }

    @Test
    void belowThresholdCreatesNoAction() {
        var recommendations = service.recommend(List.of(), List.of(), List.of(candidate(100)), 0);

        assertEquals(RealtimeWorkloadRecommendationType.NO_ACTION, recommendations.get(0).type());
    }

    private RoomWorkloadCandidate candidate(long decisionWork) {
        return new RoomWorkloadCandidate(1L, decisionWork, decisionWork, decisionWork,
                RoomScaleTier.SMALL, 1, 1, 10, false, "node-1");
    }

    private RealtimeNodeWorkloadSnapshot snapshotWithSendFailure() {
        return snapshotWithSignals(3, 0);
    }

    private RealtimeNodeWorkloadSnapshot snapshotWithReconnectOnly() {
        return snapshotWithSignals(0, 5);
    }

    private RealtimeNodeWorkloadSnapshot snapshotWithSignals(long sendFailedDelta, long reconnectSentDelta) {
        return new RealtimeNodeWorkloadSnapshot("node-1", "realtime", 1, 30_000, Set.of(0), Set.of(0),
                0, 0, 0, 0, 0, 0, 0, 0, sendFailedDelta, reconnectSentDelta, List.of());
    }
}
