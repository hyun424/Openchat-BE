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
    private static final String ACTION_NONE = "none";
    private static final String ACTION_POLL_STATUS = "poll_status";
    private static final String ACTION_RETRY_RECONNECT = "retry_reconnect";
    private static final String ACTION_WAIT_ASSIGNMENT = "wait_assignment";
    private static final String ACTION_WAIT_REPLACEMENT_READY = "wait_replacement_ready";
    private static final String ACTION_WAIT_NODE_HEARTBEAT = "wait_node_heartbeat";
    private static final String ACTION_ADD_REPLACEMENT_NODE = "add_replacement_node";
    private static final String ACTION_FIX_REQUEST = "fix_request";
    private static final String ACTION_ENABLE_NODE_DRAIN = "enable_node_drain";
    private static final String ACTION_INVESTIGATE_PUBLISH = "investigate_publish";
    private static final String ACTION_START_DRAIN = "start_drain";

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
            return NodeDrainResult.disabled(nodeId, operationId);
        }
        if (nodeId == null || nodeId.isBlank()) {
            return NodeDrainResult.invalid(nodeId, operationId);
        }

        List<RealtimeNode> nodes = registry.nodes();
        Optional<RealtimeNode> target = findNode(nodes, nodeId);
        if (target.isEmpty()) {
            log.info("realtime node drain rejected because node is unknown or stale nodeId={}", nodeId);
            return NodeDrainResult.unknown(nodeId, operationId);
        }
        if (!hasReplacementActiveNode(nodes, nodeId)) {
            log.info("realtime node drain rejected because target is the last active node nodeId={}", nodeId);
            return NodeDrainResult.lastActive(nodeId, operationId, target.get().openSessions());
        }

        registry.markDraining(nodeId, true);
        Readiness readiness = waitForReplacementReady(nodeId);
        RealtimeNode currentTarget = currentTarget(nodeId).orElse(target.get());
        if (!readiness.ready()) {
            NodeDrainResult result = NodeDrainResult.waitingForReadiness(
                    nodeId,
                    operationId,
                    readiness.reason(),
                    currentTarget.openSessions()
            );
            log.info("realtime node drain waiting nodeId={} operationId={} status={} nextAction={} remainingSessions={} readinessReason={}",
                    nodeId, operationId, result.status(), result.nextAction(), result.remainingSessions(), result.readinessReason());
            return result;
        }
        if (currentTarget.openSessions() <= 0) {
            NodeDrainResult result = NodeDrainResult.complete(nodeId, operationId, readiness.reason());
            log.info("realtime node drain complete without reconnect nodeId={} operationId={} status={} nextAction={} remainingSessions={} readinessReason={}",
                    nodeId, operationId, result.status(), result.nextAction(), result.remainingSessions(), result.readinessReason());
            return result;
        }

        RoomPartitionControlCommand command = RoomPartitionControlCommand.nodeReconnect(
                nodeId,
                REASON,
                limit == null ? properties.nodeDrainReconnectLimit() : limit,
                retryAfterMs == null ? properties.nodeDrainRetryAfterMs() : retryAfterMs
        );
        int targetedSessions = Math.min(currentTarget.openSessions(), command.limit());
        boolean published = controlPublisher.publish(command);
        NodeDrainResult result = published
                ? NodeDrainResult.reconnectPublished(nodeId, operationId, targetedSessions, currentTarget.openSessions(), readiness.reason())
                : NodeDrainResult.publishFailed(nodeId, operationId, targetedSessions, currentTarget.openSessions(), readiness.reason());
        log.info("realtime node drain requested nodeId={} operationId={} status={} nextAction={} remainingSessions={} readinessReason={} reconnectPublished={} limit={} retryAfterMs={}",
                nodeId, operationId, result.status(), result.nextAction(), result.remainingSessions(), result.readinessReason(),
                published, command.limit(), command.retryAfterMs());
        return result;
    }

    public NodeDrainResult drainStatus(String nodeId) {
        String operationId = operationId(nodeId);
        if (!properties.nodeDrainEnabled()) {
            return NodeDrainResult.disabled(nodeId, operationId);
        }
        if (nodeId == null || nodeId.isBlank()) {
            return NodeDrainResult.invalid(nodeId, operationId);
        }

        Optional<RealtimeNode> target = currentTarget(nodeId);
        if (target.isEmpty()) {
            return NodeDrainResult.unknown(nodeId, operationId);
        }
        RealtimeNode currentTarget = target.get();
        if (!currentTarget.draining()) {
            return NodeDrainResult.notDraining(nodeId, operationId, currentTarget.openSessions());
        }

        Readiness readiness = replacementReady(nodeId);
        if (!readiness.ready()) {
            return NodeDrainResult.waitingForReadiness(nodeId, operationId, readiness.reason(), currentTarget.openSessions());
        }
        if (currentTarget.openSessions() <= 0) {
            return NodeDrainResult.complete(nodeId, operationId, readiness.reason());
        }
        return NodeDrainResult.sessionsRemaining(nodeId, operationId, currentTarget.openSessions(), readiness.reason());
    }

    public NodeDrainResult stopDrain(String nodeId) {
        registry.markDraining(nodeId, false);
        log.info("realtime node drain cleared nodeId={}", nodeId);
        return new NodeDrainResult(
                nodeId,
                operationId(nodeId),
                false,
                "undrained",
                false,
                0,
                0,
                REASON,
                false,
                ACTION_NONE,
                null
        );
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
            String reason,
            boolean retryable,
            String nextAction,
            String readinessReason
    ) {
        static NodeDrainResult disabled(String nodeId, String operationId) {
            return skipped(nodeId, operationId, "disabled", 0, false, ACTION_ENABLE_NODE_DRAIN, null);
        }

        static NodeDrainResult invalid(String nodeId, String operationId) {
            return skipped(nodeId, operationId, "invalid_node", 0, false, ACTION_FIX_REQUEST, null);
        }

        static NodeDrainResult unknown(String nodeId, String operationId) {
            return skipped(nodeId, operationId, "unknown_node", 0, true, ACTION_WAIT_NODE_HEARTBEAT, null);
        }

        static NodeDrainResult lastActive(String nodeId, String operationId, int remainingSessions) {
            return skipped(nodeId, operationId, "last_active_node", remainingSessions, false, ACTION_ADD_REPLACEMENT_NODE, null);
        }

        static NodeDrainResult notDraining(String nodeId, String operationId, int remainingSessions) {
            return skipped(nodeId, operationId, "not_draining", remainingSessions, false, ACTION_START_DRAIN, null);
        }

        static NodeDrainResult skipped(
                String nodeId,
                String operationId,
                String status,
                int remainingSessions,
                boolean retryable,
                String nextAction,
                String readinessReason
        ) {
            return new NodeDrainResult(
                    nodeId,
                    operationId,
                    false,
                    status,
                    false,
                    0,
                    Math.max(0, remainingSessions),
                    REASON,
                    retryable,
                    nextAction,
                    readinessReason
            );
        }

        static NodeDrainResult waitingForReadiness(String nodeId, String operationId, String status, int remainingSessions) {
            return new NodeDrainResult(
                    nodeId,
                    operationId,
                    true,
                    status,
                    false,
                    0,
                    Math.max(0, remainingSessions),
                    REASON,
                    true,
                    actionForReadiness(status),
                    status
            );
        }

        static NodeDrainResult complete(String nodeId, String operationId, String readinessReason) {
            return new NodeDrainResult(
                    nodeId,
                    operationId,
                    true,
                    "complete",
                    false,
                    0,
                    0,
                    REASON,
                    false,
                    ACTION_NONE,
                    readinessReason
            );
        }

        static NodeDrainResult reconnectPublished(
                String nodeId,
                String operationId,
                int targetedSessions,
                int remainingSessions,
                String readinessReason
        ) {
            return new NodeDrainResult(
                    nodeId,
                    operationId,
                    true,
                    "reconnect_published",
                    true,
                    Math.max(0, targetedSessions),
                    Math.max(0, remainingSessions),
                    REASON,
                    true,
                    ACTION_POLL_STATUS,
                    readinessReason
            );
        }

        static NodeDrainResult publishFailed(
                String nodeId,
                String operationId,
                int targetedSessions,
                int remainingSessions,
                String readinessReason
        ) {
            return new NodeDrainResult(
                    nodeId,
                    operationId,
                    true,
                    "publish_failed",
                    false,
                    Math.max(0, targetedSessions),
                    Math.max(0, remainingSessions),
                    REASON,
                    true,
                    ACTION_INVESTIGATE_PUBLISH,
                    readinessReason
            );
        }

        static NodeDrainResult sessionsRemaining(
                String nodeId,
                String operationId,
                int remainingSessions,
                String readinessReason
        ) {
            return new NodeDrainResult(
                    nodeId,
                    operationId,
                    true,
                    "sessions_remaining",
                    false,
                    0,
                    Math.max(0, remainingSessions),
                    REASON,
                    true,
                    ACTION_RETRY_RECONNECT,
                    readinessReason
            );
        }

        private static String actionForReadiness(String status) {
            if ("assignment_unavailable".equals(status)) {
                return ACTION_WAIT_ASSIGNMENT;
            }
            return ACTION_WAIT_REPLACEMENT_READY;
        }
    }

    private record Readiness(boolean ready, String reason) {
    }
}
