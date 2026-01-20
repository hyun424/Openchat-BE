package io.hyun424.openchat.chat.message.service;

import io.hyun424.openchat.chat.member.service.RoomMemberService;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.message.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageService {

    private final MessageRepository messageRepository;
    private final RoomMemberService roomMemberService;

    /** 🔥 ingest 전용 저장 */
    public Message save(
            Long roomId,
            String senderId,
            String nickname,
            String content,
            String messageId,
            Long createdAt
    ) {
        Message message = Message.builder()
                .messageId(messageId)
                .roomId(roomId)
                .senderId(senderId)
                .senderNickname(nickname)
                .content(content)
                .createdAt(createdAt)
                .build();

        return messageRepository.save(message);
    }


    @Transactional(readOnly = true)
    public List<ChatMessageDto> getMessagesForUser(Long roomId, String userId) {
        long joinedAt = roomMemberService.getJoinedAtMillis(roomId, userId);

        // Compound sort (createdAt, id) ensures stable ordering
        // even when multiple messages share the same millisecond
        List<Message> messages = messageRepository
                .findByRoomIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAscIdAsc(
                        roomId,
                        joinedAt
                );

        log.debug("[GET MESSAGES] roomId={} userId={} joinedAt={} count={}",
                roomId, userId, joinedAt, messages.size());

        return messages.stream()
                .map(ChatMessageDto::from)
                .toList();
    }
}


