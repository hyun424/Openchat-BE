package io.hyun424.openchat.chat.room.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor

public class RoomCreateRequest {
    private String name;
    private String category;
    private Integer maxMembers;
    private String description;
    private String roomType;
    private Boolean requiresApproval;
    private String rules;

    public String getName() { return name; }
}

