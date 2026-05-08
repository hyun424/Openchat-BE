package io.hyun424.openchat.chat.room.workload.service;

import io.hyun424.openchat.chat.room.workload.config.RealtimeWorkloadProperties;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeNodeWorkloadSnapshot;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadClusterSummary;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadRecommendation;
import io.hyun424.openchat.chat.room.workload.dto.RoomPartitionDrainProgress;
import io.hyun424.openchat.chat.room.workload.dto.RoomWorkloadCandidate;
import io.hyun424.openchat.chat.room.workload.infra.RealtimeWorkloadSnapshotRepository;
import io.hyun424.openchat.chat.room.workload.metrics.RealtimeWorkloadMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
@ConditionalOnProperty(name = "spring.data.redis.host")
public class RealtimeWorkloadClusterSummaryService {

    private final RealtimeWorkloadSnapshotRepository snapshotRepository;
    private final RealtimeWorkloadRecommendationService recommendationService;
    private final RealtimeWorkloadProperties properties;
    private final RealtimeWorkloadMetrics metrics;

    public RealtimeWorkloadClusterSummaryService(RealtimeWorkloadSnapshotRepository snapshotRepository,
                                                 RealtimeWorkloadRecommendationService recommendationService,
                                                 RealtimeWorkloadProperties properties,
                                                 RealtimeWorkloadMetrics metrics) {
        this.snapshotRepository = snapshotRepository;
        this.recommendationService = recommendationService;
        this.properties = properties;
        this.metrics = metrics;
    }

    public RealtimeWorkloadClusterSummary summary() {
        long now = System.currentTimeMillis();
        List<RealtimeNodeWorkloadSnapshot> snapshots = snapshotRepository.readAll();
        List<RealtimeNodeWorkloadSnapshot> activeSnapshots = snapshots.stream()
                .filter(snapshot -> !snapshot.stale(now))
                .toList();
        List<String> staleNodeIds = snapshots.stream()
                .filter(snapshot -> snapshot.stale(now))
                .map(RealtimeNodeWorkloadSnapshot::nodeId)
                .sorted()
                .toList();

        int totalSessions = activeSnapshots.stream().mapToInt(RealtimeNodeWorkloadSnapshot::totalSessions).sum();
        int activeSessions = activeSnapshots.stream().mapToInt(RealtimeNodeWorkloadSnapshot::activeSessions).sum();
        int passiveSessions = activeSnapshots.stream().mapToInt(RealtimeNodeWorkloadSnapshot::passiveSessions).sum();
        int queueDepth = activeSnapshots.stream().mapToInt(RealtimeNodeWorkloadSnapshot::broadcastQueueDepth).sum();
        long maxActual = activeSnapshots.stream()
                .mapToLong(RealtimeNodeWorkloadSnapshot::maxActualDeliveryWorkPerSecond)
                .max()
                .orElse(0);
        long maxConceptual = activeSnapshots.stream()
                .mapToLong(RealtimeNodeWorkloadSnapshot::maxConceptualRoomWorkPerSecond)
                .max()
                .orElse(0);
        long maxDecision = activeSnapshots.stream()
                .mapToLong(RealtimeNodeWorkloadSnapshot::maxScaleDecisionWorkPerSecond)
                .max()
                .orElse(0);
        int limitedCount = activeSnapshots.stream()
                .mapToInt(RealtimeNodeWorkloadSnapshot::partitionRecommendationLimitedCount)
                .sum();
        long sendFailedDelta = activeSnapshots.stream()
                .mapToLong(RealtimeNodeWorkloadSnapshot::sendFailedDelta)
                .sum();
        long reconnectSentDelta = activeSnapshots.stream()
                .mapToLong(RealtimeNodeWorkloadSnapshot::reconnectSentDelta)
                .sum();
        List<RoomWorkloadCandidate> topRooms = activeSnapshots.stream()
                .flatMap(snapshot -> snapshot.topRooms().stream())
                .sorted(Comparator.comparingLong(RoomWorkloadCandidate::scaleDecisionWorkPerSecond).reversed())
                .limit(properties.topRoomLimit())
                .toList();
        List<RealtimeWorkloadRecommendation> recommendations = recommendationService.recommend(
                activeSnapshots,
                staleNodeIds,
                topRooms,
                limitedCount
        );
        List<RoomPartitionDrainProgress> drainProgress = aggregateDrainProgress(activeSnapshots);

        metrics.updateClusterSummary(
                activeSnapshots.size(),
                staleNodeIds.size(),
                totalSessions,
                activeSessions,
                maxDecision
        );

        return new RealtimeWorkloadClusterSummary(
                now,
                activeSnapshots.size(),
                staleNodeIds.size(),
                staleNodeIds,
                totalSessions,
                activeSessions,
                passiveSessions,
                queueDepth,
                maxActual,
                maxConceptual,
                maxDecision,
                limitedCount,
                sendFailedDelta,
                reconnectSentDelta,
                topRooms,
                recommendations,
                drainProgress
        );
    }

    public List<RealtimeWorkloadRecommendation> recommendations() {
        return summary().recommendations();
    }

    private List<RoomPartitionDrainProgress> aggregateDrainProgress(List<RealtimeNodeWorkloadSnapshot> activeSnapshots) {
        Map<DrainKey, Integer> openSessionsByPartition = new TreeMap<>();
        activeSnapshots.stream()
                .flatMap(snapshot -> snapshot.drainProgress().stream())
                .forEach(progress -> openSessionsByPartition.merge(
                        new DrainKey(progress.roomId(), progress.partitionId()),
                        progress.openSessions(),
                        Integer::sum
                ));
        return openSessionsByPartition.entrySet().stream()
                .map(entry -> new RoomPartitionDrainProgress(
                        entry.getKey().roomId(),
                        entry.getKey().partitionId(),
                        entry.getValue(),
                        "cluster"
                ))
                .toList();
    }

    private record DrainKey(Long roomId, int partitionId) implements Comparable<DrainKey> {
        @Override
        public int compareTo(DrainKey other) {
            int roomCompare = Long.compare(roomId == null ? 0L : roomId, other.roomId == null ? 0L : other.roomId);
            if (roomCompare != 0) {
                return roomCompare;
            }
            return Integer.compare(partitionId, other.partitionId);
        }
    }
}
