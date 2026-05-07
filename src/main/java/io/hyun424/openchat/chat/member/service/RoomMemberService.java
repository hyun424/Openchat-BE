package io.hyun424.openchat.chat.member.service;

import io.hyun424.openchat.chat.member.entity.MemberStatus;
import io.hyun424.openchat.chat.member.entity.RoomMember;
import io.hyun424.openchat.chat.member.repository.RoomMemberRepository;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.chat.room.repository.RoomRepository;
import io.hyun424.openchat.global.exception.ApiException;
import io.hyun424.openchat.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class RoomMemberService {

    private final RoomMemberRepository roomMemberRepository;
    private final ChatPipelineMetrics chatPipelineMetrics;
    private final RoomMemberReader reader;
    private final RoomMemberPolicy policy;
    private final RoomMemberJoinLock joinLock;
    private final PendingMemberMapper pendingMemberMapper;

    public RoomMemberService(RoomMemberRepository roomMemberRepository,
                             RoomRepository roomRepository,
                             ChatPipelineMetrics chatPipelineMetrics) {
        this.roomMemberRepository = roomMemberRepository;
        this.chatPipelineMetrics = chatPipelineMetrics;
        this.reader = new RoomMemberReader(roomMemberRepository, roomRepository);
        this.policy = new RoomMemberPolicy(reader);
        this.joinLock = new RoomMemberJoinLock(roomMemberRepository);
        this.pendingMemberMapper = new PendingMemberMapper();
    }

    /**
     * 메시지 접근 기준 시점 조회
     * - join 안 했으면 예외
     */
    @Transactional(readOnly = true)
    public Instant getJoinedAtOrThrow(Long roomId, String userId) {
        long startNanos = System.nanoTime();
        try {
            return reader.getApprovedMemberOrThrow(roomId, userId).getJoinedAt();
        } finally {
            chatPipelineMetrics.recordStage("room_member.get_joined_at", startNanos);
        }
    }

    public long getJoinedAtMillis(Long roomId, String userId) {
        long startNanos = System.nanoTime();
        RoomMember member;
        try {
            member = reader.getApprovedMemberOrThrow(roomId, userId);
        } finally {
            chatPipelineMetrics.recordStage("room_member.get_joined_at_millis", startNanos);
        }

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
        long totalStartNanos = System.nanoTime();
        long lockStartNanos = System.nanoTime();
        boolean joinLockAcquired = false;
        boolean capacityLockAcquired = false;
        try {
            joinLock.acquireOrThrow(roomId, userId);
            joinLockAcquired = true;
            joinLock.acquireRoomCapacityOrThrow(roomId);
            capacityLockAcquired = true;
            chatPipelineMetrics.recordStage("room_member.join.lock_acquire", lockStartNanos);

            long roomLookupStartNanos = System.nanoTime();
            Room room = reader.getRoomOrThrow(roomId);
            chatPipelineMetrics.recordStage("room_member.join.room_lookup", roomLookupStartNanos);
            policy.ensureRoomAccessible(room);

            if (userId.equals(room.getOwnerId())) {
                return joinAsOwner(roomId, userId);
            }

            policy.rejectIfAlreadyJoined(roomId, userId);
            policy.ensureRoomHasCapacity(room);

            boolean requiresApproval = Boolean.TRUE.equals(room.getRequiresApproval());
            long saveStartNanos = System.nanoTime();
            RoomMember member = createNewMemberWithRaceHandling(roomId, userId, requiresApproval);
            chatPipelineMetrics.recordStage("room_member.join.save", saveStartNanos);
            return new JoinResult(member.getStatus(), requiresApproval);
        } finally {
            if (capacityLockAcquired) {
                joinLock.releaseRoomCapacity(roomId);
            }
            if (joinLockAcquired) {
                joinLock.release(roomId, userId);
            }
            chatPipelineMetrics.recordStage("room_member.join.total", totalStartNanos);
        }
    }

    private JoinResult joinAsOwner(Long roomId, String userId) {
        Optional<RoomMember> existing = reader.findActiveMember(roomId, userId);

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
        RoomMember member = reader.getActiveMemberOrThrow(roomId, userId);
        member.leave();
    }

    /**
     * 멤버 승인 (방장만)
     */
    @Transactional
    public void approveMember(Long roomId, String targetUserId, String requesterId) {
        joinLock.acquireRoomCapacityOrThrow(roomId);
        try {
            Room room = reader.getRoomOrThrow(roomId);
            policy.ensureRoomOwner(room, requesterId);
            policy.ensureRoomHasCapacity(room);

            RoomMember member = reader.getPendingMemberOrThrow(roomId, targetUserId);
            member.approve();
            log.info("APPROVE roomId={}, userId={}", roomId, targetUserId);
        } finally {
            joinLock.releaseRoomCapacity(roomId);
        }
    }

    /**
     * 멤버 거절 (방장만)
     */
    @Transactional
    public void rejectMember(Long roomId, String targetUserId, String requesterId) {
        Room room = reader.getRoomOrThrow(roomId);
        policy.ensureRoomOwner(room, requesterId);

        RoomMember member = reader.getPendingMemberOrThrow(roomId, targetUserId);
        member.leave();  // 거절 = 퇴장 처리
        log.info("REJECT roomId={}, userId={}", roomId, targetUserId);
    }

    /**
     * 대기 중인 멤버 목록 (방장용)
     */
    @Transactional(readOnly = true)
    public List<PendingMember> getPendingMembers(Long roomId, String requesterId) {
        Room room = reader.getRoomOrThrow(roomId);
        policy.ensureRoomOwner(room, requesterId);

        return reader.findPendingMembersWithNickname(roomId)
                .stream()
                .map(pendingMemberMapper::toPendingMember)
                .toList();
    }

    public record PendingMember(String userId, String nickname, Instant joinedAt) {}

    /**
     * 승인된 멤버 수
     */
    @Transactional(readOnly = true)
    public int getApprovedMemberCount(Long roomId) {
        return reader.countApprovedMembers(roomId);
    }

    /**
     * 멤버십 상태 확인
     */
    @Transactional(readOnly = true)
    public MembershipStatus getMembershipStatus(Long roomId, String userId) {
        return reader.findActiveMember(roomId, userId)
                .map(member -> new MembershipStatus(true, member.getStatus()))
                .orElse(new MembershipStatus(false, null));
    }

    public record MembershipStatus(boolean isMember, MemberStatus status) {}

    /**
     * join 중복 호출 허용 (이전 버전 호환)
     */
    @Transactional
    public JoinResult joinIfNotExists(Long roomId, String userId) {
        try {
            return join(roomId, userId);
        } catch (ApiException e) {
            if (e.getErrorCode() == ErrorCode.ALREADY_JOINED || e.getErrorCode() == ErrorCode.PENDING_APPROVAL) {
                Optional<RoomMember> existing = reader.findActiveMember(roomId, userId);
                if (existing.isPresent()) {
                    RoomMember member = existing.get();
                    return new JoinResult(member.getStatus(), member.getStatus() == MemberStatus.PENDING);
                }
            }
            throw e;
        }
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

    /**
     * user+room advisory lock으로 대부분의 중복 입장을 직렬화한다.
     * 그래도 DB unique 제약이 마지막 방어선이므로 저장 경합은 ALREADY_JOINED로 통일한다.
     */
    private RoomMember createNewMemberWithRaceHandling(Long roomId, String userId, boolean requiresApproval) {
        try {
            RoomMember member = roomMemberRepository.save(RoomMember.join(roomId, userId, requiresApproval));
            log.info("JOIN roomId={}, userId={}, status={}", roomId, userId, member.getStatus());
            return member;
        } catch (DataIntegrityViolationException e) {
            log.warn("[RACE_CONDITION] Duplicate join attempt: roomId={}, userId={}", roomId, userId);
            throw new ApiException(ErrorCode.ALREADY_JOINED);
        }
    }
}
