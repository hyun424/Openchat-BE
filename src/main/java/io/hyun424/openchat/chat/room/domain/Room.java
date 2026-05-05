package io.hyun424.openchat.chat.room.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(
        name = "room",
        indexes = {
                @Index(name = "idx_room_status_created", columnList = "status, created_at"),
                @Index(name = "idx_room_status_shard", columnList = "status, shard_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String ownerId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_message_at")
    private Long lastMessageAt;

    @Column(name = "last_message", length = 500)
    private String lastMessage;

    @Column(name = "last_message_sender", length = 100)
    private String lastMessageSender;

    @Column(name = "shard_id")
    private Integer shardId;

    @Column(name = "max_members")
    private Integer maxMembers;  // null = 무제한

    @Column(name = "requires_approval", nullable = false)
    @Builder.Default
    private Boolean requiresApproval = false;

    @Column(length = 500)
    private String description;

    @Column(length = 1000)
    private String rules;

    @Column(length = 500)
    private String imageUrl;

    private String category;

    @Column(name = "meeting_date")
    private LocalDate meetingDate;

    @Column(name = "meeting_time")
    private LocalTime meetingTime;

    @Column(precision = 10, scale = 7)
    private BigDecimal lat;

    @Column(precision = 10, scale = 7)
    private BigDecimal lng;

    @Column(name = "location_name", length = 200)
    private String locationName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RoomStatus status = RoomStatus.ACTIVE;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    // Called when a new message is successfully ingested
    public void updateLastMessage(Long timestamp, String message, String senderName) {
        // Only update if the new timestamp is more recent (handles out-of-order updates in multi-server env)
        if (this.lastMessageAt == null || timestamp > this.lastMessageAt) {
            this.lastMessageAt = timestamp;
            this.lastMessage = truncateMessage(message);
            this.lastMessageSender = senderName;
        }
    }

    private String truncateMessage(String message) {
        if (message == null) return null;
        return message.length() > 100 ? message.substring(0, 100) + "..." : message;
    }

    /**
     * 방장이 방 삭제 시 호출
     */
    public void delete() {
        this.status = RoomStatus.ENDED;
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 스케줄러에 의한 자동 종료 시 호출
     */
    public void end() {
        this.status = RoomStatus.ENDED;
    }

    /**
     * 방 접근 가능 여부 확인
     */
    public boolean isAccessible() {
        return this.status == RoomStatus.ACTIVE;
    }
}
