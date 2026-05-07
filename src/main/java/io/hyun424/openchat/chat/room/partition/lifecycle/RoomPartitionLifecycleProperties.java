package io.hyun424.openchat.chat.room.partition.lifecycle;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RoomPartitionLifecycleProperties {

    private final boolean enabled;
    private final long intervalMillis;
    private final long leaseTtlMillis;
    private final int maxActionsPerRun;
    private final String updatedBy;
    private final ScaleUp scaleUp;
    private final ScaleDown scaleDown;
    private final Redistribution redistribution;
    private final Drain drain;

    @Autowired
    public RoomPartitionLifecycleProperties(
            @Value("${app.room-partition.lifecycle.enabled:false}") boolean enabled,
            @Value("${app.room-partition.lifecycle.interval-ms:5000}") long intervalMillis,
            @Value("${app.room-partition.lifecycle.lease-ttl-ms:15000}") long leaseTtlMillis,
            @Value("${app.room-partition.lifecycle.max-actions-per-run:1}") int maxActionsPerRun,
            @Value("${app.room-partition.lifecycle.updated-by:auto-partition-lifecycle}") String updatedBy,
            @Value("${app.room-partition.lifecycle.scale-up.enabled:true}") boolean scaleUpEnabled,
            @Value("${app.room-partition.lifecycle.scale-up.stable-window-ms:30000}") long scaleUpStableWindowMillis,
            @Value("${app.room-partition.lifecycle.scale-up.min-observations:2}") int scaleUpMinObservations,
            @Value("${app.room-partition.lifecycle.scale-up.cooldown-ms:300000}") long scaleUpCooldownMillis,
            @Value("${app.room-partition.lifecycle.scale-down.enabled:true}") boolean scaleDownEnabled,
            @Value("${app.room-partition.lifecycle.scale-down.stable-window-ms:300000}") long scaleDownStableWindowMillis,
            @Value("${app.room-partition.lifecycle.scale-down.min-observations:3}") int scaleDownMinObservations,
            @Value("${app.room-partition.lifecycle.scale-down.cooldown-ms:600000}") long scaleDownCooldownMillis,
            @Value("${app.room-partition.lifecycle.scale-down.min-partition-age-ms:600000}") long scaleDownMinPartitionAgeMillis,
            @Value("${app.room-partition.lifecycle.scale-down.auto-managed-only:true}") boolean scaleDownAutoManagedOnly,
            @Value("${app.room-partition.lifecycle.redistribution.enabled:true}") boolean redistributionEnabled,
            @Value("${app.room-partition.lifecycle.redistribution.limit-per-partition:50}") int redistributionLimitPerPartition,
            @Value("${app.room-partition.lifecycle.redistribution.retry-after-ms:500}") long redistributionRetryAfterMillis,
            @Value("${app.room-partition.lifecycle.drain.complete-empty-observations:2}") int drainCompleteEmptyObservations,
            @Value("${app.room-partition.lifecycle.drain.reconnect-limit-per-partition:50}") int drainReconnectLimitPerPartition,
            @Value("${app.room-partition.lifecycle.drain.reconnect-retry-after-ms:500}") long drainReconnectRetryAfterMillis
    ) {
        this(
                enabled,
                intervalMillis,
                leaseTtlMillis,
                maxActionsPerRun,
                updatedBy,
                new ScaleUp(scaleUpEnabled, scaleUpStableWindowMillis, scaleUpMinObservations, scaleUpCooldownMillis),
                new ScaleDown(scaleDownEnabled, scaleDownStableWindowMillis, scaleDownMinObservations,
                        scaleDownCooldownMillis, scaleDownMinPartitionAgeMillis, scaleDownAutoManagedOnly),
                new Redistribution(redistributionEnabled, redistributionLimitPerPartition, redistributionRetryAfterMillis),
                new Drain(drainCompleteEmptyObservations, drainReconnectLimitPerPartition, drainReconnectRetryAfterMillis)
        );
    }

    public RoomPartitionLifecycleProperties(boolean enabled,
                                            long intervalMillis,
                                            long leaseTtlMillis,
                                            int maxActionsPerRun,
                                            String updatedBy,
                                            ScaleUp scaleUp,
                                            ScaleDown scaleDown,
                                            Redistribution redistribution,
                                            Drain drain) {
        this.enabled = enabled;
        this.intervalMillis = Math.max(1_000L, intervalMillis);
        this.leaseTtlMillis = Math.max(1_000L, leaseTtlMillis);
        this.maxActionsPerRun = Math.max(1, maxActionsPerRun);
        this.updatedBy = updatedBy == null || updatedBy.isBlank() ? "auto-partition-lifecycle" : updatedBy.trim();
        this.scaleUp = scaleUp == null ? ScaleUp.defaults() : scaleUp.normalized();
        this.scaleDown = scaleDown == null ? ScaleDown.defaults() : scaleDown.normalized();
        this.redistribution = redistribution == null ? Redistribution.defaults() : redistribution.normalized();
        this.drain = drain == null ? Drain.defaults() : drain.normalized();
    }

    public boolean enabled() {
        return enabled;
    }

    public long intervalMillis() {
        return intervalMillis;
    }

    public long leaseTtlMillis() {
        return leaseTtlMillis;
    }

    public int maxActionsPerRun() {
        return maxActionsPerRun;
    }

    public String updatedBy() {
        return updatedBy;
    }

    public ScaleUp scaleUp() {
        return scaleUp;
    }

    public ScaleDown scaleDown() {
        return scaleDown;
    }

    public Redistribution redistribution() {
        return redistribution;
    }

    public Drain drain() {
        return drain;
    }

    public record ScaleUp(boolean enabled,
                          long stableWindowMillis,
                          int minObservations,
                          long cooldownMillis) {
        static ScaleUp defaults() {
            return new ScaleUp(true, 30_000L, 2, 300_000L);
        }

        ScaleUp normalized() {
            return new ScaleUp(enabled, Math.max(1_000L, stableWindowMillis), Math.max(1, minObservations), Math.max(0L, cooldownMillis));
        }
    }

    public record ScaleDown(boolean enabled,
                            long stableWindowMillis,
                            int minObservations,
                            long cooldownMillis,
                            long minPartitionAgeMillis,
                            boolean autoManagedOnly) {
        static ScaleDown defaults() {
            return new ScaleDown(true, 300_000L, 3, 600_000L, 600_000L, true);
        }

        ScaleDown normalized() {
            return new ScaleDown(
                    enabled,
                    Math.max(1_000L, stableWindowMillis),
                    Math.max(1, minObservations),
                    Math.max(0L, cooldownMillis),
                    Math.max(0L, minPartitionAgeMillis),
                    autoManagedOnly
            );
        }
    }

    public record Redistribution(boolean enabled,
                                 int limitPerPartition,
                                 long retryAfterMillis) {
        static Redistribution defaults() {
            return new Redistribution(true, 50, 500L);
        }

        Redistribution normalized() {
            return new Redistribution(enabled, Math.max(1, limitPerPartition), Math.max(0L, retryAfterMillis));
        }
    }

    public record Drain(int completeEmptyObservations,
                        int reconnectLimitPerPartition,
                        long reconnectRetryAfterMillis) {
        static Drain defaults() {
            return new Drain(2, 50, 500L);
        }

        Drain normalized() {
            return new Drain(Math.max(1, completeEmptyObservations), Math.max(1, reconnectLimitPerPartition), Math.max(0L, reconnectRetryAfterMillis));
        }
    }
}
