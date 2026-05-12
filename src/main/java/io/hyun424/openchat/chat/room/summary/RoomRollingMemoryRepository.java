package io.hyun424.openchat.chat.room.summary;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoomRollingMemoryRepository extends JpaRepository<RoomRollingMemory, Long> {
    Optional<RoomRollingMemory> findByRoomId(Long roomId);
}
