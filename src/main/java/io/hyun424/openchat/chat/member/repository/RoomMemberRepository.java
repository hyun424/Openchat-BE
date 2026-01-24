package io.hyun424.openchat.chat.member.repository;

import io.hyun424.openchat.chat.member.entity.MemberStatus;
import io.hyun424.openchat.chat.member.entity.RoomMember;
import org.springframework.data.jpa.repository.JpaRepository;

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

    // 특정 유저의 대기 중인 멤버십
    Optional<RoomMember> findByRoomIdAndUserIdAndLeftAtIsNullAndStatus(
            Long roomId, String userId, MemberStatus status);
}
