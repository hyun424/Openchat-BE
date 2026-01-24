package io.hyun424.openchat.chat.member.service;

import io.hyun424.openchat.auth.entity.User;
import io.hyun424.openchat.auth.repository.UserRepository;
import io.hyun424.openchat.chat.member.entity.MemberStatus;
import io.hyun424.openchat.chat.member.entity.RoomMember;
import io.hyun424.openchat.chat.member.repository.RoomMemberRepository;
import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.chat.room.repository.RoomRepository;
import io.hyun424.openchat.global.exception.ApiException;
import io.hyun424.openchat.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomMemberService {

    private final RoomMemberRepository roomMemberRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;

    /**
     * 메시지 접근 기준 시점 조회
     * - join 안 했으면 예외
     */
    @Transactional(readOnly = true)
    public Instant getJoinedAtOrThrow(Long roomId, String userId) {
        return roomMemberRepository
                .findByRoomIdAndUserIdAndLeftAtIsNullAndStatus(roomId, userId, MemberStatus.APPROVED)
                .map(RoomMember::getJoinedAt)
                .orElseThrow(() ->
                        new ApiException(ErrorCode.NOT_JOINED, "방에 먼저 입장해야 합니다.")
                );
    }

    public long getJoinedAtMillis(Long roomId, String userId) {
        RoomMember member = roomMemberRepository
                .findByRoomIdAndUserIdAndLeftAtIsNullAndStatus(roomId, userId, MemberStatus.APPROVED)
                .orElseThrow(() ->
                        new IllegalStateException("ROOM_MEMBER_NOT_FOUND")
                );

        return member.getJoinedAt().toEpochMilli();
    }

    /**
     * 방 입장
     * - 종료된 방 접근 차단
     * - 인원 초과 체크
     * - 승인 필요 시 PENDING 상태로 저장
     * @return JoinResult (상태 정보)
     */
    @Transactional
    public JoinResult join(Long roomId, String userId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ApiException(ErrorCode.ROOM_NOT_FOUND));

        // 종료된 방 체크
        if (!room.isAccessible()) {
            throw new ApiException(ErrorCode.ROOM_ENDED);
        }

        // 방장은 무조건 입장
        if (userId.equals(room.getOwnerId())) {
            return joinAsOwner(roomId, userId);
        }

        // 이미 입장했는지 확인
        Optional<RoomMember> existing = roomMemberRepository
                .findByRoomIdAndUserIdAndLeftAtIsNull(roomId, userId);

        if (existing.isPresent()) {
            RoomMember member = existing.get();
            if (member.getStatus() == MemberStatus.PENDING) {
                throw new ApiException(ErrorCode.PENDING_APPROVAL);
            }
            throw new ApiException(ErrorCode.ALREADY_JOINED);
        }

        // 인원수 체크 (승인된 멤버만 카운트)
        if (room.getMaxMembers() != null) {
            int currentCount = roomMemberRepository
                    .countByRoomIdAndLeftAtIsNullAndStatus(roomId, MemberStatus.APPROVED);
            if (currentCount >= room.getMaxMembers()) {
                throw new ApiException(ErrorCode.ROOM_FULL);
            }
        }

        // 입장 처리
        // Security: Race condition 방지 - DB unique constraint로 중복 입장 차단
        boolean requiresApproval = Boolean.TRUE.equals(room.getRequiresApproval());
        try {
            RoomMember member = roomMemberRepository.save(
                    RoomMember.join(roomId, userId, requiresApproval)
            );
            log.info("JOIN roomId={}, userId={}, status={}", roomId, userId, member.getStatus());
            return new JoinResult(member.getStatus(), requiresApproval);
        } catch (DataIntegrityViolationException e) {
            // 동시 요청으로 인한 중복 - 이미 입장한 것으로 처리
            log.warn("[RACE_CONDITION] Duplicate join attempt: roomId={}, userId={}", roomId, userId);
            throw new ApiException(ErrorCode.ALREADY_JOINED);
        }
    }

    private JoinResult joinAsOwner(Long roomId, String userId) {
        Optional<RoomMember> existing = roomMemberRepository
                .findByRoomIdAndUserIdAndLeftAtIsNull(roomId, userId);

        if (existing.isPresent()) {
            return new JoinResult(MemberStatus.APPROVED, false);
        }

        roomMemberRepository.save(RoomMember.join(roomId, userId, false));
        return new JoinResult(MemberStatus.APPROVED, false);
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
     * 멤버 승인 (방장만)
     */
    @Transactional
    public void approveMember(Long roomId, String targetUserId, String requesterId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ApiException(ErrorCode.ROOM_NOT_FOUND));

        if (!requesterId.equals(room.getOwnerId())) {
            throw new ApiException(ErrorCode.NOT_ROOM_OWNER);
        }

        // 인원수 체크
        if (room.getMaxMembers() != null) {
            int currentCount = roomMemberRepository
                    .countByRoomIdAndLeftAtIsNullAndStatus(roomId, MemberStatus.APPROVED);
            if (currentCount >= room.getMaxMembers()) {
                throw new ApiException(ErrorCode.ROOM_FULL);
            }
        }

        RoomMember member = roomMemberRepository
                .findByRoomIdAndUserIdAndLeftAtIsNullAndStatus(roomId, targetUserId, MemberStatus.PENDING)
                .orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_FOUND));

        member.approve();
        log.info("APPROVE roomId={}, userId={}", roomId, targetUserId);
    }

    /**
     * 멤버 거절 (방장만)
     */
    @Transactional
    public void rejectMember(Long roomId, String targetUserId, String requesterId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ApiException(ErrorCode.ROOM_NOT_FOUND));

        if (!requesterId.equals(room.getOwnerId())) {
            throw new ApiException(ErrorCode.NOT_ROOM_OWNER);
        }

        RoomMember member = roomMemberRepository
                .findByRoomIdAndUserIdAndLeftAtIsNullAndStatus(roomId, targetUserId, MemberStatus.PENDING)
                .orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_FOUND));

        member.leave();  // 거절 = 퇴장 처리
        log.info("REJECT roomId={}, userId={}", roomId, targetUserId);
    }

    /**
     * 대기 중인 멤버 목록 (방장용)
     */
    @Transactional(readOnly = true)
    public List<PendingMember> getPendingMembers(Long roomId, String requesterId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ApiException(ErrorCode.ROOM_NOT_FOUND));

        if (!requesterId.equals(room.getOwnerId())) {
            throw new ApiException(ErrorCode.NOT_ROOM_OWNER);
        }

        List<RoomMember> members = roomMemberRepository
                .findByRoomIdAndLeftAtIsNullAndStatus(roomId, MemberStatus.PENDING);

        return members.stream()
                .map(m -> {
                    String nickname = userRepository.findById(m.getUserId())
                            .map(User::getNickname)
                            .orElse("알 수 없음");
                    return new PendingMember(m.getUserId(), nickname, m.getJoinedAt());
                })
                .toList();
    }

    public record PendingMember(String userId, String nickname, Instant joinedAt) {}

    /**
     * 승인된 멤버 수
     */
    @Transactional(readOnly = true)
    public int getApprovedMemberCount(Long roomId) {
        return roomMemberRepository
                .countByRoomIdAndLeftAtIsNullAndStatus(roomId, MemberStatus.APPROVED);
    }

    /**
     * 멤버십 상태 확인
     */
    @Transactional(readOnly = true)
    public MembershipStatus getMembershipStatus(Long roomId, String userId) {
        return roomMemberRepository
                .findByRoomIdAndUserIdAndLeftAtIsNull(roomId, userId)
                .map(member -> new MembershipStatus(true, member.getStatus()))
                .orElse(new MembershipStatus(false, null));
    }

    public record MembershipStatus(boolean isMember, MemberStatus status) {}

    /**
     * join 중복 호출 허용 (이전 버전 호환)
     */
    @Transactional
    public JoinResult joinIfNotExists(Long roomId, String userId) {
        Optional<RoomMember> existing = roomMemberRepository
                .findByRoomIdAndUserIdAndLeftAtIsNull(roomId, userId);

        if (existing.isPresent()) {
            return new JoinResult(existing.get().getStatus(), false);
        }

        return join(roomId, userId);
    }

    /**
     * 입장 결과
     */
    public record JoinResult(MemberStatus status, boolean requiresApproval) {
        public boolean isApproved() {
            return status == MemberStatus.APPROVED;
        }

        public boolean isPending() {
            return status == MemberStatus.PENDING;
        }
    }
}
