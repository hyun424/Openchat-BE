package io.hyun424.openchat.chat.room.summary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "room_summary_job",
        indexes = {
                @Index(name = "idx_room_summary_job_status_id", columnList = "status,id"),
                @Index(name = "idx_room_summary_job_room", columnList = "room_id")
        }
)
public class RoomSummaryJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "start_message_id", nullable = false)
    private Long startMessageId;

    @Column(name = "end_message_id", nullable = false)
    private Long endMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RoomSummaryJobStatus status;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public static RoomSummaryJob pending(Long roomId, Long startMessageId, Long endMessageId) {
        RoomSummaryJob job = new RoomSummaryJob();
        job.roomId = roomId;
        job.startMessageId = startMessageId;
        job.endMessageId = endMessageId;
        job.status = RoomSummaryJobStatus.PENDING;
        job.createdAt = Instant.now();
        return job;
    }

    public void start(Instant now) {
        this.status = RoomSummaryJobStatus.PROCESSING;
        this.startedAt = now;
        this.failureReason = null;
    }

    public void complete(Instant now) {
        this.status = RoomSummaryJobStatus.COMPLETED;
        this.completedAt = now;
        this.failureReason = null;
    }

    public void fail(String reason, Instant now) {
        this.status = RoomSummaryJobStatus.FAILED;
        this.completedAt = now;
        this.failureReason = reason;
    }
}
