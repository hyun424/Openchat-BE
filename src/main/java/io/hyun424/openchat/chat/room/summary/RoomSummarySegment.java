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
import java.util.List;
import java.util.stream.Collectors;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "room_summary_segment",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_room_summary_segment_range",
                        columnNames = {"room_id", "start_message_id", "end_message_id"}
                )
        },
        indexes = {
                @Index(name = "idx_room_summary_segment_room_end", columnList = "room_id,end_message_id")
        }
)
public class RoomSummarySegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "start_message_id", nullable = false)
    private Long startMessageId;

    @Column(name = "end_message_id", nullable = false)
    private Long endMessageId;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "summary_text", nullable = false, columnDefinition = "TEXT")
    private String summaryText;

    @Column(name = "evidence_message_ids", columnDefinition = "TEXT")
    private String evidenceMessageIds;

    @Column(name = "signals_json", columnDefinition = "TEXT")
    private String signalsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static RoomSummarySegment create(Long roomId,
                                            Long startMessageId,
                                            Long endMessageId,
                                            int messageCount,
                                            RoomSegmentSummary summary) {
        RoomSummarySegment segment = new RoomSummarySegment();
        segment.roomId = roomId;
        segment.startMessageId = startMessageId;
        segment.endMessageId = endMessageId;
        segment.messageCount = messageCount;
        segment.summaryText = summary.summaryText();
        segment.evidenceMessageIds = joinIds(summary.evidenceMessageIds());
        segment.signalsJson = summary.signals().toString();
        segment.createdAt = Instant.now();
        return segment;
    }

    private static String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
