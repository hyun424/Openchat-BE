package io.hyun424.openchat.infra.metrics;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChatPipelineMetrics {

    private final MeterRegistry meterRegistry;
    private final Map<String, Timer> stageTimers = new ConcurrentHashMap<>();
    private final Map<String, DistributionSummary> summaries = new ConcurrentHashMap<>();
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public ChatPipelineMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordStageMillis(String stage, long millis) {
        if (millis < 0) {
            return;
        }
        stageTimer(stage).record(Duration.ofMillis(millis));
    }

    public void recordStageNanos(String stage, long nanos) {
        if (nanos < 0) {
            return;
        }
        stageTimer(stage).record(Duration.ofNanos(nanos));
    }

    public void recordSinceCreated(String stage, ChatMessageDto message) {
        if (message == null || message.getCreatedAt() == null) {
            return;
        }
        recordStageMillis(stage, System.currentTimeMillis() - message.getCreatedAt());
    }

    public void recordSummary(String name, double value) {
        summary(name).record(value);
    }

    public void incrementCounter(String name) {
        counter(name).increment();
    }

    public void bindFanoutDispatcherQueue(int stripe, BlockingQueue<?> queue) {
        Gauge.builder("openchat_fanout_dispatcher_queue_size", queue, BlockingQueue::size)
                .description("Current fanout dispatcher queue size")
                .tag("stripe", String.valueOf(stripe))
                .register(meterRegistry);
    }

    public void bindBroadcastLaneQueue(int lane, BlockingQueue<?> queue) {
        Gauge.builder("openchat_ws_broadcast_lane_queue_size", queue, BlockingQueue::size)
                .description("Current WebSocket broadcast lane queue size")
                .tag("lane", String.valueOf(lane))
                .register(meterRegistry);
    }

    private Timer stageTimer(String stage) {
        return stageTimers.computeIfAbsent(stage, key ->
                Timer.builder("openchat_chat_stage_latency")
                        .description("Chat pipeline stage latency")
                        .tag("stage", key)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .publishPercentileHistogram()
                        .register(meterRegistry));
    }

    private DistributionSummary summary(String name) {
        return summaries.computeIfAbsent(name, key ->
                DistributionSummary.builder(key)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .publishPercentileHistogram()
                        .register(meterRegistry));
    }

    private Counter counter(String name) {
        return counters.computeIfAbsent(name, key ->
                Counter.builder(key)
                        .register(meterRegistry));
    }
}
