package io.hyun424.openchat.chat.room.dto;

import io.hyun424.openchat.chat.room.domain.Room;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class RoomMapResponse {
    private Long id;
    private String name;
    private String category;
    private BigDecimal lat;
    private BigDecimal lng;
    private String locationName;
    private int currentMembers;
    private Integer maxMembers;
    private String meetingDate;
    private String meetingTime;

    public static RoomMapResponse from(Room room, int currentMembers) {
        return RoomMapResponse.builder()
                .id(room.getId())
                .name(room.getName())
                .category(room.getCategory())
                .lat(room.getLat())
                .lng(room.getLng())
                .locationName(room.getLocationName())
                .currentMembers(currentMembers)
                .maxMembers(room.getMaxMembers())
                .meetingDate(room.getMeetingDate() != null ? room.getMeetingDate().toString() : null)
                .meetingTime(room.getMeetingTime() != null ? room.getMeetingTime().toString() : null)
                .build();
    }
}
