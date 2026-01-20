package io.hyun424.openchat.chat.fanout.ws;

import io.hyun424.openchat.chat.fanout.ChatFanoutService;
import io.hyun424.openchat.chat.fanout.ChatOutboundSender;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.websocket.handler.ChatWebSocketHandler;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

@Component
@RequiredArgsConstructor
public class WebSocketOutboundSender implements ChatOutboundSender {

    private final RoomSessionRegistry roomSessionRegistry;

    @Override
    public void send(ChatMessageDto message) {
        roomSessionRegistry.sendToRoom(message.getRoomId(), message);
    }
}
