package io.hyun424.openchat.infra.websocket.session;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

record BroadcastTask(Long roomId,
                     int laneIndex,
                     String payloadType,
                     TextMessage textMessage,
                     int payloadBytes,
                     List<ChatMessageDto> messages,
                     List<WebSocketSession> sessions,
                     long enqueuedNanos) {
}
