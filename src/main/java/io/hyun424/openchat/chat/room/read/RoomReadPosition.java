package io.hyun424.openchat.chat.room.read;

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
        name = "room_read_position",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_room_read_position_room_user", columnNames = {"room_id", "user_id"})
        },
        indexes = {
                @Index(name = "idx_room_read_position_room_user", columnList = "room_id,user_id"),
                @Index(name = "idx_room_read_position_room_message", columnList = "room_id,last_read_message_id")
        }
)
public class RoomReadPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "last_read_message_id", nullable = false)
    private Long lastReadMessageId;

    @Column(name = "last_read_at", nullable = false)
    private Instant lastReadAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static RoomReadPosition create(Long roomId, String userId, Long lastReadMessageId, Instant now) {
        RoomReadPosition position = new RoomReadPosition();
        position.roomId = roomId;
        position.userId = userId;
        position.lastReadMessageId = lastReadMessageId;
        position.lastReadAt = now;
        position.updatedAt = now;
        return position;
    }

    public boolean advanceTo(Long messageId, Instant now) {
        if (messageId <= lastReadMessageId) {
            return false;
        }
        this.lastReadMessageId = messageId;
        this.lastReadAt = now;
        this.updatedAt = now;
        return true;
    }
}
