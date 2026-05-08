package io.hyun424.openchat.chat.room.partition.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionControlCommand;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionControlHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RoomPartitionControlSubscriber {

    private final ObjectMapper redisObjectMapper;
    private final RoomPartitionControlHandler controlHandler;
    private final RoomPartitionMetrics metrics;

    public RoomPartitionControlSubscriber(
            @Qualifier("redisObjectMapper") ObjectMapper redisObjectMapper,
            RoomPartitionControlHandler controlHandler,
            RoomPartitionMetrics metrics
    ) {
        this.redisObjectMapper = redisObjectMapper;
        this.controlHandler = controlHandler;
        this.metrics = metrics;
    }

    public void onMessage(String payload, String channel) {
        RoomPartitionControlCommand command;
        try {
            command = redisObjectMapper.readValue(payload, RoomPartitionControlCommand.class);
        } catch (Exception e) {
            metrics.recordControlReceived("unknown", "malformed");
            metrics.recordControlIgnored("malformed");
            log.warn("[ROOM PARTITION CONTROL MALFORMED] channel={} message={}", channel, payload);
            return;
        }

        String type = command.type() == null ? "unknown" : command.type();
        if (command.isNodeReconnect()) {
            if (!command.isValidNodeReconnect()) {
                metrics.recordControlReceived(type, "ignored");
                metrics.recordControlIgnored("invalid_node_reconnect");
                return;
            }
            RoomPartitionControlHandler.NodeReconnectResult result = controlHandler.handleNodeReconnect(command);
            metrics.recordControlReceived(type, "success");
            log.info("[ROOM PARTITION CONTROL NODE RECONNECT] nodeId={} openSessionsBefore={} sent={} remainingOpenSessions={} reason={}",
                    command.nodeId(), result.openSessionsBefore(), result.sent(), result.remainingOpenSessions(), command.reason());
            return;
        }

        if (!command.isReconnect()) {
            metrics.recordControlReceived(type, "ignored");
            metrics.recordControlIgnored("unknown_type");
            return;
        }
        if (!command.isValidReconnect()) {
            metrics.recordControlReceived(type, "ignored");
            metrics.recordControlIgnored("invalid_reconnect");
            return;
        }

        int targeted = controlHandler.handleReconnect(command);
        metrics.recordControlReceived(type, "success");
        log.debug("[ROOM PARTITION CONTROL RECONNECT] roomId={} partitionId={} targeted={} reason={}",
                command.roomId(), command.partitionId(), targeted, command.reason());
    }
}
