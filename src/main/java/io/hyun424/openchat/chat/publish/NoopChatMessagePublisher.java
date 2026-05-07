package io.hyun424.openchat.chat.publish;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Fallback publisher when neither Redis nor Kafka is configured.
 * Only activated when no other ChatMessagePublisher bean exists.
 */
@Slf4j
@Service
@ConditionalOnMissingBean({ChatCompositePublisher.class, ChatRedisOnlyPublisher.class})
public class NoopChatMessagePublisher implements ChatMessagePublisher {

    @PostConstruct
    public void warnNoPublisher() {
        log.warn("============================================================");
        log.warn(" WARNING: NoopChatMessagePublisher is active!");
        log.warn(" Messages will NOT be delivered to other users in real-time.");
        log.warn(" Configure Redis (spring.data.redis.host) for real-time delivery.");
        log.warn(" Configure Kafka (spring.kafka.bootstrap-servers) for durability.");
        log.warn("============================================================");
    }

    @Override
    public CompletableFuture<Void> publish(ChatMessageDto message) {
        log.warn("[NOOP PUB] roomId={} messageId={} - NOT delivered (no Redis/Kafka configured)",
                message.getRoomId(), message.getMessageId());
        return CompletableFuture.completedFuture(null);
    }
}
