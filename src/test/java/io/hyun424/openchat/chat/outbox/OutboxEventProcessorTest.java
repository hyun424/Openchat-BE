package io.hyun424.openchat.chat.outbox;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.publish.ChatMessagePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxEventProcessorTest {

    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private OutboxPayloadSerializer payloadSerializer;
    @Mock private ChatMessagePublisher publisher;
    @Mock private ChatPipelineMetrics chatPipelineMetrics;
    @Mock private TransactionTemplate transactionTemplate;

    private OutboxEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new OutboxEventProcessor(
                outboxEventRepository,
                payloadSerializer,
                publisher,
                chatPipelineMetrics,
                transactionTemplate
        );
        ReflectionTestUtils.setField(processor, "batchSize", 100);
        ReflectionTestUtils.setField(processor, "maxAttempts", 20);
        ReflectionTestUtils.setField(processor, "processingTimeoutMs", 30_000L);

        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("PENDING 이벤트를 claim 후 Redis publish만 재시도하고 PUBLISHED로 변경한다")
    void processBatch_success() {
        OutboxEvent event = event();
        ChatMessageDto message = message();
        when(outboxEventRepository.findReadyIds(eq(OutboxEventStatus.PENDING), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(1L));
        when(outboxEventRepository.claim(eq(1L), eq(OutboxEventStatus.PENDING), eq(OutboxEventStatus.PROCESSING),
                anyLong(), anyLong()))
                .thenReturn(1);
        when(outboxEventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(payloadSerializer.deserialize(event.getPayloadJson())).thenReturn(message);
        when(publisher.publish(message)).thenReturn(CompletableFuture.completedFuture(null));

        int processed = processor.processBatch();

        assertEquals(1, processed);
        assertEquals(OutboxEventStatus.PUBLISHED, event.getStatus());
        verify(publisher).publish(message);
    }

    @Test
    @DisplayName("worker 처리 실패 시 이벤트를 PENDING 재시도 상태로 되돌린다")
    void processBatch_failureSchedulesRetry() {
        OutboxEvent event = event();
        ChatMessageDto message = message();
        when(outboxEventRepository.findReadyIds(eq(OutboxEventStatus.PENDING), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(1L));
        when(outboxEventRepository.claim(eq(1L), eq(OutboxEventStatus.PENDING), eq(OutboxEventStatus.PROCESSING),
                anyLong(), anyLong()))
                .thenReturn(1);
        when(outboxEventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(payloadSerializer.deserialize(event.getPayloadJson())).thenReturn(message);
        when(publisher.publish(message)).thenReturn(CompletableFuture.failedFuture(new RuntimeException("redis down")));

        int processed = processor.processBatch();

        assertEquals(1, processed);
        assertEquals(OutboxEventStatus.PENDING, event.getStatus());
        assertEquals(1, event.getAttemptCount());
    }

    private OutboxEvent event() {
        return OutboxEvent.builder()
                .eventId("event-1")
                .eventType(OutboxEvent.CHAT_MESSAGE_CREATED)
                .aggregateType(OutboxEvent.AGGREGATE_MESSAGE)
                .aggregateId(10L)
                .roomId(1L)
                .messageId("message-1")
                .payloadJson("{\"messageId\":\"message-1\"}")
                .status(OutboxEventStatus.PROCESSING)
                .attemptCount(0)
                .nextRetryAt(System.currentTimeMillis())
                .createdAt(System.currentTimeMillis())
                .build();
    }

    private ChatMessageDto message() {
        return ChatMessageDto.builder()
                .id(10L)
                .sequence(10L)
                .messageId("message-1")
                .roomId(1L)
                .senderId("user-1")
                .senderName("User")
                .message("hello")
                .createdAt(1000L)
                .build();
    }
}
