package io.hyun424.openchat.chat.room.summary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomSummaryQueryServiceTest {

    @Mock
    private RoomSummaryAvailabilityService availabilityService;

    @Mock
    private RoomRollingMemoryRepository rollingMemoryRepository;

    @Test
    @DisplayName("summary 조회도 availability 조건을 만족하지 않으면 rolling memory를 노출하지 않는다")
    void getSummary_notAvailable_returnsNotReady() {
        RoomSummaryQueryService service = new RoomSummaryQueryService(availabilityService, rollingMemoryRepository);
        when(availabilityService.availability(1L, "user-1"))
                .thenReturn(RoomSummaryAvailabilityResponse.unavailable(99, 100, "below_threshold", 10L));

        RoomSummaryResponse response = service.getSummary(1L, "user-1");

        assertFalse(response.available());
        verify(rollingMemoryRepository, never()).findByRoomId(1L);
    }

    @Test
    @DisplayName("available이면 rolling memory를 summary로 반환한다")
    void getSummary_available_returnsRollingMemory() {
        RoomSummaryQueryService service = new RoomSummaryQueryService(availabilityService, rollingMemoryRepository);
        RoomRollingMemory memory = RoomRollingMemory.create(1L, 100L, 300L, "summary", 3);
        when(availabilityService.availability(1L, "user-1"))
                .thenReturn(RoomSummaryAvailabilityResponse.available(150, 100, 10L, memory));
        when(rollingMemoryRepository.findByRoomId(1L)).thenReturn(Optional.of(memory));

        RoomSummaryResponse response = service.getSummary(1L, "user-1");

        assertTrue(response.available());
    }
}
