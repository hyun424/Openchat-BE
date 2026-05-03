package io.hyun424.openchat.chat.metrics;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class ChatPipelineMetrics {

    private final MeterRegistry meterRegistry;
    private final boolean enabled;

    public ChatPipelineMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.enabled = true;
    }

    private ChatPipelineMetrics() {
        this.meterRegistry = null;
        this.enabled = false;
    }

    public static ChatPipelineMetrics noop() {
        return new ChatPipelineMetrics();
    }

    public void recordStageNanos(String stage, long nanos) {
        if (!enabled) {
            return;
        }
        Timer.builder("openchat_pipeline_stage")
                .tag("stage", stage)
                .register(meterRegistry)
                .record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
    }

    public void recordStage(String stage, long startNanos) {
        recordStageNanos(stage, System.nanoTime() - startNanos);
    }

    public void recordSinceCreated(String stage, ChatMessageDto message) {
        if (!enabled || message == null || message.getCreatedAt() == null) {
            return;
        }
        long elapsedMillis = System.currentTimeMillis() - message.getCreatedAt();
        Timer.builder("openchat_pipeline_since_created")
                .tag("stage", stage)
                .register(meterRegistry)
                .record(Math.max(0, elapsedMillis), TimeUnit.MILLISECONDS);
    }

    public void incrementCounter(String event) {
        if (!enabled) {
            return;
        }
        Counter.builder("openchat_pipeline_events")
                .tag("event", event)
                .register(meterRegistry)
                .increment();
    }

    public void recordDistribution(String name, String type, double amount) {
        if (!enabled) {
            return;
        }
        DistributionSummary.builder(name)
                .tag("type", type)
                .register(meterRegistry)
                .record(amount);
    }
}
