package io.hyun424.openchat.chat.room.summary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "room_rolling_memory",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_room_rolling_memory_room", columnNames = "room_id")
        },
        indexes = {
                @Index(name = "idx_room_rolling_memory_room", columnList = "room_id")
        }
)
public class RoomRollingMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "start_message_id", nullable = false)
    private Long startMessageId;

    @Column(name = "end_message_id", nullable = false)
    private Long endMessageId;

    @Column(name = "summary_text", nullable = false, columnDefinition = "TEXT")
    private String summaryText;

    @Column(name = "segment_count", nullable = false)
    private int segmentCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static RoomRollingMemory create(Long roomId,
                                           Long startMessageId,
                                           Long endMessageId,
                                           String summaryText,
                                           int segmentCount) {
        RoomRollingMemory memory = new RoomRollingMemory();
        memory.roomId = roomId;
        memory.replace(startMessageId, endMessageId, summaryText, segmentCount, Instant.now());
        return memory;
    }

    public void replace(Long startMessageId, Long endMessageId, String summaryText, int segmentCount, Instant now) {
        this.startMessageId = startMessageId;
        this.endMessageId = endMessageId;
        this.summaryText = summaryText;
        this.segmentCount = segmentCount;
        this.updatedAt = now;
    }
}
