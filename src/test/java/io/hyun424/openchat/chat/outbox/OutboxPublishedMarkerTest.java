package io.hyun424.openchat.chat.outbox;

import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublishedMarkerTest {

    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private ChatPipelineMetrics chatPipelineMetrics;
    @Mock private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    @DisplayName("큐에 쌓인 outbox id를 bulk PUBLISHED 마킹한다")
    void flushOnce_marksPublishedInBatch() {
        OutboxPublishedMarker marker = marker(10, 500, 60_000);
        when(outboxEventRepository.markPublishedByIds(anyList(), eq(OutboxEventStatus.PENDING), eq(OutboxEventStatus.PUBLISHED), anyLong()))
                .thenReturn(3);

        marker.enqueue(1L);
        marker.enqueue(2L);
        marker.enqueue(2L);
        marker.enqueue(3L);

        int updated = marker.flushOnce();

        assertEquals(3, updated);
        verify(outboxEventRepository).markPublishedByIds(eq(java.util.List.of(1L, 2L, 3L)),
                eq(OutboxEventStatus.PENDING), eq(OutboxEventStatus.PUBLISHED), anyLong());
        marker.shutdown();
    }

    @Test
    @DisplayName("marker queue overflow는 live publish path에 예외를 던지지 않는다")
    void enqueue_overflowDoesNotThrow() {
        OutboxPublishedMarker marker = marker(1, 500, 60_000);

        marker.enqueue(1L);
        marker.enqueue(2L);

        verify(chatPipelineMetrics).incrementCounter("outbox.published_marker.overflow");
        marker.shutdown();
    }

    @Test
    @DisplayName("published marker mismatch는 batch 수와 누락 row 수를 구분해 기록한다")
    void flushOnce_recordsMismatchRows() {
        OutboxPublishedMarker marker = marker(10, 500, 60_000);
        when(outboxEventRepository.markPublishedByIds(anyList(), eq(OutboxEventStatus.PENDING), eq(OutboxEventStatus.PUBLISHED), anyLong()))
                .thenReturn(1);

        marker.enqueue(1L);
        marker.enqueue(2L);
        marker.enqueue(3L);

        int updated = marker.flushOnce();

        assertEquals(1, updated);
        verify(chatPipelineMetrics).incrementCounter("outbox.published_marker.state_mismatch");
        verify(chatPipelineMetrics).incrementCounter("outbox.published_marker.state_mismatch.rows", 2);
        marker.shutdown();
    }

    private OutboxPublishedMarker marker(int queueCapacity, int batchSize, long flushIntervalMs) {
        return new OutboxPublishedMarker(
                outboxEventRepository,
                chatPipelineMetrics,
                transactionTemplate,
                true,
                queueCapacity,
                batchSize,
                flushIntervalMs
        );
    }
}
