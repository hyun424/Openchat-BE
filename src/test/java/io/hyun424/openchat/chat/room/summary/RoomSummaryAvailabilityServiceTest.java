package io.hyun424.openchat.chat.room.summary;

import io.hyun424.openchat.chat.message.repository.MessageRepository;
import io.hyun424.openchat.chat.room.read.RoomReadPositionResponse;
import io.hyun424.openchat.chat.room.read.RoomReadPositionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomSummaryAvailabilityServiceTest {

    @Mock
    private RoomReadPositionService readPositionService;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private RoomRollingMemoryRepository rollingMemoryRepository;

    @Test
    @DisplayName("read position이 없으면 요약을 노출하지 않는다")
    void availability_noReadPosition() {
        RoomSummaryAvailabilityService service = service();
        when(readPositionService.get(1L, "user-1"))
                .thenReturn(new RoomReadPositionResponse(1L, "user-1", null, null, null));

        RoomSummaryAvailabilityResponse response = service.availability(1L, "user-1");

        assertFalse(response.available());
        assertEquals("no_read_position", response.reason());
        assertEquals(100, response.threshold());
    }

    @Test
    @DisplayName("unread count가 threshold 미만이면 요약을 노출하지 않는다")
    void availability_belowThreshold() {
        RoomSummaryAvailabilityService service = service();
        when(readPositionService.get(1L, "user-1"))
                .thenReturn(new RoomReadPositionResponse(1L, "user-1", 10L, Instant.now(), Instant.now()));
        when(messageRepository.countByRoomIdAndIdGreaterThan(1L, 10L)).thenReturn(99L);

        RoomSummaryAvailabilityResponse response = service.availability(1L, "user-1");

        assertFalse(response.available());
        assertEquals("below_threshold", response.reason());
        assertEquals(99L, response.unreadCount());
    }

    @Test
    @DisplayName("rolling memory가 없으면 요약을 노출하지 않는다")
    void availability_noRollingMemory() {
        RoomSummaryAvailabilityService service = service();
        when(readPositionService.get(1L, "user-1"))
                .thenReturn(new RoomReadPositionResponse(1L, "user-1", 10L, Instant.now(), Instant.now()));
        when(messageRepository.countByRoomIdAndIdGreaterThan(1L, 10L)).thenReturn(100L);
        when(rollingMemoryRepository.findByRoomId(1L)).thenReturn(Optional.empty());

        RoomSummaryAvailabilityResponse response = service.availability(1L, "user-1");

        assertFalse(response.available());
        assertEquals("no_rolling_memory", response.reason());
    }

    @Test
    @DisplayName("unread threshold와 rolling memory가 모두 있으면 요약을 노출한다")
    void availability_available() {
        RoomSummaryAvailabilityService service = service();
        RoomRollingMemory memory = RoomRollingMemory.create(1L, 100L, 300L, "최근 대화 요약", 3);
        when(readPositionService.get(1L, "user-1"))
                .thenReturn(new RoomReadPositionResponse(1L, "user-1", 10L, Instant.now(), Instant.now()));
        when(messageRepository.countByRoomIdAndIdGreaterThan(1L, 10L)).thenReturn(150L);
        when(rollingMemoryRepository.findByRoomId(1L)).thenReturn(Optional.of(memory));

        RoomSummaryAvailabilityResponse response = service.availability(1L, "user-1");

        assertTrue(response.available());
        assertEquals("available", response.reason());
        assertEquals(100L, response.rollingMemoryStartMessageId());
        assertEquals(300L, response.rollingMemoryEndMessageId());
    }

    private RoomSummaryAvailabilityService service() {
        return new RoomSummaryAvailabilityService(
                readPositionService,
                messageRepository,
                rollingMemoryRepository,
                new RoomSummaryProperties(100, 100, 3, 60, 10)
        );
    }
}
