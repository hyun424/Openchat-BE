package io.hyun424.openchat.chat.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis-only publisher: Active when Redis is configured but Kafka is not.
 * Falls back to this when ChatCompositePublisher is not available.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "spring.data.redis.host")
@ConditionalOnMissingBean(ChatCompositePublisher.class)
public class ChatRedisOnlyPublisher implements ChatMessagePublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.instance-id:local}")
    private String instanceId;

    public ChatRedisOnlyPublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        log.info("ChatRedisOnlyPublisher initialized (Redis only, no Kafka)");
    }

    @Override
    public void publish(ChatMessageDto message) {
        String channel = "chat:room:" + message.getRoomId();
        try {
            String payload = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(channel, payload);
            log.info("[REDIS PUB][{}] channel={} roomId={} messageId={}",
                    instanceId, channel, message.getRoomId(), message.getMessageId());
        } catch (Exception e) {
            log.error("[REDIS PUB FAIL][{}] roomId={} messageId={}",
                    instanceId, message.getRoomId(), message.getMessageId(), e);
            // No Kafka fallback - message may be lost for real-time delivery
            // But it's already saved in DB, so polling can recover it
        }
    }
}
