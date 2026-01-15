package io.hyun424.openchat.chat.fanout.ws;

import io.hyun424.openchat.chat.fanout.ChatOutboundSender;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.websocket.handler.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WebSocketOutboundSender implements ChatOutboundSender {

    private final ChatWebSocketHandler webSocketHandler;

    @Override
    public void send(Long roomId, ChatMessageDto message) {
        webSocketHandler.broadcast(roomId, message);
    }
}
