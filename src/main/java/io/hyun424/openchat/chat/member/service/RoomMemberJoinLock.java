package io.hyun424.openchat.chat.member.service;

import io.hyun424.openchat.chat.member.repository.RoomMemberRepository;
import io.hyun424.openchat.global.exception.ApiException;
import io.hyun424.openchat.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Slf4j
class RoomMemberJoinLock {

    private static final int JOIN_LOCK_TIMEOUT_SECONDS = 3;
    private static final int JOIN_LOCK_HASH_BYTES = 12;

    private final RoomMemberRepository roomMemberRepository;

    RoomMemberJoinLock(RoomMemberRepository roomMemberRepository) {
        this.roomMemberRepository = roomMemberRepository;
    }

    void acquireOrThrow(Long roomId, String userId) {
        Integer acquired = roomMemberRepository.acquireJoinLock(joinLockName(roomId, userId), JOIN_LOCK_TIMEOUT_SECONDS);
        if (acquired == null || acquired != 1) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "요청이 몰려 잠시 처리할 수 없습니다. 다시 시도해주세요.");
        }
    }

    void acquireRoomCapacityOrThrow(Long roomId) {
        Integer acquired = roomMemberRepository.acquireRoomCapacityLock(roomId, JOIN_LOCK_TIMEOUT_SECONDS);
        if (acquired == null || acquired != 1) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "방 인원 변경 요청이 몰려 잠시 처리할 수 없습니다. 다시 시도해주세요.");
        }
    }

    void release(Long roomId, String userId) {
        try {
            roomMemberRepository.releaseJoinLock(joinLockName(roomId, userId));
        } catch (Exception e) {
            log.warn("[LOCK] Failed to release join lock: roomId={}, userId={}", roomId, userId, e);
        }
    }

    void releaseRoomCapacity(Long roomId) {
        try {
            roomMemberRepository.releaseRoomCapacityLock(roomId);
        } catch (Exception e) {
            log.warn("[LOCK] Failed to release room capacity lock: roomId={}", roomId, e);
        }
    }

    static String joinLockName(Long roomId, String userId) {
        return "room_join:" + roomId + ':' + shortSha256(userId);
    }

    private static String shortSha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(JOIN_LOCK_HASH_BYTES * 2);
            for (int i = 0; i < JOIN_LOCK_HASH_BYTES; i++) {
                hex.append(Character.forDigit((digest[i] >>> 4) & 0xf, 16));
                hex.append(Character.forDigit(digest[i] & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
