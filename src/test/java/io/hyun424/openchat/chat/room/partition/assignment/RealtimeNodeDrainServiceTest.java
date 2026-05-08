package io.hyun424.openchat.chat.room.partition.assignment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentCaptor.forClass;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.hyun424.openchat.chat.room.hot.RoomScaleTier;
import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionControlCommand;
import io.hyun424.openchat.chat.room.partition.infra.RoomPartitionControlPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RealtimeNodeDrainServiceTest {

    @Test
    void startDrain_rejectsUnknownNodeWithoutPublish() {
        Fixture fixture = new Fixture(List.of(node("node-a", false, Set.of(0, 1, 2, 3), 0)));

        RealtimeNodeDrainService.NodeDrainResult result = fixture.service.startDrain("missing", null, null);

        assertEquals("unknown_node", result.status());
        assertFalse(result.reconnectPublished());
        verify(fixture.publisher, never()).publish(any());
    }

    @Test
    void startDrain_rejectsLastActiveNode() {
        Fixture fixture = new Fixture(List.of(node("node-a", false, Set.of(0, 1, 2, 3), 10)));

        RealtimeNodeDrainService.NodeDrainResult result = fixture.service.startDrain("node-a", null, null);

        assertEquals("last_active_node", result.status());
        assertEquals(10, result.remainingSessions());
        verify(fixture.publisher, never()).publish(any());
    }

    @Test
    void startDrain_waitsWhenReplacementOwnerIsNotReady() {
        Fixture fixture = new Fixture(List.of(
                node("node-a", false, Set.of(0, 2), 10),
                node("node-b", false, Set.of(), 0)
        ));

        RealtimeNodeDrainService.NodeDrainResult result = fixture.service.startDrain("node-a", null, null);

        assertTrue(result.draining());
        assertEquals("owner_not_ready", result.status());
        assertFalse(result.reconnectPublished());
        verify(fixture.publisher, never()).publish(any());
    }

    @Test
    void startDrain_publishesNodeReconnectWhenReplacementOwnerReady() {
        Fixture fixture = new Fixture(List.of(
                node("node-a", false, Set.of(0, 2), 12),
                node("node-b", false, Set.of(0, 1, 2, 3), 0)
        ));
        when(fixture.publisher.publish(any())).thenReturn(true);

        RealtimeNodeDrainService.NodeDrainResult result = fixture.service.startDrain("node-a", 50, 250L);

        assertTrue(result.draining());
        assertEquals("reconnect_published", result.status());
        assertTrue(result.reconnectPublished());
        assertEquals(12, result.targetedSessions());
        assertEquals(12, result.remainingSessions());
        verify(fixture.publisher).publish(any(RoomPartitionControlCommand.class));
    }

    @Test
    void startDrain_reportsTargetedSessionsUsingBoundedLimit() {
        Fixture fixture = new Fixture(List.of(
                node("node-a", false, Set.of(0, 2), 12),
                node("node-b", false, Set.of(0, 1, 2, 3), 0)
        ));
        when(fixture.publisher.publish(any())).thenReturn(true);

        RealtimeNodeDrainService.NodeDrainResult result = fixture.service.startDrain("node-a", 5, 250L);

        assertEquals(5, result.targetedSessions());
        assertEquals(12, result.remainingSessions());
        ArgumentCaptor<RoomPartitionControlCommand> captor = forClass(RoomPartitionControlCommand.class);
        verify(fixture.publisher).publish(captor.capture());
        assertEquals(5, captor.getValue().limit());
    }

    @Test
    void startDrain_returnsPublishFailedWhenNodeControlHasNoReceivers() {
        Fixture fixture = new Fixture(List.of(
                node("node-a", false, Set.of(0, 2), 12),
                node("node-b", false, Set.of(0, 1, 2, 3), 0)
        ));
        when(fixture.publisher.publish(any())).thenReturn(false);

        RealtimeNodeDrainService.NodeDrainResult result = fixture.service.startDrain("node-a", 50, 250L);

        assertEquals("publish_failed", result.status());
        assertFalse(result.reconnectPublished());
        assertEquals(12, result.remainingSessions());
    }

    @Test
    void startDrain_completesWithoutReconnectWhenNoSessionsRemain() {
        Fixture fixture = new Fixture(List.of(
                node("node-a", false, Set.of(0, 2), 0),
                node("node-b", false, Set.of(0, 1, 2, 3), 0)
        ));

        RealtimeNodeDrainService.NodeDrainResult result = fixture.service.startDrain("node-a", null, null);

        assertEquals("complete", result.status());
        assertFalse(result.reconnectPublished());
        assertEquals(0, result.remainingSessions());
        verify(fixture.publisher, never()).publish(any());
    }

    private static RealtimeNode node(String nodeId, boolean draining, Set<Integer> subscribedPartitions, int openSessions) {
        Instant now = Instant.now();
        return new RealtimeNode(
                nodeId,
                "realtime",
                "ws://" + nodeId + ":8080",
                draining,
                now,
                now.plusSeconds(30),
                subscribedPartitions,
                openSessions
        );
    }

    private static class Fixture {
        private final TestRegistry registry;
        private final RoomPartitionControlPublisher publisher = mock(RoomPartitionControlPublisher.class);
        private final RealtimeNodeDrainService service;

        Fixture(List<RealtimeNode> nodes) {
            this.registry = new TestRegistry(nodes);
            RoomPartitionAssignmentProperties assignmentProperties = new RoomPartitionAssignmentProperties();
            assignmentProperties.setNodeDrainEnabled(true);
            assignmentProperties.setNodeDrainReadinessTimeoutMs(1);
            RoomPartitionProperties partitionProperties = new RoomPartitionProperties(
                    true,
                    4,
                    Set.of(0, 1, 2, 3),
                    RoomScaleTier.CRITICAL,
                    16
            );
            RoomPartitionAssignmentService assignmentService = new RoomPartitionAssignmentService(registry);
            this.service = new RealtimeNodeDrainService(
                    registry,
                    publisher,
                    assignmentProperties,
                    assignmentService,
                    partitionProperties
            );
        }
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
            return nodes.stream()
                    .map(node -> new RealtimeNode(
                            node.nodeId(),
                            node.role(),
                            node.wsUrl(),
                            node.draining() || draining.contains(node.nodeId()),
                            node.reportedAt(),
                            node.expiresAt(),
                            node.subscribedPartitions(),
                            node.openSessions()
                    ))
                    .toList();
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
