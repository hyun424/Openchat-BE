package io.hyun424.openchat.chat.room.partition.assignment;

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

    public RealtimeNodeDrainService(
            RealtimeNodeRegistry registry,
            RoomPartitionControlPublisher controlPublisher,
            RoomPartitionAssignmentProperties properties
    ) {
        this.registry = registry;
        this.controlPublisher = controlPublisher;
        this.properties = properties;
    }

    public NodeDrainResult startDrain(String nodeId, Integer limit, Long retryAfterMs) {
        if (!properties.nodeDrainEnabled()) {
            log.info("realtime node drain skipped because node drain is disabled nodeId={}", nodeId);
            return new NodeDrainResult(nodeId, false, false);
        }
        registry.markDraining(nodeId, true);
        RoomPartitionControlCommand command = RoomPartitionControlCommand.nodeReconnect(
                nodeId,
                REASON,
                limit == null ? properties.nodeDrainReconnectLimit() : limit,
                retryAfterMs == null ? properties.nodeDrainRetryAfterMs() : retryAfterMs
        );
        boolean published = controlPublisher.publish(command);
        log.info("realtime node drain requested nodeId={} reconnectPublished={} limit={} retryAfterMs={}",
                nodeId, published, command.limit(), command.retryAfterMs());
        return new NodeDrainResult(nodeId, true, published);
    }

    public NodeDrainResult stopDrain(String nodeId) {
        registry.markDraining(nodeId, false);
        log.info("realtime node drain cleared nodeId={}", nodeId);
        return new NodeDrainResult(nodeId, false, false);
    }

    public record NodeDrainResult(
            String nodeId,
            boolean draining,
            boolean reconnectPublished
    ) {
    }
}
