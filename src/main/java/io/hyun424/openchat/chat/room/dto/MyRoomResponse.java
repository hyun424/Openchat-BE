package io.hyun424.openchat.chat.room.dto;

import io.hyun424.openchat.chat.room.domain.Room;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MyRoomResponse {

    private Long roomId;
    private String roomName;
    private Long lastMessageAt;
    private String lastMessage;
    private String lastMessageSender;

    public static MyRoomResponse from(Room room) {
        return MyRoomResponse.builder()
                .roomId(room.getId())
                .roomName(room.getName())
                .lastMessageAt(room.getLastMessageAt())
                .lastMessage(room.getLastMessage())
                .lastMessageSender(room.getLastMessageSender())
                .build();
    }
}
