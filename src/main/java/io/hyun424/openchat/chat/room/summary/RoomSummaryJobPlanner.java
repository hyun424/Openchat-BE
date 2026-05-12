package io.hyun424.openchat.chat.room.summary;

import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.message.repository.MessageRepository;
import io.hyun424.openchat.global.role.ConditionalOnRuntimeRole;
import io.hyun424.openchat.global.role.RuntimeCapability;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@ConditionalOnRuntimeRole(capabilities = RuntimeCapability.AI_WORKER)
public class RoomSummaryJobPlanner {

    private final RoomSummaryJobRepository jobRepository;
    private final MessageRepository messageRepository;
    private final RoomSummaryProperties properties;

    @Transactional
    public int enqueueActiveRoomJobs() {
        long sinceMillis = Clock.systemUTC().millis() - Duration.ofMinutes(properties.activeWindowMinutes()).toMillis();
        List<Long> roomIds = messageRepository.findActiveRoomIdsSince(sinceMillis, properties.unreadThreshold())
                .stream()
                .limit(properties.maxActiveRoomsPerRun())
                .toList();

        int enqueued = 0;
        for (Long roomId : roomIds) {
            List<Message> segment = messageRepository.findLatestRoomMessages(
                    roomId,
                    PageRequest.of(0, properties.segmentSize())
            );
            if (segment.size() < properties.segmentSize()) {
                continue;
            }
            List<Message> ordered = segment.stream()
                    .sorted(Comparator.comparing(Message::getId))
                    .toList();
            Long startMessageId = ordered.get(0).getId();
            Long endMessageId = ordered.get(ordered.size() - 1).getId();
            if (jobRepository.existsByRoomIdAndStartMessageIdAndEndMessageId(roomId, startMessageId, endMessageId)) {
                continue;
            }
            jobRepository.save(RoomSummaryJob.pending(roomId, startMessageId, endMessageId));
            enqueued++;
        }
        return enqueued;
    }
}
