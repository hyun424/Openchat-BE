package io.hyun424.openchat.chat.room.dto;

import io.hyun424.openchat.chat.room.domain.Room;
import lombok.Getter;

@Getter
public class RoomResponse {

    private Long id;
    private String name;
    private String ownerId;
    private Integer maxMembers;
    private Boolean requiresApproval;

    public static RoomResponse from(Room room) {
        RoomResponse response = new RoomResponse();
        response.id = room.getId();
        response.name = room.getName();
        response.ownerId = room.getOwnerId();
        response.maxMembers = room.getMaxMembers();
        response.requiresApproval = room.getRequiresApproval();
        return response;
    }
}
