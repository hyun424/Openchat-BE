package io.hyun424.openchat.chat.room.partition.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionControlCommand;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Primary
@Component
public class RedisRoomPartitionControlPublisher implements RoomPartitionControlPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper redisObjectMapper;
    private final RoomPartitionControlChannelResolver channelResolver;
    private final RoomPartitionMetrics metrics;

    public RedisRoomPartitionControlPublisher(
            StringRedisTemplate redisTemplate,
            @Qualifier("redisObjectMapper") ObjectMapper redisObjectMapper,
            RoomPartitionControlChannelResolver channelResolver,
            RoomPartitionMetrics metrics
    ) {
        this.redisTemplate = redisTemplate;
        this.redisObjectMapper = redisObjectMapper;
        this.channelResolver = channelResolver;
        this.metrics = metrics;
    }

    @Override
    public boolean publish(RoomPartitionControlCommand command) {
        String type = command == null ? "unknown" : command.type();
        try {
            if (command == null || (command.roomId() == null && command.nodeId() == null)) {
                metrics.recordControlPublish(type, "invalid");
                return false;
            }
            String payload = redisObjectMapper.writeValueAsString(command);
            String channel = command.nodeId() == null
                    ? channelResolver.channel(command.roomId())
                    : channelResolver.nodeChannel(command.nodeId());
            Long receivers = redisTemplate.convertAndSend(channel, payload);
            if (command.nodeId() != null && (receivers == null || receivers <= 0)) {
                metrics.recordControlPublish(type, "no_receivers");
                log.warn("[ROOM PARTITION CONTROL PUB NO RECEIVERS] type={} nodeId={}",
                        type, command.nodeId());
                return false;
            }
            metrics.recordControlPublish(type, "success");
            return true;
        } catch (Exception e) {
            metrics.recordControlPublish(type, "publish_failed");
            log.warn("[ROOM PARTITION CONTROL PUB FAIL] type={} roomId={} nodeId={}",
                    type,
                    command != null ? command.roomId() : null,
                    command != null ? command.nodeId() : null,
                    e);
            return false;
        }
    }
}
