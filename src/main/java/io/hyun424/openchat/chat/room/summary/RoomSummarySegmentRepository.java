package io.hyun424.openchat.chat.room.summary;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoomSummarySegmentRepository extends JpaRepository<RoomSummarySegment, Long> {
    List<RoomSummarySegment> findTop3ByRoomIdOrderByEndMessageIdDesc(Long roomId);
}
