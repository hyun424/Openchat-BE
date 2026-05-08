package io.hyun424.openchat.chat.room.partition.assignment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RoomPartitionAssignmentService {

    private final RealtimeNodeRegistry registry;

    @Autowired
    public RoomPartitionAssignmentService(ObjectProvider<RealtimeNodeRegistry> registryProvider) {
        this.registry = registryProvider.getIfAvailable(NoopRealtimeNodeRegistry::new);
    }

    RoomPartitionAssignmentService(RealtimeNodeRegistry registry) {
        this.registry = registry;
    }

    public List<RealtimeNode> activeNodes() {
        Instant now = Instant.now();
        return registry.nodes()
                .stream()
                .filter(node -> node.activeAt(now))
                .sorted(Comparator.comparing(RealtimeNode::nodeId))
                .toList();
    }

    public Map<Integer, RoomPartitionAssignment> assignments(int partitionCount) {
        if (partitionCount <= 0) {
            return Map.of();
        }
        List<RealtimeNode> nodes = activeNodes();
        if (nodes.isEmpty()) {
            return Map.of();
        }
        String version = assignmentVersion(nodes, partitionCount);
        Map<Integer, RoomPartitionAssignment> assignments = new LinkedHashMap<>();
        for (int partitionId = 0; partitionId < partitionCount; partitionId++) {
            RealtimeNode owner = nodes.get(Math.floorMod(partitionId, nodes.size()));
            boolean ready = subscribes(owner, partitionId);
            int resolvedPartitionId = partitionId;
            List<String> alternateSubscribedNodeIds = nodes.stream()
                    .filter(node -> !node.nodeId().equals(owner.nodeId()))
                    .filter(node -> subscribes(node, resolvedPartitionId))
                    .map(RealtimeNode::nodeId)
                    .toList();
            assignments.put(partitionId, new RoomPartitionAssignment(
                    partitionId,
                    owner.nodeId(),
                    owner.wsUrl(),
                    version,
                    ready,
                    ready ? "ready" : "owner_not_ready",
                    alternateSubscribedNodeIds
            ));
        }
        return Map.copyOf(assignments);
    }

    public Optional<RoomPartitionAssignment> assignmentFor(int partitionId, int partitionCount) {
        return Optional.ofNullable(assignments(partitionCount).get(partitionId));
    }

    public List<Integer> ownedPartitions(String nodeId, int partitionCount) {
        if (nodeId == null || nodeId.isBlank()) {
            return List.of();
        }
        List<Integer> partitions = new ArrayList<>();
        assignments(partitionCount).forEach((partitionId, assignment) -> {
            if (nodeId.equals(assignment.nodeId())) {
                partitions.add(partitionId);
            }
        });
        return partitions;
    }

    private String assignmentVersion(List<RealtimeNode> nodes, int partitionCount) {
        String material = nodes.stream()
                .map(RealtimeNode::nodeId)
                .reduce("partitionCount=" + partitionCount, (left, right) -> left + "|" + right);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < Math.min(8, bytes.length); i++) {
                builder.append(String.format("%02x", bytes[i]));
            }
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(material.hashCode());
        }
    }

    private boolean subscribes(RealtimeNode node, int partitionId) {
        return node.subscribedPartitions() != null && node.subscribedPartitions().contains(partitionId);
    }
}
