package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;

import java.util.List;

public interface ChatOutboundSender {
    void send(ChatMessageDto message);

    default void sendBatch(Long roomId, List<ChatMessageDto> messages) {
        for (ChatMessageDto message : messages) {
            send(message);
        }
    }
}
