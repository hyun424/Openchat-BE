package io.hyun424.openchat.chat.room.dto;

import io.hyun424.openchat.chat.room.domain.Room;



public class RoomResponse {

    private Long id;
    private String name;

    // ⭐ 이 메서드가 반드시 필요함
    public static RoomResponse from(Room room) {
        RoomResponse response = new RoomResponse();
        response.id = room.getId();
        response.name = room.getName();
        return response;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
