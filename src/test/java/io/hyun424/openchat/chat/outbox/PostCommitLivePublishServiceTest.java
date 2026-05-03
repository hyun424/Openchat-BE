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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostCommitLivePublishServiceTest {

    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private ChatMessagePublisher publisher;
    @Mock private ChatPipelineMetrics chatPipelineMetrics;
    @Mock private TransactionTemplate transactionTemplate;

    private PostCommitLivePublishService service;

    @BeforeEach
    void setUp() {
        service = new PostCommitLivePublishService(
                outboxEventRepository,
                publisher,
                chatPipelineMetrics,
                transactionTemplate,
                true,
                1,
                10,
                30_000L,
                20
        );
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
    @DisplayName("commit 이후 async live publish 성공 시 outbox를 PUBLISHED로 변경한다")
    void publishAsync_successMarksPublished() {
        OutboxEvent event = event();
        ChatMessageDto message = message();
        when(outboxEventRepository.findFirstByMessageIdOrderByIdAsc("message-1"))
                .thenReturn(Optional.of(event));

        service.publishAsync(message);

        verify(publisher, timeout(500)).publish(message);
        verify(outboxEventRepository, timeout(500)).findFirstByMessageIdOrderByIdAsc("message-1");
        verify(outboxEventRepository, never()).claimByMessageId(anyString(), any(), any(), anyLong(), anyLong());
        assertEquals(OutboxEventStatus.PUBLISHED, event.getStatus());
    }

    @Test
    @DisplayName("async live publish 실패 시 outbox를 PENDING 재시도 상태로 남긴다")
    void publishAsync_failureLeavesPendingForRetry() {
        OutboxEvent event = event();
        ChatMessageDto message = message();
        when(outboxEventRepository.findFirstByMessageIdOrderByIdAsc("message-1"))
                .thenReturn(Optional.of(event));
        doThrow(new RuntimeException("redis down")).when(publisher).publish(message);

        service.publishAsync(message);

        verify(publisher, timeout(500)).publish(message);
        verify(outboxEventRepository, timeout(500)).findFirstByMessageIdOrderByIdAsc("message-1");
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
                .status(OutboxEventStatus.PENDING)
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
