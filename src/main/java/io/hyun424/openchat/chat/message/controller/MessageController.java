package io.hyun424.openchat.chat.message.controller;

import io.hyun424.openchat.auth.resolver.AuthUserResolver;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.message.dto.MessagePageResponse;
import io.hyun424.openchat.chat.message.service.MessageService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rooms")
public class MessageController {

    private final MessageService messageService;
    private final AuthUserResolver authUserResolver;

    /**
     * 채팅방 입장 시 최신 메시지를 조회한다.
     * limit 상한을 controller 경계에서 제한해 메시지 조회 쿼리가 예측 가능한 범위에서 실행되게 한다.
     */
    @GetMapping("/{roomId}/messages")
    public MessagePageResponse getMessages(
            @RequestHeader("Authorization") String authorization,
            @PathVariable @Positive Long roomId,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit
    ) {
        String userId = authUserResolver.extractUserId(authorization);
        return messageService.getInitialMessages(roomId, userId, limit);
    }

    /**
     * 무한 스크롤에서 이전 메시지를 조회한다.
     * cursor는 이전 응답의 nextCursor 값을 그대로 사용한다.
     */
    @GetMapping("/{roomId}/messages/before")
    public MessagePageResponse getMessagesBefore(
            @RequestHeader("Authorization") String authorization,
            @PathVariable @Positive Long roomId,
            @RequestParam @Min(0) Long cursor,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit
    ) {
        String userId = authUserResolver.extractUserId(authorization);
        return messageService.getMessagesBeforeCursor(roomId, userId, cursor, limit);
    }

    /**
     * WebSocket batch/재연결 보정을 위해 cursor 이후 메시지를 조회한다.
     * cursor는 마지막으로 처리한 Message.id 또는 sequence 값을 사용한다.
     */
    @GetMapping("/{roomId}/messages/after")
    public MessagePageResponse getMessagesAfter(
            @RequestHeader("Authorization") String authorization,
            @PathVariable @Positive Long roomId,
            @RequestParam @Positive Long cursor,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        String userId = authUserResolver.extractUserId(authorization);
        return messageService.getMessagesAfterCursor(roomId, userId, cursor, limit);
    }

    /**
     * 기존 클라이언트 호환을 위한 전체 조회 API.
     * 신규 화면은 cursor 기반 API를 사용한다.
     */
    @GetMapping("/{roomId}/messages/all")
    public List<ChatMessageDto> getAllMessages(
            @RequestHeader("Authorization") String authorization,
            @PathVariable @Positive Long roomId
    ) {
        String userId = authUserResolver.extractUserId(authorization);
        return messageService.getMessagesForUser(roomId, userId);
    }
}
