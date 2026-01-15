package io.hyun424.openchat.chat.publish;


import io.hyun424.openchat.chat.message.dto.ChatMessageDto;

public interface ChatMessagePublisher {
    void publish(ChatMessageDto message);
}
