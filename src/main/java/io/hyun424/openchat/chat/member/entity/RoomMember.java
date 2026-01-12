package io.hyun424.openchat.chat.member.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "room_member",
        indexes = {
                @Index(
                        name = "idx_room_user_active",
                        columnList = "room_id, user_id, left_at"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // ✅ JPA 기본 생성자
@AllArgsConstructor(access = AccessLevel.PRIVATE)  // ✅ Builder 전용
@Builder
public class RoomMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    /* ===========================
       🔥 도메인 팩토리 (MVP)
       =========================== */

    public static RoomMember join(Long roomId, String userId) {
        return RoomMember.builder()
                .roomId(roomId)
                .userId(userId)
                .joinedAt(Instant.now())
                .leftAt(null)
                .build();
    }

    /* ===========================
       도메인 행위
       =========================== */

    public void leave() {
        this.leftAt = Instant.now();
    }

    public boolean isActive() {
        return leftAt == null;
    }

    // TODO: 추후 Redis 세션 기반 참여 관리로 교체 가능
}
