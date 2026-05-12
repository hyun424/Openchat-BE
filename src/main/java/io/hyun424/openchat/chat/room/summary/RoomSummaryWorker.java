package io.hyun424.openchat.chat.room.summary;

import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.message.repository.MessageRepository;
import io.hyun424.openchat.global.role.ConditionalOnRuntimeRole;
import io.hyun424.openchat.global.role.RuntimeCapability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnRuntimeRole(capabilities = RuntimeCapability.AI_WORKER)
public class RoomSummaryWorker {

    private final RoomSummaryJobRepository jobRepository;
    private final RoomSummarySegmentRepository segmentRepository;
    private final RoomRollingMemoryRepository rollingMemoryRepository;
    private final MessageRepository messageRepository;
    private final RoomSegmentSummarizer summarizer;
    private final RoomSummaryProperties properties;

    @Transactional
    public int runOnce() {
        RoomSummaryJob job = jobRepository.findTopByStatusOrderByIdAsc(RoomSummaryJobStatus.PENDING)
                .orElse(null);
        if (job == null) {
            return 0;
        }

        job.start(Instant.now());
        try {
            List<Message> messages = messageRepository.findByRoomIdAndIdBetweenOrderByIdAsc(
                    job.getRoomId(),
                    job.getStartMessageId(),
                    job.getEndMessageId()
            );
            RoomSegmentSummary summary = summarizer.summarize(
                    job.getRoomId(),
                    job.getStartMessageId(),
                    job.getEndMessageId(),
                    messages
            );
            RoomSummarySegment segment = RoomSummarySegment.create(
                    job.getRoomId(),
                    job.getStartMessageId(),
                    job.getEndMessageId(),
                    messages.size(),
                    summary
            );
            segmentRepository.save(segment);
            updateRollingMemory(job.getRoomId(), segment);
            job.complete(Instant.now());
            return 1;
        } catch (RuntimeException e) {
            job.fail(e.getMessage(), Instant.now());
            log.warn("Room summary job failed: roomId={}, startMessageId={}, endMessageId={}, reason={}",
                    job.getRoomId(), job.getStartMessageId(), job.getEndMessageId(), e.getMessage());
            return 1;
        }
    }

    private void updateRollingMemory(Long roomId, RoomSummarySegment currentSegment) {
        List<RoomSummarySegment> recent = new ArrayList<>(segmentRepository.findTop3ByRoomIdOrderByEndMessageIdDesc(roomId));
        recent.add(currentSegment);
        List<RoomSummarySegment> selected = recent.stream()
                .sorted(Comparator.comparing(RoomSummarySegment::getEndMessageId).reversed())
                .limit(properties.rollingSegmentCount())
                .sorted(Comparator.comparing(RoomSummarySegment::getEndMessageId))
                .toList();

        Long startMessageId = selected.get(0).getStartMessageId();
        Long endMessageId = selected.get(selected.size() - 1).getEndMessageId();
        String text = selected.stream()
                .map(RoomSummarySegment::getSummaryText)
                .collect(Collectors.joining("\n\n"));

        RoomRollingMemory memory = rollingMemoryRepository.findByRoomId(roomId)
                .orElseGet(() -> RoomRollingMemory.create(roomId, startMessageId, endMessageId, text, selected.size()));
        memory.replace(startMessageId, endMessageId, text, selected.size(), Instant.now());
        rollingMemoryRepository.save(memory);
    }
}
