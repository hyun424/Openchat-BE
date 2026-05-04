package io.hyun424.openchat.chat.message.repository;

import io.hyun424.openchat.chat.message.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    Optional<Message> findByRoomIdAndSenderIdAndClientMessageId(Long roomId, String senderId, String clientMessageId);

    /**
     * Initial load: Latest N messages (newest first by id, then reversed for display)
     * Uses id as primary sort key for consistent ordering across real-time and refresh
     */
    @Query("SELECT m FROM Message m WHERE m.roomId = :roomId AND m.createdAt >= :joinedAt " +
           "ORDER BY m.id DESC")
    List<Message> findLatestMessages(
            @Param("roomId") Long roomId,
            @Param("joinedAt") Long joinedAt,
            Pageable pageable
    );

    /**
     * Infinite scroll: Load older messages before cursor (by id)
     * Cursor = id for stable, consistent pagination
     */
    @Query("SELECT m FROM Message m WHERE m.roomId = :roomId " +
           "AND m.createdAt >= :joinedAt " +
           "AND m.id < :cursorId " +
           "ORDER BY m.id DESC")
    List<Message> findMessagesBeforeCursor(
            @Param("roomId") Long roomId,
            @Param("joinedAt") Long joinedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );

    /**
     * WebSocket reconnect/sync: Load newer messages after cursor (by id).
     */
    @Query("SELECT m FROM Message m WHERE m.roomId = :roomId " +
           "AND m.createdAt >= :joinedAt " +
           "AND m.id > :cursorId " +
           "ORDER BY m.id ASC")
    List<Message> findMessagesAfterCursor(
            @Param("roomId") Long roomId,
            @Param("joinedAt") Long joinedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );

    /**
     * Legacy: All messages since joined (for backward compatibility)
     */
    List<Message> findByRoomIdAndCreatedAtGreaterThanEqualOrderByIdAsc(
            Long roomId,
            Long joinedAt
    );
}
