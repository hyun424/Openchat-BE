package io.hyun424.openchat.chat.room.partition.assignment;

import java.time.Instant;
import java.util.Set;

import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.room-partition.assignment.enabled", havingValue = "true")
public class RealtimeNodeHeartbeatScheduler {

    private final RealtimeNodeRegistry registry;
    private final RealtimeNodeSubscriptionState subscriptionState;
    private final RoomPartitionAssignmentProperties properties;
    private final RoomPartitionProperties partitionProperties;
    private final String nodeId;
    private final String role;

    public RealtimeNodeHeartbeatScheduler(
            RealtimeNodeRegistry registry,
            RealtimeNodeSubscriptionState subscriptionState,
            RoomPartitionAssignmentProperties properties,
            RoomPartitionProperties partitionProperties,
            @Value("${app.instance-id:local}") String nodeId,
            @Value("${app.role:combined}") String role
    ) {
        this.registry = registry;
        this.subscriptionState = subscriptionState;
        this.properties = properties;
        this.partitionProperties = partitionProperties;
        this.nodeId = nodeId;
        this.role = role;
    }

    @Scheduled(fixedDelayString = "${app.room-partition.assignment.heartbeat-interval-ms:5000}")
    public void heartbeat() {
        Instant now = Instant.now();
        Set<String> drainingIds = registry.drainingNodeIds();
        Set<Integer> subscribedPartitions = subscribedPartitions();
        RealtimeNode node = new RealtimeNode(
                nodeId,
                role,
                properties.wsUrl(),
                drainingIds.contains(nodeId),
                now,
                now.plusMillis(properties.retentionMs()),
                subscribedPartitions
        );
        registry.heartbeat(node);
    }

    private Set<Integer> subscribedPartitions() {
        Set<Integer> dynamic = subscriptionState.subscribedPartitions();
        if (!dynamic.isEmpty() || properties.dynamicSubscribeEnabled() || !partitionProperties.enabled()) {
            return dynamic;
        }
        return partitionProperties.ownedPartitions();
    }
}
