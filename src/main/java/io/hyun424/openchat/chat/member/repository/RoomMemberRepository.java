package io.hyun424.openchat.chat.member.repository;

import io.hyun424.openchat.chat.member.entity.MemberStatus;
import io.hyun424.openchat.chat.member.entity.RoomMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RoomMemberRepository
        extends JpaRepository<RoomMember, Long> {

    Optional<RoomMember> findTopByRoomIdAndUserIdOrderByJoinedAtDesc(
            Long roomId,
            String userId
    );

    Optional<RoomMember> findByRoomIdAndUserIdAndLeftAtIsNull(
            Long roomId,
            String userId
    );

    // 승인된 활성 멤버 수
    int countByRoomIdAndLeftAtIsNullAndStatus(Long roomId, MemberStatus status);

    // 대기 중인 멤버 목록
    List<RoomMember> findByRoomIdAndLeftAtIsNullAndStatus(Long roomId, MemberStatus status);

    // 대기 중인 멤버 목록 + 닉네임 JOIN (N+1 해결)
    @Query("SELECT rm, u.nickname FROM RoomMember rm JOIN io.hyun424.openchat.auth.entity.User u ON rm.userId = u.id " +
           "WHERE rm.roomId = :roomId AND rm.leftAt IS NULL AND rm.status = :status")
    List<Object[]> findPendingMembersWithNickname(@Param("roomId") Long roomId, @Param("status") MemberStatus status);

    // 특정 유저의 대기 중인 멤버십
    Optional<RoomMember> findByRoomIdAndUserIdAndLeftAtIsNullAndStatus(
            Long roomId, String userId, MemberStatus status);

    /**
     * Per room/user advisory lock for join flow.
     * GET_LOCK returns 1 on success, 0 on timeout, NULL on error.
     */
    @Query(value = "SELECT GET_LOCK(:lockName, :timeoutSeconds)", nativeQuery = true)
    Integer acquireJoinLock(@Param("lockName") String lockName,
                            @Param("timeoutSeconds") int timeoutSeconds);

    @Query(value = "SELECT RELEASE_LOCK(:lockName)", nativeQuery = true)
    Integer releaseJoinLock(@Param("lockName") String lockName);

    /**
     * Room-level lock for capacity-changing operations.
     */
    @Query(value = "SELECT GET_LOCK(CONCAT('room_capacity:', :roomId), :timeoutSeconds)", nativeQuery = true)
    Integer acquireRoomCapacityLock(@Param("roomId") Long roomId,
                                    @Param("timeoutSeconds") int timeoutSeconds);

    @Query(value = "SELECT RELEASE_LOCK(CONCAT('room_capacity:', :roomId))", nativeQuery = true)
    Integer releaseRoomCapacityLock(@Param("roomId") Long roomId);
}
