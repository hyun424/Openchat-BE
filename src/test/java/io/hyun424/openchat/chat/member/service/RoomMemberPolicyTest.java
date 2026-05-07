package io.hyun424.openchat.chat.member.service;

import io.hyun424.openchat.chat.member.entity.MemberStatus;
import io.hyun424.openchat.chat.member.entity.RoomMember;
import io.hyun424.openchat.chat.member.repository.RoomMemberRepository;
import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.chat.room.repository.RoomRepository;
import io.hyun424.openchat.global.exception.ApiException;
import io.hyun424.openchat.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomMemberPolicyTest {

    private final RoomMemberRepository roomMemberRepository = mock(RoomMemberRepository.class);
    private final RoomRepository roomRepository = mock(RoomRepository.class);
    private final RoomMemberReader reader = new RoomMemberReader(roomMemberRepository, roomRepository);
    private final RoomMemberPolicy policy = new RoomMemberPolicy(reader);

    @Test
    void ensureRoomAccessible_whenEnded_throwsRoomEnded() {
        Room room = room(false, 10);
        room.end();

        ApiException exception = assertThrows(ApiException.class, () -> policy.ensureRoomAccessible(room));

        assertEquals(ErrorCode.ROOM_ENDED, exception.getErrorCode());
    }

    @Test
    void ensureRoomOwner_whenRequesterIsNotOwner_throwsNotRoomOwner() {
        Room room = room(false, 10);

        ApiException exception = assertThrows(ApiException.class, () -> policy.ensureRoomOwner(room, "other"));

        assertEquals(ErrorCode.NOT_ROOM_OWNER, exception.getErrorCode());
    }

    @Test
    void rejectIfAlreadyJoined_whenApprovedMemberExists_throwsAlreadyJoined() {
        when(roomMemberRepository.findByRoomIdAndUserIdAndLeftAtIsNull(1L, "user1"))
                .thenReturn(Optional.of(member(MemberStatus.APPROVED)));

        ApiException exception = assertThrows(ApiException.class, () -> policy.rejectIfAlreadyJoined(1L, "user1"));

        assertEquals(ErrorCode.ALREADY_JOINED, exception.getErrorCode());
    }

    @Test
    void rejectIfAlreadyJoined_whenPendingMemberExists_throwsPendingApproval() {
        when(roomMemberRepository.findByRoomIdAndUserIdAndLeftAtIsNull(1L, "user1"))
                .thenReturn(Optional.of(member(MemberStatus.PENDING)));

        ApiException exception = assertThrows(ApiException.class, () -> policy.rejectIfAlreadyJoined(1L, "user1"));

        assertEquals(ErrorCode.PENDING_APPROVAL, exception.getErrorCode());
    }

    @Test
    void ensureRoomHasCapacity_whenFull_throwsRoomFull() {
        Room room = room(false, 5);
        when(roomMemberRepository.countByRoomIdAndLeftAtIsNullAndStatus(1L, MemberStatus.APPROVED))
                .thenReturn(5);

        ApiException exception = assertThrows(ApiException.class, () -> policy.ensureRoomHasCapacity(room));

        assertEquals(ErrorCode.ROOM_FULL, exception.getErrorCode());
    }

    private Room room(boolean requiresApproval, Integer maxMembers) {
        return Room.builder()
                .id(1L)
                .name("room")
                .ownerId("owner1")
                .requiresApproval(requiresApproval)
                .maxMembers(maxMembers)
                .build();
    }

    private RoomMember member(MemberStatus status) {
        return RoomMember.builder()
                .roomId(1L)
                .userId("user1")
                .joinedAt(Instant.now())
                .status(status)
                .build();
    }
}
