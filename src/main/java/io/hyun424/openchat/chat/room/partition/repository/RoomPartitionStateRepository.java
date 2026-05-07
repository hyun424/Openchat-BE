package io.hyun424.openchat.chat.room.partition.repository;

import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RoomPartitionStateRepository extends JpaRepository<RoomPartitionState, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM RoomPartitionState s WHERE s.roomId = :roomId")
    Optional<RoomPartitionState> findByIdForUpdate(@Param("roomId") Long roomId);
}
