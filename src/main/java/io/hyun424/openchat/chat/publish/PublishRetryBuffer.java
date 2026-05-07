package io.hyun424.openchat.chat.publish;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Publish 실패 메시지를 인메모리 버퍼에 보관하고 주기적으로 재시도한다.
 * - 버퍼 크기 제한: 1000건 초과 시 oldest drop + 경고 로그
 * - 1초 간격으로 drain 시도
 * - Redis/Kafka 복구 시 자동 drain
 */
@Slf4j
@Component
public class PublishRetryBuffer {

    private static final int MAX_BUFFER_SIZE = 1000;

    private final LinkedBlockingQueue<ChatMessageDto> buffer = new LinkedBlockingQueue<>(MAX_BUFFER_SIZE);
    private final ChatMessagePublisher publisher;
    private final ScheduledExecutorService retryExecutor;

    public PublishRetryBuffer(ChatMessagePublisher publisher) {
        this.publisher = publisher;
        this.retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "publish-retry");
            t.setDaemon(true);
            return t;
        });
        startRetryLoop();
    }

    public void enqueue(ChatMessageDto message) {
        if (!buffer.offer(message)) {
            // Buffer full — drop oldest and retry
            ChatMessageDto dropped = buffer.poll();
            if (dropped != null) {
                log.warn("[RETRY BUFFER] Buffer full ({}), dropped oldest messageId={}",
                        MAX_BUFFER_SIZE, dropped.getMessageId());
            }
            buffer.offer(message);
        }
        log.debug("[RETRY BUFFER] Enqueued messageId={} bufferSize={}", message.getMessageId(), buffer.size());
    }

    public int size() {
        return buffer.size();
    }

    private void startRetryLoop() {
        retryExecutor.scheduleWithFixedDelay(() -> {
            if (buffer.isEmpty()) return;

            int retried = 0;
            int failed = 0;

            while (!buffer.isEmpty()) {
                ChatMessageDto msg = buffer.peek();
                if (msg == null) break;

                try {
                    publisher.publish(msg).join();
                    buffer.poll(); // remove only after successful publish
                    retried++;
                } catch (Exception e) {
                    failed++;
                    log.debug("[RETRY BUFFER] Retry failed for messageId={}, will retry later", msg.getMessageId());
                    break; // stop draining, bus is still down
                }
            }

            if (retried > 0) {
                log.info("[RETRY BUFFER] Drained {} messages, remaining={}", retried, buffer.size());
            }
            if (failed > 0) {
                log.debug("[RETRY BUFFER] {} messages still pending retry", buffer.size());
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        log.info("[RETRY BUFFER] Shutting down, {} messages remaining", buffer.size());
        retryExecutor.shutdown();
        try {
            if (!retryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                retryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            retryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
