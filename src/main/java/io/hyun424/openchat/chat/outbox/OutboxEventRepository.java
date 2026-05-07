package io.hyun424.openchat.chat.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query("SELECT e.id FROM OutboxEvent e " +
            "WHERE e.status = :status AND e.nextRetryAt <= :now " +
            "ORDER BY e.id ASC")
    List<Long> findReadyIds(@Param("status") OutboxEventStatus status,
                            @Param("now") long now,
                            Pageable pageable);

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.status = :processing, e.nextRetryAt = :processingDeadline " +
            "WHERE e.id = :id AND e.status = :pending AND e.nextRetryAt <= :now")
    int claim(@Param("id") Long id,
              @Param("pending") OutboxEventStatus pending,
              @Param("processing") OutboxEventStatus processing,
              @Param("now") long now,
              @Param("processingDeadline") long processingDeadline);

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.status = :processing, e.nextRetryAt = :processingDeadline " +
            "WHERE e.messageId = :messageId AND e.status = :pending AND e.nextRetryAt <= :now")
    int claimByMessageId(@Param("messageId") String messageId,
                         @Param("pending") OutboxEventStatus pending,
                         @Param("processing") OutboxEventStatus processing,
                         @Param("now") long now,
                         @Param("processingDeadline") long processingDeadline);

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.status = :pending " +
            "WHERE e.status = :processing AND e.nextRetryAt <= :now")
    int resetExpiredProcessing(@Param("processing") OutboxEventStatus processing,
                               @Param("pending") OutboxEventStatus pending,
                               @Param("now") long now);

    long countByStatus(OutboxEventStatus status);

    @Query("SELECT MIN(e.createdAt) FROM OutboxEvent e WHERE e.status = :status")
    Long findOldestCreatedAtByStatus(@Param("status") OutboxEventStatus status);

    Optional<OutboxEvent> findFirstByMessageIdOrderByIdAsc(String messageId);

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.status = :published, e.publishedAt = :publishedAt, e.lastError = null " +
            "WHERE e.id IN :ids AND e.status = :expectedStatus")
    int markPublishedByIds(@Param("ids") List<Long> ids,
                           @Param("expectedStatus") OutboxEventStatus expectedStatus,
                           @Param("published") OutboxEventStatus published,
                           @Param("publishedAt") long publishedAt);
}
