package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.hot.RoomHotState;
import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
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
    private final long warmBatchWindowMillis;
    private final long hotBatchWindowMillis;
    private final long superHotBatchWindowMillis;
    private final int maxBatchSize;
    private final int maxFlushBatchesPerRun;
    private final ChatPipelineMetrics chatPipelineMetrics;
    private final RoomTrafficMonitor roomTrafficMonitor;

    @Value("${app.instance-id:local}")
    private String instanceId;

    private final ConcurrentHashMap<String, Long> dedupeCache = new ConcurrentHashMap<>();
    private static final long DEDUPE_TTL_MS = 60_000;

    public ChatFanoutService(ChatOutboundSender outboundSender) {
        this(outboundSender, true, 20, 30, 50, 100, 64, 16, ChatPipelineMetrics.noop(), new RoomTrafficMonitor());
    }

    public ChatFanoutService(ChatOutboundSender outboundSender,
                             boolean batchEnabled,
                             long batchWindowMillis,
                             int maxBatchSize) {
        this(outboundSender, batchEnabled, batchWindowMillis, 30, 50, 100, maxBatchSize, 16,
                ChatPipelineMetrics.noop(), new RoomTrafficMonitor());
    }

    @Autowired
    public ChatFanoutService(ChatOutboundSender outboundSender,
                             @Value("${app.websocket.batch.enabled:true}") boolean batchEnabled,
                             @Value("${app.websocket.batch.window-ms:20}") long batchWindowMillis,
                             @Value("${app.websocket.batch.warm-window-ms:30}") long warmBatchWindowMillis,
                             @Value("${app.websocket.batch.hot-window-ms:50}") long hotBatchWindowMillis,
                             @Value("${app.websocket.batch.super-hot-window-ms:100}") long superHotBatchWindowMillis,
                             @Value("${app.websocket.batch.max-size:64}") int maxBatchSize,
                             @Value("${app.websocket.batch.max-flush-batches-per-run:16}") int maxFlushBatchesPerRun,
                             ChatPipelineMetrics chatPipelineMetrics,
                             RoomTrafficMonitor roomTrafficMonitor) {
        this.outboundSender = outboundSender;
        this.batchEnabled = batchEnabled;
        this.batchWindowMillis = Math.max(1, batchWindowMillis);
        this.warmBatchWindowMillis = Math.max(this.batchWindowMillis, warmBatchWindowMillis);
        this.hotBatchWindowMillis = Math.max(this.warmBatchWindowMillis, hotBatchWindowMillis);
        this.superHotBatchWindowMillis = Math.max(this.hotBatchWindowMillis, superHotBatchWindowMillis);
        this.maxBatchSize = Math.max(1, maxBatchSize);
        this.maxFlushBatchesPerRun = Math.max(1, maxFlushBatchesPerRun);
        this.chatPipelineMetrics = chatPipelineMetrics;
        this.roomTrafficMonitor = roomTrafficMonitor;
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
        long startNanos = System.nanoTime();
        String messageId = message.getMessageId();

        if (hasProcessedLocally(messageId)) {
            chatPipelineMetrics.incrementCounter("fanout.dedupe_skip");
            log.debug("[DEDUPE][{}] messageId={} - already processed", instanceId, messageId);
            return;
        }
        chatPipelineMetrics.recordSinceCreated("fanout.enter.since_created", message);

        if (batchEnabled) {
            enqueueForRoomBatch(message);
        } else {
            long sendStartNanos = System.nanoTime();
            outboundSender.send(message);
            chatPipelineMetrics.recordStage("fanout.outbound_single", sendStartNanos);
        }
        chatPipelineMetrics.recordStage("fanout.total", startNanos);

        log.debug("[FANOUT][{}] roomId={} messageId={}",
                instanceId, message.getRoomId(), message.getMessageId());
    }

    private void enqueueForRoomBatch(ChatMessageDto message) {
        Long roomId = message.getRoomId();
        RoomFanoutBuffer buffer = roomBuffers.computeIfAbsent(roomId, ignored -> new RoomFanoutBuffer());
        buffer.add(message);
        chatPipelineMetrics.recordDistribution("openchat_pipeline_batch_pending", "fanout.room", buffer.size());
        chatPipelineMetrics.recordSinceCreated("fanout.batch.enqueued.since_created", message);

        if (buffer.markScheduled()) {
            scheduleFlush(roomId, resolveBatchWindowMillis(roomId));
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

    private long resolveBatchWindowMillis(Long roomId) {
        RoomHotState state = roomTrafficMonitor.state(roomId);
        return switch (state) {
            case WARM -> warmBatchWindowMillis;
            case HOT -> hotBatchWindowMillis;
            case SUPER_HOT -> superHotBatchWindowMillis;
            case NORMAL, WATCHED -> batchWindowMillis;
        };
    }

    private void flushRoomBatch(Long roomId) {
        long flushStartNanos = System.nanoTime();
        RoomFanoutBuffer buffer = roomBuffers.get(roomId);
        if (buffer == null) {
            return;
        }

        int flushedBatchCount = 0;
        int flushedMessageCount = 0;
        try {
            while (flushedBatchCount < maxFlushBatchesPerRun) {
                List<ChatMessageDto> messages = buffer.drain(maxBatchSize);
                if (messages.isEmpty()) {
                    break;
                }

                for (ChatMessageDto message : messages) {
                    chatPipelineMetrics.recordSinceCreated("fanout.batch.flush_start.since_created", message);
                }
                chatPipelineMetrics.recordDistribution("openchat_pipeline_batch_size", "fanout.flush", messages.size());
                sendFlushedMessages(roomId, messages);
                flushedBatchCount++;
                flushedMessageCount += messages.size();
            }
        } finally {
            buffer.clearScheduled();
        }

        chatPipelineMetrics.recordStage("fanout.batch.flush_total", flushStartNanos);
        if (flushedBatchCount > 0) {
            chatPipelineMetrics.recordDistribution("openchat_pipeline_batch_flush_batches", "fanout.room", flushedBatchCount);
            chatPipelineMetrics.recordDistribution("openchat_pipeline_batch_flush_messages", "fanout.room", flushedMessageCount);
        }

        if (buffer.size() > 0 && buffer.markScheduled()) {
            scheduleFlush(roomId, 0);
        }
    }

    private void sendFlushedMessages(Long roomId, List<ChatMessageDto> messages) {
        long sendStartNanos = System.nanoTime();
        if (messages.size() == 1) {
            outboundSender.send(messages.get(0));
            chatPipelineMetrics.recordStage("fanout.batch.outbound_single", sendStartNanos);
            return;
        }
        outboundSender.sendBatch(roomId, messages);
        chatPipelineMetrics.recordStage("fanout.batch.outbound_batch", sendStartNanos);
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
