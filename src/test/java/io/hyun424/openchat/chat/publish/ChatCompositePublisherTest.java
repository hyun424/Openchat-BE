package io.hyun424.openchat.chat.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatCompositePublisherTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ObjectMapper objectMapper;
    @Mock private KafkaTemplate<String, ChatMessageDto> kafkaTemplate;
    @Mock private RedisHealthState redisHealthState;
    @Mock private ChatPipelineMetrics chatPipelineMetrics;

    @Test
    @DisplayName("수정 전 장애 재현: Kafka send 실패가 발생해도 publish()는 예외를 던지지 않는다")
    void publish_kafkaAsyncFailure_isNotPropagatedToCaller() {
        // given
        ChatCompositePublisher publisher = new ChatCompositePublisher(
                redisTemplate,
                objectMapper,
                kafkaTemplate,
                redisHealthState,
                chatPipelineMetrics
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
        assertDoesNotThrow(() -> publisher.publish(message));
        verify(kafkaTemplate).send(eq("chat-message"), eq("1"), eq(message));
    }
}
