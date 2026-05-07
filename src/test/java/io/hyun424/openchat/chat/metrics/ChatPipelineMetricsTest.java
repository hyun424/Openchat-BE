package io.hyun424.openchat.chat.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatPipelineMetricsTest {

    @Test
    void recordWebSocketSendSuccess_countsLogicalDeliveryFrameAndBytes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatPipelineMetrics metrics = new ChatPipelineMetrics(registry);

        metrics.recordWebSocketSendAttempt(3);
        metrics.recordWebSocketSendSuccess(3, 120, System.nanoTime());

        assertCounter(registry, "ws.send.attempted", 3);
        assertCounter(registry, "ws.send.frame.attempted", 1);
        assertCounter(registry, "ws.send.succeeded", 3);
        assertCounter(registry, "ws.send.frame.succeeded", 1);
        assertCounter(registry, "ws.send.bytes", 120);

        metrics.shutdown();
    }

    @Test
    void recordWebSocketSendFailure_countsLogicalDeliveryFrameAndReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatPipelineMetrics metrics = new ChatPipelineMetrics(registry);

        metrics.recordWebSocketSendAttempt(2);
        metrics.recordWebSocketSendFailure(2, "closed_before_send", System.nanoTime());

        assertCounter(registry, "ws.send.attempted", 2);
        assertCounter(registry, "ws.send.frame.attempted", 1);
        assertCounter(registry, "ws.send.failed", 2);
        assertCounter(registry, "ws.send.frame.failed", 1);
        assertCounter(registry, "ws.send.fail.closed_before_send", 1);
        assertEquals(2, metrics.counterValue("ws.send.failed"));

        metrics.recordWebSocketSendFailure(1, "io_exception", System.nanoTime());
        assertCounter(registry, "ws.send.fail.io_exception", 1);
        assertEquals(3, metrics.counterValue("ws.send.failed"));

        metrics.shutdown();
    }

    @Test
    void noopCounterValueReturnsZero() {
        ChatPipelineMetrics metrics = ChatPipelineMetrics.noop();

        assertEquals(0, metrics.counterValue("ws.send.failed"));
    }

    private void assertCounter(SimpleMeterRegistry registry, String event, double expected) {
        assertEquals(expected, registry.get("openchat_pipeline_events")
                .tag("event", event)
                .counter()
                .count());
    }
}
