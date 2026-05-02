package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.metrics.ChatPipelineMetrics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ChatFanoutService {

    private final ChatOutboundSender outboundSender;
    private final ScheduledExecutorService cleanerExecutor;
    private final ChatPipelineMetrics chatPipelineMetrics;

    @Value("${app.instance-id:local}")
    private String instanceId;

    private final ConcurrentHashMap<String, Long> dedupeCache = new ConcurrentHashMap<>();
    private static final long DEDUPE_TTL_MS = 60_000;

    public ChatFanoutService(ChatOutboundSender outboundSender,
                             ChatPipelineMetrics chatPipelineMetrics) {
        this.outboundSender = outboundSender;
        this.chatPipelineMetrics = chatPipelineMetrics;
        this.cleanerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dedupe-cleaner");
            t.setDaemon(true);
            return t;
        });
        startCacheCleaner();
    }

    /**
     * 메시지를 현재 인스턴스의 WebSocket 세션에 전달한다.
     * Redis Pub/Sub은 모든 앱 인스턴스가 같은 메시지를 받아야 하므로 dedupe는 인스턴스 로컬 범위로만 수행한다.
     */
    public void fanout(ChatMessageDto message) {
        String messageId = message.getMessageId();

        if (hasProcessedLocally(messageId)) {
            chatPipelineMetrics.incrementCounter("openchat_fanout_dedupe_skip_total");
            log.debug("[DEDUPE][{}] messageId={} - already processed", instanceId, messageId);
            return;
        }

        chatPipelineMetrics.recordSinceCreated("fanout.enter.since_created", message);
        chatPipelineMetrics.recordSinceCreated("fanout.before_outbound.since_created", message);
        long startNanos = System.nanoTime();
        outboundSender.send(message);
        chatPipelineMetrics.recordStageNanos("fanout.total", System.nanoTime() - startNanos);

        log.debug("[FANOUT][{}] roomId={} messageId={}",
                instanceId, message.getRoomId(), message.getMessageId());
    }

    private boolean hasProcessedLocally(String messageId) {
        Long previous = dedupeCache.putIfAbsent(messageId, System.currentTimeMillis());
        return previous != null;
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
