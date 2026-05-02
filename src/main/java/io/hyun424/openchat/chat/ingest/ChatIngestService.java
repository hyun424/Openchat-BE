package io.hyun424.openchat.chat.ingest;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.message.service.MessageService;
import io.hyun424.openchat.chat.publish.ChatMessagePublisher;
import io.hyun424.openchat.chat.publish.PublishRetryBuffer;
import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
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
 * 모든 채팅 메시지가 처음 들어오는 단일 진입점.
 * 메시지는 반드시 DB에 먼저 저장한 뒤 publish한다. 실시간 전송보다 영속성을 우선해야
 * "사용자가 본 메시지가 새로고침 후 사라지는" 상황을 막을 수 있기 때문이다.
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
    private final RoomTrafficMonitor roomTrafficMonitor;

    private final ConcurrentHashMap<String, Long> clientMessageIdCache = new ConcurrentHashMap<>();
    private static final long CLIENT_MSG_CACHE_TTL_MS = 60_000;
    private final ScheduledExecutorService cacheCleaner;

    @Value("${app.instance-id:local}")
    private String instanceId;

    public ChatIngestService(ChatMessagePublisher publisher,
                             PublishRetryBuffer retryBuffer,
                             StringRedisTemplate redisTemplate,
                             MessageService messageService,
                             RoomService roomService,
                             RedisHealthState redisHealthState,
                             RoomTrafficMonitor roomTrafficMonitor) {
        this.publisher = publisher;
        this.retryBuffer = retryBuffer;
        this.redisTemplate = redisTemplate;
        this.messageService = messageService;
        this.roomService = roomService;
        this.redisHealthState = redisHealthState;
        this.roomTrafficMonitor = roomTrafficMonitor;
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
        String normalizedClientMessageId = normalizeClientMessageId(clientMessageId);

        if (isDuplicateClientMessage(roomId, senderId, normalizedClientMessageId)) {
            return;
        }

        roomTrafficMonitor.recordInboundMessage(roomId);

        String messageId = UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();

        log.debug("[INGEST START][{}] roomId={} senderId={} clientMessageId={}",
                instanceId, roomId, senderId, clientMessageId);

        Message saved = saveMessageFirst(
                roomId, senderId, nickname, content, normalizedClientMessageId, messageId, createdAt);
        rememberClientMessageId(roomId, senderId, normalizedClientMessageId);
        updateRoomLastMessage(roomId, createdAt, content, nickname);

        ChatMessageDto dto = buildMessageDto(saved, normalizedClientMessageId);
        publishOrBuffer(dto, roomId, messageId);
        updateHotChatBucket(roomId, messageId);

        log.debug("[INGEST DONE][{}] roomId={} messageId={} latency={}ms",
                instanceId, roomId, messageId, System.currentTimeMillis() - createdAt);
    }

    private String normalizeClientMessageId(String clientMessageId) {
        return StringUtils.hasText(clientMessageId) ? clientMessageId.trim() : null;
    }

    private boolean isDuplicateClientMessage(Long roomId, String senderId, String clientMessageId) {
        if (clientMessageId == null) {
            return false;
        }

        String cacheKey = clientMessageCacheKey(roomId, senderId, clientMessageId);
        if (clientMessageIdCache.containsKey(cacheKey)) {
            log.info("[INGEST DEDUPE CACHE][{}] roomId={} senderId={} clientMessageId={}",
                    instanceId, roomId, senderId, clientMessageId);
            return true;
        }

        Message existing = messageService.findByClientMessageId(roomId, senderId, clientMessageId);
        if (existing == null) {
            return false;
        }

        rememberClientMessageId(roomId, senderId, clientMessageId);
        log.info("[INGEST DEDUPE][{}] roomId={} senderId={} clientMessageId={} messageId={}",
                instanceId, roomId, senderId, clientMessageId, existing.getMessageId());
        return true;
    }

    private Message saveMessageFirst(Long roomId,
                                     String senderId,
                                     String nickname,
                                     String content,
                                     String clientMessageId,
                                     String messageId,
                                     long createdAt) {
        try {
            Message saved = messageService.save(
                    roomId, senderId, nickname, content, clientMessageId, messageId, createdAt);
            log.debug("[DB SAVED][{}] messageId={} dbId={}", instanceId, messageId, saved.getId());
            return saved;
        } catch (Exception e) {
            log.error("[DB SAVE FAIL][{}] roomId={} senderId={} messageId={}",
                    instanceId, roomId, senderId, messageId, e);
            throw e;
        }
    }

    private void rememberClientMessageId(Long roomId, String senderId, String clientMessageId) {
        if (clientMessageId == null) {
            return;
        }
        clientMessageIdCache.put(
                clientMessageCacheKey(roomId, senderId, clientMessageId),
                System.currentTimeMillis());
    }

    private String clientMessageCacheKey(Long roomId, String senderId, String clientMessageId) {
        return roomId + ":" + senderId + ":" + clientMessageId;
    }

    private void updateRoomLastMessage(Long roomId, long createdAt, String content, String nickname) {
        roomService.updateLastMessage(roomId, createdAt, content, nickname);
    }

    private ChatMessageDto buildMessageDto(Message saved, String normalizedClientMessageId) {
        ChatMessageDto dto = ChatMessageDto.from(saved);
        dto.setClientMessageId(normalizedClientMessageId);
        return dto;
    }

    private void publishOrBuffer(ChatMessageDto dto, Long roomId, String messageId) {
        try {
            publisher.publish(dto);
            log.debug("[PUBLISH OK][{}] roomId={} messageId={}", instanceId, roomId, messageId);
        } catch (Exception e) {
            log.error("[PUBLISH FAIL][{}] roomId={} messageId={} - enqueuing for retry",
                    instanceId, roomId, messageId, e);
            retryBuffer.enqueue(dto);
        }
    }

    /**
     * HotChat은 채팅 전송의 핵심 경로가 아니다.
     * Redis가 흔들리더라도 메시지 저장과 전달을 막지 않도록 실패 시 Redis down 상태만 기록한다.
     */
    private void updateHotChatBucket(Long roomId, String messageId) {
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
