package io.hyun424.openchat.chat.fanout;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
class FanoutDedupeCache {

    static final long DEFAULT_TTL_MILLIS = 60_000L;

    private final ConcurrentHashMap<String, Long> processedAtByKey = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanerExecutor;
    private final long ttlMillis;

    FanoutDedupeCache() {
        this(DEFAULT_TTL_MILLIS, true);
    }

    FanoutDedupeCache(long ttlMillis, boolean startCleaner) {
        this.ttlMillis = Math.max(1, ttlMillis);
        this.cleanerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fanout-dedupe-cleaner");
            t.setDaemon(true);
            return t;
        });
        if (startCleaner) {
            cleanerExecutor.scheduleAtFixedRate(this::evictExpired, 1, 1, TimeUnit.MINUTES);
        }
    }

    boolean hasProcessedLocally(String messageId, Integer partitionId) {
        Long previous = processedAtByKey.putIfAbsent(dedupeKey(messageId, partitionId), System.currentTimeMillis());
        return previous != null;
    }

    int evictExpired() {
        return evictExpired(System.currentTimeMillis());
    }

    int evictExpired(long nowMillis) {
        int removed = 0;
        for (var entry : processedAtByKey.entrySet()) {
            if (nowMillis - entry.getValue() > ttlMillis && processedAtByKey.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("[FANOUT DEDUPE CLEANUP] removed {} expired entries", removed);
        }
        return removed;
    }

    void shutdown() {
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

    int size() {
        return processedAtByKey.size();
    }

    private String dedupeKey(String messageId, Integer partitionId) {
        return (partitionId == null ? "legacy" : "partition-" + partitionId) + ":" + messageId;
    }
}
