package io.hyun424.openchat.chat.member.repository;

import io.hyun424.openchat.chat.member.entity.RoomMember;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
