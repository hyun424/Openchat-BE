package io.hyun424.openchat.chat.room.shard;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.room.hot.RoomScaleTier;
import io.hyun424.openchat.chat.room.partition.assignment.RoomPartitionAssignmentProperties;
import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionRoutingService;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatRedisChannelResolverTest {

    @Test
    void publishChannel_legacyMode() {
        RoomShardProperties properties = properties(false, 4, Set.of(0), true);
        RoomShardResolver shardResolver = mock(RoomShardResolver.class);
        ChatRedisChannelResolver resolver = resolver(properties, shardResolver, partitionProperties(false, 1, Set.of(0)));

        ChatRedisChannelResolver.ResolvedChannel channel = resolver.publishChannel(message(12L));

        assertEquals("chat:room:12", channel.channel());
        assertEquals("legacy", channel.mode());
    }

    @Test
    void publishChannel_shardMode() {
        RoomShardProperties properties = properties(true, 4, Set.of(1), true);
        RoomShardResolver shardResolver = mock(RoomShardResolver.class);
        when(shardResolver.resolveShardId(12L)).thenReturn(3);
        ChatRedisChannelResolver resolver = resolver(properties, shardResolver, partitionProperties(false, 1, Set.of(0)));

        ChatRedisChannelResolver.ResolvedChannel channel = resolver.publishChannel(message(12L));

        assertEquals("chat:room-shard:3", channel.channel());
        assertEquals("shard", channel.mode());
    }

    @Test
    void subscribePatterns_ownedShardsAndLegacy() {
        RoomShardProperties properties = properties(true, 4, Set.of(2, 0), true);
        ChatRedisChannelResolver resolver = resolver(properties, mock(RoomShardResolver.class), partitionProperties(false, 1, Set.of(0)));

        assertEquals(
                java.util.List.of("chat:room-shard:0", "chat:room-shard:2", "chat:room:*"),
                resolver.subscribePatterns()
        );
    }

    @Test
    void modeForChannel() {
        ChatRedisChannelResolver resolver = resolver(
                properties(true, 2, Set.of(0), true),
                mock(RoomShardResolver.class),
                partitionProperties(false, 1, Set.of(0)));

        assertEquals("shard", resolver.modeForChannel("chat:room-shard:1"));
        assertEquals("partition", resolver.modeForChannel("chat:room-partition:99:1"));
        assertEquals("legacy", resolver.modeForChannel("chat:room:99"));
        assertEquals("unknown", resolver.modeForChannel("other"));
    }

    @Test
    void publishChannels_partitionMode() {
        RoomShardProperties shardProperties = properties(true, 4, Set.of(0), true);
        RoomPartitionRoutingService partitionRoutingService = mock(RoomPartitionRoutingService.class);
        when(partitionRoutingService.publishPartitions(12L)).thenReturn(java.util.List.of(0, 1));
        ChatRedisChannelResolver resolver = new ChatRedisChannelResolver(
                shardProperties,
                mock(RoomShardResolver.class),
                partitionProperties(true, 2, Set.of(0, 1)),
                partitionRoutingService,
                new RoomPartitionAssignmentProperties());

        java.util.List<ChatRedisChannelResolver.ResolvedChannel> channels = resolver.publishChannels(message(12L));

        assertEquals(2, channels.size());
        assertEquals("chat:room-partition:12:0", channels.get(0).channel());
        assertEquals("chat:room-partition:12:1", channels.get(1).channel());
        assertEquals("partition", channels.get(0).mode());
    }

    @Test
    void subscribePatterns_includesOwnedPartitionPatterns() {
        ChatRedisChannelResolver resolver = resolver(
                properties(true, 2, Set.of(0), false),
                mock(RoomShardResolver.class),
                partitionProperties(true, 3, Set.of(1, 2)));

        assertEquals(
                java.util.List.of("chat:room-partition:*:1", "chat:room-partition:*:2", "chat:room-shard:0"),
                resolver.subscribePatterns()
        );
    }

    private ChatRedisChannelResolver resolver(RoomShardProperties properties,
                                              RoomShardResolver shardResolver,
                                              RoomPartitionProperties partitionProperties) {
        return new ChatRedisChannelResolver(
                properties,
                shardResolver,
                partitionProperties,
                mock(RoomPartitionRoutingService.class),
                new RoomPartitionAssignmentProperties()
        );
    }

    private RoomPartitionProperties partitionProperties(boolean enabled,
                                                        int partitionCount,
                                                        Set<Integer> ownedPartitions) {
        return new RoomPartitionProperties(
                enabled,
                partitionCount,
                ownedPartitions,
                RoomScaleTier.CRITICAL,
                16
        );
    }

    private RoomShardProperties properties(boolean enabled,
                                           int shardCount,
                                           Set<Integer> ownedShards,
                                           boolean legacySubscribeEnabled) {
        return new RoomShardProperties(
                enabled,
                shardCount,
                ownedShards,
                legacySubscribeEnabled,
                10_000,
                0.8,
                500,
                5_000,
                30_000,
                180_000
        );
    }

    private ChatMessageDto message(Long roomId) {
        return ChatMessageDto.builder()
                .roomId(roomId)
                .messageId("message")
                .build();
    }
}
