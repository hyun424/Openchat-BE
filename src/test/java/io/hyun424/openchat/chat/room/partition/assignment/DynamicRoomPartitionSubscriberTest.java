package io.hyun424.openchat.chat.room.partition.assignment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.hyun424.openchat.chat.room.hot.RoomScaleTier;
import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import io.hyun424.openchat.chat.room.shard.ChatRedisChannelResolver;
import io.hyun424.openchat.infra.redis.config.RedisChatMessageDispatcher;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.Topic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

class DynamicRoomPartitionSubscriberTest {

    @Test
    void refreshSubscriptions_addsListenerAndPublishesReadinessImmediately() {
        Fixture fixture = new Fixture();
        when(fixture.assignmentService.ownedPartitions("node-a", 4)).thenReturn(List.of(1));
        when(fixture.channelResolver.partitionPattern(1)).thenReturn("chat:room-partition:*:1");

        fixture.subscriber.refreshSubscriptions();

        verify(fixture.container).addMessageListener(eq(fixture.listener), any(Topic.class));
        verify(fixture.heartbeatService, atLeastOnce()).publishHeartbeat();
        assertEquals(Set.of(1), fixture.subscriptionState.subscribedPartitions());
    }

    @Test
    void refreshSubscriptions_doesNotUnsubscribeUntilReplacementOwnerIsReady() {
        Fixture fixture = new Fixture();
        when(fixture.assignmentService.ownedPartitions("node-a", 4)).thenReturn(List.of(1));
        when(fixture.channelResolver.partitionPattern(1)).thenReturn("chat:room-partition:*:1");
        fixture.subscriber.refreshSubscriptions();

        when(fixture.assignmentService.ownedPartitions("node-a", 4)).thenReturn(List.of());
        when(fixture.assignmentService.activeNodes()).thenReturn(List.of(node("node-a"), node("node-b")));
        when(fixture.roomSessionRegistry.openSessionCountForPartition(1)).thenReturn(0);
        when(fixture.assignmentService.assignmentFor(1, 4)).thenReturn(Optional.of(
                new RoomPartitionAssignment(1, "node-b", "ws://node-b:8080", "v1", false, "owner_not_ready", List.of())
        ));

        fixture.subscriber.refreshSubscriptions();

        verify(fixture.container, never()).removeMessageListener(eq(fixture.listener), any(Topic.class));
        assertEquals(Set.of(1), fixture.subscriptionState.subscribedPartitions());
    }

    @Test
    void refreshSubscriptions_unsubscribesWhenSessionsAreEmptyAndReplacementOwnerIsReady() {
        Fixture fixture = new Fixture();
        when(fixture.assignmentService.ownedPartitions("node-a", 4)).thenReturn(List.of(1));
        when(fixture.channelResolver.partitionPattern(1)).thenReturn("chat:room-partition:*:1");
        fixture.subscriber.refreshSubscriptions();

        when(fixture.assignmentService.ownedPartitions("node-a", 4)).thenReturn(List.of());
        when(fixture.assignmentService.activeNodes()).thenReturn(List.of(node("node-a"), node("node-b")));
        when(fixture.roomSessionRegistry.openSessionCountForPartition(1)).thenReturn(0);
        when(fixture.assignmentService.assignmentFor(1, 4)).thenReturn(Optional.of(
                new RoomPartitionAssignment(1, "node-b", "ws://node-b:8080", "v1", true, "ready", List.of())
        ));

        fixture.subscriber.refreshSubscriptions();

        verify(fixture.container).removeMessageListener(eq(fixture.listener), any(Topic.class));
        assertEquals(Set.of(), fixture.subscriptionState.subscribedPartitions());
    }

    private static RealtimeNode node(String nodeId) {
        return new RealtimeNode(nodeId, "realtime", "ws://" + nodeId + ":8080", false, Instant.now(), Instant.now().plusSeconds(30), Set.of());
    }

    private static class Fixture {
        private final RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        private final RedisChatMessageDispatcher dispatcher = mock(RedisChatMessageDispatcher.class);
        private final ChatRedisChannelResolver channelResolver = mock(ChatRedisChannelResolver.class);
        private final RoomPartitionAssignmentService assignmentService = mock(RoomPartitionAssignmentService.class);
        private final RoomPartitionProperties partitionProperties = new RoomPartitionProperties(
                true,
                4,
                Set.of(0, 1, 2, 3),
                RoomScaleTier.CRITICAL,
                16
        );
        private final RoomPartitionAssignmentProperties assignmentProperties = new RoomPartitionAssignmentProperties();
        private final RoomSessionRegistry roomSessionRegistry = mock(RoomSessionRegistry.class);
        private final RealtimeNodeSubscriptionState subscriptionState = new RealtimeNodeSubscriptionState();
        private final RealtimeNodeHeartbeatService heartbeatService = mock(RealtimeNodeHeartbeatService.class);
        private final MessageListener listener = mock(MessageListener.class);
        private final DynamicRoomPartitionSubscriber subscriber;

        private Fixture() {
            when(dispatcher.listener()).thenReturn(listener);
            subscriber = new DynamicRoomPartitionSubscriber(
                    container,
                    dispatcher,
                    channelResolver,
                    assignmentService,
                    partitionProperties,
                    assignmentProperties,
                    roomSessionRegistry,
                    subscriptionState,
                    heartbeatService,
                    "node-a"
            );
        }
    }
}
