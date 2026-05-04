package io.hyun424.openchat.chat.message.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.hyun424.openchat.chat.message.entity.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessageDto {

    /** DB PK (sequence for ordering tiebreaker) */
    private Long id;

    /** Server sequence alias backed by Message.id */
    private Long sequence;

    /** Server-generated message ID (dedupe / idempotency key) */
    private String messageId;

    /** Client-generated message ID (optimistic UI matching) */
    private String clientMessageId;

    private Long roomId;
    private String senderId;
    private String senderName;

    @JsonProperty("content")
    private String message;

    /** Epoch millis - use this for sorting (numeric comparison) */
    private Long createdAt;

    /**
     * Entity → DTO conversion
     * - Used when returning DB results via REST or WebSocket
     * - clientMessageId is null for historical messages
     */
    public static ChatMessageDto from(Message entity) {
        return ChatMessageDto.builder()
                .id(entity.getId())
                .sequence(entity.getId())
                .messageId(entity.getMessageId())
                .clientMessageId(null)
                .roomId(entity.getRoomId())
                .senderId(entity.getSenderId())
                .senderName(entity.getSenderNickname())
                .message(entity.getContent())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
