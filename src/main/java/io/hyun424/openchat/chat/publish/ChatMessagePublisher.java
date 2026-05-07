package io.hyun424.openchat.chat.publish;


import io.hyun424.openchat.chat.message.dto.ChatMessageDto;

import java.util.concurrent.CompletableFuture;

public interface ChatMessagePublisher {
    CompletableFuture<Void> publish(ChatMessageDto message);
}
