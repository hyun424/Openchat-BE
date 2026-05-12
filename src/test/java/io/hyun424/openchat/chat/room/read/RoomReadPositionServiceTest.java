package io.hyun424.openchat.chat.room.read;

import io.hyun424.openchat.chat.member.service.RoomMemberService;
import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.message.repository.MessageRepository;
import io.hyun424.openchat.global.exception.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomReadPositionServiceTest {

    @Mock
    private RoomReadPositionRepository readPositionRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private RoomMemberService roomMemberService;

    @Test
    @DisplayName("저장된 read position이 없으면 null cursor를 반환한다")
    void get_noPosition_returnsNullCursor() {
        RoomReadPositionService service = service();
        when(roomMemberService.getJoinedAtMillis(1L, "user-1")).thenReturn(1000L);
        when(readPositionRepository.findByRoomIdAndUserId(1L, "user-1")).thenReturn(Optional.empty());

        RoomReadPositionResponse response = service.get(1L, "user-1");

        assertEquals(1L, response.roomId());
        assertEquals("user-1", response.userId());
        assertNull(response.lastReadMessageId());
        assertNull(response.lastReadAt());
    }

    @Test
    @DisplayName("새 cursor는 room_read_position row로 저장한다")
    void update_newPosition_savesCursor() {
        RoomReadPositionService service = service();
        Message message = message(10L, 1L, 2000L);
        when(roomMemberService.getJoinedAtMillis(1L, "user-1")).thenReturn(1000L);
        when(messageRepository.findByIdAndRoomId(10L, 1L)).thenReturn(Optional.of(message));
        when(readPositionRepository.findByRoomIdAndUserId(1L, "user-1")).thenReturn(Optional.empty());
        when(readPositionRepository.save(any(RoomReadPosition.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoomReadPositionResponse response = service.update(1L, "user-1", 10L);

        assertEquals(10L, response.lastReadMessageId());
        ArgumentCaptor<RoomReadPosition> captor = ArgumentCaptor.forClass(RoomReadPosition.class);
        verify(readPositionRepository).save(captor.capture());
        assertEquals(1L, captor.getValue().getRoomId());
        assertEquals("user-1", captor.getValue().getUserId());
        assertEquals(10L, captor.getValue().getLastReadMessageId());
    }

    @Test
    @DisplayName("더 작은 cursor update는 기존 read position을 뒤로 밀지 않는다")
    void update_smallerCursor_keepsExistingPosition() {
        RoomReadPositionService service = service();
        RoomReadPosition existing = RoomReadPosition.create(1L, "user-1", 20L, Instant.parse("2026-05-12T00:00:00Z"));
        Message message = message(10L, 1L, 2000L);
        when(roomMemberService.getJoinedAtMillis(1L, "user-1")).thenReturn(1000L);
        when(messageRepository.findByIdAndRoomId(10L, 1L)).thenReturn(Optional.of(message));
        when(readPositionRepository.findByRoomIdAndUserId(1L, "user-1")).thenReturn(Optional.of(existing));

        RoomReadPositionResponse response = service.update(1L, "user-1", 10L);

        assertEquals(20L, response.lastReadMessageId());
        verify(readPositionRepository, never()).save(any());
    }

    @Test
    @DisplayName("없는 message id는 read position으로 저장할 수 없다")
    void update_missingMessage_throwsInvalidRequest() {
        RoomReadPositionService service = service();
        when(roomMemberService.getJoinedAtMillis(1L, "user-1")).thenReturn(1000L);
        when(messageRepository.findByIdAndRoomId(10L, 1L)).thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> service.update(1L, "user-1", 10L));
    }

    @Test
    @DisplayName("join 이전 message id는 read position으로 저장할 수 없다")
    void update_messageBeforeJoin_throwsInvalidRequest() {
        RoomReadPositionService service = service();
        Message message = message(10L, 1L, 999L);
        when(roomMemberService.getJoinedAtMillis(1L, "user-1")).thenReturn(1000L);
        when(messageRepository.findByIdAndRoomId(10L, 1L)).thenReturn(Optional.of(message));

        assertThrows(ApiException.class, () -> service.update(1L, "user-1", 10L));
    }

    @Test
    @DisplayName("non-member는 RoomMemberService 검증에서 거부된다")
    void update_nonMember_propagatesMembershipFailure() {
        RoomReadPositionService service = service();
        when(roomMemberService.getJoinedAtMillis(1L, "user-1")).thenThrow(new ApiException(io.hyun424.openchat.global.exception.ErrorCode.NOT_JOINED));

        assertThrows(ApiException.class, () -> service.update(1L, "user-1", 10L));
        verifyNoInteractions(messageRepository);
    }

    private RoomReadPositionService service() {
        return new RoomReadPositionService(readPositionRepository, messageRepository, roomMemberService);
    }

    private Message message(Long id, Long roomId, Long createdAt) {
        Message message = mock(Message.class);
        when(message.getId()).thenReturn(id);
        when(message.getRoomId()).thenReturn(roomId);
        when(message.getCreatedAt()).thenReturn(createdAt);
        return message;
    }
}
