package io.hyun424.openchat.chat.room.summary;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RoomSummaryQueryService {

    private final RoomSummaryAvailabilityService availabilityService;
    private final RoomRollingMemoryRepository rollingMemoryRepository;

    @Transactional(readOnly = true)
    public RoomSummaryResponse getSummary(Long roomId, String userId) {
        RoomSummaryAvailabilityResponse availability = availabilityService.availability(roomId, userId);
        if (!availability.available()) {
            return RoomSummaryResponse.notReady();
        }
        return rollingMemoryRepository.findByRoomId(roomId)
                .map(RoomSummaryResponse::ready)
                .orElseGet(RoomSummaryResponse::notReady);
    }
}
