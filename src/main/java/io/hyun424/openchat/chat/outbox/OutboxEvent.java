package io.hyun424.openchat.chat.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "outbox_event",
        indexes = {
                @Index(name = "idx_outbox_status_retry_id", columnList = "status, nextRetryAt, id"),
                @Index(name = "idx_outbox_room_id", columnList = "roomId, id"),
                @Index(name = "idx_outbox_message_id", columnList = "messageId"),
                @Index(name = "uk_outbox_event_id", columnList = "eventId", unique = true)
        }
)
public class OutboxEvent {

    public static final String CHAT_MESSAGE_CREATED = "CHAT_MESSAGE_CREATED";
    public static final String AGGREGATE_MESSAGE = "MESSAGE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(nullable = false, length = 80)
    private String eventType;

    @Column(nullable = false, length = 80)
    private String aggregateType;

    @Column(nullable = false)
    private Long aggregateId;

    @Column(nullable = false)
    private Long roomId;

    @Column(nullable = false, length = 36)
    private String messageId;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxEventStatus status;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private Long nextRetryAt;

    @Column(nullable = false)
    private Long createdAt;

    private Long publishedAt;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String lastError;

    @Builder
    public OutboxEvent(String eventId,
                       String eventType,
                       String aggregateType,
                       Long aggregateId,
                       Long roomId,
                       String messageId,
                       String payloadJson,
                       OutboxEventStatus status,
                       int attemptCount,
                       Long nextRetryAt,
                       Long createdAt,
                       Long publishedAt,
                       String lastError) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.roomId = roomId;
        this.messageId = messageId;
        this.payloadJson = payloadJson;
        this.status = status;
        this.attemptCount = attemptCount;
        this.nextRetryAt = nextRetryAt;
        this.createdAt = createdAt;
        this.publishedAt = publishedAt;
        this.lastError = lastError;
    }

    public void markPublished(long now) {
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = now;
        this.lastError = null;
    }

    public void markPendingForRetry(long nextRetryAt, String error) {
        this.status = OutboxEventStatus.PENDING;
        this.attemptCount++;
        this.nextRetryAt = nextRetryAt;
        this.lastError = truncate(error);
    }

    public void markFailed(String error) {
        this.status = OutboxEventStatus.FAILED;
        this.attemptCount++;
        this.lastError = truncate(error);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 2000) {
            return value;
        }
        return value.substring(0, 2000);
    }
}
