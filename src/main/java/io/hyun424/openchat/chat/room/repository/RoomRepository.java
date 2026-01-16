package io.hyun424.openchat.chat.room.repository;

import io.hyun424.openchat.chat.room.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoomRepository extends JpaRepository<Room, Long> {

    /**
     * Redis 장애 시 fallback 용
     * - 최신 생성된 채팅방 기준
     */
    List<Room> findTop5ByOrderByCreatedAtDesc();
}
