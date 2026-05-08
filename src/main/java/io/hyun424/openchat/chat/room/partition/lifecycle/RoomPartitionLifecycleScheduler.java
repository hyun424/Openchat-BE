package io.hyun424.openchat.chat.room.partition.lifecycle;

import io.hyun424.openchat.chat.room.workload.service.RealtimeWorkloadClusterSummaryService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.room-partition.lifecycle.enabled", havingValue = "true")
public class RoomPartitionLifecycleScheduler {

    private static final String LEASE_KEY = "openchat:room-partition:lifecycle:lease";

    private final RealtimeWorkloadClusterSummaryService summaryService;
    private final RoomPartitionLifecycleService lifecycleService;
    private final RoomPartitionLifecycleProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final String token = UUID.randomUUID().toString();

    public RoomPartitionLifecycleScheduler(RealtimeWorkloadClusterSummaryService summaryService,
                                           RoomPartitionLifecycleService lifecycleService,
                                           RoomPartitionLifecycleProperties properties,
                                           StringRedisTemplate redisTemplate) {
        this.summaryService = summaryService;
        this.lifecycleService = lifecycleService;
        this.properties = properties;
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    void logEnabled() {
        log.info("partition lifecycle scheduler enabled intervalMs={} leaseTtlMs={} updatedBy={}",
                properties.intervalMillis(), properties.leaseTtlMillis(), properties.updatedBy());
    }

    @Scheduled(fixedDelayString = "${app.room-partition.lifecycle.interval-ms:5000}")
    public void run() {
        if (!properties.enabled()) {
            return;
        }
        if (!acquireLease()) {
            return;
        }
        try {
            int actions = lifecycleService.run(summaryService.summary());
            if (actions > 0) {
                log.info("partition lifecycle scheduler completed actions={}", actions);
            }
        } catch (RuntimeException e) {
            log.warn("partition lifecycle scheduler failed", e);
        }
    }

    private boolean acquireLease() {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                LEASE_KEY,
                token,
                Duration.ofMillis(properties.leaseTtlMillis())
        );
        return Boolean.TRUE.equals(acquired);
    }
}
