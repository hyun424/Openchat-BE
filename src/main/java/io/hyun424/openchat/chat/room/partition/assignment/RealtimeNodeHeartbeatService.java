package io.hyun424.openchat.chat.room.partition.assignment;

import java.time.Instant;
import java.util.Set;

import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RealtimeNodeHeartbeatService {

    private final RealtimeNodeRegistry registry;
    private final RealtimeNodeSubscriptionState subscriptionState;
    private final RoomPartitionAssignmentProperties properties;
    private final RoomPartitionProperties partitionProperties;
    private final RoomSessionRegistry roomSessionRegistry;
    private final String nodeId;
    private final String role;

    public RealtimeNodeHeartbeatService(
            ObjectProvider<RealtimeNodeRegistry> registryProvider,
            RealtimeNodeSubscriptionState subscriptionState,
            RoomPartitionAssignmentProperties properties,
            RoomPartitionProperties partitionProperties,
            RoomSessionRegistry roomSessionRegistry,
            @Value("${app.instance-id:local}") String nodeId,
            @Value("${app.role:combined}") String role
    ) {
        this.registry = registryProvider.getIfAvailable(NoopRealtimeNodeRegistry::new);
        this.subscriptionState = subscriptionState;
        this.properties = properties;
        this.partitionProperties = partitionProperties;
        this.roomSessionRegistry = roomSessionRegistry;
        this.nodeId = nodeId;
        this.role = role;
    }

    public void publishHeartbeat() {
        Instant now = Instant.now();
        Set<String> drainingIds = registry.drainingNodeIds();
        RealtimeNode node = new RealtimeNode(
                nodeId,
                role,
                properties.wsUrl(),
                drainingIds.contains(nodeId),
                now,
                now.plusMillis(properties.retentionMs()),
                subscribedPartitions(),
                roomSessionRegistry.openSessions().size()
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
