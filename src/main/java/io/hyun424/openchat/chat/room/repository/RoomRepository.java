package io.hyun424.openchat.chat.room.repository;

import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.chat.room.domain.RoomStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public interface RoomRepository extends JpaRepository<Room, Long> {

    /**
     * 전체 방 목록 (ACTIVE만, 최신순) - 페이지네이션 + 멤버 수 집계 (N+1 해결)
     */
    @Query("SELECT r, COUNT(rm) FROM Room r " +
           "LEFT JOIN RoomMember rm ON r.id = rm.roomId AND rm.leftAt IS NULL AND rm.status = 'APPROVED' " +
           "WHERE r.status = :status " +
           "GROUP BY r.id " +
           "ORDER BY r.createdAt DESC")
    Page<Object[]> findRoomsWithMemberCount(@Param("status") RoomStatus status, Pageable pageable);

    /**
     * Redis fallback: recently created rooms
     */
    List<Room> findTop5ByOrderByCreatedAtDesc();

    @Query("SELECT COALESCE(r.shardId, 0) FROM Room r WHERE r.id = :roomId")
    java.util.Optional<Integer> findShardIdById(@Param("roomId") Long roomId);

    @Query("SELECT COUNT(r) FROM Room r " +
           "WHERE r.status = :status AND COALESCE(r.shardId, 0) = :shardId")
    long countActiveRoomsByResolvedShardId(@Param("status") RoomStatus status,
                                           @Param("shardId") int shardId);

    /**
     * My Chats: rooms user has joined (active membership), sorted by recent activity
     * - Only ACTIVE rooms
     * - Rooms with messages first (by lastMessageAt DESC)
     * - Rooms without messages last (by createdAt DESC)
     */
    @Query("SELECT r FROM Room r " +
           "JOIN RoomMember rm ON r.id = rm.roomId " +
           "WHERE rm.userId = :userId AND rm.leftAt IS NULL AND rm.status = 'APPROVED' AND r.status = 'ACTIVE' " +
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
     * 지도용: ACTIVE 상태 + 위치 정보 있는 방 + 멤버 수 집계 + bounding box 필터
     */
    @Query("SELECT r, COUNT(rm) FROM Room r " +
           "LEFT JOIN RoomMember rm ON r.id = rm.roomId AND rm.leftAt IS NULL AND rm.status = 'APPROVED' " +
           "WHERE r.status = :status AND r.lat IS NOT NULL AND r.lng IS NOT NULL " +
           "AND r.lat BETWEEN :swLat AND :neLat " +
           "AND r.lng BETWEEN :swLng AND :neLng " +
           "GROUP BY r.id")
    List<Object[]> findRoomsForMapWithMemberCount(
            @Param("status") RoomStatus status,
            @Param("swLat") java.math.BigDecimal swLat,
            @Param("swLng") java.math.BigDecimal swLng,
            @Param("neLat") java.math.BigDecimal neLat,
            @Param("neLng") java.math.BigDecimal neLng);

    @Modifying
    @Query("UPDATE Room r " +
           "SET r.lastMessageAt = :timestamp, r.lastMessage = :message, r.lastMessageSender = :senderName " +
           "WHERE r.id = :roomId " +
           "AND (r.lastMessageAt IS NULL OR r.lastMessageAt < :timestamp)")
    int updateLastMessageIfNewer(@Param("roomId") Long roomId,
                                 @Param("timestamp") Long timestamp,
                                 @Param("message") String message,
                                 @Param("senderName") String senderName);
}
