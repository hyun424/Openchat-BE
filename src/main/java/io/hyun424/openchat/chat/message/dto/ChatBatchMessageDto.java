package io.hyun424.openchat.chat.message.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ChatBatchMessageDto {

    @Builder.Default
    private final String type = "chat.batch";

    private final Long roomId;
    private final Long firstSequence;
    private final Long lastSequence;
    private final boolean realtimeComplete;
    private final int omittedCount;
    private final List<ChatMessageDto> messages;

    public static ChatBatchMessageDto from(Long roomId, List<ChatMessageDto> messages) {
        return from(roomId, messages, true, 0, sequenceOf(messages.get(messages.size() - 1)));
    }

    public static ChatBatchMessageDto from(Long roomId,
                                           List<ChatMessageDto> messages,
                                           boolean realtimeComplete,
                                           int omittedCount,
                                           Long lastSequence) {
        return ChatBatchMessageDto.builder()
                .roomId(roomId)
                .firstSequence(sequenceOf(messages.get(0)))
                .lastSequence(lastSequence)
                .realtimeComplete(realtimeComplete)
                .omittedCount(Math.max(0, omittedCount))
                .messages(messages)
                .build();
    }

    private static Long sequenceOf(ChatMessageDto message) {
        return message.getSequence() != null ? message.getSequence() : message.getId();
    }
}
