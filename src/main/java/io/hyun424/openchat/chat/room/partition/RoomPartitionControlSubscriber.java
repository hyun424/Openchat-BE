package io.hyun424.openchat.chat.room.partition;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class RoomPartitionControlSubscriber {

    private static final String PAYLOAD_TYPE = "room.reconnect";

    private final ObjectMapper redisObjectMapper;
    private final RoomSessionRegistry roomSessionRegistry;
    private final RoomPartitionMetrics metrics;

    public RoomPartitionControlSubscriber(
            @Qualifier("redisObjectMapper") ObjectMapper redisObjectMapper,
            RoomSessionRegistry roomSessionRegistry,
            RoomPartitionMetrics metrics
    ) {
        this.redisObjectMapper = redisObjectMapper;
        this.roomSessionRegistry = roomSessionRegistry;
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

        int targeted = sendReconnect(command);
        metrics.recordControlReceived(type, "success");
        metrics.recordReconnectTargeted(command.reason(), targeted);
        log.debug("[ROOM PARTITION CONTROL RECONNECT] roomId={} partitionId={} targeted={} reason={}",
                command.roomId(), command.partitionId(), targeted, command.reason());
    }

    private int sendReconnect(RoomPartitionControlCommand command) {
        List<String> sessionIds = roomSessionRegistry.openSessionIds(command.roomId(), command.partitionId());
        int limit = Math.min(command.limit(), sessionIds.size());
        int sent = 0;
        for (int i = 0; i < limit; i++) {
            String sessionId = sessionIds.get(i);
            boolean success = roomSessionRegistry.sendControlToSession(
                    sessionId,
                    RoomReconnectControlPayload.of(
                            command.roomId(),
                            command.reason(),
                            command.retryAfterMs(),
                            command.routeVersion()
                    ),
                    PAYLOAD_TYPE
            );
            metrics.recordReconnectControlSent(command.reason(), success ? "success" : "failed");
            if (success) {
                sent++;
            }
        }
        return sent;
    }
}
