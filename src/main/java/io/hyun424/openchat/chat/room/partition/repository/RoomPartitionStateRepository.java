package io.hyun424.openchat.chat.room.partition.repository;

import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomPartitionStateRepository extends JpaRepository<RoomPartitionState, Long> {
}
