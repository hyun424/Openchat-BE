package io.hyun424.openchat.chat.room.partition.assignment;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartitionAssignmentServiceTest {

    @Test
    void assignsPartitionsToSortedActiveNodesByModulo() {
        PartitionAssignmentService service = new PartitionAssignmentService(new FixedRegistry(List.of(
                node("node-c", false),
                node("node-a", false),
                node("node-b", false)
        )));

        Map<Integer, PartitionAssignment> assignments = service.assignments(6);

        assertEquals("node-a", assignments.get(0).nodeId());
        assertEquals("node-b", assignments.get(1).nodeId());
        assertEquals("node-c", assignments.get(2).nodeId());
        assertEquals("node-a", assignments.get(3).nodeId());
        assertEquals("node-b", assignments.get(4).nodeId());
        assertEquals("node-c", assignments.get(5).nodeId());
    }

    @Test
    void excludesDrainingNodesFromAssignment() {
        PartitionAssignmentService service = new PartitionAssignmentService(new FixedRegistry(List.of(
                node("node-a", false),
                node("node-b", true)
        )));

        Map<Integer, PartitionAssignment> assignments = service.assignments(3);

        assertEquals("node-a", assignments.get(0).nodeId());
        assertEquals("node-a", assignments.get(1).nodeId());
        assertEquals("node-a", assignments.get(2).nodeId());
    }

    @Test
    void returnsEmptyWhenNoActiveNodeExists() {
        PartitionAssignmentService service = new PartitionAssignmentService(new FixedRegistry(List.of(node("node-a", true))));

        assertTrue(service.assignmentFor(0).isEmpty());
        assertTrue(service.assignments(4).isEmpty());
        assertTrue(service.ownedPartitions("node-a", 4).isEmpty());
    }

    @Test
    void ownedPartitionsReturnsPartitionsForTargetNode() {
        PartitionAssignmentService service = new PartitionAssignmentService(new FixedRegistry(List.of(
                node("node-b", false),
                node("node-a", false)
        )));

        assertEquals(Set.of(0, 2, 4), service.ownedPartitions("node-a", 5));
        assertEquals(Set.of(1, 3), service.ownedPartitions("node-b", 5));
    }

    @Test
    void assignmentForReturnsSingleOwner() {
        PartitionAssignmentService service = new PartitionAssignmentService(new FixedRegistry(List.of(
                node("node-a", false),
                node("node-b", false)
        )));

        Optional<PartitionAssignment> assignment = service.assignmentFor(3);

        assertTrue(assignment.isPresent());
        assertEquals(3, assignment.get().partitionId());
        assertEquals("node-b", assignment.get().nodeId());
    }

    private RealtimeNode node(String nodeId, boolean draining) {
        return new RealtimeNode(
                nodeId,
                "realtime",
                nodeId + ".internal",
                8080,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1),
                draining
        );
    }

    private record FixedRegistry(List<RealtimeNode> nodes) implements RealtimeNodeRegistry {
        @Override
        public boolean heartbeat(RealtimeNode node) {
            return true;
        }

        @Override
        public List<RealtimeNode> activeNodes() {
            return nodes;
        }
    }
}
