package io.hyun424.openchat.chat.fanout;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatFanoutService {

    private final RoomSessionRegistry roomSessionRegistry;
    private final ObjectMapper objectMapper;

    public void fanout(Long roomId, ChatMessageDto message) {
        Set<WebSocketSession> sessions = roomSessionRegistry.get(roomId);

        int sent = 0;
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(
                            new TextMessage(objectMapper.writeValueAsString(message))
                    );
                    sent++;
                }
            } catch (Exception e) {
                log.error("WS_FANOUT_ERROR roomId={} sessionId={}",
                        roomId, session.getId(), e);
            }
        }

        log.info("[WS FANOUT] room={} sessionCount={} sent={} messageId={}",
                roomId, sessions.size(), sent, message.getMessageId());
        log.info(
                "[WS SEND PAYLOAD] messageId={} clientMessageId={} message='{}'",
                message.getMessageId(),
                message.getClientMessageId(),
                message.getMessage()
        );

    }

}
