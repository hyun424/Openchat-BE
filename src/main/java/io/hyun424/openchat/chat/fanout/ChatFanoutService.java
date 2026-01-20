package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
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

    @Value("${app.instance-id:local}")
    private String instanceId;

    // In-memory dedupe cache (per-instance, no Redis dependency)
    private final ConcurrentHashMap<String, Long> dedupeCache = new ConcurrentHashMap<>();
    private static final long DEDUPE_TTL_MS = 60_000; // 1 minute

    public ChatFanoutService(ChatOutboundSender outboundSender) {
        this.outboundSender = outboundSender;
        startCacheCleaner();
    }

    /**
     * Fan-out message to local WebSocket sessions.
     * Uses in-memory dedupe to prevent duplicate processing within the same instance.
     */
    public void fanout(ChatMessageDto message) {
        String messageId = message.getMessageId();

        // In-memory dedupe check (per-instance)
        Long previous = dedupeCache.putIfAbsent(messageId, System.currentTimeMillis());
        if (previous != null) {
            log.debug("[DEDUPE][{}] messageId={} - already processed locally", instanceId, messageId);
            return;
        }

        outboundSender.send(message);

        log.info("[FANOUT][{}] roomId={} messageId={}",
                instanceId, message.getRoomId(), message.getMessageId());
    }

    /**
     * Periodic cleanup of expired dedupe entries to prevent memory leak.
     */
    private void startCacheCleaner() {
        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dedupe-cleaner");
            t.setDaemon(true);
            return t;
        });

        cleaner.scheduleAtFixedRate(() -> {
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
}
