package io.hyun424.openchat.chat.message.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class MessagePageResponse {

    private List<ChatMessageDto> messages;

    /**
     * Cursor for loading more messages (message id)
     * null if no more messages available
     */
    private String nextCursor;

    /**
     * true if more messages can be loaded
     */
    private boolean hasMore;
}
