package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class ChatFanoutService {

    private final ChatOutboundSender outboundSender;
    private final ScheduledExecutorService cleanerExecutor;
    private final ScheduledExecutorService batchExecutor;
    private final ConcurrentHashMap<Long, RoomFanoutBuffer> roomBuffers = new ConcurrentHashMap<>();
    private final boolean batchEnabled;
    private final long batchWindowMillis;
    private final int maxBatchSize;

    @Value("${app.instance-id:local}")
    private String instanceId;

    private final ConcurrentHashMap<String, Long> dedupeCache = new ConcurrentHashMap<>();
    private static final long DEDUPE_TTL_MS = 60_000;

    public ChatFanoutService(ChatOutboundSender outboundSender) {
        this(outboundSender, true, 20, 64);
    }

    @Autowired
    public ChatFanoutService(ChatOutboundSender outboundSender,
                             @Value("${app.websocket.batch.enabled:true}") boolean batchEnabled,
                             @Value("${app.websocket.batch.window-ms:20}") long batchWindowMillis,
                             @Value("${app.websocket.batch.max-size:64}") int maxBatchSize) {
        this.outboundSender = outboundSender;
        this.batchEnabled = batchEnabled;
        this.batchWindowMillis = Math.max(1, batchWindowMillis);
        this.maxBatchSize = Math.max(1, maxBatchSize);
        this.cleanerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dedupe-cleaner");
            t.setDaemon(true);
            return t;
        });
        this.batchExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fanout-batch-flusher");
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
            log.debug("[DEDUPE][{}] messageId={} - already processed", instanceId, messageId);
            return;
        }

        if (batchEnabled) {
            enqueueForRoomBatch(message);
        } else {
            outboundSender.send(message);
        }

        log.debug("[FANOUT][{}] roomId={} messageId={}",
                instanceId, message.getRoomId(), message.getMessageId());
    }

    private void enqueueForRoomBatch(ChatMessageDto message) {
        Long roomId = message.getRoomId();
        RoomFanoutBuffer buffer = roomBuffers.computeIfAbsent(roomId, ignored -> new RoomFanoutBuffer());
        buffer.add(message);

        if (buffer.markScheduled()) {
            scheduleFlush(roomId, batchWindowMillis);
        }

        if (buffer.size() >= maxBatchSize) {
            scheduleFlush(roomId, 0);
        }
    }

    private void scheduleFlush(Long roomId, long delayMillis) {
        if (batchExecutor.isShutdown()) {
            flushRoomBatch(roomId);
            return;
        }
        try {
            batchExecutor.schedule(() -> flushRoomBatch(roomId), delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            flushRoomBatch(roomId);
        }
    }

    private void flushRoomBatch(Long roomId) {
        RoomFanoutBuffer buffer = roomBuffers.get(roomId);
        if (buffer == null) {
            return;
        }

        List<ChatMessageDto> messages = buffer.drain(maxBatchSize);
        if (messages.isEmpty()) {
            buffer.clearScheduled();
            return;
        }

        sendFlushedMessages(roomId, messages);

        buffer.clearScheduled();
        if (buffer.size() > 0 && buffer.markScheduled()) {
            scheduleFlush(roomId, 0);
        }
    }

    private void sendFlushedMessages(Long roomId, List<ChatMessageDto> messages) {
        if (messages.size() == 1) {
            outboundSender.send(messages.get(0));
            return;
        }
        outboundSender.sendBatch(roomId, messages);
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
        log.info("[FANOUT][{}] Shutting down fanout service", instanceId);
        flushAllRoomBatches();
        batchExecutor.shutdown();
        cleanerExecutor.shutdown();
        waitExecutorShutdown(batchExecutor);
        waitExecutorShutdown(cleanerExecutor);
    }

    private void flushAllRoomBatches() {
        for (Long roomId : roomBuffers.keySet()) {
            flushRoomBatch(roomId);
        }
    }

    boolean awaitBatchIdle(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            boolean idle = roomBuffers.values().stream()
                    .allMatch(buffer -> buffer.size() == 0 && !buffer.isScheduled());
            if (idle) {
                return true;
            }
            Thread.sleep(5);
        }
        return false;
    }

    private void waitExecutorShutdown(ScheduledExecutorService executor) {
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static class RoomFanoutBuffer {

        private final ConcurrentLinkedQueue<ChatMessageDto> messages = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
        private final AtomicInteger size = new AtomicInteger(0);

        void add(ChatMessageDto message) {
            messages.add(message);
            size.incrementAndGet();
        }

        boolean markScheduled() {
            return flushScheduled.compareAndSet(false, true);
        }

        void clearScheduled() {
            flushScheduled.set(false);
        }

        boolean isScheduled() {
            return flushScheduled.get();
        }

        int size() {
            return size.get();
        }

        List<ChatMessageDto> drain(int maxMessages) {
            List<ChatMessageDto> drained = new ArrayList<>(Math.min(maxMessages, size()));
            for (int i = 0; i < maxMessages; i++) {
                ChatMessageDto message = messages.poll();
                if (message == null) {
                    break;
                }
                size.decrementAndGet();
                drained.add(message);
            }
            return drained;
        }
    }
}
