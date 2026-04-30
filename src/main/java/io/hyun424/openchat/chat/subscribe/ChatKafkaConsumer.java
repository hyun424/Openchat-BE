package io.hyun424.openchat.chat.subscribe;

import io.hyun424.openchat.chat.fanout.ChatFanoutService;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/**
 * Kafka consumer for chat messages.
 * Always active when Kafka is configured.
 *
 * Fan-out strategy:
 * - Redis UP: Skip fan-out (Redis subscriber handles it)
 * - Redis DOWN: Handle fan-out as fallback
 *
 * Fanout 실패 시 ack하지 않아 DefaultErrorHandler가 재시도/DLQ 처리.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class ChatKafkaConsumer {

    private final ChatFanoutService fanoutService;
    private final RedisHealthState redisHealthState;

    @KafkaListener(topics = "chat-message")
    public void consume(ChatMessageDto message, Acknowledgment ack) {
        if (redisHealthState.isUp()) {
            // Redis is handling fan-out, just consume for durability
            log.debug("[KAFKA CONSUME] Redis UP, skip fanout. roomId={} messageId={}",
                    message.getRoomId(), message.getMessageId());
        } else {
            // Redis is down, Kafka handles fan-out as fallback
            log.info("[KAFKA FANOUT] Redis DOWN, handling fanout. roomId={} messageId={}",
                    message.getRoomId(), message.getMessageId());
            fanoutService.fanout(message);
        }
        // Only ack after successful processing; exceptions propagate to DefaultErrorHandler
        ack.acknowledge();
    }
}
