package io.hyun424.openchat.chat.outbox;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.publish.ChatMessagePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CompletionException;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventProcessor {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxPayloadSerializer payloadSerializer;
    private final ChatMessagePublisher publisher;
    private final ChatPipelineMetrics chatPipelineMetrics;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.outbox.batch-size:100}")
    private int batchSize;

    @Value("${app.outbox.max-attempts:20}")
    private int maxAttempts;

    @Value("${app.outbox.processing-timeout-ms:30000}")
    private long processingTimeoutMs;

    public int processBatch() {
        long now = System.currentTimeMillis();
        resetExpiredProcessing(now);
        recordBacklogMetrics(now);

        List<Long> readyIds = outboxEventRepository.findReadyIds(
                OutboxEventStatus.PENDING,
                now,
                PageRequest.of(0, Math.max(1, batchSize))
        );

        int processed = 0;
        for (Long id : readyIds) {
            if (claim(id, now)) {
                processClaimed(id);
                processed++;
            }
        }
        return processed;
    }

    private void resetExpiredProcessing(long now) {
        int reset = transactionTemplate.execute(status -> outboxEventRepository.resetExpiredProcessing(
                OutboxEventStatus.PROCESSING,
                OutboxEventStatus.PENDING,
                now
        ));
        if (reset > 0) {
            chatPipelineMetrics.incrementCounter("outbox.processing.reset");
            log.warn("[OUTBOX] reset expired processing events count={}", reset);
        }
    }

    private boolean claim(Long id, long now) {
        int updated = transactionTemplate.execute(status -> outboxEventRepository.claim(
                id,
                OutboxEventStatus.PENDING,
                OutboxEventStatus.PROCESSING,
                now,
                now + processingTimeoutMs
        ));
        return updated == 1;
    }

    private void processClaimed(Long id) {
        long totalStartNanos = System.nanoTime();
        OutboxEvent event = outboxEventRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Outbox event not found: " + id));

        try {
            ChatMessageDto message = payloadSerializer.deserialize(event.getPayloadJson());
            processMessageCreated(message);
            markPublished(id);
            chatPipelineMetrics.recordStage("outbox.process.total", totalStartNanos);
            chatPipelineMetrics.incrementCounter("outbox.process.success");
        } catch (Exception e) {
            chatPipelineMetrics.recordStage("outbox.process.fail", totalStartNanos);
            chatPipelineMetrics.incrementCounter("outbox.process.fail");
            markFailure(id, e);
        }
    }

    private void processMessageCreated(ChatMessageDto message) {
        long publishStartNanos = System.nanoTime();
        try {
            publisher.publish(message).join();
            chatPipelineMetrics.recordStage("outbox.publish", publishStartNanos);
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(cause);
        }
    }

    private void markPublished(Long id) {
        transactionTemplate.executeWithoutResult(status -> {
            OutboxEvent event = outboxEventRepository.findById(id)
                    .orElseThrow(() -> new IllegalStateException("Outbox event not found: " + id));
            event.markPublished(System.currentTimeMillis());
        });
    }

    private void markFailure(Long id, Exception e) {
        transactionTemplate.executeWithoutResult(status -> {
            OutboxEvent event = outboxEventRepository.findById(id)
                    .orElseThrow(() -> new IllegalStateException("Outbox event not found: " + id));
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (event.getAttemptCount() + 1 >= maxAttempts) {
                event.markFailed(error);
                log.error("[OUTBOX] event failed permanently id={} messageId={} attempts={}",
                        event.getId(), event.getMessageId(), event.getAttemptCount() + 1, e);
                return;
            }

            long nextRetryAt = System.currentTimeMillis() + backoffMillis(event.getAttemptCount() + 1);
            event.markPendingForRetry(nextRetryAt, error);
            log.warn("[OUTBOX] event retry scheduled id={} messageId={} attempts={} nextRetryAt={}",
                    event.getId(), event.getMessageId(), event.getAttemptCount(), nextRetryAt);
        });
    }

    private long backoffMillis(int attempt) {
        return Math.min(30_000L, 100L * (1L << Math.min(attempt, 8)));
    }

    private void recordBacklogMetrics(long now) {
        long pending = outboxEventRepository.countByStatus(OutboxEventStatus.PENDING);
        chatPipelineMetrics.recordDistribution("openchat_outbox_pending_count", "pending", pending);
        Long oldest = outboxEventRepository.findOldestCreatedAtByStatus(OutboxEventStatus.PENDING);
        if (oldest != null) {
            chatPipelineMetrics.recordDistribution("openchat_outbox_oldest_pending_age_ms", "pending", now - oldest);
        }
    }
}
