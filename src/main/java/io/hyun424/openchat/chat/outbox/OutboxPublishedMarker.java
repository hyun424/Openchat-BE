package io.hyun424.openchat.chat.outbox;

import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnExpression("'${app.role:combined}'.toLowerCase() != 'api'")
public class OutboxPublishedMarker {

    private final boolean enabled;
    private final OutboxEventRepository outboxEventRepository;
    private final ChatPipelineMetrics chatPipelineMetrics;
    private final TransactionTemplate transactionTemplate;
    private final BlockingQueue<Long> queue;
    private final ScheduledExecutorService executor;
    private final int batchSize;

    public OutboxPublishedMarker(OutboxEventRepository outboxEventRepository,
                                 ChatPipelineMetrics chatPipelineMetrics,
                                 TransactionTemplate transactionTemplate,
                                 @Value("${app.outbox.published-marker.enabled:true}") boolean enabled,
                                 @Value("${app.outbox.published-marker.queue-capacity:200000}") int queueCapacity,
                                 @Value("${app.outbox.published-marker.batch-size:500}") int batchSize,
                                 @Value("${app.outbox.published-marker.flush-interval-ms:25}") long flushIntervalMs) {
        this.enabled = enabled;
        this.outboxEventRepository = outboxEventRepository;
        this.chatPipelineMetrics = chatPipelineMetrics;
        this.transactionTemplate = transactionTemplate;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.batchSize = Math.max(1, batchSize);
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-published-marker");
            t.setDaemon(true);
            return t;
        });
        if (enabled) {
            this.executor.scheduleWithFixedDelay(
                    this::flushSafely,
                    Math.max(1L, flushIntervalMs),
                    Math.max(1L, flushIntervalMs),
                    TimeUnit.MILLISECONDS
            );
        }
    }

    public void enqueue(Long outboxEventId) {
        if (!enabled) {
            return;
        }
        if (outboxEventId == null) {
            chatPipelineMetrics.incrementCounter("outbox.published_marker.missing_id");
            return;
        }
        if (!queue.offer(outboxEventId)) {
            chatPipelineMetrics.incrementCounter("outbox.published_marker.overflow");
            log.warn("[OUTBOX PUBLISHED MARKER FULL] outboxEventId={}", outboxEventId);
            return;
        }
        chatPipelineMetrics.incrementCounter("outbox.published_marker.enqueued");
        chatPipelineMetrics.recordDistribution(
                "openchat_outbox_published_marker_queue_size",
                "pending",
                queue.size()
        );
    }

    private void flushSafely() {
        try {
            flushOnce();
        } catch (Exception e) {
            chatPipelineMetrics.incrementCounter("outbox.published_marker.flush.fail");
            log.warn("[OUTBOX PUBLISHED MARKER FAIL]", e);
        }
    }

    int flushOnce() {
        if (!enabled) {
            return 0;
        }
        long startNanos = System.nanoTime();
        List<Long> drained = drainBatch();
        if (drained.isEmpty()) {
            return 0;
        }

        Integer updated = transactionTemplate.execute(status -> outboxEventRepository.markPublishedByIds(
                drained,
                OutboxEventStatus.PENDING,
                OutboxEventStatus.PUBLISHED,
                System.currentTimeMillis()
        ));
        int safeUpdated = updated == null ? 0 : updated;
        chatPipelineMetrics.recordStage("outbox.published_marker.flush", startNanos);
        chatPipelineMetrics.recordDistribution("openchat_outbox_published_marker_batch_size", "ids", drained.size());
        chatPipelineMetrics.recordDistribution("openchat_outbox_published_marker_updated", "rows", safeUpdated);
        if (safeUpdated < drained.size()) {
            int mismatchRows = drained.size() - safeUpdated;
            chatPipelineMetrics.incrementCounter("outbox.published_marker.state_mismatch");
            chatPipelineMetrics.incrementCounter("outbox.published_marker.state_mismatch.rows", mismatchRows);
            log.warn("[OUTBOX PUBLISHED MARKER MISMATCH] requested={} updated={} mismatchRows={}",
                    drained.size(), safeUpdated, mismatchRows);
        }
        chatPipelineMetrics.incrementCounter("outbox.published_marker.flush.success");
        return safeUpdated;
    }

    private List<Long> drainBatch() {
        List<Long> drained = new ArrayList<>(batchSize);
        queue.drainTo(drained, batchSize);
        if (drained.size() <= 1) {
            return drained;
        }
        return new ArrayList<>(new LinkedHashSet<>(drained));
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        flushSafely();
    }
}
