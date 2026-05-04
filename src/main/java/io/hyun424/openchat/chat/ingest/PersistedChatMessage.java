package io.hyun424.openchat.chat.ingest;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.outbox.OutboxEvent;

public record PersistedChatMessage(
        Message message,
        ChatMessageDto dto,
        OutboxEvent outboxEvent
) {
}
