package io.hyun424.openchat.chat.room.workload.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.room.hot.RoomScaleTier;
import io.hyun424.openchat.chat.room.workload.config.RealtimeWorkloadProperties;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeNodeWorkloadSnapshot;
import io.hyun424.openchat.chat.room.workload.dto.RoomWorkloadCandidate;
import io.hyun424.openchat.chat.room.workload.metrics.RealtimeWorkloadMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeWorkloadSnapshotRepositoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RealtimeWorkloadProperties properties = new RealtimeWorkloadProperties(
            true, true, 5_000, 30_000, 10, 0.7, 10_000);
    private final RealtimeWorkloadMetrics metrics = new RealtimeWorkloadMetrics(new SimpleMeterRegistry());

    @Test
    void saveStoresSnapshotWithRetentionTtlAndRegistersNodeId() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        RealtimeWorkloadSnapshotRepository repository = new RealtimeWorkloadSnapshotRepository(
                redisTemplate, objectMapper, properties, metrics);

        assertTrue(repository.save(snapshot("node-1", 1_000, 31_000)));

        verify(setOps).add(RealtimeWorkloadSnapshotRepository.NODE_SET_KEY, "node-1");
        verify(valueOps).set(eq("openchat:realtime:workload:nodes:node-1"), anyString(), eq(Duration.ofMillis(60_000)));
    }

    @Test
    void saveFailureDoesNotThrow() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForSet()).thenThrow(new IllegalStateException("redis down"));
        RealtimeWorkloadSnapshotRepository repository = new RealtimeWorkloadSnapshotRepository(
                redisTemplate, objectMapper, properties, metrics);

        assertFalse(repository.save(snapshot("node-1", 1_000, 31_000)));
    }

    @Test
    void readAllReturnsValidSnapshotsAndSkipsMalformedPayload() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(setOps.members(RealtimeWorkloadSnapshotRepository.NODE_SET_KEY)).thenReturn(Set.of("node-1", "node-2"));
        when(valueOps.get("openchat:realtime:workload:nodes:node-1"))
                .thenReturn(objectMapper.writeValueAsString(snapshot("node-1", 1_000, 31_000)));
        when(valueOps.get("openchat:realtime:workload:nodes:node-2")).thenReturn("not-json");
        RealtimeWorkloadSnapshotRepository repository = new RealtimeWorkloadSnapshotRepository(
                redisTemplate, objectMapper, properties, metrics);

        List<RealtimeNodeWorkloadSnapshot> snapshots = repository.readAll();

        assertEquals(1, snapshots.size());
        assertEquals("node-1", snapshots.get(0).nodeId());
    }

    private RealtimeNodeWorkloadSnapshot snapshot(String nodeId, long reportedAt, long expiresAt) {
        return new RealtimeNodeWorkloadSnapshot(
                nodeId,
                "realtime",
                reportedAt,
                expiresAt,
                Set.of(0),
                Set.of(0),
                10,
                7,
                3,
                0,
                500,
                300,
                500,
                0,
                0,
                0,
                List.of(new RoomWorkloadCandidate(1L, 500, 300, 500, RoomScaleTier.SMALL, 1, 1, 7, false, nodeId))
        );
    }
}
