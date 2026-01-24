package io.hyun424.openchat.chat.room.repository;

import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.chat.room.domain.RoomStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public interface RoomRepository extends JpaRepository<Room, Long> {

    /**
     * 전체 방 목록 (ACTIVE만, 최신순)
     */
    List<Room> findAllByStatusOrderByCreatedAtDesc(RoomStatus status);

    /**
     * Redis fallback: recently created rooms
     */
    List<Room> findTop5ByOrderByCreatedAtDesc();

    /**
     * My Chats: rooms user has joined (active membership), sorted by recent activity
     * - Only ACTIVE rooms
     * - Rooms with messages first (by lastMessageAt DESC)
     * - Rooms without messages last (by createdAt DESC)
     */
    @Query("SELECT r FROM Room r " +
           "JOIN RoomMember rm ON r.id = rm.roomId " +
           "WHERE rm.userId = :userId AND rm.leftAt IS NULL AND r.status = 'ACTIVE' " +
           "ORDER BY COALESCE(r.lastMessageAt, 0) DESC, r.createdAt DESC")
    List<Room> findMyRooms(@Param("userId") String userId);

    /**
     * 자동 종료 대상 방 조회:
     * - ACTIVE 상태
     * - 모임 날짜가 지났거나 (오늘 && 모임 시간이 지남)
     */
    @Query("SELECT r FROM Room r " +
           "WHERE r.status = 'ACTIVE' " +
           "AND r.meetingDate IS NOT NULL " +
           "AND (r.meetingDate < :today " +
           "     OR (r.meetingDate = :today AND r.meetingTime IS NOT NULL AND r.meetingTime < :now))")
    List<Room> findExpiredRooms(@Param("today") LocalDate today, @Param("now") LocalTime now);

    /**
     * 지도용: ACTIVE 상태만
     */
    List<Room> findAllByStatusAndLatIsNotNullAndLngIsNotNull(RoomStatus status);
}
