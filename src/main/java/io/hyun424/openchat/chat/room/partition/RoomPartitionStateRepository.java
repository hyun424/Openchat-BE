package io.hyun424.openchat.chat.room.partition;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomPartitionStateRepository extends JpaRepository<RoomPartitionState, Long> {
}
