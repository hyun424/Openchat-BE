package io.hyun424.openchat.chat.ingest;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.message.service.MessageService;
import io.hyun424.openchat.chat.publish.ChatMessagePublisher;
import io.hyun424.openchat.chat.publish.PublishRetryBuffer;
import io.hyun424.openchat.chat.room.service.RoomService;
import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import io.hyun424.openchat.infra.time.BucketKeyUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Single entry point for all incoming chat messages.
 * Responsibilities:
 * 1. Generate server-side messageId (idempotency key)
 * 2. Persist to DB (durability)
 * 3. Publish to message bus (fan-out)
 * 4. Update HotChat metrics (non-critical side effect)
 */
@Service
@Slf4j
public class ChatIngestService {

    private final ChatMessagePublisher publisher;
    private final PublishRetryBuffer retryBuffer;
    private final StringRedisTemplate redisTemplate;
    private final MessageService messageService;
    private final RoomService roomService;
    private final RedisHealthState redisHealthState;

    // In-memory cache: "roomId:senderId:clientMessageId" → timestamp
    private final ConcurrentHashMap<String, Long> clientMessageIdCache = new ConcurrentHashMap<>();
    private static final long CLIENT_MSG_CACHE_TTL_MS = 60_000; // 1 minute
    private final ScheduledExecutorService cacheCleaner;

    @Value("${app.instance-id:local}")
    private String instanceId;

    public ChatIngestService(ChatMessagePublisher publisher,
                             PublishRetryBuffer retryBuffer,
                             StringRedisTemplate redisTemplate,
                             MessageService messageService,
                             RoomService roomService,
                             RedisHealthState redisHealthState) {
        this.publisher = publisher;
        this.retryBuffer = retryBuffer;
        this.redisTemplate = redisTemplate;
        this.messageService = messageService;
        this.roomService = roomService;
        this.redisHealthState = redisHealthState;
        this.cacheCleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ingest-cache-cleaner");
            t.setDaemon(true);
            return t;
        });
        startCacheCleaner();
    }

    private void startCacheCleaner() {
        cacheCleaner.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            int removed = 0;
            for (var entry : clientMessageIdCache.entrySet()) {
                if (now - entry.getValue() > CLIENT_MSG_CACHE_TTL_MS) {
                    clientMessageIdCache.remove(entry.getKey());
                    removed++;
                }
            }
            if (removed > 0) {
                log.debug("[INGEST CACHE CLEANUP] removed {} expired entries", removed);
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void shutdown() {
        cacheCleaner.shutdown();
    }

    public void ingest(
            Long roomId,
            String senderId,
            String nickname,
            String content,
            String clientMessageId
    ) {
        String normalizedClientMessageId = StringUtils.hasText(clientMessageId) ? clientMessageId.trim() : null;

        // Idempotency: L1 in-memory cache check (avoids DB query for common case)
        if (normalizedClientMessageId != null) {
            String cacheKey = roomId + ":" + senderId + ":" + normalizedClientMessageId;
            if (clientMessageIdCache.containsKey(cacheKey)) {
                log.info("[INGEST DEDUPE CACHE][{}] roomId={} senderId={} clientMessageId={}",
                        instanceId, roomId, senderId, normalizedClientMessageId);
                return;
            }
        }

        // Idempotency: L2 DB check (catches duplicates after cache eviction or restart)
        Message existing = messageService.findByClientMessageId(roomId, senderId, normalizedClientMessageId);
        if (existing != null) {
            // Populate cache for future fast-path lookups
            if (normalizedClientMessageId != null) {
                clientMessageIdCache.put(
                        roomId + ":" + senderId + ":" + normalizedClientMessageId,
                        System.currentTimeMillis());
            }
            log.info("[INGEST DEDUPE][{}] roomId={} senderId={} clientMessageId={} messageId={}",
                    instanceId, roomId, senderId, normalizedClientMessageId, existing.getMessageId());
            return;
        }

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
                    normalizedClientMessageId,
                    messageId,
                    createdAt
            );
            log.debug("[DB SAVED][{}] messageId={} dbId={}", instanceId, messageId, saved.getId());

            // Populate in-memory cache after successful save
            if (normalizedClientMessageId != null) {
                clientMessageIdCache.put(
                        roomId + ":" + senderId + ":" + normalizedClientMessageId,
                        System.currentTimeMillis());
            }

            // Update room's last message info for "My Chats" feature
            roomService.updateLastMessage(roomId, createdAt, content, nickname);
        } catch (Exception e) {
            log.error("[DB SAVE FAIL][{}] roomId={} senderId={} messageId={}",
                    instanceId, roomId, senderId, messageId, e);
            throw e; // DB fail = critical, abort entire flow
        }

        // 3. Build DTO from persisted entity (ensures consistency)
        ChatMessageDto dto = ChatMessageDto.from(saved);
        dto.setClientMessageId(normalizedClientMessageId);

        // 4. Publish to message bus (Redis Pub/Sub or Kafka)
        try {
            publisher.publish(dto);
            log.info("[PUBLISH OK][{}] roomId={} messageId={}", instanceId, roomId, messageId);
        } catch (Exception e) {
            log.error("[PUBLISH FAIL][{}] roomId={} messageId={} - enqueuing for retry",
                    instanceId, roomId, messageId, e);
            retryBuffer.enqueue(dto);
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
