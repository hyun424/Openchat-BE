package io.hyun424.openchat.chat.message.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ChatAckMessageDto {

    @Builder.Default
    private final String type = "chat.ack";

    private final Long roomId;
    private final String messageId;
    private final Long sequence;
    private final String clientMessageId;
    private final Long createdAt;

    public static ChatAckMessageDto from(ChatMessageDto message) {
        return ChatAckMessageDto.builder()
                .roomId(message.getRoomId())
                .messageId(message.getMessageId())
                .sequence(message.getSequence() != null ? message.getSequence() : message.getId())
                .clientMessageId(message.getClientMessageId())
                .createdAt(message.getCreatedAt())
                .build();
    }
}
