package io.hyun424.openchat.chat.member.service;

import io.hyun424.openchat.chat.member.entity.RoomMember;
import io.hyun424.openchat.chat.member.repository.RoomMemberRepository;
import io.hyun424.openchat.global.exception.ApiException;
import io.hyun424.openchat.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomMemberService {

    private final RoomMemberRepository roomMemberRepository;

    /**
     * 메시지 접근 기준 시점 조회
     * - join 안 했으면 예외
     */
    @Transactional(readOnly = true)
    public Instant getJoinedAtOrThrow(Long roomId, String userId) {
        return roomMemberRepository
                .findByRoomIdAndUserIdAndLeftAtIsNull(roomId, userId)
                .map(RoomMember::getJoinedAt)
                .orElseThrow(() ->
                        new ApiException(ErrorCode.NOT_JOINED, "방에 먼저 입장해야 합니다.")
                );
    }

    public long getJoinedAtMillis(Long roomId, String userId) {
        RoomMember member = roomMemberRepository
                .findByRoomIdAndUserIdAndLeftAtIsNull(roomId, userId)
                .orElseThrow(() ->
                        new IllegalStateException("ROOM_MEMBER_NOT_FOUND")
                );

        return member.getJoinedAt().toEpochMilli();
    }


    /**
     * 방 입장 (REST 전용)
     */
    @Transactional
    public void join(Long roomId, String userId) {
        roomMemberRepository
                .findByRoomIdAndUserIdAndLeftAtIsNull(roomId, userId)
                .ifPresent(rm -> {
                    throw new ApiException(ErrorCode.ALREADY_JOINED, "이미 입장한 방입니다.");
                });

        log.info("JOIN roomId={}, userId={}", roomId, userId);
        roomMemberRepository.save(RoomMember.join(roomId, userId));
    }

    /**
     * 방 퇴장
     */
    @Transactional
    public void leave(Long roomId, String userId) {
        RoomMember member = roomMemberRepository
                .findByRoomIdAndUserIdAndLeftAtIsNull(roomId, userId)
                .orElseThrow(() ->
                        new ApiException(ErrorCode.NOT_JOINED, "입장하지 않은 방입니다.")
                );

        member.leave();
    }

    /**
     * (MVP) join API 중복 호출 허용을 위한 유틸
     */
    @Transactional
    public void joinIfNotExists(Long roomId, String userId) {
        Optional<RoomMember> existing =
                roomMemberRepository.findByRoomIdAndUserIdAndLeftAtIsNull(roomId, userId);

        if (existing.isPresent()) return;

        log.info("JOIN roomId={}, userId={}", roomId, userId);

        roomMemberRepository.save(
                RoomMember.builder()
                        .roomId(roomId)
                        .userId(userId)
                        .joinedAt(Instant.now())
                        .build()
        );
    }
}
