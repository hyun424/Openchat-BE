package io.hyun424.openchat.chat.room.partition.repository;

import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionState;
import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RoomPartitionStateRepository extends JpaRepository<RoomPartitionState, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO room_partition_state
                (room_id, partition_count, version, status, draining_partitions, updated_at, updated_by)
            VALUES
                (:roomId, :partitionCount, 1, 'ACTIVE', '', :updatedAt, :updatedBy)
            ON DUPLICATE KEY UPDATE room_id = room_id
            """, nativeQuery = true)
    int insertIfAbsent(@Param("roomId") Long roomId,
                       @Param("partitionCount") int partitionCount,
                       @Param("updatedAt") Instant updatedAt,
                       @Param("updatedBy") String updatedBy);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM RoomPartitionState s WHERE s.roomId = :roomId")
    Optional<RoomPartitionState> findByIdForUpdate(@Param("roomId") Long roomId);

    List<RoomPartitionState> findByStatus(RoomPartitionStatus status);

    @Query("""
            SELECT s FROM RoomPartitionState s
            WHERE s.status = io.hyun424.openchat.chat.room.partition.domain.RoomPartitionStatus.ACTIVE
              AND s.partitionCount > 1
            """)
    List<RoomPartitionState> findActiveScaleDownCandidates();

    @Query("""
            SELECT s FROM RoomPartitionState s
            WHERE s.status = io.hyun424.openchat.chat.room.partition.domain.RoomPartitionStatus.ACTIVE
              AND s.partitionCount > 1
              AND s.updatedBy LIKE CONCAT(:updatedByPrefix, '%')
            """)
    List<RoomPartitionState> findAutoManagedActiveScaleDownCandidates(@Param("updatedByPrefix") String updatedByPrefix);
}
