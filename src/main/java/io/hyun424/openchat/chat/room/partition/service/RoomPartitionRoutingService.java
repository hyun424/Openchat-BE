package io.hyun424.openchat.chat.room.partition.service;

import io.hyun424.openchat.chat.room.partition.assignment.RoomPartitionAssignment;
import io.hyun424.openchat.chat.room.partition.assignment.RoomPartitionAssignmentProperties;
import io.hyun424.openchat.chat.room.partition.assignment.RoomPartitionAssignmentService;
import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionRoute;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class RoomPartitionRoutingService {

    private final RoomPartitionProperties properties;
    private final RoomPartitionStateReader stateReader;
    private final RoomPartitionMetrics metrics;
    private final RoomPartitionAssignmentProperties assignmentProperties;
    private final RoomPartitionAssignmentService assignmentService;

    public RoomPartitionRoutingService(RoomPartitionProperties properties,
                                       RoomPartitionStateReader stateReader,
                                       RoomPartitionMetrics metrics,
                                       RoomPartitionAssignmentProperties assignmentProperties,
                                       RoomPartitionAssignmentService assignmentService) {
        this.properties = properties;
        this.stateReader = stateReader;
        this.metrics = metrics;
        this.assignmentProperties = assignmentProperties;
        this.assignmentService = assignmentService;
        this.metrics.updateConfig(properties);
    }

    public RoomPartitionRoute route(Long roomId, String userId) {
        int partitionCount = partitionCountForRoom(roomId);
        if (partitionCount <= 1) {
            metrics.recordRoute("legacy");
            return RoomPartitionRoute.legacy(roomId);
        }

        int partitionId = stateReader.routePartition(roomId, userId);
        int version = stateReader.versionForRoom(roomId);
        if (assignmentProperties.enabled()) {
            RoomPartitionRoute nodeRoute = nodeAwareRoute(roomId, partitionId, partitionCount, version);
            if (nodeRoute.nodeId() != null || nodeRoute.fallbackReason() != null) {
                return nodeRoute;
            }
        }
        metrics.recordRoute("partitioned");
        return RoomPartitionRoute.partitioned(roomId, partitionId, partitionCount, version);
    }

    private RoomPartitionRoute nodeAwareRoute(Long roomId, int partitionId, int partitionCount, int version) {
        try {
            Optional<RoomPartitionAssignment> assignment = assignmentService.assignmentFor(partitionId, partitionCount);
            if (assignment.isEmpty()) {
                metrics.recordRoute("partitioned_assignment_unavailable");
                throw new RoomPartitionRouteUnavailableException("assignment_unavailable");
            }
            RoomPartitionAssignment owner = assignment.get();
            if (!owner.ready()) {
                metrics.recordRoute("partitioned_assignment_" + owner.readinessReason());
                throw new RoomPartitionRouteUnavailableException(owner.readinessReason());
            }
            metrics.recordRoute("partitioned_node_aware");
            return RoomPartitionRoute.nodeAware(
                    roomId,
                    partitionId,
                    partitionCount,
                    version,
                    owner.wsUrl(),
                    owner.nodeId(),
                    owner.assignmentVersion()
            );
        } catch (RoomPartitionRouteUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("room partition assignment route fallback roomId={} partitionId={}", roomId, partitionId, e);
            metrics.recordRoute("partitioned_assignment_error");
            throw new RoomPartitionRouteUnavailableException("assignment_error", e);
        }
    }

    public boolean shouldPartition(Long roomId) {
        return partitionCountForRoom(roomId) > 1;
    }

    public int partitionCountForRoom(Long roomId) {
        if (!properties.enabled()) {
            return 1;
        }
        return stateReader.partitionCountForRoom(roomId);
    }

    public List<Integer> publishPartitions(Long roomId) {
        if (!properties.enabled()) {
            return List.of();
        }
        return stateReader.publishPartitions(roomId);
    }

    public Integer partitionIdForChannel(String channel) {
        if (channel == null || !channel.startsWith("chat:room-partition:")) {
            return null;
        }
        String[] parts = channel.split(":");
        if (parts.length != 4) {
            return null;
        }
        try {
            return Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public int normalizePartitionId(Integer partitionId, Long roomId) {
        return properties.normalizeForRoom(partitionId, partitionCountForRoom(roomId));
    }
}
