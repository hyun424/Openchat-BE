package io.hyun424.openchat.chat.metrics;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
@Component
public class ChatPipelineMetrics {

    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final ConcurrentHashMap<String, Timer> timers;
    private final ConcurrentHashMap<String, Counter> counters;
    private final ConcurrentHashMap<String, DistributionSummary> summaries;
    private final ConcurrentHashMap<String, DurationAccumulator> durationStats;
    private final ConcurrentHashMap<String, DistributionAccumulator> distributionStats;
    private final ConcurrentHashMap<String, LongAdder> counterStats;
    private final ScheduledExecutorService loggerExecutor;

    @Autowired
    public ChatPipelineMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.enabled = true;
        this.timers = new ConcurrentHashMap<>();
        this.counters = new ConcurrentHashMap<>();
        this.summaries = new ConcurrentHashMap<>();
        this.durationStats = new ConcurrentHashMap<>();
        this.distributionStats = new ConcurrentHashMap<>();
        this.counterStats = new ConcurrentHashMap<>();
        this.loggerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chat-pipeline-metrics-logger");
            t.setDaemon(true);
            return t;
        });
        Counter.builder("openchat_pipeline_probe")
                .tag("component", "chat")
                .register(meterRegistry)
                .increment();
        log.info("[PIPELINE METRICS] enabled registry={}", meterRegistry.getClass().getName());
        startPeriodicLogging();
    }

    private ChatPipelineMetrics() {
        this.meterRegistry = null;
        this.enabled = false;
        this.timers = null;
        this.counters = null;
        this.summaries = null;
        this.durationStats = null;
        this.distributionStats = null;
        this.counterStats = null;
        this.loggerExecutor = null;
    }

    public static ChatPipelineMetrics noop() {
        return new ChatPipelineMetrics();
    }

    public void recordStageNanos(String stage, long nanos) {
        if (!enabled) {
            return;
        }
        long safeNanos = Math.max(0, nanos);
        durationStats.computeIfAbsent("stage." + stage, ignored -> new DurationAccumulator()).record(safeNanos);
        timer("openchat_pipeline_stage", stage).record(safeNanos, TimeUnit.NANOSECONDS);
    }

    public void recordStage(String stage, long startNanos) {
        recordStageNanos(stage, System.nanoTime() - startNanos);
    }

    public void recordSinceCreated(String stage, ChatMessageDto message) {
        if (!enabled || message == null || message.getCreatedAt() == null) {
            return;
        }
        recordSinceEpochMillis(stage, message.getCreatedAt());
    }

    public void recordSinceEpochMillis(String stage, Long epochMillis) {
        if (!enabled || epochMillis == null) {
            return;
        }
        long elapsedMillis = System.currentTimeMillis() - epochMillis;
        long safeMillis = Math.max(0, elapsedMillis);
        durationStats.computeIfAbsent("since." + stage, ignored -> new DurationAccumulator())
                .record(TimeUnit.MILLISECONDS.toNanos(safeMillis));
        timer("openchat_pipeline_since_created", stage).record(safeMillis, TimeUnit.MILLISECONDS);
    }

    public void incrementCounter(String event) {
        incrementCounter(event, 1);
    }

    public void incrementCounter(String event, double amount) {
        if (!enabled) {
            return;
        }
        double safeAmount = Math.max(0, amount);
        if (safeAmount == 0) {
            return;
        }
        counterStats.computeIfAbsent(event, ignored -> new LongAdder()).add(Math.round(safeAmount));
        counters.computeIfAbsent(event, key -> Counter.builder("openchat_pipeline_events")
                .tag("event", key)
                .register(meterRegistry)).increment(safeAmount);
    }

    public void recordWebSocketSendAttempt(int logicalDeliveries) {
        int safeDeliveries = Math.max(1, logicalDeliveries);
        incrementCounter("ws.send.attempted", safeDeliveries);
        incrementCounter("ws.send.frame.attempted");
    }

    public void recordWebSocketSendSuccess(int logicalDeliveries, int payloadBytes, long startNanos) {
        int safeDeliveries = Math.max(1, logicalDeliveries);
        incrementCounter("ws.send.succeeded", safeDeliveries);
        incrementCounter("ws.send.frame.succeeded");
        incrementCounter("ws.send.bytes", Math.max(0, payloadBytes));
        recordStage("ws.send.duration", startNanos);
    }

    public void recordWebSocketSendFailure(int logicalDeliveries, String reason, long startNanos) {
        int safeDeliveries = Math.max(1, logicalDeliveries);
        String safeReason = reason == null || reason.isBlank() ? "unknown" : reason;
        incrementCounter("ws.send.failed", safeDeliveries);
        incrementCounter("ws.send.frame.failed");
        incrementCounter("ws.send.fail." + safeReason);
        recordStage("ws.send.duration.fail", startNanos);
    }

    public long counterValue(String event) {
        if (!enabled || event == null || event.isBlank()) {
            return 0;
        }
        LongAdder counter = counterStats.get(event);
        return counter == null ? 0 : counter.sum();
    }

    public void recordDistribution(String name, String type, double amount) {
        if (!enabled) {
            return;
        }
        double safeAmount = Math.max(0, amount);
        distributionStats.computeIfAbsent(name + "." + type, ignored -> new DistributionAccumulator()).record(safeAmount);
        summaries.computeIfAbsent(name + "." + type, ignored -> DistributionSummary.builder(name)
                .tag("type", type)
                .register(meterRegistry)).record(safeAmount);
    }

    @PreDestroy
    public void shutdown() {
        if (!enabled) {
            return;
        }
        logSnapshot();
        loggerExecutor.shutdown();
    }

    private Timer timer(String name, String stage) {
        return timers.computeIfAbsent(name + "." + stage, ignored -> Timer.builder(name)
                .tag("stage", stage)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry));
    }

    private void startPeriodicLogging() {
        loggerExecutor.scheduleAtFixedRate(this::logSnapshotSafely, 10, 10, TimeUnit.SECONDS);
    }

    private void logSnapshotSafely() {
        try {
            logSnapshot();
        } catch (Exception e) {
            log.warn("[PIPELINE METRICS] failed to log snapshot", e);
        }
    }

    private void logSnapshot() {
        durationStats.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    DurationSnapshot snapshot = entry.getValue().snapshotAndReset();
                    if (snapshot.count() > 0) {
                        log.info("[PIPELINE METRICS] type=duration name={} count={} avgMs={} maxMs={}",
                                entry.getKey(),
                                snapshot.count(),
                                formatMillis(snapshot.avgNanos()),
                                formatMillis(snapshot.maxNanos()));
                    }
                });

        distributionStats.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    DistributionSnapshot snapshot = entry.getValue().snapshotAndReset();
                    if (snapshot.count() > 0) {
                        log.info("[PIPELINE METRICS] type=distribution name={} count={} avg={} max={}",
                                entry.getKey(),
                                snapshot.count(),
                                formatDouble(snapshot.avg()),
                                formatDouble(snapshot.max()));
                    }
                });

        counterStats.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> {
                    long count = entry.getValue().sumThenReset();
                    if (count > 0) {
                        log.info("[PIPELINE METRICS] type=counter name={} count={}", entry.getKey(), count);
                    }
                });
    }

    private static String formatMillis(double nanos) {
        return formatDouble(nanos / 1_000_000.0);
    }

    private static String formatDouble(double value) {
        return String.format("%.3f", value);
    }

    private static final class DurationAccumulator {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();

        void record(long nanos) {
            count.increment();
            totalNanos.add(nanos);
            maxNanos.accumulateAndGet(nanos, Math::max);
        }

        DurationSnapshot snapshotAndReset() {
            long c = count.sumThenReset();
            long total = totalNanos.sumThenReset();
            long max = maxNanos.getAndSet(0);
            return new DurationSnapshot(c, c == 0 ? 0 : (double) total / c, max);
        }
    }

    private record DurationSnapshot(long count, double avgNanos, long maxNanos) {
    }

    private static final class DistributionAccumulator {
        private final LongAdder count = new LongAdder();
        private final LongAdder total = new LongAdder();
        private final AtomicLong max = new AtomicLong();

        void record(double amount) {
            long scaled = Math.round(amount * 1000);
            count.increment();
            total.add(scaled);
            max.accumulateAndGet(scaled, Math::max);
        }

        DistributionSnapshot snapshotAndReset() {
            long c = count.sumThenReset();
            long sum = total.sumThenReset();
            long currentMax = max.getAndSet(0);
            return new DistributionSnapshot(
                    c,
                    c == 0 ? 0 : (sum / 1000.0) / c,
                    currentMax / 1000.0
            );
        }
    }

    private record DistributionSnapshot(long count, double avg, double max) {
    }
}
