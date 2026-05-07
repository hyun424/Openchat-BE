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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
}
