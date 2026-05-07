package io.hyun424.openchat.chat.room.workload.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.room.workload.config.RealtimeWorkloadProperties;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeNodeWorkloadSnapshot;
import io.hyun424.openchat.chat.room.workload.metrics.RealtimeWorkloadMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@ConditionalOnProperty(name = "spring.data.redis.host")
public class RealtimeWorkloadSnapshotRepository {

    static final String NODE_SET_KEY = "openchat:realtime:workload:nodes";
    static final String NODE_KEY_PREFIX = "openchat:realtime:workload:nodes:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper redisObjectMapper;
    private final RealtimeWorkloadProperties properties;
    private final RealtimeWorkloadMetrics metrics;

    public RealtimeWorkloadSnapshotRepository(StringRedisTemplate redisTemplate,
                                              @Qualifier("redisObjectMapper") ObjectMapper redisObjectMapper,
                                              RealtimeWorkloadProperties properties,
                                              RealtimeWorkloadMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.redisObjectMapper = redisObjectMapper;
        this.properties = properties;
        this.metrics = metrics;
    }

    public boolean save(RealtimeNodeWorkloadSnapshot snapshot) {
        if (snapshot == null || snapshot.nodeId() == null || snapshot.nodeId().isBlank()) {
            metrics.recordSnapshotPublish("invalid");
            return false;
        }
        try {
            String payload = redisObjectMapper.writeValueAsString(snapshot);
            redisTemplate.opsForSet().add(NODE_SET_KEY, snapshot.nodeId());
            redisTemplate.opsForValue().set(key(snapshot.nodeId()), payload, Duration.ofMillis(properties.snapshotRetentionMillis()));
            metrics.recordSnapshotPublish("success");
            return true;
        } catch (Exception e) {
            metrics.recordSnapshotPublish("failed");
            log.warn("[REALTIME WORKLOAD SNAPSHOT SAVE FAIL] nodeId={}", snapshot.nodeId(), e);
            return false;
        }
    }

    public List<RealtimeNodeWorkloadSnapshot> readAll() {
        Set<String> nodeIds;
        try {
            nodeIds = redisTemplate.opsForSet().members(NODE_SET_KEY);
        } catch (Exception e) {
            metrics.recordSnapshotRead("node_set_failed");
            log.warn("[REALTIME WORKLOAD SNAPSHOT NODE SET READ FAIL]", e);
            return List.of();
        }
        if (nodeIds == null || nodeIds.isEmpty()) {
            metrics.recordSnapshotRead("empty");
            return List.of();
        }

        List<RealtimeNodeWorkloadSnapshot> snapshots = new ArrayList<>();
        for (String nodeId : nodeIds) {
            String payload;
            try {
                payload = redisTemplate.opsForValue().get(key(nodeId));
            } catch (Exception e) {
                metrics.recordSnapshotRead("value_failed");
                log.warn("[REALTIME WORKLOAD SNAPSHOT READ FAIL] nodeId={}", nodeId, e);
                continue;
            }
            if (payload == null || payload.isBlank()) {
                metrics.recordSnapshotRead("missing");
                continue;
            }
            try {
                snapshots.add(redisObjectMapper.readValue(payload, RealtimeNodeWorkloadSnapshot.class));
                metrics.recordSnapshotRead("success");
            } catch (Exception e) {
                metrics.recordSnapshotRead("malformed");
                log.warn("[REALTIME WORKLOAD SNAPSHOT MALFORMED] nodeId={}", nodeId, e);
            }
        }
        return snapshots;
    }

    private String key(String nodeId) {
        return NODE_KEY_PREFIX + nodeId;
    }
}
