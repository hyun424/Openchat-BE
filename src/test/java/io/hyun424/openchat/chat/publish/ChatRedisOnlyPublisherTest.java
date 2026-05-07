package io.hyun424.openchat.chat.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.shard.ChatRedisChannelResolver;
import io.hyun424.openchat.chat.room.shard.RoomShardMetrics;
import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatRedisOnlyPublisherTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ObjectMapper objectMapper;
    @Mock private RedisHealthState redisHealthState;
    @Mock private ChatPipelineMetrics chatPipelineMetrics;
    @Mock private ChatRedisChannelResolver channelResolver;
    @Mock private RoomShardMetrics roomShardMetrics;
    @Mock private RoomPartitionMetrics roomPartitionMetrics;

    @Test
    @DisplayName("Redis-only publisher도 shard channel resolver가 반환한 channel로 publish한다")
    void publish_usesResolvedRedisChannel() throws Exception {
        ChatRedisOnlyPublisher publisher = new ChatRedisOnlyPublisher(
                redisTemplate,
                objectMapper,
                redisHealthState,
                chatPipelineMetrics,
                channelResolver,
                roomShardMetrics,
                roomPartitionMetrics
        );
        ChatMessageDto message = ChatMessageDto.builder()
                .roomId(7L)
                .messageId("message-7")
                .senderId("user-1")
                .senderName("tester")
                .message("hello")
                .createdAt(System.currentTimeMillis())
                .build();

        when(redisHealthState.isUp()).thenReturn(true);
        when(channelResolver.publishChannels(message))
                .thenReturn(List.of(new ChatRedisChannelResolver.ResolvedChannel("chat:room-shard:1", "shard")));
        when(objectMapper.writeValueAsString(message)).thenReturn("{}");

        publisher.publish(message);

        verify(redisTemplate).convertAndSend(eq("chat:room-shard:1"), eq("{}"));
        verify(roomShardMetrics).recordPublish("shard");
        verify(roomPartitionMetrics).recordPublish("shard");
    }
}
