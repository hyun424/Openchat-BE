package io.hyun424.openchat.chat.outbox;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.publish.ChatMessagePublisher;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class PostCommitLivePublishService {

    private final boolean enabled;
    private final OutboxEventRepository outboxEventRepository;
    private final ChatMessagePublisher publisher;
    private final ChatPipelineMetrics chatPipelineMetrics;
    private final TransactionTemplate transactionTemplate;
    private final ThreadPoolExecutor executor;
    private final int maxAttempts;

    public PostCommitLivePublishService(OutboxEventRepository outboxEventRepository,
                                        ChatMessagePublisher publisher,
                                        ChatPipelineMetrics chatPipelineMetrics,
                                        TransactionTemplate transactionTemplate,
                                        @Value("${app.live-publish.enabled:true}") boolean enabled,
                                        @Value("${app.live-publish.threads:8}") int threads,
                                        @Value("${app.live-publish.queue-capacity:100000}") int queueCapacity,
                                        @Value("${app.outbox.processing-timeout-ms:30000}") long processingTimeoutMs,
                                        @Value("${app.outbox.max-attempts:20}") int maxAttempts) {
        this.outboxEventRepository = outboxEventRepository;
        this.enabled = enabled;
        this.publisher = publisher;
        this.chatPipelineMetrics = chatPipelineMetrics;
        this.transactionTemplate = transactionTemplate;
        this.maxAttempts = maxAttempts;
        this.executor = new ThreadPoolExecutor(
                Math.max(1, threads),
                Math.max(1, threads),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                r -> {
                    Thread t = new Thread(r, "post-commit-live-publish");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public void publishAsync(ChatMessageDto message) {
        if (!enabled) {
            return;
        }
        try {
            executor.execute(() -> publishClaimed(message));
            chatPipelineMetrics.recordDistribution(
                    "openchat_live_publish_queue_size",
                    "pending",
                    executor.getQueue().size()
            );
        } catch (RuntimeException e) {
            chatPipelineMetrics.incrementCounter("live_publish.enqueue.fail");
            log.warn("[LIVE PUBLISH ENQUEUE FAIL] roomId={} messageId={} - outbox will retry",
                    message.getRoomId(), message.getMessageId(), e);
        }
    }

    private void publishClaimed(ChatMessageDto message) {
        long totalStartNanos = System.nanoTime();
        try {
            long publishStartNanos = System.nanoTime();
            publisher.publish(message);
            chatPipelineMetrics.recordStage("live_publish.redis", publishStartNanos);
            markPublished(message.getMessageId());
            chatPipelineMetrics.recordStage("live_publish.total", totalStartNanos);
            chatPipelineMetrics.incrementCounter("live_publish.success");
        } catch (Exception e) {
            chatPipelineMetrics.recordStage("live_publish.fail", totalStartNanos);
            chatPipelineMetrics.incrementCounter("live_publish.fail");
            markFailure(message.getMessageId(), e);
        }
    }

    private void markPublished(String messageId) {
        transactionTemplate.executeWithoutResult(status -> {
            OutboxEvent event = outboxEventRepository.findFirstByMessageIdOrderByIdAsc(messageId)
                    .orElseThrow(() -> new IllegalStateException("Outbox event not found: " + messageId));
            if (event.getStatus() == OutboxEventStatus.PUBLISHED) {
                return;
            }
            event.markPublished(System.currentTimeMillis());
        });
    }

    private void markFailure(String messageId, Exception e) {
        transactionTemplate.executeWithoutResult(status -> {
            OutboxEvent event = outboxEventRepository.findFirstByMessageIdOrderByIdAsc(messageId)
                    .orElseThrow(() -> new IllegalStateException("Outbox event not found: " + messageId));
            if (event.getStatus() == OutboxEventStatus.PUBLISHED) {
                return;
            }
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (event.getAttemptCount() + 1 >= maxAttempts) {
                event.markFailed(error);
                return;
            }
            long nextRetryAt = System.currentTimeMillis() + backoffMillis(event.getAttemptCount() + 1);
            event.markPendingForRetry(nextRetryAt, error);
        });
    }

    private long backoffMillis(int attempt) {
        return Math.min(30_000L, 100L * (1L << Math.min(attempt, 8)));
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
