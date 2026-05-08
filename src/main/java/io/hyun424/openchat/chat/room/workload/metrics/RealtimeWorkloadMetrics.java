package io.hyun424.openchat.chat.room.workload.metrics;

import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadRecommendationType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RealtimeWorkloadMetrics {

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> publishCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> readCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<RealtimeWorkloadRecommendationType, Counter> recommendationCounters = new ConcurrentHashMap<>();
    private final AtomicInteger activeNodeCount = new AtomicInteger(0);
    private final AtomicInteger staleNodeCount = new AtomicInteger(0);
    private final AtomicInteger clusterSessions = new AtomicInteger(0);
    private final AtomicInteger clusterActiveSessions = new AtomicInteger(0);
    private final AtomicLong clusterDecisionWorkMax = new AtomicLong(0);

    public RealtimeWorkloadMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("openchat_realtime_workload_active_node_count", activeNodeCount, AtomicInteger::get)
                .description("Realtime workload active node count from latest cluster summary")
                .register(meterRegistry);
        Gauge.builder("openchat_realtime_workload_stale_node_count", staleNodeCount, AtomicInteger::get)
                .description("Realtime workload stale node count from latest cluster summary")
                .register(meterRegistry);
        Gauge.builder("openchat_realtime_workload_cluster_sessions", clusterSessions, AtomicInteger::get)
                .description("Realtime workload total open sessions from latest cluster summary")
                .register(meterRegistry);
        Gauge.builder("openchat_realtime_workload_cluster_active_sessions", clusterActiveSessions, AtomicInteger::get)
                .description("Realtime workload active sessions from latest cluster summary")
                .register(meterRegistry);
        Gauge.builder("openchat_realtime_workload_cluster_decision_work_max", clusterDecisionWorkMax, AtomicLong::get)
                .description("Realtime workload max scale decision work from latest cluster summary")
                .register(meterRegistry);
    }

    public void recordSnapshotPublish(String result) {
        publishCounters.computeIfAbsent(safe(result), key -> Counter.builder("openchat_realtime_workload_snapshot_publish_total")
                .description("Realtime workload snapshot publish events")
                .tag("result", key)
                .register(meterRegistry)).increment();
    }

    public void recordSnapshotRead(String result) {
        readCounters.computeIfAbsent(safe(result), key -> Counter.builder("openchat_realtime_workload_snapshot_read_total")
                .description("Realtime workload snapshot read events")
                .tag("result", key)
                .register(meterRegistry)).increment();
    }

    public void recordRecommendation(RealtimeWorkloadRecommendationType type) {
        recommendationCounters.computeIfAbsent(type, key -> Counter.builder("openchat_realtime_workload_recommendation_total")
                .description("Realtime workload recommendation decisions")
                .tag("type", key.name().toLowerCase())
                .register(meterRegistry)).increment();
    }

    public void updateClusterSummary(int activeNodes,
                                     int staleNodes,
                                     int sessions,
                                     int activeSessions,
                                     long decisionWorkMax) {
        activeNodeCount.set(Math.max(0, activeNodes));
        staleNodeCount.set(Math.max(0, staleNodes));
        clusterSessions.set(Math.max(0, sessions));
        clusterActiveSessions.set(Math.max(0, activeSessions));
        clusterDecisionWorkMax.set(Math.max(0, decisionWorkMax));
    }

    private String safe(String result) {
        return result == null || result.isBlank() ? "unknown" : result;
    }
}
