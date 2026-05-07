package io.hyun424.openchat.infra.websocket.handler;

import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
class RoomPresenceControlService {

    private final RoomSessionRegistry roomSessionRegistry;
    private final ChatPipelineMetrics chatPipelineMetrics;

    RoomPresenceControlService(RoomSessionRegistry roomSessionRegistry, ChatPipelineMetrics chatPipelineMetrics) {
        this.roomSessionRegistry = roomSessionRegistry;
        this.chatPipelineMetrics = chatPipelineMetrics;
    }

    void handle(WebSocketSession session, ChatWebSocketMessage message, Long connectionRoomId, String senderId) {
        if (message.roomId() != null && !message.roomId().equals(connectionRoomId)) {
            log.warn("[WS_CONTROL_ROOM_MISMATCH] sessionId={} senderId={} connectionRoomId={} payloadRoomId={} type={}",
                    session.getId(), senderId, connectionRoomId, message.roomId(), message.type());
            chatPipelineMetrics.incrementCounter("ws.control.room_mismatch");
            return;
        }

        if (message.marksActive()) {
            roomSessionRegistry.markActive(connectionRoomId, session.getId(), message.lastSeenSequence());
            if (ChatWebSocketMessage.TYPE_ROOM_ACTIVE_HEARTBEAT.equals(message.type())) {
                chatPipelineMetrics.incrementCounter("ws.control.room_active_heartbeat");
            } else {
                chatPipelineMetrics.incrementCounter("ws.control.room_active");
            }
            return;
        }

        roomSessionRegistry.markPassive(connectionRoomId, session.getId(), message.lastSeenSequence());
        chatPipelineMetrics.incrementCounter("ws.control.room_passive");
    }
}
