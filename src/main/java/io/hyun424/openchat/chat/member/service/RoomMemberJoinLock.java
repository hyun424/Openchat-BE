package io.hyun424.openchat.chat.member.service;

import io.hyun424.openchat.chat.member.repository.RoomMemberRepository;
import io.hyun424.openchat.global.exception.ApiException;
import io.hyun424.openchat.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class RoomMemberJoinLock {

    private static final int JOIN_LOCK_TIMEOUT_SECONDS = 3;

    private final RoomMemberRepository roomMemberRepository;

    RoomMemberJoinLock(RoomMemberRepository roomMemberRepository) {
        this.roomMemberRepository = roomMemberRepository;
    }

    void acquireOrThrow(Long roomId, String userId) {
        Integer acquired = roomMemberRepository.acquireJoinLock(roomId, userId, JOIN_LOCK_TIMEOUT_SECONDS);
        if (acquired == null || acquired != 1) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "요청이 몰려 잠시 처리할 수 없습니다. 다시 시도해주세요.");
        }
    }

    void release(Long roomId, String userId) {
        try {
            roomMemberRepository.releaseJoinLock(roomId, userId);
        } catch (Exception e) {
            log.warn("[LOCK] Failed to release join lock: roomId={}, userId={}", roomId, userId, e);
        }
    }
}
