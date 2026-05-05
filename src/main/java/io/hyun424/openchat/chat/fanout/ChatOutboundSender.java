package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;

import java.util.List;

public interface ChatOutboundSender {
    void send(ChatMessageDto message);

    default void send(ChatMessageDto message, Integer partitionId) {
        send(message);
    }

    default void sendBatch(Long roomId, List<ChatMessageDto> messages) {
        for (ChatMessageDto message : messages) {
            send(message);
        }
    }

    default void sendBatch(Long roomId, Integer partitionId, List<ChatMessageDto> messages) {
        sendBatch(roomId, messages);
    }

    default void sendBatch(Long roomId,
                           List<ChatMessageDto> messages,
                           boolean realtimeComplete,
                           int omittedCount,
                           Long lastSequence) {
        sendBatch(roomId, messages);
    }

    default void sendBatch(Long roomId,
                           Integer partitionId,
                           List<ChatMessageDto> messages,
                           boolean realtimeComplete,
                           int omittedCount,
                           Long lastSequence) {
        sendBatch(roomId, messages, realtimeComplete, omittedCount, lastSequence);
    }
}
