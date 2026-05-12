package io.hyun424.openchat.chat.room.summary;

import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.message.repository.MessageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomSummaryJobPlannerTest {

    @Mock
    private RoomSummaryJobRepository jobRepository;

    @Mock
    private MessageRepository messageRepository;

    @Test
    @DisplayName("최근 1시간 100개 이상 메시지가 있는 active room은 100개 segment job으로 등록한다")
    void enqueueActiveRoomJobs_activeRoom_savesPendingSegmentJob() {
        RoomSummaryJobPlanner planner = new RoomSummaryJobPlanner(
                jobRepository,
                messageRepository,
                new RoomSummaryProperties(100, 100, 3, 60, 10)
        );
        List<Message> messages = java.util.stream.LongStream.rangeClosed(1, 100)
                .mapToObj(this::message)
                .toList();
        List<Message> newestFirst = messages.stream()
                .sorted(Comparator.comparing(Message::getId).reversed())
                .toList();
        when(messageRepository.findActiveRoomIdsSince(any(Long.class), eq(100L))).thenReturn(List.of(1L));
        when(messageRepository.findLatestRoomMessages(eq(1L), any(Pageable.class))).thenReturn(newestFirst);
        when(jobRepository.existsByRoomIdAndStartMessageIdAndEndMessageId(1L, 1L, 100L)).thenReturn(false);

        int enqueued = planner.enqueueActiveRoomJobs();

        assertEquals(1, enqueued);
        verify(jobRepository).save(any(RoomSummaryJob.class));
    }

    private Message message(Long id) {
        Message message = mock(Message.class);
        when(message.getId()).thenReturn(id);
        return message;
    }
}
