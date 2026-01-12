package io.hyun424.openchat.chat.room.repository;

import io.hyun424.openchat.chat.room.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long> {
    // 기본 CRUD는 전부 JpaRepository가 제공
}
