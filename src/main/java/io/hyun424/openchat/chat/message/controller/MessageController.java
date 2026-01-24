package io.hyun424.openchat.chat.message.controller;

import io.hyun424.openchat.auth.resolver.AuthUserResolver;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.message.dto.MessagePageResponse;
import io.hyun424.openchat.chat.message.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rooms")
public class MessageController {

    private final MessageService messageService;
    private final AuthUserResolver authUserResolver;

    /**
     * Get initial messages (latest N messages)
     * Used when user first enters the chat room
     *
     * @param limit number of messages to load (default: 30)
     */
    @GetMapping("/{roomId}/messages")
    public MessagePageResponse getMessages(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long roomId,
            @RequestParam(defaultValue = "30") int limit
    ) {
        String userId = authUserResolver.extractUserId(authorization);
        return messageService.getInitialMessages(roomId, userId, limit);
    }

    /**
     * Load more messages (infinite scroll)
     * Used when user scrolls up to load older messages
     *
     * @param cursor message id from previous response's nextCursor
     * @param limit number of messages to load (default: 30)
     */
    @GetMapping("/{roomId}/messages/before")
    public MessagePageResponse getMessagesBefore(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long roomId,
            @RequestParam Long cursor,
            @RequestParam(defaultValue = "30") int limit
    ) {
        String userId = authUserResolver.extractUserId(authorization);
        return messageService.getMessagesBeforeCursor(roomId, userId, cursor, limit);
    }

    /**
     * Legacy endpoint for backward compatibility
     */
    @GetMapping("/{roomId}/messages/all")
    public List<ChatMessageDto> getAllMessages(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long roomId
    ) {
        String userId = authUserResolver.extractUserId(authorization);
        return messageService.getMessagesForUser(roomId, userId);
    }
}
