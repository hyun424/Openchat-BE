package io.hyun424.openchat.chat.room.summary;

import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.message.repository.MessageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomSummaryWorkerTest {

    @Mock
    private RoomSummaryJobRepository jobRepository;

    @Mock
    private RoomSummarySegmentRepository segmentRepository;

    @Mock
    private RoomRollingMemoryRepository rollingMemoryRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private RoomSegmentSummarizer summarizer;

    @Test
    @DisplayName("pending job을 mock summarizer로 처리하고 segment와 rolling memory를 저장한다")
    void runOnce_pendingJob_completesSegmentAndRollingMemory() {
        RoomSummaryJob job = RoomSummaryJob.pending(1L, 10L, 12L);
        List<Message> messages = List.of(message(10L), message(11L), message(12L));
        RoomSegmentSummary summary = new RoomSegmentSummary("읽지 않은 최근 메시지를 요약했어요.", List.of(11L), List.of());
        when(jobRepository.findTopByStatusOrderByIdAsc(RoomSummaryJobStatus.PENDING)).thenReturn(Optional.of(job));
        when(messageRepository.findByRoomIdAndIdBetweenOrderByIdAsc(1L, 10L, 12L)).thenReturn(messages);
        when(summarizer.summarize(1L, 10L, 12L, messages)).thenReturn(summary);
        when(segmentRepository.findTop3ByRoomIdOrderByEndMessageIdDesc(1L)).thenReturn(List.of());

        RoomSummaryWorker worker = new RoomSummaryWorker(
                jobRepository,
                segmentRepository,
                rollingMemoryRepository,
                messageRepository,
                summarizer,
                new RoomSummaryProperties(100, 100, 3, 60, 10)
        );

        int processed = worker.runOnce();

        assertEquals(1, processed);
        assertEquals(RoomSummaryJobStatus.COMPLETED, job.getStatus());
        verify(segmentRepository).save(any(RoomSummarySegment.class));
        verify(rollingMemoryRepository).save(any(RoomRollingMemory.class));
    }

    @Test
    @DisplayName("summarizer 실패는 job failed 상태와 reason으로 남긴다")
    void runOnce_summarizerFails_marksJobFailed() {
        RoomSummaryJob job = RoomSummaryJob.pending(1L, 10L, 12L);
        when(jobRepository.findTopByStatusOrderByIdAsc(RoomSummaryJobStatus.PENDING)).thenReturn(Optional.of(job));
        when(messageRepository.findByRoomIdAndIdBetweenOrderByIdAsc(1L, 10L, 12L)).thenReturn(List.of(message(10L)));
        when(summarizer.summarize(anyLong(), anyLong(), anyLong(), anyList())).thenThrow(new IllegalStateException("rag unavailable"));

        RoomSummaryWorker worker = new RoomSummaryWorker(
                jobRepository,
                segmentRepository,
                rollingMemoryRepository,
                messageRepository,
                summarizer,
                new RoomSummaryProperties(100, 100, 3, 60, 10)
        );

        int processed = worker.runOnce();

        assertEquals(1, processed);
        assertEquals(RoomSummaryJobStatus.FAILED, job.getStatus());
        assertEquals("rag unavailable", job.getFailureReason());
        verify(segmentRepository, never()).save(any());
    }

    private Message message(Long id) {
        return mock(Message.class);
    }
}
