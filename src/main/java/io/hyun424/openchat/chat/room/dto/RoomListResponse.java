package io.hyun424.openchat.chat.room.dto;

import io.hyun424.openchat.chat.room.domain.Room;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class RoomListResponse {
    private Long id;
    private String name;
    private String ownerId;
    private Integer maxMembers;
    private Boolean requiresApproval;
    private String description;
    private String rules;
    private String imageUrl;
    private String category;
    private String meetingDate;
    private String meetingTime;
    private BigDecimal lat;
    private BigDecimal lng;
    private String locationName;
    private int currentMembers;

    public static RoomListResponse from(Object[] row) {
        Room room = (Room) row[0];
        int currentMembers = ((Long) row[1]).intValue();
        return from(room, currentMembers);
    }

    public static RoomListResponse from(Room room, int currentMembers) {
        return RoomListResponse.builder()
                .id(room.getId())
                .name(room.getName())
                .ownerId(room.getOwnerId())
                .maxMembers(room.getMaxMembers())
                .requiresApproval(room.getRequiresApproval())
                .description(room.getDescription())
                .rules(room.getRules())
                .imageUrl(room.getImageUrl())
                .category(room.getCategory())
                .meetingDate(room.getMeetingDate() != null ? room.getMeetingDate().toString() : null)
                .meetingTime(room.getMeetingTime() != null ? room.getMeetingTime().toString() : null)
                .lat(room.getLat())
                .lng(room.getLng())
                .locationName(room.getLocationName())
                .currentMembers(currentMembers)
                .build();
    }
}
