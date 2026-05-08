package io.hyun424.openchat.chat.room.partition.assignment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.room-partition.assignment.enabled", havingValue = "true")
public class RedisRealtimeNodeRegistry implements RealtimeNodeRegistry {

    private static final String NODES_KEY = "openchat:realtime:nodes";
    private static final String NODE_KEY_PREFIX = "openchat:realtime:nodes:";
    private static final String DRAINING_KEY = "openchat:realtime:nodes:draining";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RoomPartitionAssignmentProperties properties;

    public RedisRealtimeNodeRegistry(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RoomPartitionAssignmentProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void heartbeat(RealtimeNode node) {
        if (node == null || node.nodeId() == null || node.nodeId().isBlank()) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(node);
            String nodeKey = nodeKey(node.nodeId());
            redisTemplate.opsForValue().set(nodeKey, payload, java.time.Duration.ofMillis(properties.retentionMs()));
            redisTemplate.opsForSet().add(NODES_KEY, node.nodeId());
        } catch (Exception e) {
            log.warn("realtime node heartbeat failed nodeId={}", node.nodeId(), e);
        }
    }

    @Override
    public List<RealtimeNode> nodes() {
        try {
            Set<String> ids = redisTemplate.opsForSet().members(NODES_KEY);
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            Set<String> drainingIds = drainingNodeIds();
            Instant now = Instant.now();
            List<RealtimeNode> nodes = new ArrayList<>();
            for (String id : ids) {
                String payload = redisTemplate.opsForValue().get(nodeKey(id));
                if (payload == null || payload.isBlank()) {
                    redisTemplate.opsForSet().remove(NODES_KEY, id);
                    redisTemplate.opsForSet().remove(DRAINING_KEY, id);
                    continue;
                }
                try {
                    RealtimeNode node = objectMapper.readValue(payload, RealtimeNode.class);
                    RealtimeNode normalized = new RealtimeNode(
                            node.nodeId(),
                            node.role(),
                            node.wsUrl(),
                            node.draining() || drainingIds.contains(node.nodeId()),
                            node.reportedAt(),
                            node.expiresAt(),
                            node.subscribedPartitions() == null ? Set.of() : node.subscribedPartitions(),
                            Math.max(0, node.openSessions())
                    );
                    if (normalized.expiresAt() != null && normalized.expiresAt().isAfter(now)) {
                        nodes.add(normalized);
                    } else {
                        redisTemplate.opsForSet().remove(NODES_KEY, id);
                        redisTemplate.opsForSet().remove(DRAINING_KEY, id);
                    }
                } catch (Exception e) {
                    log.warn("malformed realtime node registry entry nodeId={}", id, e);
                }
            }
            return nodes;
        } catch (Exception e) {
            log.warn("realtime node registry read failed", e);
            return List.of();
        }
    }

    @Override
    public Set<String> drainingNodeIds() {
        try {
            Set<String> ids = redisTemplate.opsForSet().members(DRAINING_KEY);
            return ids == null ? Set.of() : ids;
        } catch (Exception e) {
            log.warn("realtime node draining registry read failed", e);
            return Set.of();
        }
    }

    @Override
    public void markDraining(String nodeId, boolean draining) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }
        try {
            if (draining) {
                redisTemplate.opsForSet().add(DRAINING_KEY, nodeId);
            } else {
                redisTemplate.opsForSet().remove(DRAINING_KEY, nodeId);
            }
        } catch (Exception e) {
            log.warn("realtime node draining update failed nodeId={} draining={}", nodeId, draining, e);
        }
    }

    private String nodeKey(String nodeId) {
        return NODE_KEY_PREFIX + nodeId;
    }
}
