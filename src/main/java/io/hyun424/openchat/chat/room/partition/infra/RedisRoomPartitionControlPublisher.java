package io.hyun424.openchat.chat.room.partition.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionControlCommand;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    private final boolean commandTraceEnabled;

    @Autowired
    public RedisRoomPartitionControlPublisher(
            StringRedisTemplate redisTemplate,
            @Qualifier("redisObjectMapper") ObjectMapper redisObjectMapper,
            RoomPartitionControlChannelResolver channelResolver,
            RoomPartitionMetrics metrics,
            @Value("${app.room-partition.control.command-trace-enabled:false}") boolean commandTraceEnabled
    ) {
        this.redisTemplate = redisTemplate;
        this.redisObjectMapper = redisObjectMapper;
        this.channelResolver = channelResolver;
        this.metrics = metrics;
        this.commandTraceEnabled = commandTraceEnabled;
    }

    RedisRoomPartitionControlPublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper redisObjectMapper,
            RoomPartitionControlChannelResolver channelResolver,
            RoomPartitionMetrics metrics
    ) {
        this(redisTemplate, redisObjectMapper, channelResolver, metrics, false);
    }

    @Override
    public boolean publish(RoomPartitionControlCommand command) {
        String type = command == null ? "unknown" : command.type();
        try {
            if (command == null || (command.roomId() == null && command.nodeId() == null)) {
                metrics.recordControlPublish(type, "invalid");
                return false;
            }
            String payload = serialize(command);
            String channel = command.nodeId() == null
                    ? channelResolver.channel(command.roomId())
                    : channelResolver.nodeChannel(command.nodeId());
            Long receivers = redisTemplate.convertAndSend(channel, payload);
            if (command.nodeId() != null && (receivers == null || receivers <= 0)) {
                metrics.recordControlPublish(type, "no_receivers");
                log.warn("[ROOM PARTITION CONTROL PUB NO RECEIVERS] type={} nodeId={} commandId={}",
                        type, command.nodeId(), command.commandId());
                return false;
            }
            metrics.recordControlPublish(type, "success");
            return true;
        } catch (Exception e) {
            metrics.recordControlPublish(type, "publish_failed");
            log.warn("[ROOM PARTITION CONTROL PUB FAIL] type={} roomId={} nodeId={} commandId={}",
                    type,
                    command != null ? command.roomId() : null,
                    command != null ? command.nodeId() : null,
                    command != null ? command.commandId() : null,
                    e);
            return false;
        }
    }

    private String serialize(RoomPartitionControlCommand command) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (commandTraceEnabled) {
            return redisObjectMapper.writeValueAsString(command);
        }
        ObjectNode node = redisObjectMapper.valueToTree(command);
        node.remove("commandId");
        return redisObjectMapper.writeValueAsString(node);
    }
}
