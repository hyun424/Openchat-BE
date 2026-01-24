package io.hyun424.openchat.chat.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Default publisher: Redis for real-time, Kafka for durability.
 * Active when spring.kafka.bootstrap-servers is configured.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class ChatCompositePublisher implements ChatMessagePublisher {

    private static final String KAFKA_TOPIC = "chat-message";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, ChatMessageDto> kafkaTemplate;
    private final RedisHealthState redisHealthState;

    @Value("${app.instance-id:local}")
    private String instanceId;

    public ChatCompositePublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            KafkaTemplate<String, ChatMessageDto> kafkaTemplate,
            RedisHealthState redisHealthState
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.redisHealthState = redisHealthState;
        log.info("ChatCompositePublisher initialized (Redis + Kafka)");
    }

    @Override
    public void publish(ChatMessageDto message) {
        // 1. Redis Pub/Sub - real-time delivery (fire-and-forget)
        publishToRedis(message);

        // 2. Kafka - durability & ordering guarantee
        publishToKafka(message);
    }

    private void publishToRedis(ChatMessageDto message) {
        // Skip if Redis is known to be down
        if (!redisHealthState.isUp()) {
            log.debug("[REDIS PUB SKIP][{}] Redis down, roomId={}", instanceId, message.getRoomId());
            return;
        }

        String channel = "chat:room:" + message.getRoomId();
        try {
            String payload = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(channel, payload);
            log.debug("[REDIS PUB][{}] channel={} messageId={}",
                    instanceId, channel, message.getMessageId());
        } catch (Exception e) {
            // Mark Redis as down, Kafka will handle durability
            redisHealthState.markDown();
            log.warn("[REDIS PUB FAIL][{}] roomId={} messageId={} - marking Redis down",
                    instanceId, message.getRoomId(), message.getMessageId());
        }
    }

    private void publishToKafka(ChatMessageDto message) {
        String key = String.valueOf(message.getRoomId());
        kafkaTemplate.send(KAFKA_TOPIC, key, message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[KAFKA PUB FAIL][{}] roomId={} messageId={}",
                                instanceId, message.getRoomId(), message.getMessageId(), ex);
                    } else {
                        log.debug("[KAFKA PUB][{}] partition={} offset={} messageId={}",
                                instanceId,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset(),
                                message.getMessageId());
                    }
                });
    }
}
