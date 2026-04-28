package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ChatFanoutService {

    private final ChatOutboundSender outboundSender;
    private final StringRedisTemplate redisTemplate;
    private final RedisHealthState redisHealthState;
    private final ScheduledExecutorService cleanerExecutor;

    @Value("${app.instance-id:local}")
    private String instanceId;

    // In-memory dedupe cache (fallback when Redis is down)
    private final ConcurrentHashMap<String, Long> dedupeCache = new ConcurrentHashMap<>();
    private static final long DEDUPE_TTL_MS = 60_000; // 1 minute
    private static final Duration REDIS_DEDUPE_TTL = Duration.ofSeconds(60);

    public ChatFanoutService(ChatOutboundSender outboundSender,
                             StringRedisTemplate redisTemplate,
                             RedisHealthState redisHealthState) {
        this.outboundSender = outboundSender;
        this.redisTemplate = redisTemplate;
        this.redisHealthState = redisHealthState;
        this.cleanerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dedupe-cleaner");
            t.setDaemon(true);
            return t;
        });
        startCacheCleaner();
    }

    /**
     * Fan-out message to local WebSocket sessions.
     * Uses Redis SETNX for distributed dedupe (fallback to in-memory when Redis is down).
     */
    public void fanout(ChatMessageDto message) {
        String messageId = message.getMessageId();

        if (isDuplicate(messageId)) {
            log.debug("[DEDUPE][{}] messageId={} - already processed", instanceId, messageId);
            return;
        }

        outboundSender.send(message);

        log.info("[FANOUT][{}] roomId={} messageId={}",
                instanceId, message.getRoomId(), message.getMessageId());
    }

    private boolean isDuplicate(String messageId) {
        // L1: In-memory dedupe first (nanosecond-level, no I/O)
        Long previous = dedupeCache.putIfAbsent(messageId, System.currentTimeMillis());
        if (previous != null) {
            return true; // already processed locally
        }

        // L2: Redis distributed dedupe (only needed for multi-instance coordination)
        if (redisHealthState.isUp()) {
            try {
                String key = "dedupe:chat:" + messageId;
                Boolean wasAbsent = redisTemplate.opsForValue()
                        .setIfAbsent(key, "1", REDIS_DEDUPE_TTL);
                if (Boolean.FALSE.equals(wasAbsent)) {
                    return true; // already processed by another instance
                }
            } catch (Exception e) {
                redisHealthState.markDown();
                log.warn("[DEDUPE][{}] Redis dedupe failed, in-memory only", instanceId);
            }
        }

        return false;
    }

    private void startCacheCleaner() {
        cleanerExecutor.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            int removed = 0;
            for (var entry : dedupeCache.entrySet()) {
                if (now - entry.getValue() > DEDUPE_TTL_MS) {
                    dedupeCache.remove(entry.getKey());
                    removed++;
                }
            }
            if (removed > 0) {
                log.debug("[DEDUPE CLEANUP][{}] removed {} expired entries", instanceId, removed);
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void shutdown() {
        log.info("[FANOUT][{}] Shutting down dedupe cleaner", instanceId);
        cleanerExecutor.shutdown();
        try {
            if (!cleanerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
