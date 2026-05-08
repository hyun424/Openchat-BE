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
                node("node-b", false, Instant.now().plusSeconds(30)),
                node("node-a", false, Instant.now().plusSeconds(30))
        ));
        RoomPartitionAssignmentService service = new RoomPartitionAssignmentService(registry);

        var assignments = service.assignments(4);

        assertEquals("node-a", assignments.get(0).nodeId());
        assertEquals("node-b", assignments.get(1).nodeId());
        assertEquals("node-a", assignments.get(2).nodeId());
        assertEquals("node-b", assignments.get(3).nodeId());
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
                node("node-a", false, Instant.now().plusSeconds(30)),
                node("node-b", false, Instant.now().plusSeconds(30))
        )));
        RoomPartitionAssignmentService oneNode = new RoomPartitionAssignmentService(new TestRegistry(List.of(
                node("node-a", false, Instant.now().plusSeconds(30))
        )));

        String base = twoNodes.assignments(4).get(0).assignmentVersion();
        String differentPartitionCount = twoNodes.assignments(3).get(0).assignmentVersion();
        String differentNodeSet = oneNode.assignments(4).get(0).assignmentVersion();

        assertNotEquals(base, differentPartitionCount);
        assertNotEquals(base, differentNodeSet);
    }

    @Test
    void assignments_returnsEmptyWhenNoActiveNodes() {
        RoomPartitionAssignmentService service = new RoomPartitionAssignmentService(new TestRegistry(List.of(
                node("node-a", true, Instant.now().plusSeconds(30))
        )));

        assertTrue(service.assignments(4).isEmpty());
    }

    private static RealtimeNode node(String nodeId, boolean draining, Instant expiresAt) {
        return new RealtimeNode(nodeId, "realtime", "ws://" + nodeId + ":8080", draining, Instant.now(), expiresAt, Set.of());
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
