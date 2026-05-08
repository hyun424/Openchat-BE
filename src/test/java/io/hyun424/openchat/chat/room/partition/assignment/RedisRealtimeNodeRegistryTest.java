package io.hyun424.openchat.chat.room.partition.assignment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRealtimeNodeRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final RealtimeNodeRegistryProperties properties = new RealtimeNodeRegistryProperties(
            true,
            "node-a",
            "realtime",
            "node-a.internal",
            8080,
            5_000,
            15_000
    );

    @Test
    void heartbeatStoresNodePayloadWithTtlAndRegistersNodeId() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        RedisRealtimeNodeRegistry registry = new RedisRealtimeNodeRegistry(redisTemplate, objectMapper, properties);

        assertTrue(registry.heartbeat(node("node-a", false)));

        verify(setOps).add(RedisRealtimeNodeRegistry.NODE_SET_KEY, "node-a");
        verify(valueOps).set(eq("openchat:realtime:nodes:node-a"), anyString(), eq(Duration.ofMillis(15_000)));
    }

    @Test
    void heartbeatFailureDoesNotThrow() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForSet()).thenThrow(new IllegalStateException("redis down"));
        RedisRealtimeNodeRegistry registry = new RedisRealtimeNodeRegistry(redisTemplate, objectMapper, properties);

        assertFalse(registry.heartbeat(node("node-a", false)));
    }

    @Test
    void activeNodesReturnsSortedNonDrainingNodesAndSkipsMalformedPayload() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(setOps.members(RedisRealtimeNodeRegistry.NODE_SET_KEY)).thenReturn(Set.of("node-c", "node-a", "node-b", "node-d"));
        when(valueOps.get("openchat:realtime:nodes:node-c")).thenReturn(objectMapper.writeValueAsString(node("node-c", false)));
        when(valueOps.get("openchat:realtime:nodes:node-a")).thenReturn(objectMapper.writeValueAsString(node("node-a", false)));
        when(valueOps.get("openchat:realtime:nodes:node-b")).thenReturn(objectMapper.writeValueAsString(node("node-b", true)));
        when(valueOps.get("openchat:realtime:nodes:node-d")).thenReturn("not-json");
        RedisRealtimeNodeRegistry registry = new RedisRealtimeNodeRegistry(redisTemplate, objectMapper, properties);

        List<RealtimeNode> nodes = registry.activeNodes();

        assertEquals(List.of("node-a", "node-c"), nodes.stream().map(RealtimeNode::nodeId).toList());
    }

    private RealtimeNode node(String nodeId, boolean draining) {
        return new RealtimeNode(
                nodeId,
                "realtime",
                nodeId + ".internal",
                8080,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1),
                draining
        );
    }
}
