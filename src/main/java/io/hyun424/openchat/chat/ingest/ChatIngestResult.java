package io.hyun424.openchat.chat.ingest;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;

public record ChatIngestResult(
        ChatMessageDto message,
        boolean newMessage,
        Long outboxEventId
) {
}
