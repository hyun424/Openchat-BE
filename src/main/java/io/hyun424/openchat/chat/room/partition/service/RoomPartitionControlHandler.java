package io.hyun424.openchat.chat.room.partition.service;

import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionControlCommand;
import io.hyun424.openchat.chat.room.partition.dto.RoomReconnectControlPayload;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RoomPartitionControlHandler {

    private static final String PAYLOAD_TYPE = "room.reconnect";

    private final RoomSessionRegistry roomSessionRegistry;
    private final RoomPartitionMetrics metrics;

    public RoomPartitionControlHandler(RoomSessionRegistry roomSessionRegistry,
                                       RoomPartitionMetrics metrics) {
        this.roomSessionRegistry = roomSessionRegistry;
        this.metrics = metrics;
    }

    public int handleReconnect(RoomPartitionControlCommand command) {
        List<String> sessionIds = roomSessionRegistry.openSessionIds(command.roomId(), command.partitionId());
        int limit = Math.min(command.limit(), sessionIds.size());
        int sent = 0;
        for (int i = 0; i < limit; i++) {
            boolean success = sendReconnect(sessionIds.get(i), command);
            metrics.recordReconnectControlSent(command.reason(), success ? "success" : "failed");
            if (success) {
                sent++;
            }
        }
        metrics.recordReconnectTargeted(command.reason(), sent);
        return sent;
    }

    public NodeReconnectResult handleNodeReconnect(RoomPartitionControlCommand command) {
        List<io.hyun424.openchat.infra.websocket.session.SessionStateTracker.OpenSessionInfo> sessions =
                roomSessionRegistry.openSessions();
        int limit = Math.min(command.limit(), sessions.size());
        int sent = 0;
        for (int i = 0; i < limit; i++) {
            io.hyun424.openchat.infra.websocket.session.SessionStateTracker.OpenSessionInfo session = sessions.get(i);
            boolean success = roomSessionRegistry.sendControlToSession(
                    session.sessionId(),
                    RoomReconnectControlPayload.of(
                            session.roomId(),
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
        metrics.recordReconnectTargeted(command.reason(), sent);
        int remaining = roomSessionRegistry.openSessions().size();
        return new NodeReconnectResult(sessions.size(), sent, remaining);
    }

    private boolean sendReconnect(String sessionId, RoomPartitionControlCommand command) {
        return roomSessionRegistry.sendControlToSession(
                sessionId,
                RoomReconnectControlPayload.of(
                        command.roomId(),
                        command.reason(),
                        command.retryAfterMs(),
                        command.routeVersion()
                ),
                PAYLOAD_TYPE
        );
    }

    public record NodeReconnectResult(
            int openSessionsBefore,
            int sent,
            int remainingOpenSessions
    ) {
    }
}
