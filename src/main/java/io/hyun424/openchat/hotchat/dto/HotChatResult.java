package io.hyun424.openchat.hotchat.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class HotChatResult {

    /**
     * Redis ZSET member 값
     * 예: "room:42"
     */
    private String roomKey;

    /**
     * 최근 window 동안의 누적 점수
     */
    private Double score;

    public Long getRoomId() {
        // "room:42" → 42
        return Long.parseLong(roomKey.split(":")[1]);
    }
}
