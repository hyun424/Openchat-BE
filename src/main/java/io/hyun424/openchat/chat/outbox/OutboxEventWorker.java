package io.hyun424.openchat.chat.outbox;

import io.hyun424.openchat.global.role.ConditionalOnRuntimeRole;
import io.hyun424.openchat.global.role.RuntimeCapability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnRuntimeRole(capabilities = RuntimeCapability.REALTIME)
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxEventWorker {

    private final OutboxEventProcessor processor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:50}")
    public void poll() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            processor.processBatch();
        } catch (Exception e) {
            log.warn("[OUTBOX] poll failed", e);
        } finally {
            running.set(false);
        }
    }
}
