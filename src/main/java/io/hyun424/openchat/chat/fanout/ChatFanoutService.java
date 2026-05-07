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

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ChatFanoutService {

    private final ChatOutboundSender outboundSender;
    private final ConcurrentHashMap<FanoutBufferKey, FanoutBatchBuffer> roomBuffers = new ConcurrentHashMap<>();
    private final boolean batchEnabled;
    private final int maxBatchSize;
    private final int maxFlushBatchesPerRun;
    private final ChatPipelineMetrics chatPipelineMetrics;
    private final RoomTrafficMonitor roomTrafficMonitor;
    private final FanoutDedupeCache dedupeCache;
    private final FanoutBatchScheduler batchScheduler;
    private final FanoutBatchWindowPolicy batchWindowPolicy;
    private final LiveFanoutPolicy liveFanoutPolicy;

    @Value("${app.instance-id:local}")
    private String instanceId;

    public ChatFanoutService(ChatOutboundSender outboundSender) {
        this(outboundSender, true, 20, 30, 100, 250, 64, 16,
                true, 100, 50, ChatPipelineMetrics.noop(), new RoomTrafficMonitor());
    }

    public ChatFanoutService(ChatOutboundSender outboundSender,
                             boolean batchEnabled,
                             long batchWindowMillis,
                             int maxBatchSize) {
        this(outboundSender, batchEnabled, batchWindowMillis, 30, 50, 100, maxBatchSize, 16,
                true, 100, 50, ChatPipelineMetrics.noop(), new RoomTrafficMonitor());
    }

    public ChatFanoutService(ChatOutboundSender outboundSender,
                             boolean batchEnabled,
                             long batchWindowMillis,
                             long warmBatchWindowMillis,
                             long hotBatchWindowMillis,
                             long superHotBatchWindowMillis,
                             int maxBatchSize,
                             int maxFlushBatchesPerRun,
                             ChatPipelineMetrics chatPipelineMetrics,
                             RoomTrafficMonitor roomTrafficMonitor) {
        this(outboundSender, batchEnabled, batchWindowMillis, warmBatchWindowMillis, hotBatchWindowMillis,
                superHotBatchWindowMillis, maxBatchSize, maxFlushBatchesPerRun,
                true, 100, 50, chatPipelineMetrics, roomTrafficMonitor);
    }

    @Autowired
    public ChatFanoutService(ChatOutboundSender outboundSender,
                             @Value("${app.websocket.batch.enabled:true}") boolean batchEnabled,
                             @Value("${app.websocket.batch.window-ms:20}") long batchWindowMillis,
                             @Value("${app.websocket.batch.warm-window-ms:30}") long warmBatchWindowMillis,
                             @Value("${app.websocket.batch.hot-window-ms:100}") long hotBatchWindowMillis,
                             @Value("${app.websocket.batch.super-hot-window-ms:250}") long superHotBatchWindowMillis,
                             @Value("${app.websocket.batch.max-size:64}") int maxBatchSize,
                             @Value("${app.websocket.batch.max-flush-batches-per-run:16}") int maxFlushBatchesPerRun,
                             @Value("${app.websocket.controlled-realtime.enabled:true}") boolean controlledRealtimeEnabled,
                             @Value("${app.websocket.controlled-realtime.hot-max-messages-per-sec:100}") int hotLiveMaxMessagesPerSecond,
                             @Value("${app.websocket.controlled-realtime.super-hot-max-messages-per-sec:50}") int superHotLiveMaxMessagesPerSecond,
                             ChatPipelineMetrics chatPipelineMetrics,
                             RoomTrafficMonitor roomTrafficMonitor) {
        this.outboundSender = outboundSender;
        this.batchEnabled = batchEnabled;
        this.maxBatchSize = Math.max(1, maxBatchSize);
        this.maxFlushBatchesPerRun = Math.max(1, maxFlushBatchesPerRun);
        this.chatPipelineMetrics = chatPipelineMetrics;
        this.roomTrafficMonitor = roomTrafficMonitor;
        this.dedupeCache = new FanoutDedupeCache();
        this.batchScheduler = new FanoutBatchScheduler();
        this.batchWindowPolicy = new FanoutBatchWindowPolicy(
                batchWindowMillis,
                warmBatchWindowMillis,
                hotBatchWindowMillis,
                superHotBatchWindowMillis
        );
        this.liveFanoutPolicy = new LiveFanoutPolicy(
                controlledRealtimeEnabled,
                hotLiveMaxMessagesPerSecond,
                superHotLiveMaxMessagesPerSecond
        );
    }

    /**
     * 메시지를 현재 인스턴스의 WebSocket 세션에 전달한다.
     * Redis Pub/Sub은 모든 앱 인스턴스가 같은 메시지를 받아야 하므로 dedupe는 인스턴스 로컬 범위로만 수행한다.
     */
    public void fanout(ChatMessageDto message) {
        fanout(message, null);
    }

    public void fanout(ChatMessageDto message, Integer partitionId) {
        long startNanos = System.nanoTime();
        String messageId = message.getMessageId();

        if (dedupeCache.hasProcessedLocally(messageId, partitionId)) {
            chatPipelineMetrics.incrementCounter("fanout.dedupe_skip");
            log.debug("[DEDUPE][{}] messageId={} - already processed", instanceId, messageId);
            return;
        }
        chatPipelineMetrics.recordSinceCreated("fanout.enter.since_created", message);

        if (batchEnabled) {
            enqueueForRoomBatch(message, partitionId);
        } else {
            sendSingle(message, partitionId);
        }
        chatPipelineMetrics.recordStage("fanout.total", startNanos);

        log.debug("[FANOUT][{}] roomId={} messageId={}",
                instanceId, message.getRoomId(), message.getMessageId());
    }

    private void sendSingle(ChatMessageDto message, Integer partitionId) {
        long sendStartNanos = System.nanoTime();
        if (partitionId == null) {
            outboundSender.send(message);
        } else {
            outboundSender.send(message, partitionId);
        }
        chatPipelineMetrics.recordStage("fanout.outbound_single", sendStartNanos);
    }

    private void enqueueForRoomBatch(ChatMessageDto message, Integer partitionId) {
        FanoutBufferKey key = new FanoutBufferKey(message.getRoomId(), partitionId);
        FanoutBatchBuffer buffer = roomBuffers.computeIfAbsent(key, ignored -> new FanoutBatchBuffer());
        buffer.add(message);
        chatPipelineMetrics.recordDistribution("openchat_pipeline_batch_pending", "fanout.room", buffer.size());
        chatPipelineMetrics.recordSinceCreated("fanout.batch.enqueued.since_created", message);

        if (buffer.markScheduled()) {
            scheduleFlush(key, resolveBatchWindowMillis(key.roomId()));
        }

        if (buffer.size() >= maxBatchSize) {
            scheduleFlush(key, 0);
        }
    }

    private void scheduleFlush(FanoutBufferKey key, long delayMillis) {
        batchScheduler.schedule(key, delayMillis, this::flushRoomBatch);
    }

    private long resolveBatchWindowMillis(Long roomId) {
        return batchWindowPolicy.windowFor(roomTrafficMonitor.state(roomId));
    }

    private void flushRoomBatch(FanoutBufferKey key) {
        long flushStartNanos = System.nanoTime();
        FanoutBatchBuffer buffer = roomBuffers.get(key);
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
                sendFlushedMessages(key, messages);
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
            scheduleFlush(key, 0);
        }
    }

    private void sendFlushedMessages(FanoutBufferKey key, List<ChatMessageDto> messages) {
        long sendStartNanos = System.nanoTime();
        Long roomId = key.roomId();
        RoomHotState state = roomTrafficMonitor.state(roomId);
        LiveBatch liveBatch = liveFanoutPolicy.apply(roomId, messages, state);
        recordLiveCapMetrics(roomId, liveBatch);
        if (liveBatch.messages().isEmpty()) {
            chatPipelineMetrics.recordStage("fanout.batch.outbound_omitted", sendStartNanos);
            return;
        }

        if (liveBatch.messages().size() == 1 && liveBatch.realtimeComplete()) {
            sendBatchSingle(key, liveBatch.messages().get(0), sendStartNanos);
            return;
        }
        sendBatch(key, liveBatch);
        chatPipelineMetrics.recordStage("fanout.batch.outbound_batch", sendStartNanos);
    }

    private void recordLiveCapMetrics(Long roomId, LiveBatch liveBatch) {
        if (!liveBatch.controlled()) {
            return;
        }
        String stateTag = liveBatch.state().name().toLowerCase();
        chatPipelineMetrics.recordDistribution("openchat_pipeline_live_visible_messages", stateTag, liveBatch.messages().size());
        chatPipelineMetrics.recordDistribution("openchat_pipeline_live_omitted_messages", stateTag, liveBatch.omittedCount());
        if (liveBatch.messages().isEmpty()) {
            log.debug("[FANOUT LIVE OMIT] roomId={} state={} omitted={} lastSequence={}",
                    roomId, liveBatch.state(), liveBatch.omittedCount(), liveBatch.lastSequence());
        }
    }

    private void sendBatchSingle(FanoutBufferKey key, ChatMessageDto message, long sendStartNanos) {
        if (key.partitionId() == null) {
            outboundSender.send(message);
        } else {
            outboundSender.send(message, key.partitionId());
        }
        chatPipelineMetrics.recordStage("fanout.batch.outbound_single", sendStartNanos);
    }

    private void sendBatch(FanoutBufferKey key, LiveBatch liveBatch) {
        if (key.partitionId() == null) {
            outboundSender.sendBatch(
                    key.roomId(),
                    liveBatch.messages(),
                    liveBatch.realtimeComplete(),
                    liveBatch.omittedCount(),
                    liveBatch.lastSequence()
            );
            return;
        }
        outboundSender.sendBatch(
                key.roomId(),
                key.partitionId(),
                liveBatch.messages(),
                liveBatch.realtimeComplete(),
                liveBatch.omittedCount(),
                liveBatch.lastSequence()
        );
    }

    @PreDestroy
    public void shutdown() {
        log.info("[FANOUT][{}] Shutting down fanout service", instanceId);
        flushAllRoomBatches();
        batchScheduler.shutdown();
        dedupeCache.shutdown();
    }

    private void flushAllRoomBatches() {
        for (FanoutBufferKey key : roomBuffers.keySet()) {
            flushRoomBatch(key);
        }
    }

    boolean awaitBatchIdle(long timeoutMillis) throws InterruptedException {
        return batchScheduler.awaitIdle(this::allRoomBatchesIdle, timeoutMillis);
    }

    private boolean allRoomBatchesIdle() {
        return roomBuffers.values().stream()
                .allMatch(buffer -> buffer.size() == 0 && !buffer.isScheduled());
    }
}
