package io.hyun424.openchat.chat.room.read;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoomReadPositionRepository extends JpaRepository<RoomReadPosition, Long> {
    Optional<RoomReadPosition> findByRoomIdAndUserId(Long roomId, String userId);
}
