package io.hyun424.openchat.chat.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.shard.ChatRedisChannelResolver;
import io.hyun424.openchat.chat.room.shard.RoomShardMetrics;
import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatCompositePublisherTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ObjectMapper objectMapper;
    @Mock private KafkaTemplate<String, ChatMessageDto> kafkaTemplate;
    @Mock private RedisHealthState redisHealthState;
    @Mock private ChatPipelineMetrics chatPipelineMetrics;
    @Mock private ChatRedisChannelResolver channelResolver;
    @Mock private RoomShardMetrics roomShardMetrics;
    @Mock private RoomPartitionMetrics roomPartitionMetrics;

    @Test
    @DisplayName("Kafka send 실패는 publish future 실패로 caller에 전달된다")
    void publish_kafkaAsyncFailure_completesFutureExceptionally() {
        // given
        ChatCompositePublisher publisher = new ChatCompositePublisher(
                redisTemplate,
                objectMapper,
                kafkaTemplate,
                redisHealthState,
                chatPipelineMetrics,
                channelResolver,
                roomShardMetrics,
                roomPartitionMetrics
        );
        ChatMessageDto message = ChatMessageDto.builder()
                .roomId(1L)
                .messageId("message-1")
                .senderId("user-1")
                .senderName("tester")
                .message("hello")
                .createdAt(System.currentTimeMillis())
                .build();

        when(redisHealthState.isUp()).thenReturn(false);
        when(kafkaTemplate.send(eq("chat-message"), eq("1"), eq(message)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka broker down")));

        // when & then
        assertThrows(CompletionException.class, () -> publisher.publish(message).join());
        verify(kafkaTemplate).send(eq("chat-message"), eq("1"), eq(message));
    }

    @Test
    @DisplayName("shard channel resolver가 반환한 Redis channel로 publish한다")
    void publish_usesResolvedRedisChannel() throws Exception {
        ChatCompositePublisher publisher = new ChatCompositePublisher(
                redisTemplate,
                objectMapper,
                kafkaTemplate,
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
        when(kafkaTemplate.send(eq("chat-message"), eq("7"), eq(message)))
                .thenAnswer(ignored -> CompletableFuture.completedFuture(sendResult()));

        assertDoesNotThrow(() -> publisher.publish(message).join());

        verify(redisTemplate).convertAndSend(eq("chat:room-shard:1"), eq("{}"));
        verify(roomShardMetrics).recordPublish("shard");
        verify(roomPartitionMetrics).recordPublish("shard");
    }

    @Test
    @DisplayName("partition channel이 여러 개면 Redis publish를 partition 수만큼 수행한다")
    void publish_partitionMode_publishesToEveryPartitionChannel() throws Exception {
        ChatCompositePublisher publisher = new ChatCompositePublisher(
                redisTemplate,
                objectMapper,
                kafkaTemplate,
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
                .thenReturn(List.of(
                        new ChatRedisChannelResolver.ResolvedChannel("chat:room-partition:7:0", "partition"),
                        new ChatRedisChannelResolver.ResolvedChannel("chat:room-partition:7:1", "partition")
                ));
        when(objectMapper.writeValueAsString(message)).thenReturn("{}");
        when(kafkaTemplate.send(eq("chat-message"), eq("7"), eq(message)))
                .thenAnswer(ignored -> CompletableFuture.completedFuture(sendResult()));

        assertDoesNotThrow(() -> publisher.publish(message).join());

        verify(redisTemplate).convertAndSend(eq("chat:room-partition:7:0"), eq("{}"));
        verify(redisTemplate).convertAndSend(eq("chat:room-partition:7:1"), eq("{}"));
        verify(roomPartitionMetrics, times(2)).recordPublish("partition");
    }
    private SendResult<String, ChatMessageDto> sendResult() {
        @SuppressWarnings("unchecked")
        SendResult<String, ChatMessageDto> sendResult = mock(SendResult.class);
        RecordMetadata metadata = mock(RecordMetadata.class);
        when(sendResult.getRecordMetadata()).thenReturn(metadata);
        when(metadata.partition()).thenReturn(0);
        when(metadata.offset()).thenReturn(1L);
        return sendResult;
    }

}
