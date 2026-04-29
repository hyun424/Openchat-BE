package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;

public interface ChatOutboundSender {
    void send(ChatMessageDto message);
}