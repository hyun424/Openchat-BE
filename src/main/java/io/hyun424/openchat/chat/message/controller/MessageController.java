package io.hyun424.openchat.chat.message.controller;

import io.hyun424.openchat.auth.resolver.AuthUserResolver;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.message.entity.Message;
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

    @GetMapping("/{roomId}/messages")
    public List<ChatMessageDto> getMessages(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long roomId
    ) {
        String userId = authUserResolver.extractUserId(authorization);
        return messageService.getMessagesForUser(roomId, userId);
    }

}
