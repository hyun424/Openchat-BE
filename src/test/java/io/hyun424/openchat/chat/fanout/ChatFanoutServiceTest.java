package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ChatFanoutServiceTest {

    private final ChatOutboundSender outboundSender = mock(ChatOutboundSender.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RedisHealthState redisHealthState = mock(RedisHealthState.class);
    private final ChatFanoutService fanoutService =
            new ChatFanoutService(outboundSender, redisTemplate, redisHealthState);

    @AfterEach
    void tearDown() {
        fanoutService.shutdown();
    }

    @Test
    @DisplayName("같은 messageId가 두 번 들어오면 현재 인스턴스에서는 한 번만 전송한다")
    void fanout_sameMessageId_sendsOnce() {
        when(redisHealthState.isUp()).thenReturn(false);
        ChatMessageDto message = message("message-1");

        fanoutService.fanout(message);
        fanoutService.fanout(message);

        verify(outboundSender, times(1)).send(message);
    }

    @Test
    @DisplayName("Redis dedupe에서 이미 처리된 메시지면 WebSocket 전송을 생략한다")
    @SuppressWarnings("unchecked")
    void fanout_redisDuplicate_skipsSend() {
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisHealthState.isUp()).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("dedupe:chat:message-2"), eq("1"), any(Duration.class)))
                .thenReturn(false);

        fanoutService.fanout(message("message-2"));

        verify(outboundSender, never()).send(any());
    }

    private ChatMessageDto message(String messageId) {
        return ChatMessageDto.builder()
                .messageId(messageId)
                .roomId(1L)
                .senderId("user1")
                .senderName("tester")
                .message("hello")
                .createdAt(System.currentTimeMillis())
                .build();
    }
}
