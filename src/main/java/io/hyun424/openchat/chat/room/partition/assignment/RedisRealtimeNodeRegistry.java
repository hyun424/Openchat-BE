package io.hyun424.openchat.chat.room.partition.assignment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "spring.data.redis.host")
public class RedisRealtimeNodeRegistry implements RealtimeNodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(RedisRealtimeNodeRegistry.class);

    static final String NODE_SET_KEY = "openchat:realtime:nodes";
    static final String NODE_KEY_PREFIX = "openchat:realtime:nodes:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper redisObjectMapper;
    private final RealtimeNodeRegistryProperties properties;

    public RedisRealtimeNodeRegistry(StringRedisTemplate redisTemplate,
                                     @Qualifier("redisObjectMapper") ObjectMapper redisObjectMapper,
                                     RealtimeNodeRegistryProperties properties) {
        this.redisTemplate = redisTemplate;
        this.redisObjectMapper = redisObjectMapper;
        this.properties = properties;
    }

    @Override
    public boolean heartbeat(RealtimeNode node) {
        if (node == null || node.nodeId() == null || node.nodeId().isBlank()) {
            return false;
        }
        try {
            String payload = redisObjectMapper.writeValueAsString(node);
            redisTemplate.opsForSet().add(NODE_SET_KEY, node.nodeId());
            redisTemplate.opsForValue().set(key(node.nodeId()), payload, Duration.ofMillis(properties.retentionMillis()));
            return true;
        } catch (Exception e) {
            log.warn("[REALTIME NODE HEARTBEAT FAIL] nodeId={}", node.nodeId(), e);
            return false;
        }
    }

    @Override
    public List<RealtimeNode> activeNodes() {
        Set<String> nodeIds;
        try {
            nodeIds = redisTemplate.opsForSet().members(NODE_SET_KEY);
        } catch (Exception e) {
            log.warn("[REALTIME NODE SET READ FAIL]", e);
            return List.of();
        }
        if (nodeIds == null || nodeIds.isEmpty()) {
            return List.of();
        }

        List<RealtimeNode> nodes = new ArrayList<>();
        for (String nodeId : nodeIds) {
            String payload;
            try {
                payload = redisTemplate.opsForValue().get(key(nodeId));
            } catch (Exception e) {
                log.warn("[REALTIME NODE READ FAIL] nodeId={}", nodeId, e);
                continue;
            }
            if (payload == null || payload.isBlank()) {
                continue;
            }
            try {
                RealtimeNode node = redisObjectMapper.readValue(payload, RealtimeNode.class);
                if (node.active()) {
                    nodes.add(node);
                }
            } catch (Exception e) {
                log.warn("[REALTIME NODE PAYLOAD MALFORMED] nodeId={}", nodeId, e);
            }
        }
        nodes.sort(Comparator.comparing(RealtimeNode::nodeId));
        return List.copyOf(nodes);
    }

    private String key(String nodeId) {
        return NODE_KEY_PREFIX + nodeId;
    }
}
