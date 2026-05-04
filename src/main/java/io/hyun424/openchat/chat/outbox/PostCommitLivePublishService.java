package io.hyun424.openchat.chat.outbox;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.publish.ChatMessagePublisher;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@ConditionalOnExpression("'${app.role:combined}'.toLowerCase() != 'api'")
public class PostCommitLivePublishService {

    private final boolean enabled;
    private final ChatMessagePublisher publisher;
    private final ChatPipelineMetrics chatPipelineMetrics;
    private final OutboxPublishedMarker outboxPublishedMarker;
    private final ThreadPoolExecutor executor;

    public PostCommitLivePublishService(ChatMessagePublisher publisher,
                                        ChatPipelineMetrics chatPipelineMetrics,
                                        OutboxPublishedMarker outboxPublishedMarker,
                                        @Value("${app.live-publish.enabled:true}") boolean enabled,
                                        @Value("${app.live-publish.threads:8}") int threads,
                                        @Value("${app.live-publish.queue-capacity:100000}") int queueCapacity) {
        this.enabled = enabled;
        this.publisher = publisher;
        this.chatPipelineMetrics = chatPipelineMetrics;
        this.outboxPublishedMarker = outboxPublishedMarker;
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
        publishAsync(message, null);
    }

    public void publishAsync(ChatMessageDto message, Long outboxEventId) {
        if (!enabled) {
            return;
        }
        try {
            executor.execute(() -> publishLive(message, outboxEventId));
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

    private void publishLive(ChatMessageDto message, Long outboxEventId) {
        long totalStartNanos = System.nanoTime();
        try {
            long publishStartNanos = System.nanoTime();
            publisher.publish(message);
            chatPipelineMetrics.recordStage("live_publish.redis", publishStartNanos);
            outboxPublishedMarker.enqueue(outboxEventId);
            chatPipelineMetrics.recordStage("live_publish.total", totalStartNanos);
            chatPipelineMetrics.incrementCounter("live_publish.success");
        } catch (Exception e) {
            chatPipelineMetrics.recordStage("live_publish.fail", totalStartNanos);
            chatPipelineMetrics.incrementCounter("live_publish.fail");
            log.warn("[LIVE PUBLISH FAIL] roomId={} messageId={} - outbox will retry",
                    message.getRoomId(), message.getMessageId(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
