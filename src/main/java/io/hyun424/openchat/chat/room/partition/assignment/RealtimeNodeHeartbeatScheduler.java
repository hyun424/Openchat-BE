package io.hyun424.openchat.chat.room.partition.assignment;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = "app.room-partition.node-registry.enabled", havingValue = "true")
@ConditionalOnBean(RealtimeNodeRegistry.class)
public class RealtimeNodeHeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeNodeHeartbeatScheduler.class);

    private final RealtimeNodeRegistry registry;
    private final RealtimeNodeRegistryProperties properties;
    private final Instant startedAt = Instant.now();

    public RealtimeNodeHeartbeatScheduler(RealtimeNodeRegistry registry,
                                          RealtimeNodeRegistryProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @PostConstruct
    void logEnabled() {
        log.info("realtime node registry enabled nodeId={} host={} port={} heartbeatIntervalMs={} retentionMs={}",
                properties.nodeId(), properties.host(), properties.port(),
                properties.heartbeatIntervalMillis(), properties.retentionMillis());
    }

    @Scheduled(fixedDelayString = "${app.room-partition.node-registry.heartbeat-interval-ms:5000}")
    public void heartbeat() {
        registry.heartbeat(new RealtimeNode(
                properties.nodeId(),
                properties.role(),
                properties.host(),
                properties.port(),
                startedAt,
                Instant.now(),
                false
        ));
    }
}
