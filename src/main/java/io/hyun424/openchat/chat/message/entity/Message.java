package io.hyun424.openchat.chat.message.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "chat_message",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_room_sender_client_message_id",
                        columnNames = {"room_id", "sender_id", "client_message_id"}
                )
        },
        indexes = {
                // Compound index for ordered message retrieval by id
                // Supports: findLatestMessages, findMessagesBeforeCursor
                @Index(name = "idx_room_id", columnList = "roomId, id"),
                // For joinedAt filtering
                @Index(name = "idx_room_created", columnList = "roomId, createdAt"),
                @Index(name = "uk_message_id", columnList = "messageId", unique = true)
        }
)
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 🔥 서버 생성 메시지 ID (dedupe / fan-out 기준)
    @Column(nullable = false, unique = true, length = 36)
    private String messageId;

    @Column(nullable = false)
    private Long roomId;

    @Column(nullable = false)
    private String senderId;

    // Client-generated idempotency key (nullable for legacy clients)
    @Column(length = 100)
    private String clientMessageId;

    @Column(nullable = false)
    private String senderNickname;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // 🔥 epoch millis (Kafka / Redis / FE 공통)
    @Column(nullable = false)
    private Long createdAt;

    @Builder
    public Message(
            String messageId,
            Long roomId,
            String senderId,
            String clientMessageId,
            String senderNickname,
            String content,
            Long createdAt
    ) {
        this.messageId = messageId;
        this.roomId = roomId;
        this.senderId = senderId;
        this.clientMessageId = clientMessageId;
        this.senderNickname = senderNickname;
        this.content = content;
        this.createdAt = createdAt;
    }
}
