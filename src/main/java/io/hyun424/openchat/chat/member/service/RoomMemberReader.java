package io.hyun424.openchat.chat.member.service;

import io.hyun424.openchat.chat.member.entity.MemberStatus;
import io.hyun424.openchat.chat.member.entity.RoomMember;
import io.hyun424.openchat.chat.member.repository.RoomMemberRepository;
import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.chat.room.repository.RoomRepository;
import io.hyun424.openchat.global.exception.ApiException;
import io.hyun424.openchat.global.exception.ErrorCode;

import java.util.List;
import java.util.Optional;

class RoomMemberReader {

    private final RoomMemberRepository roomMemberRepository;
    private final RoomRepository roomRepository;

    RoomMemberReader(RoomMemberRepository roomMemberRepository, RoomRepository roomRepository) {
        this.roomMemberRepository = roomMemberRepository;
        this.roomRepository = roomRepository;
    }

    Room getRoomOrThrow(Long roomId) {
        return roomRepository.findById(roomId)
                .orElseThrow(() -> new ApiException(ErrorCode.ROOM_NOT_FOUND));
    }

    RoomMember getApprovedMemberOrThrow(Long roomId, String userId) {
        return roomMemberRepository
                .findByRoomIdAndUserIdAndLeftAtIsNullAndStatus(roomId, userId, MemberStatus.APPROVED)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_JOINED, "방에 먼저 입장해야 합니다."));
    }

    RoomMember getActiveMemberOrThrow(Long roomId, String userId) {
        return roomMemberRepository
                .findByRoomIdAndUserIdAndLeftAtIsNull(roomId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_JOINED, "입장하지 않은 방입니다."));
    }

    Optional<RoomMember> findActiveMember(Long roomId, String userId) {
        return roomMemberRepository.findByRoomIdAndUserIdAndLeftAtIsNull(roomId, userId);
    }

    RoomMember getPendingMemberOrThrow(Long roomId, String userId) {
        return roomMemberRepository
                .findByRoomIdAndUserIdAndLeftAtIsNullAndStatus(roomId, userId, MemberStatus.PENDING)
                .orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_FOUND));
    }

    List<Object[]> findPendingMembersWithNickname(Long roomId) {
        return roomMemberRepository.findPendingMembersWithNickname(roomId, MemberStatus.PENDING);
    }

    int countApprovedMembers(Long roomId) {
        return roomMemberRepository.countByRoomIdAndLeftAtIsNullAndStatus(roomId, MemberStatus.APPROVED);
    }
}
