package io.hyun424.openchat.chat.member.service;

import io.hyun424.openchat.chat.member.entity.MemberStatus;
import io.hyun424.openchat.chat.member.entity.RoomMember;
import io.hyun424.openchat.chat.member.repository.RoomMemberRepository;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.chat.room.repository.RoomRepository;
import io.hyun424.openchat.global.exception.ApiException;
import io.hyun424.openchat.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomMemberServiceTest {

    @Mock private RoomMemberRepository roomMemberRepository;
    @Mock private RoomRepository roomRepository;
    @Mock private ChatPipelineMetrics chatPipelineMetrics;

    @InjectMocks
    private RoomMemberService roomMemberService;

    private static final Long ROOM_ID = 1L;
    private static final String USER_ID = "user1";
    private static final String OWNER_ID = "owner1";

    @BeforeEach
    void setUpLocks() {
        lenient().when(roomMemberRepository.acquireRoomCapacityLock(eq(ROOM_ID), anyInt())).thenReturn(1);
    }

    private Room activeRoom(boolean requiresApproval, Integer maxMembers) {
        return Room.builder()
                .id(ROOM_ID)
                .name("Test Room")
                .ownerId(OWNER_ID)
                .maxMembers(maxMembers)
                .requiresApproval(requiresApproval)
                .build();
    }

    @Test
    @DisplayName("정상 join: APPROVED 상태로 저장")
    void join_success() {
        // given
        Room room = activeRoom(false, 10);
        when(roomMemberRepository.acquireJoinLock(anyString(), anyInt())).thenReturn(1);
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByRoomIdAndUserIdAndLeftAtIsNull(ROOM_ID, USER_ID))
                .thenReturn(Optional.empty());
        when(roomMemberRepository.countByRoomIdAndLeftAtIsNullAndStatus(ROOM_ID, MemberStatus.APPROVED))
                .thenReturn(5);

        RoomMember savedMember = RoomMember.builder()
                .roomId(ROOM_ID).userId(USER_ID).joinedAt(Instant.now())
                .status(MemberStatus.APPROVED).build();
        when(roomMemberRepository.save(any())).thenReturn(savedMember);

        // when
        RoomMemberService.JoinResult result = roomMemberService.join(ROOM_ID, USER_ID);

        // then
        assertTrue(result.isApproved());
        assertFalse(result.requiresApproval());
        verify(roomMemberRepository).save(any());
    }

    @Test
    @DisplayName("중복 join: ALREADY_JOINED 예외")
    void join_alreadyJoined_throws() {
        // given
        Room room = activeRoom(false, 10);
        when(roomMemberRepository.acquireJoinLock(anyString(), anyInt())).thenReturn(1);
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

        RoomMember existingMember = RoomMember.builder()
                .roomId(ROOM_ID).userId(USER_ID).joinedAt(Instant.now())
                .status(MemberStatus.APPROVED).build();
        when(roomMemberRepository.findByRoomIdAndUserIdAndLeftAtIsNull(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(existingMember));

        // when & then
        ApiException ex = assertThrows(ApiException.class, () ->
                roomMemberService.join(ROOM_ID, USER_ID));
        assertEquals(ErrorCode.ALREADY_JOINED, ex.getErrorCode());
    }

    @Test
    @DisplayName("인원 초과: ROOM_FULL 예외")
    void join_roomFull_throws() {
        // given
        Room room = activeRoom(false, 5);
        when(roomMemberRepository.acquireJoinLock(anyString(), anyInt())).thenReturn(1);
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByRoomIdAndUserIdAndLeftAtIsNull(ROOM_ID, USER_ID))
                .thenReturn(Optional.empty());
        when(roomMemberRepository.countByRoomIdAndLeftAtIsNullAndStatus(ROOM_ID, MemberStatus.APPROVED))
                .thenReturn(5);

        // when & then
        ApiException ex = assertThrows(ApiException.class, () ->
                roomMemberService.join(ROOM_ID, USER_ID));
        assertEquals(ErrorCode.ROOM_FULL, ex.getErrorCode());
    }

    @Test
    @DisplayName("승인 필요한 방: PENDING 상태로 저장")
    void join_requiresApproval_pendingStatus() {
        // given
        Room room = activeRoom(true, 10);
        when(roomMemberRepository.acquireJoinLock(anyString(), anyInt())).thenReturn(1);
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByRoomIdAndUserIdAndLeftAtIsNull(ROOM_ID, USER_ID))
                .thenReturn(Optional.empty());
        when(roomMemberRepository.countByRoomIdAndLeftAtIsNullAndStatus(ROOM_ID, MemberStatus.APPROVED))
                .thenReturn(3);

        RoomMember pendingMember = RoomMember.builder()
                .roomId(ROOM_ID).userId(USER_ID).joinedAt(Instant.now())
                .status(MemberStatus.PENDING).build();
        when(roomMemberRepository.save(any())).thenReturn(pendingMember);

        // when
        RoomMemberService.JoinResult result = roomMemberService.join(ROOM_ID, USER_ID);

        // then
        assertTrue(result.isPending());
        assertTrue(result.requiresApproval());
    }

    @Test
    @DisplayName("종료된 방 입장: ROOM_ENDED 예외")
    void join_endedRoom_throws() {
        // given
        Room room = activeRoom(false, 10);
        room.end(); // mark as ended
        when(roomMemberRepository.acquireJoinLock(anyString(), anyInt())).thenReturn(1);
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

        // when & then
        ApiException ex = assertThrows(ApiException.class, () ->
                roomMemberService.join(ROOM_ID, USER_ID));
        assertEquals(ErrorCode.ROOM_ENDED, ex.getErrorCode());
    }

    @Test
    @DisplayName("advisory lock 획득 실패: INVALID_REQUEST 예외")
    void join_lockFailed_throws() {
        // given
        when(roomMemberRepository.acquireJoinLock(anyString(), anyInt())).thenReturn(0);

        // when & then
        ApiException ex = assertThrows(ApiException.class, () ->
                roomMemberService.join(ROOM_ID, USER_ID));
        assertEquals(ErrorCode.INVALID_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("room capacity lock 획득 실패 시 먼저 획득한 join lock 해제")
    void join_capacityLockFailed_releasesJoinLock() {
        // given
        when(roomMemberRepository.acquireJoinLock(anyString(), anyInt())).thenReturn(1);
        when(roomMemberRepository.acquireRoomCapacityLock(eq(ROOM_ID), anyInt())).thenReturn(0);

        // when & then
        ApiException ex = assertThrows(ApiException.class, () ->
                roomMemberService.join(ROOM_ID, USER_ID));
        assertEquals(ErrorCode.INVALID_REQUEST, ex.getErrorCode());

        verify(roomMemberRepository).releaseJoinLock(anyString());
        verify(roomMemberRepository, never()).releaseRoomCapacityLock(ROOM_ID);
        verify(roomRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("승인 흐름: PENDING → approve → APPROVED")
    void approveMember_success() {
        // given
        Room room = activeRoom(true, 10);
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(roomMemberRepository.countByRoomIdAndLeftAtIsNullAndStatus(ROOM_ID, MemberStatus.APPROVED))
                .thenReturn(3);

        RoomMember pendingMember = RoomMember.builder()
                .roomId(ROOM_ID).userId(USER_ID).joinedAt(Instant.now())
                .status(MemberStatus.PENDING).build();
        when(roomMemberRepository.findByRoomIdAndUserIdAndLeftAtIsNullAndStatus(
                ROOM_ID, USER_ID, MemberStatus.PENDING))
                .thenReturn(Optional.of(pendingMember));

        // when
        roomMemberService.approveMember(ROOM_ID, USER_ID, OWNER_ID);

        // then
        assertEquals(MemberStatus.APPROVED, pendingMember.getStatus());
    }
}
