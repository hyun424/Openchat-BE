package io.hyun424.openchat.chat.message.service;

import io.hyun424.openchat.chat.member.service.RoomMemberService;
import io.hyun424.openchat.chat.message.dto.MessagePageResponse;
import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.message.repository.MessageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private RoomMemberService roomMemberService;

    @InjectMocks
    private MessageService messageService;

    @Test
    @DisplayName("cursor 이후 메시지를 id 오름차순 응답으로 변환한다")
    void getMessagesAfterCursor_returnsMessagesAfterCursorAscending() {
        Long roomId = 1L;
        String userId = "user-1";
        long joinedAt = 1000L;
        List<Message> messages = List.of(message(11L), message(12L));
        when(roomMemberService.getJoinedAtMillis(roomId, userId)).thenReturn(joinedAt);
        when(messageRepository.findMessagesAfterCursor(eq(roomId), eq(joinedAt), eq(10L), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(messages);

        MessagePageResponse response = messageService.getMessagesAfterCursor(roomId, userId, 10L, 500);

        assertEquals(2, response.getMessages().size());
        assertEquals(11L, response.getMessages().get(0).getId());
        assertEquals(11L, response.getMessages().get(0).getSequence());
        assertEquals(12L, response.getMessages().get(1).getId());
        assertFalse(response.isHasMore());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(messageRepository).findMessagesAfterCursor(eq(roomId), eq(joinedAt), eq(10L), pageableCaptor.capture());
        assertEquals(500, pageableCaptor.getValue().getPageSize());
    }

    @Test
    @DisplayName("cursor=0 조회는 live에서 생략된 메시지 복구의 시작점으로 허용한다")
    void getMessagesAfterCursor_zeroCursorAllowedForRecovery() {
        Long roomId = 1L;
        String userId = "user-1";
        long joinedAt = 1000L;
        List<Message> messages = List.of(message(1L));
        when(roomMemberService.getJoinedAtMillis(roomId, userId)).thenReturn(joinedAt);
        when(messageRepository.findMessagesAfterCursor(eq(roomId), eq(joinedAt), eq(0L), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(messages);

        MessagePageResponse response = messageService.getMessagesAfterCursor(roomId, userId, 0L, 500);

        assertEquals(1, response.getMessages().size());
        assertEquals(1L, response.getMessages().get(0).getSequence());
    }

    private Message message(Long id) {
        Message message = mock(Message.class);
        when(message.getId()).thenReturn(id);
        when(message.getMessageId()).thenReturn("message-" + id);
        when(message.getRoomId()).thenReturn(1L);
        when(message.getSenderId()).thenReturn("user-1");
        when(message.getSenderNickname()).thenReturn("User 1");
        when(message.getContent()).thenReturn("hello");
        when(message.getCreatedAt()).thenReturn(1000L + id);
        return message;
    }
}
