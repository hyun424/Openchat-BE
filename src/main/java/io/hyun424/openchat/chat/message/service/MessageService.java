package io.hyun424.openchat.chat.message.service;

import io.hyun424.openchat.chat.member.service.RoomMemberService;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.message.dto.MessagePageResponse;
import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.message.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageService {

    private final MessageRepository messageRepository;
    private final RoomMemberService roomMemberService;

    private static final int DEFAULT_PAGE_SIZE = 30;

    public Message save(
            Long roomId,
            String senderId,
            String nickname,
            String content,
            String clientMessageId,
            String messageId,
            Long createdAt
    ) {
        Message message = Message.builder()
                .messageId(messageId)
                .roomId(roomId)
                .senderId(senderId)
                .clientMessageId(StringUtils.hasText(clientMessageId) ? clientMessageId : null)
                .senderNickname(nickname)
                .content(content)
                .createdAt(createdAt)
                .build();

        return messageRepository.save(message);
    }

    @Transactional(readOnly = true)
    public Message findByClientMessageId(Long roomId, String senderId, String clientMessageId) {
        if (!StringUtils.hasText(clientMessageId)) {
            return null;
        }
        return messageRepository
                .findByRoomIdAndSenderIdAndClientMessageId(roomId, senderId, clientMessageId)
                .orElse(null);
    }

    /**
     * Initial load: Get latest messages for chat room
     * Returns messages in chronological order (oldest first) for display
     */
    @Transactional(readOnly = true)
    public MessagePageResponse getInitialMessages(Long roomId, String userId, int limit) {
        long joinedAt = roomMemberService.getJoinedAtMillis(roomId, userId);
        int pageSize = limit > 0 ? limit : DEFAULT_PAGE_SIZE;

        List<Message> messages = messageRepository.findLatestMessages(
                roomId,
                joinedAt,
                PageRequest.of(0, pageSize)
        );

        // Reverse to chronological order (oldest first)
        Collections.reverse(messages);

        List<ChatMessageDto> dtos = messages.stream()
                .map(ChatMessageDto::from)
                .toList();

        boolean hasMore = messages.size() == pageSize;
        String nextCursor = hasMore && !messages.isEmpty()
                ? String.valueOf(messages.get(0).getId())  // oldest message's id in this batch
                : null;

        log.debug("[INITIAL MESSAGES] roomId={} userId={} count={} hasMore={}",
                roomId, userId, messages.size(), hasMore);

        return new MessagePageResponse(dtos, nextCursor, hasMore);
    }

    /**
     * Infinite scroll: Load older messages before cursor
     * Returns messages in chronological order (oldest first) for display
     */
    @Transactional(readOnly = true)
    public MessagePageResponse getMessagesBeforeCursor(
            Long roomId,
            String userId,
            Long cursorId,
            int limit
    ) {
        long joinedAt = roomMemberService.getJoinedAtMillis(roomId, userId);
        int pageSize = limit > 0 ? limit : DEFAULT_PAGE_SIZE;

        List<Message> messages = messageRepository.findMessagesBeforeCursor(
                roomId,
                joinedAt,
                cursorId,
                PageRequest.of(0, pageSize)
        );

        // Reverse to chronological order (oldest first)
        Collections.reverse(messages);

        List<ChatMessageDto> dtos = messages.stream()
                .map(ChatMessageDto::from)
                .toList();

        boolean hasMore = messages.size() == pageSize;
        String nextCursor = hasMore && !messages.isEmpty()
                ? String.valueOf(messages.get(0).getId())
                : null;

        log.debug("[LOAD MORE] roomId={} cursorId={} count={} hasMore={}",
                roomId, cursorId, messages.size(), hasMore);

        return new MessagePageResponse(dtos, nextCursor, hasMore);
    }

    /**
     * WebSocket 보정: 클라이언트가 마지막으로 확인한 Message.id 이후 메시지를 조회한다.
     */
    @Transactional(readOnly = true)
    public MessagePageResponse getMessagesAfterCursor(
            Long roomId,
            String userId,
            Long cursorId,
            int limit
    ) {
        long joinedAt = roomMemberService.getJoinedAtMillis(roomId, userId);
        int pageSize = limit > 0 ? limit : DEFAULT_PAGE_SIZE;

        List<Message> messages = messageRepository.findMessagesAfterCursor(
                roomId,
                joinedAt,
                cursorId,
                PageRequest.of(0, pageSize)
        );

        List<ChatMessageDto> dtos = messages.stream()
                .map(ChatMessageDto::from)
                .toList();

        boolean hasMore = messages.size() == pageSize;
        String nextCursor = hasMore && !messages.isEmpty()
                ? String.valueOf(messages.get(messages.size() - 1).getId())
                : null;

        log.debug("[SYNC AFTER] roomId={} cursorId={} count={} hasMore={}",
                roomId, cursorId, messages.size(), hasMore);

        return new MessagePageResponse(dtos, nextCursor, hasMore);
    }

    /**
     * Legacy method for backward compatibility
     */
    @Transactional(readOnly = true)
    public List<ChatMessageDto> getMessagesForUser(Long roomId, String userId) {
        long joinedAt = roomMemberService.getJoinedAtMillis(roomId, userId);

        List<Message> messages = messageRepository
                .findByRoomIdAndCreatedAtGreaterThanEqualOrderByIdAsc(
                        roomId,
                        joinedAt
                );

        return messages.stream()
                .map(ChatMessageDto::from)
                .toList();
    }
}
