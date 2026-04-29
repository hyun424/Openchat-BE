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

    private final ConcurrentHashMap<String, Long> dedupeCache = new ConcurrentHashMap<>();
    private static final long DEDUPE_TTL_MS = 60_000;
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
     * 메시지를 현재 인스턴스의 WebSocket 세션에 전달한다.
     * Redis와 Kafka를 함께 쓰면 같은 messageId가 두 경로로 들어올 수 있으므로 fan-out 직전에 중복을 막는다.
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
        if (hasProcessedLocally(messageId)) {
            return true;
        }
        return hasProcessedInCluster(messageId);
    }

    private boolean hasProcessedLocally(String messageId) {
        Long previous = dedupeCache.putIfAbsent(messageId, System.currentTimeMillis());
        return previous != null;
    }

    private boolean hasProcessedInCluster(String messageId) {
        if (!redisHealthState.isUp()) {
            return false;
        }

        try {
            String key = "dedupe:chat:" + messageId;
            Boolean wasAbsent = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", REDIS_DEDUPE_TTL);
            return Boolean.FALSE.equals(wasAbsent);
        } catch (Exception e) {
            /*
             * Redis 장애 때문에 Kafka fallback을 두는 구조다.
             * 따라서 dedupe Redis가 실패해도 fan-out 자체는 계속 진행하고, 이 인스턴스의 인메모리 dedupe에 의존한다.
             */
            redisHealthState.markDown();
            log.warn("[DEDUPE][{}] Redis dedupe failed, in-memory only", instanceId);
            return false;
        }
    }

    private void startCacheCleaner() {
        cleanerExecutor.scheduleAtFixedRate(this::evictExpiredDedupeEntries, 1, 1, TimeUnit.MINUTES);
    }

    private void evictExpiredDedupeEntries() {
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
    }

    @PreDestroy
    public void shutdown() {
        log.info("[FANOUT][{}] Shutting down dedupe cleaner", instanceId);
        cleanerExecutor.shutdown();
        waitCleanerShutdown();
    }

    private void waitCleanerShutdown() {
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
