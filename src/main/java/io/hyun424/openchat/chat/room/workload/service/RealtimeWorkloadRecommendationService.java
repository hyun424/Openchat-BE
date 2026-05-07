package io.hyun424.openchat.chat.room.workload.service;

import io.hyun424.openchat.chat.room.workload.config.RealtimeWorkloadProperties;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeNodeWorkloadSnapshot;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadRecommendation;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadRecommendationType;
import io.hyun424.openchat.chat.room.workload.dto.RoomWorkloadCandidate;
import io.hyun424.openchat.chat.room.workload.metrics.RealtimeWorkloadMetrics;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class RealtimeWorkloadRecommendationService {

    private final RealtimeWorkloadProperties properties;
    private final RealtimeWorkloadMetrics metrics;

    public RealtimeWorkloadRecommendationService(RealtimeWorkloadProperties properties,
                                                 RealtimeWorkloadMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    public List<RealtimeWorkloadRecommendation> recommend(List<RealtimeNodeWorkloadSnapshot> activeSnapshots,
                                                           List<String> staleNodeIds,
                                                           List<RoomWorkloadCandidate> topRooms,
                                                           int partitionLimitedCount) {
        List<RealtimeWorkloadRecommendation> recommendations = new ArrayList<>();
        if (!staleNodeIds.isEmpty()) {
            recommendations.add(RealtimeWorkloadRecommendation.of(
                    RealtimeWorkloadRecommendationType.INVESTIGATE_STALE_NODE,
                    "stale realtime workload snapshot exists",
                    null,
                    staleNodeIds.get(0),
                    staleNodeIds.size(),
                    0
            ));
        }
        if (activeSnapshots.stream().anyMatch(snapshot -> snapshot.sendFailedDelta() > 0)) {
            RealtimeNodeWorkloadSnapshot source = activeSnapshots.stream()
                    .filter(snapshot -> snapshot.sendFailedDelta() > 0)
                    .findFirst()
                    .orElseThrow();
            recommendations.add(RealtimeWorkloadRecommendation.of(
                    RealtimeWorkloadRecommendationType.INVESTIGATE_SEND_FAILURE,
                    "send failure delta reported by realtime node",
                    null,
                    source.nodeId(),
                    source.sendFailedDelta(),
                    0
            ));
        }
        if (partitionLimitedCount > 0) {
            recommendations.add(RealtimeWorkloadRecommendation.of(
                    RealtimeWorkloadRecommendationType.CAP_LIMITED,
                    "recommended partition count exceeds configured cap",
                    null,
                    null,
                    partitionLimitedCount,
                    0
            ));
        }

        RoomWorkloadCandidate busiest = topRooms.stream()
                .max(Comparator.comparingLong(RoomWorkloadCandidate::scaleDecisionWorkPerSecond))
                .orElse(null);
        if (busiest != null) {
            long budget = properties.podWorkBudgetDeliveryPerSecond();
            long watchThreshold = Math.max(1, Math.round(budget * properties.watchRatio()));
            if (busiest.scaleDecisionWorkPerSecond() >= budget) {
                recommendations.add(RealtimeWorkloadRecommendation.of(
                        RealtimeWorkloadRecommendationType.SCALE_UP_CANDIDATE,
                        "room scale decision work reached pod budget",
                        busiest.roomId(),
                        busiest.sourceNodeId(),
                        busiest.scaleDecisionWorkPerSecond(),
                        budget
                ));
            } else if (busiest.scaleDecisionWorkPerSecond() >= watchThreshold) {
                recommendations.add(RealtimeWorkloadRecommendation.of(
                        RealtimeWorkloadRecommendationType.WATCH,
                        "room scale decision work reached watch threshold",
                        busiest.roomId(),
                        busiest.sourceNodeId(),
                        busiest.scaleDecisionWorkPerSecond(),
                        watchThreshold
                ));
            }
        }

        if (recommendations.isEmpty()) {
            recommendations.add(RealtimeWorkloadRecommendation.of(
                    RealtimeWorkloadRecommendationType.NO_ACTION,
                    "cluster workload is below recommendation thresholds",
                    null,
                    null,
                    0,
                    properties.podWorkBudgetDeliveryPerSecond()
            ));
        }
        recommendations.forEach(recommendation -> metrics.recordRecommendation(recommendation.type()));
        return recommendations;
    }
}
