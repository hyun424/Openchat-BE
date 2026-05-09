package io.hyun424.openchat.chat.room.partition.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.room.partition.commandlog.ReconnectCommandLogService;
import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionControlCommand;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionControlHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RoomPartitionControlSubscriber {

    private final ObjectMapper redisObjectMapper;
    private final RoomPartitionControlHandler controlHandler;
    private final RoomPartitionMetrics metrics;
    private final ReconnectCommandLogService commandLogService;
    private final String handlerNodeId;

    @Autowired
    public RoomPartitionControlSubscriber(
            @Qualifier("redisObjectMapper") ObjectMapper redisObjectMapper,
            RoomPartitionControlHandler controlHandler,
            RoomPartitionMetrics metrics,
            ReconnectCommandLogService commandLogService,
            @Value("${app.instance-id:local}") String handlerNodeId
    ) {
        this.redisObjectMapper = redisObjectMapper;
        this.controlHandler = controlHandler;
        this.metrics = metrics;
        this.commandLogService = commandLogService;
        this.handlerNodeId = handlerNodeId;
    }

    RoomPartitionControlSubscriber(
            ObjectMapper redisObjectMapper,
            RoomPartitionControlHandler controlHandler,
            RoomPartitionMetrics metrics
    ) {
        this(redisObjectMapper, controlHandler, metrics, null, "test");
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
            RoomPartitionControlHandler.ReconnectHandlingResult result = controlHandler.handleNodeReconnect(command);
            recordHandling(command, result);
            metrics.recordControlReceived(type, "success");
            log.info("[ROOM PARTITION CONTROL NODE RECONNECT] nodeId={} commandId={} openSessionsBefore={} sent={} remainingOpenSessions={} reason={}",
                    command.nodeId(), command.commandId(), result.openSessionsBefore(), result.sentSessions(), result.remainingOpenSessions(), command.reason());
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

        RoomPartitionControlHandler.ReconnectHandlingResult result = controlHandler.handleReconnect(command);
        recordHandling(command, result);
        metrics.recordControlReceived(type, "success");
        log.debug("[ROOM PARTITION CONTROL RECONNECT] roomId={} partitionId={} commandId={} targeted={} reason={}",
                command.roomId(), command.partitionId(), command.commandId(), result.targetedSessions(), command.reason());
    }

    private void recordHandling(RoomPartitionControlCommand command, RoomPartitionControlHandler.ReconnectHandlingResult result) {
        if (commandLogService != null) {
            try {
                commandLogService.recordHandling(command, handlerNodeId, result);
            } catch (Exception e) {
                log.warn("[RECONNECT COMMAND LOG HANDLING IGNORED] commandId={} handlerNodeId={}",
                        command.commandId(), handlerNodeId, e);
            }
        }
    }
}
