package io.hyun424.openchat.chat.room.summary;

import io.hyun424.openchat.chat.message.repository.MessageRepository;
import io.hyun424.openchat.chat.room.read.RoomReadPositionResponse;
import io.hyun424.openchat.chat.room.read.RoomReadPositionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RoomSummaryAvailabilityService {

    private final RoomReadPositionService readPositionService;
    private final MessageRepository messageRepository;
    private final RoomRollingMemoryRepository rollingMemoryRepository;
    private final RoomSummaryProperties properties;

    @Transactional(readOnly = true)
    public RoomSummaryAvailabilityResponse availability(Long roomId, String userId) {
        RoomReadPositionResponse readPosition = readPositionService.get(roomId, userId);
        Long lastReadMessageId = readPosition.lastReadMessageId();
        if (lastReadMessageId == null) {
            return RoomSummaryAvailabilityResponse.unavailable(0, properties.unreadThreshold(), "no_read_position", null);
        }

        long unreadCount = messageRepository.countByRoomIdAndIdGreaterThan(roomId, lastReadMessageId);
        if (unreadCount < properties.unreadThreshold()) {
            return RoomSummaryAvailabilityResponse.unavailable(
                    unreadCount,
                    properties.unreadThreshold(),
                    "below_threshold",
                    lastReadMessageId
            );
        }

        return rollingMemoryRepository.findByRoomId(roomId)
                .map(memory -> RoomSummaryAvailabilityResponse.available(
                        unreadCount,
                        properties.unreadThreshold(),
                        lastReadMessageId,
                        memory
                ))
                .orElseGet(() -> RoomSummaryAvailabilityResponse.unavailable(
                        unreadCount,
                        properties.unreadThreshold(),
                        "no_rolling_memory",
                        lastReadMessageId
                ));
    }
}
