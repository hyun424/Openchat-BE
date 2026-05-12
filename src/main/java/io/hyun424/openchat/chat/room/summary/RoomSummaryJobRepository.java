package io.hyun424.openchat.chat.room.summary;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoomSummaryJobRepository extends JpaRepository<RoomSummaryJob, Long> {
    Optional<RoomSummaryJob> findTopByStatusOrderByIdAsc(RoomSummaryJobStatus status);

    boolean existsByRoomIdAndStartMessageIdAndEndMessageId(Long roomId, Long startMessageId, Long endMessageId);
}
