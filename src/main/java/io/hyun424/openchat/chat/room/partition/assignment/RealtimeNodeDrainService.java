package io.hyun424.openchat.chat.room.partition.assignment;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionControlCommand;
import io.hyun424.openchat.chat.room.partition.infra.RoomPartitionControlPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.room-partition.assignment.enabled", havingValue = "true")
public class RealtimeNodeDrainService {

    private static final String REASON = "node_drain";

    private final RealtimeNodeRegistry registry;
    private final RoomPartitionControlPublisher controlPublisher;
    private final RoomPartitionAssignmentProperties properties;
    private final RoomPartitionAssignmentService assignmentService;
    private final RoomPartitionProperties partitionProperties;

    public RealtimeNodeDrainService(
            RealtimeNodeRegistry registry,
            RoomPartitionControlPublisher controlPublisher,
            RoomPartitionAssignmentProperties properties,
            RoomPartitionAssignmentService assignmentService,
            RoomPartitionProperties partitionProperties
    ) {
        this.registry = registry;
        this.controlPublisher = controlPublisher;
        this.properties = properties;
        this.assignmentService = assignmentService;
        this.partitionProperties = partitionProperties;
    }

    public NodeDrainResult startDrain(String nodeId, Integer limit, Long retryAfterMs) {
        String operationId = operationId(nodeId);
        if (!properties.nodeDrainEnabled()) {
            log.info("realtime node drain skipped because node drain is disabled nodeId={}", nodeId);
            return NodeDrainResult.skipped(nodeId, operationId, "disabled");
        }
        if (nodeId == null || nodeId.isBlank()) {
            return NodeDrainResult.skipped(nodeId, operationId, "invalid_node");
        }

        List<RealtimeNode> nodes = registry.nodes();
        Optional<RealtimeNode> target = findNode(nodes, nodeId);
        if (target.isEmpty()) {
            log.info("realtime node drain rejected because node is unknown or stale nodeId={}", nodeId);
            return NodeDrainResult.skipped(nodeId, operationId, "unknown_node");
        }
        if (!hasReplacementActiveNode(nodes, nodeId)) {
            log.info("realtime node drain rejected because target is the last active node nodeId={}", nodeId);
            return NodeDrainResult.skipped(nodeId, operationId, "last_active_node", target.get().openSessions());
        }

        registry.markDraining(nodeId, true);
        Readiness readiness = waitForReplacementReady(nodeId);
        RealtimeNode currentTarget = currentTarget(nodeId).orElse(target.get());
        if (!readiness.ready()) {
            log.info("realtime node drain waiting for replacement owner nodeId={} reason={} remainingSessions={}",
                    nodeId, readiness.reason(), currentTarget.openSessions());
            return NodeDrainResult.waiting(
                    nodeId,
                    operationId,
                    readiness.reason(),
                    currentTarget.openSessions()
            );
        }
        if (currentTarget.openSessions() <= 0) {
            log.info("realtime node drain complete without reconnect nodeId={}", nodeId);
            return NodeDrainResult.complete(nodeId, operationId);
        }

        RoomPartitionControlCommand command = RoomPartitionControlCommand.nodeReconnect(
                nodeId,
                REASON,
                limit == null ? properties.nodeDrainReconnectLimit() : limit,
                retryAfterMs == null ? properties.nodeDrainRetryAfterMs() : retryAfterMs
        );
        int targetedSessions = Math.min(currentTarget.openSessions(), command.limit());
        boolean published = controlPublisher.publish(command);
        String status = published ? "reconnect_published" : "publish_failed";
        log.info("realtime node drain requested nodeId={} status={} openSessions={} reconnectPublished={} limit={} retryAfterMs={}",
                nodeId, status, currentTarget.openSessions(), published, command.limit(), command.retryAfterMs());
        return new NodeDrainResult(
                nodeId,
                operationId,
                true,
                status,
                published,
                targetedSessions,
                currentTarget.openSessions(),
                REASON
        );
    }

    public NodeDrainResult stopDrain(String nodeId) {
        registry.markDraining(nodeId, false);
        log.info("realtime node drain cleared nodeId={}", nodeId);
        return new NodeDrainResult(nodeId, operationId(nodeId), false, "undrained", false, 0, 0, REASON);
    }

    private Optional<RealtimeNode> findNode(List<RealtimeNode> nodes, String nodeId) {
        if (nodeId == null) {
            return Optional.empty();
        }
        return nodes.stream()
                .filter(node -> nodeId.equals(node.nodeId()))
                .max(Comparator.comparing(RealtimeNode::reportedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private Optional<RealtimeNode> currentTarget(String nodeId) {
        return findNode(registry.nodes(), nodeId);
    }

    private boolean hasReplacementActiveNode(List<RealtimeNode> nodes, String nodeId) {
        Instant now = Instant.now();
        return nodes.stream()
                .filter(node -> !nodeId.equals(node.nodeId()))
                .anyMatch(node -> node.activeAt(now));
    }

    private Readiness waitForReplacementReady(String drainedNodeId) {
        long timeoutAt = System.currentTimeMillis() + readinessTimeoutMs();
        Readiness last = replacementReady(drainedNodeId);
        while (!last.ready() && System.currentTimeMillis() < timeoutAt) {
            sleepQuietly(250);
            last = replacementReady(drainedNodeId);
        }
        return last;
    }

    private Readiness replacementReady(String drainedNodeId) {
        int partitionCount = Math.max(1, partitionProperties.partitionCount());
        Map<Integer, RoomPartitionAssignment> assignments = assignmentService.assignments(partitionCount);
        if (assignments.isEmpty()) {
            return new Readiness(false, "assignment_unavailable");
        }
        for (RoomPartitionAssignment assignment : assignments.values()) {
            if (drainedNodeId.equals(assignment.nodeId())) {
                return new Readiness(false, "drained_node_still_owner");
            }
            if (!assignment.ready()) {
                return new Readiness(false, assignment.readinessReason());
            }
        }
        return new Readiness(true, "ready");
    }

    private long readinessTimeoutMs() {
        return properties.nodeDrainReadinessTimeoutMs();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String operationId(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return "node_drain:unknown";
        }
        return "node_drain:" + nodeId;
    }

    public record NodeDrainResult(
            String nodeId,
            String operationId,
            boolean draining,
            String status,
            boolean reconnectPublished,
            int targetedSessions,
            int remainingSessions,
            String reason
    ) {
        static NodeDrainResult skipped(String nodeId, String operationId, String status) {
            return skipped(nodeId, operationId, status, 0);
        }

        static NodeDrainResult skipped(String nodeId, String operationId, String status, int remainingSessions) {
            return new NodeDrainResult(nodeId, operationId, false, status, false, 0, Math.max(0, remainingSessions), REASON);
        }

        static NodeDrainResult waiting(String nodeId, String operationId, String status, int remainingSessions) {
            return new NodeDrainResult(nodeId, operationId, true, status, false, 0, Math.max(0, remainingSessions), REASON);
        }

        static NodeDrainResult complete(String nodeId, String operationId) {
            return new NodeDrainResult(nodeId, operationId, true, "complete", false, 0, 0, REASON);
        }
    }

    private record Readiness(boolean ready, String reason) {
    }
}
