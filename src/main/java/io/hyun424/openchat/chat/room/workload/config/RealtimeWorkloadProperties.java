package io.hyun424.openchat.chat.room.workload.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RealtimeWorkloadProperties {

    private final boolean publishEnabled;
    private final boolean summaryEnabled;
    private final long publishIntervalMillis;
    private final long snapshotTtlMillis;
    private final long snapshotRetentionMillis;
    private final int topRoomLimit;
    private final double watchRatio;
    private final long podWorkBudgetDeliveryPerSecond;

    public RealtimeWorkloadProperties(
            @Value("${app.realtime-workload.publish-enabled:true}") boolean publishEnabled,
            @Value("${app.realtime-workload.summary-enabled:false}") boolean summaryEnabled,
            @Value("${app.realtime-workload.publish-interval-ms:5000}") long publishIntervalMillis,
            @Value("${app.realtime-workload.snapshot-ttl-ms:30000}") long snapshotTtlMillis,
            @Value("${app.realtime-workload.top-room-limit:10}") int topRoomLimit,
            @Value("${app.realtime-workload.watch-ratio:0.7}") double watchRatio,
            @Value("${app.room.scale.pod-work-budget-delivery-per-sec:10000}") long podWorkBudgetDeliveryPerSecond
    ) {
        this.publishEnabled = publishEnabled;
        this.summaryEnabled = summaryEnabled;
        this.publishIntervalMillis = Math.max(1_000L, publishIntervalMillis);
        this.snapshotTtlMillis = Math.max(this.publishIntervalMillis * 2, snapshotTtlMillis);
        this.snapshotRetentionMillis = Math.max(this.snapshotTtlMillis * 2, this.publishIntervalMillis * 3);
        this.topRoomLimit = Math.max(1, topRoomLimit);
        this.watchRatio = Math.max(0.01, Math.min(1.0, watchRatio));
        this.podWorkBudgetDeliveryPerSecond = Math.max(1, podWorkBudgetDeliveryPerSecond);
    }

    public boolean publishEnabled() {
        return publishEnabled;
    }

    public boolean summaryEnabled() {
        return summaryEnabled;
    }

    public long publishIntervalMillis() {
        return publishIntervalMillis;
    }

    public long snapshotTtlMillis() {
        return snapshotTtlMillis;
    }

    public long snapshotRetentionMillis() {
        return snapshotRetentionMillis;
    }

    public int topRoomLimit() {
        return topRoomLimit;
    }

    public double watchRatio() {
        return watchRatio;
    }

    public long podWorkBudgetDeliveryPerSecond() {
        return podWorkBudgetDeliveryPerSecond;
    }
}
