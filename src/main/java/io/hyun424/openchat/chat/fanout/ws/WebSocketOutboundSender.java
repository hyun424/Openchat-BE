package io.hyun424.openchat.chat.fanout.ws;

import io.hyun424.openchat.chat.fanout.ChatOutboundSender;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class WebSocketOutboundSender implements ChatOutboundSender {

    private final RoomSessionRegistry roomSessionRegistry;

    @Override
    public void send(ChatMessageDto message) {
        roomSessionRegistry.sendToRoom(message.getRoomId(), message);
    }

    @Override
    public void send(ChatMessageDto message, Integer partitionId) {
        roomSessionRegistry.sendToRoom(message.getRoomId(), partitionId, message);
    }

    @Override
    public void sendBatch(Long roomId, List<ChatMessageDto> messages) {
        roomSessionRegistry.sendBatchToRoom(roomId, messages);
    }

    @Override
    public void sendBatch(Long roomId, Integer partitionId, List<ChatMessageDto> messages) {
        roomSessionRegistry.sendBatchToRoom(roomId, partitionId, messages);
    }

    @Override
    public void sendBatch(Long roomId,
                          List<ChatMessageDto> messages,
                          boolean realtimeComplete,
                          int omittedCount,
                          Long lastSequence) {
        roomSessionRegistry.sendBatchToRoom(roomId, messages, realtimeComplete, omittedCount, lastSequence);
    }

    @Override
    public void sendBatch(Long roomId,
                          Integer partitionId,
                          List<ChatMessageDto> messages,
                          boolean realtimeComplete,
                          int omittedCount,
                          Long lastSequence) {
        roomSessionRegistry.sendBatchToRoom(roomId, partitionId, messages, realtimeComplete, omittedCount, lastSequence);
    }
}
