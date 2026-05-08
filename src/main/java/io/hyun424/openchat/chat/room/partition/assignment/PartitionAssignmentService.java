package io.hyun424.openchat.chat.room.partition.assignment;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class PartitionAssignmentService {

    private final RealtimeNodeRegistry registry;

    @Autowired
    public PartitionAssignmentService(ObjectProvider<RealtimeNodeRegistry> registryProvider) {
        this(registryProvider.getIfAvailable(NoopRealtimeNodeRegistry::new));
    }

    PartitionAssignmentService(RealtimeNodeRegistry registry) {
        this.registry = registry;
    }

    public Optional<PartitionAssignment> assignmentFor(int partitionId) {
        List<RealtimeNode> nodes = sortedActiveNodes();
        if (nodes.isEmpty()) {
            return Optional.empty();
        }
        int normalizedPartition = Math.max(0, partitionId);
        RealtimeNode owner = nodes.get(Math.floorMod(normalizedPartition, nodes.size()));
        return Optional.of(PartitionAssignment.of(normalizedPartition, owner));
    }

    public Map<Integer, PartitionAssignment> assignments(int partitionCount) {
        if (partitionCount <= 0) {
            return Map.of();
        }
        List<RealtimeNode> nodes = sortedActiveNodes();
        if (nodes.isEmpty()) {
            return Map.of();
        }

        Map<Integer, PartitionAssignment> result = new LinkedHashMap<>();
        for (int partitionId = 0; partitionId < partitionCount; partitionId++) {
            RealtimeNode owner = nodes.get(Math.floorMod(partitionId, nodes.size()));
            result.put(partitionId, PartitionAssignment.of(partitionId, owner));
        }
        return Map.copyOf(result);
    }

    public Set<Integer> ownedPartitions(String nodeId, int partitionCount) {
        if (nodeId == null || nodeId.isBlank() || partitionCount <= 0) {
            return Set.of();
        }
        List<RealtimeNode> nodes = sortedActiveNodes();
        if (nodes.isEmpty()) {
            return Set.of();
        }
        String targetNodeId = nodeId.trim();
        return IntStream.range(0, partitionCount)
                .filter(partitionId -> nodes.get(Math.floorMod(partitionId, nodes.size())).nodeId().equals(targetNodeId))
                .boxed()
                .collect(Collectors.toUnmodifiableSet());
    }

    private List<RealtimeNode> sortedActiveNodes() {
        return registry.activeNodes().stream()
                .filter(RealtimeNode::active)
                .sorted(Comparator.comparing(RealtimeNode::nodeId))
                .toList();
    }
}
