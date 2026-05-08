package io.hyun424.openchat.chat.room.partition.assignment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RoomPartitionAssignmentServiceTest {

    @Test
    void assignments_areDeterministicModuloOverSortedActiveNodes() {
        TestRegistry registry = new TestRegistry(List.of(
                node("node-b", false, Instant.now().plusSeconds(30), Set.of(1, 3)),
                node("node-a", false, Instant.now().plusSeconds(30), Set.of(0, 2))
        ));
        RoomPartitionAssignmentService service = new RoomPartitionAssignmentService(registry);

        var assignments = service.assignments(4);

        assertEquals("node-a", assignments.get(0).nodeId());
        assertEquals("node-b", assignments.get(1).nodeId());
        assertEquals("node-a", assignments.get(2).nodeId());
        assertEquals("node-b", assignments.get(3).nodeId());
        assertTrue(assignments.get(0).ready());
        assertTrue(assignments.get(1).ready());
    }

    @Test
    void activeNodes_excludesDrainingAndExpiredNodes() {
        TestRegistry registry = new TestRegistry(List.of(
                node("node-a", false, Instant.now().plusSeconds(30)),
                node("node-b", true, Instant.now().plusSeconds(30)),
                node("node-c", false, Instant.now().minusSeconds(1))
        ));
        RoomPartitionAssignmentService service = new RoomPartitionAssignmentService(registry);

        assertEquals(List.of("node-a"), service.activeNodes().stream().map(RealtimeNode::nodeId).toList());
    }

    @Test
    void assignmentVersion_changesWhenNodeSetOrPartitionCountChanges() {
        RoomPartitionAssignmentService twoNodes = new RoomPartitionAssignmentService(new TestRegistry(List.of(
                node("node-a", false, Instant.now().plusSeconds(30), Set.of(0, 2)),
                node("node-b", false, Instant.now().plusSeconds(30), Set.of(1, 3))
        )));
        RoomPartitionAssignmentService oneNode = new RoomPartitionAssignmentService(new TestRegistry(List.of(
                node("node-a", false, Instant.now().plusSeconds(30), Set.of(0, 1, 2, 3))
        )));

        String base = twoNodes.assignments(4).get(0).assignmentVersion();
        String differentPartitionCount = twoNodes.assignments(3).get(0).assignmentVersion();
        String differentNodeSet = oneNode.assignments(4).get(0).assignmentVersion();

        assertNotEquals(base, differentPartitionCount);
        assertNotEquals(base, differentNodeSet);
    }

    @Test
    void readiness_isBasedOnDesiredOwnerSubscriptionWithoutReassigningToAlternateSubscriber() {
        TestRegistry registry = new TestRegistry(List.of(
                node("node-a", false, Instant.now().plusSeconds(30), Set.of()),
                node("node-b", false, Instant.now().plusSeconds(30), Set.of(0, 1, 2, 3))
        ));
        RoomPartitionAssignmentService service = new RoomPartitionAssignmentService(registry);

        var assignment = service.assignments(4).get(0);

        assertEquals("node-a", assignment.nodeId());
        assertEquals(false, assignment.ready());
        assertEquals("owner_not_ready", assignment.readinessReason());
        assertEquals(List.of("node-b"), assignment.alternateSubscribedNodeIds());
    }

    @Test
    void assignments_returnsEmptyWhenNoActiveNodes() {
        RoomPartitionAssignmentService service = new RoomPartitionAssignmentService(new TestRegistry(List.of(
                node("node-a", true, Instant.now().plusSeconds(30))
        )));

        assertTrue(service.assignments(4).isEmpty());
    }

    private static RealtimeNode node(String nodeId, boolean draining, Instant expiresAt) {
        return node(nodeId, draining, expiresAt, Set.of());
    }

    private static RealtimeNode node(String nodeId, boolean draining, Instant expiresAt, Set<Integer> subscribedPartitions) {
        return new RealtimeNode(nodeId, "realtime", "ws://" + nodeId + ":8080", draining, Instant.now(), expiresAt, subscribedPartitions);
    }

    private static class TestRegistry implements RealtimeNodeRegistry {
        private final List<RealtimeNode> nodes;
        private final Set<String> draining = new java.util.HashSet<>();

        TestRegistry(List<RealtimeNode> nodes) {
            this.nodes = new ArrayList<>(nodes);
        }

        @Override
        public void heartbeat(RealtimeNode node) {
            nodes.add(node);
        }

        @Override
        public List<RealtimeNode> nodes() {
            return nodes;
        }

        @Override
        public Set<String> drainingNodeIds() {
            return draining;
        }

        @Override
        public void markDraining(String nodeId, boolean draining) {
            if (draining) {
                this.draining.add(nodeId);
            } else {
                this.draining.remove(nodeId);
            }
        }
    }
}
