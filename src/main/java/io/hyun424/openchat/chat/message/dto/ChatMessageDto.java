package io.hyun424.openchat.chat.message.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.hyun424.openchat.chat.message.entity.Message;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDto {

    /** DB 메시지 ID (영속 식별자, 없을 수도 있음) */
    private Long id;

    /** 서버 발급 메시지 ID (Redis / WS / dedupe 기준) */
    private String messageId;

    /** 클라이언트 발급 메시지 ID (optimistic / UI 안정성용) */
    private String clientMessageId;

    private Long roomId;
    private String senderId;
    private String senderName;

    @JsonProperty("content")
    private String message;
    private String timestamp;

    /**
     * Entity → DTO 변환
     *  - DB 조회 결과를 WS로 내보낼 때 사용
     *  - clientMessageId는 과거 메시지에는 없음
     */
    public static ChatMessageDto from(Message message) {
        return new ChatMessageDto(
                message.getId(),
                null,                 // messageId는 ingest 단계에서 생성
                null,                 // clientMessageId는 과거 메시지에는 없음
                message.getRoomId(),
                message.getSenderId(),
                message.getSenderNickname(),
                message.getContent(),
                message.getCreatedAt().toString()
        );
    }
}
