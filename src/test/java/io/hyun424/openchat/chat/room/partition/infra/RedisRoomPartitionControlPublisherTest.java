package io.hyun424.openchat.chat.room.partition.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionControlCommand;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRoomPartitionControlPublisherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("room partition control channel에 reconnect command JSON을 publish한다")
    void publish_sendsSerializedCommandToRoomControlChannel() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisRoomPartitionControlPublisher publisher = new RedisRoomPartitionControlPublisher(
                redisTemplate,
                objectMapper,
                new RoomPartitionControlChannelResolver(),
                new RoomPartitionMetrics(new SimpleMeterRegistry())
        );
        when(redisTemplate.convertAndSend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(1L);

        boolean published = publisher.publish(RoomPartitionControlCommand.reconnect(
                10L,
                2,
                "scale_down",
                100,
                500,
                4
        ));

        assertTrue(published);
        ArgumentCaptor<String> channelCaptor = forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = forClass(String.class);
        verify(redisTemplate).convertAndSend(channelCaptor.capture(), payloadCaptor.capture());
        assertEquals("openchat:room-partition-control:10", channelCaptor.getValue());
        RoomPartitionControlCommand payload =
                objectMapper.readValue(payloadCaptor.getValue(), RoomPartitionControlCommand.class);
        assertEquals(RoomPartitionControlCommand.TYPE_RECONNECT, payload.type());
        assertEquals(10L, payload.roomId());
        assertEquals(2, payload.partitionId());
        assertEquals("scale_down", payload.reason());
        assertEquals(100, payload.limit());
        assertEquals(500L, payload.retryAfterMs());
        assertEquals(4L, payload.routeVersion());
    }

    @Test
    @DisplayName("node control channel receiver가 없으면 publish 실패로 처리한다")
    void publish_returnsFalseWhenNodeControlHasNoReceivers() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisRoomPartitionControlPublisher publisher = new RedisRoomPartitionControlPublisher(
                redisTemplate,
                objectMapper,
                new RoomPartitionControlChannelResolver(),
                new RoomPartitionMetrics(new SimpleMeterRegistry())
        );
        when(redisTemplate.convertAndSend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(0L);

        boolean published = publisher.publish(RoomPartitionControlCommand.nodeReconnect(
                "node-a",
                "node_drain",
                100,
                500
        ));

        assertFalse(published);
    }
}
