package io.hyun424.openchat.chat.message.service;

import io.hyun424.openchat.chat.member.service.RoomMemberService;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.message.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final RoomMemberService roomMemberService;

    /** 메시지 저장 */
    public Message save(Long roomId, String senderId, String senderNickname, String content) {
        Message message = Message.builder()
                .roomId(roomId)
                .senderId(senderId)
                .senderNickname(senderNickname)
                .content(content)
                .build();

        return messageRepository.save(message);
    }

    /** 방 입장 시 최근 메시지 50개 조회 */
    public List<ChatMessageDto> getRecentMessages(Long roomId) {
        List<Message> messages =
                messageRepository.findTop50ByRoomIdOrderByCreatedAtDesc(roomId);

        Collections.reverse(messages);

        return messages.stream()
                .map(ChatMessageDto::from)
                .toList();
    }

    /** 유저별 "입장 이후" 메시지 조회 */
    public List<Message> getMessagesForUser(Long roomId, String userId) {
        Instant joinedAt = roomMemberService.getJoinedAtOrThrow(roomId, userId);

        List<Message> messages =
                messageRepository.findByRoomIdAndCreatedAtAfterOrderByCreatedAtAsc(roomId, joinedAt);

        return messages;
    }
}
