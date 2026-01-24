package io.hyun424.openchat.chat.ingest;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.message.service.MessageService;
import io.hyun424.openchat.chat.publish.ChatMessagePublisher;
import io.hyun424.openchat.chat.room.service.RoomService;
import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import io.hyun424.openchat.infra.time.BucketKeyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Single entry point for all incoming chat messages.
 * Responsibilities:
 * 1. Generate server-side messageId (idempotency key)
 * 2. Persist to DB (durability)
 * 3. Publish to message bus (fan-out)
 * 4. Update HotChat metrics (non-critical side effect)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatIngestService {

    private final ChatMessagePublisher publisher;
    private final StringRedisTemplate redisTemplate;
    private final MessageService messageService;
    private final RoomService roomService;
    private final RedisHealthState redisHealthState;

    @Value("${app.instance-id:local}")
    private String instanceId;

    public void ingest(
            Long roomId,
            String senderId,
            String nickname,
            String content,
            String clientMessageId
    ) {
        // 1. Generate server-side messageId (prevents duplicate processing)
        String messageId = UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();

        log.info("[INGEST START][{}] roomId={} senderId={} clientMessageId={}",
                instanceId, roomId, senderId, clientMessageId);

        // 2. DB persistence (durability first)
        Message saved;
        try {
            saved = messageService.save(
                    roomId,
                    senderId,
                    nickname,
                    content,
                    messageId,
                    createdAt
            );
            log.debug("[DB SAVED][{}] messageId={} dbId={}", instanceId, messageId, saved.getId());

            // Update room's last message info for "My Chats" feature
            roomService.updateLastMessage(roomId, createdAt, content, nickname);
        } catch (Exception e) {
            log.error("[DB SAVE FAIL][{}] roomId={} senderId={} messageId={}",
                    instanceId, roomId, senderId, messageId, e);
            throw e; // DB fail = critical, abort entire flow
        }

        // 3. Build DTO from persisted entity (ensures consistency)
        ChatMessageDto dto = ChatMessageDto.from(saved);
        dto.setClientMessageId(clientMessageId);

        // 4. Publish to message bus (Redis Pub/Sub or Kafka)
        try {
            publisher.publish(dto);
            log.info("[PUBLISH OK][{}] roomId={} messageId={}", instanceId, roomId, messageId);
        } catch (Exception e) {
            // TODO: Consider local buffer or DLQ for retry
            log.error("[PUBLISH FAIL][{}] roomId={} messageId={} - message saved but not delivered",
                    instanceId, roomId, messageId, e);
            // Don't throw - message is persisted, can be recovered via polling
        }

        // 5. HotChat bucket update (non-critical, fire-and-forget)
        updateHotChatBucket(roomId, messageId);

        log.info("[INGEST DONE][{}] roomId={} messageId={} latency={}ms",
                instanceId, roomId, messageId, System.currentTimeMillis() - createdAt);
    }

    private void updateHotChatBucket(Long roomId, String messageId) {
        // Skip if Redis is down - HotChat is non-critical
        if (!redisHealthState.isUp()) {
            return;
        }

        try {
            String bucketKey = BucketKeyUtil.currentBucketKey();
            redisTemplate.opsForZSet()
                    .incrementScore(bucketKey, "room:" + roomId, 1);
            redisTemplate.expire(bucketKey, Duration.ofMinutes(7));
        } catch (Exception e) {
            redisHealthState.markDown();
            log.warn("[HOTCHAT FAIL][{}] roomId={} messageId={} - marking Redis down",
                    instanceId, roomId, messageId);
        }
    }
}
