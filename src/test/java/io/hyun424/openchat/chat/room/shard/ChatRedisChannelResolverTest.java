package io.hyun424.openchat.chat.room.shard;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
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
        ChatRedisChannelResolver resolver = new ChatRedisChannelResolver(properties, shardResolver);

        ChatRedisChannelResolver.ResolvedChannel channel = resolver.publishChannel(message(12L));

        assertEquals("chat:room:12", channel.channel());
        assertEquals("legacy", channel.mode());
    }

    @Test
    void publishChannel_shardMode() {
        RoomShardProperties properties = properties(true, 4, Set.of(1), true);
        RoomShardResolver shardResolver = mock(RoomShardResolver.class);
        when(shardResolver.resolveShardId(12L)).thenReturn(3);
        ChatRedisChannelResolver resolver = new ChatRedisChannelResolver(properties, shardResolver);

        ChatRedisChannelResolver.ResolvedChannel channel = resolver.publishChannel(message(12L));

        assertEquals("chat:room-shard:3", channel.channel());
        assertEquals("shard", channel.mode());
    }

    @Test
    void subscribePatterns_ownedShardsAndLegacy() {
        RoomShardProperties properties = properties(true, 4, Set.of(2, 0), true);
        ChatRedisChannelResolver resolver = new ChatRedisChannelResolver(properties, mock(RoomShardResolver.class));

        assertEquals(
                java.util.List.of("chat:room-shard:0", "chat:room-shard:2", "chat:room:*"),
                resolver.subscribePatterns()
        );
    }

    @Test
    void modeForChannel() {
        ChatRedisChannelResolver resolver = new ChatRedisChannelResolver(
                properties(true, 2, Set.of(0), true),
                mock(RoomShardResolver.class));

        assertEquals("shard", resolver.modeForChannel("chat:room-shard:1"));
        assertEquals("legacy", resolver.modeForChannel("chat:room:99"));
        assertEquals("unknown", resolver.modeForChannel("other"));
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
