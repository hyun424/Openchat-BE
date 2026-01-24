package io.hyun424.openchat.chat.room.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
public class RoomCreateRequest {

    @NotBlank(message = "방 이름은 필수입니다.")
    @Size(min = 2, max = 50, message = "방 이름은 2~50자여야 합니다.")
    private String name;

    @Size(max = 20, message = "카테고리는 20자 이하여야 합니다.")
    private String category;

    @Min(value = 2, message = "최소 인원은 2명 이상이어야 합니다.")
    @Max(value = 100, message = "최대 인원은 100명 이하여야 합니다.")
    private Integer maxMembers;

    @Size(max = 500, message = "설명은 500자 이하여야 합니다.")
    private String description;

    private String roomType;
    private Boolean requiresApproval;

    @Size(max = 1000, message = "규칙은 1000자 이하여야 합니다.")
    private String rules;

    @Size(max = 500, message = "이미지 URL은 500자 이하여야 합니다.")
    private String imageUrl;

    // 모임 위치/일시
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "날짜 형식은 YYYY-MM-DD여야 합니다.")
    private String meetingDate;

    @Pattern(regexp = "^\\d{2}:\\d{2}$", message = "시간 형식은 HH:mm이어야 합니다.")
    private String meetingTime;

    @DecimalMin(value = "-90.0", message = "위도는 -90 이상이어야 합니다.")
    @DecimalMax(value = "90.0", message = "위도는 90 이하여야 합니다.")
    private BigDecimal lat;

    @DecimalMin(value = "-180.0", message = "경도는 -180 이상이어야 합니다.")
    @DecimalMax(value = "180.0", message = "경도는 180 이하여야 합니다.")
    private BigDecimal lng;

    @Size(max = 100, message = "위치명은 100자 이하여야 합니다.")
    private String locationName;
}

