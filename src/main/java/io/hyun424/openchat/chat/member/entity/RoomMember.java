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
                ),
                @Index(
                        name = "idx_room_member_count",
                        columnList = "room_id, left_at, status"
                )
        },
        // NOTE:
        // MySQL에서 NULL 포함 유니크 제약은 완전한 활성 멤버십 중복 방지 수단이 아니므로
        // 실제 join 경합 제어는 RoomMemberService의 advisory lock(GET_LOCK)으로 보강한다.
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_room_user_active_member",
                        columnNames = {"room_id", "user_id", "left_at"}
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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private MemberStatus status = MemberStatus.APPROVED;

    /* ===========================
       도메인 팩토리
       =========================== */

    public static RoomMember join(Long roomId, String userId, boolean requiresApproval) {
        return RoomMember.builder()
                .roomId(roomId)
                .userId(userId)
                .joinedAt(Instant.now())
                .leftAt(null)
                .status(requiresApproval ? MemberStatus.PENDING : MemberStatus.APPROVED)
                .build();
    }

    /* ===========================
       도메인 행위
       =========================== */

    public void leave() {
        this.leftAt = Instant.now();
    }

    public void approve() {
        this.status = MemberStatus.APPROVED;
    }

    public boolean isActive() {
        return leftAt == null && status == MemberStatus.APPROVED;
    }

    public boolean isPending() {
        return leftAt == null && status == MemberStatus.PENDING;
    }

    // TODO: 추후 Redis 세션 기반 참여 관리로 교체 가능
}
