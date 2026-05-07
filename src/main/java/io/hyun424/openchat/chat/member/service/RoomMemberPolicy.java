package io.hyun424.openchat.chat.member.service;

import io.hyun424.openchat.chat.member.entity.MemberStatus;
import io.hyun424.openchat.chat.member.entity.RoomMember;
import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.global.exception.ApiException;
import io.hyun424.openchat.global.exception.ErrorCode;

import java.util.Optional;

class RoomMemberPolicy {

    private final RoomMemberReader reader;

    RoomMemberPolicy(RoomMemberReader reader) {
        this.reader = reader;
    }

    void ensureRoomAccessible(Room room) {
        if (!room.isAccessible()) {
            throw new ApiException(ErrorCode.ROOM_ENDED);
        }
    }

    void ensureRoomOwner(Room room, String requesterId) {
        if (!requesterId.equals(room.getOwnerId())) {
            throw new ApiException(ErrorCode.NOT_ROOM_OWNER);
        }
    }

    void rejectIfAlreadyJoined(Long roomId, String userId) {
        Optional<RoomMember> existing = reader.findActiveMember(roomId, userId);
        if (existing.isEmpty()) {
            return;
        }

        if (existing.get().getStatus() == MemberStatus.PENDING) {
            throw new ApiException(ErrorCode.PENDING_APPROVAL);
        }
        throw new ApiException(ErrorCode.ALREADY_JOINED);
    }

    void ensureRoomHasCapacity(Room room) {
        if (room.getMaxMembers() == null) {
            return;
        }

        int currentCount = reader.countApprovedMembers(room.getId());
        if (currentCount >= room.getMaxMembers()) {
            throw new ApiException(ErrorCode.ROOM_FULL);
        }
    }
}
