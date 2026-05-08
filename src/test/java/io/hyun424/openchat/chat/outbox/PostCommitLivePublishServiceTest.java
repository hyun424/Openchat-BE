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

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostCommitLivePublishServiceTest {

    @Mock private ChatMessagePublisher publisher;
    @Mock private ChatPipelineMetrics chatPipelineMetrics;
    @Mock private OutboxPublishedMarker outboxPublishedMarker;

    private PostCommitLivePublishService service;

    @BeforeEach
    void setUp() {
        service = new PostCommitLivePublishService(
                publisher,
                chatPipelineMetrics,
                outboxPublishedMarker,
                true,
                1,
                10
        );
    }

    @Test
    @DisplayName("commit 이후 async live publish 성공 시 marker에 outbox id를 넘긴다")
    void publishAsync_successEnqueuesPublishedMarker() {
        ChatMessageDto message = message();
        when(publisher.publish(message)).thenReturn(CompletableFuture.completedFuture(null));

        service.publishAsync(message, 99L);

        verify(publisher, timeout(500)).publish(message);
        verify(outboxPublishedMarker, timeout(500)).enqueue(99L);
    }

    @Test
    @DisplayName("async live publish worker 시작 시 queue wait와 createdAt 기준 시작 지연을 기록한다")
    void publishAsync_recordsQueueWaitBeforePublishing() {
        ChatMessageDto message = message();
        when(publisher.publish(message)).thenReturn(CompletableFuture.completedFuture(null));

        service.publishAsync(message, 99L);

        verify(chatPipelineMetrics, timeout(500)).recordStageNanos(eq("live_publish.queue_wait"), anyLong());
        verify(chatPipelineMetrics, timeout(500)).recordSinceCreated("live_publish.start.since_created", message);
    }

    @Test
    @DisplayName("async live publish 실패 시 marker를 호출하지 않고 outbox worker retry에 맡긴다")
    void publishAsync_failureSkipsPublishedMarker() {
        ChatMessageDto message = message();
        when(publisher.publish(message)).thenReturn(CompletableFuture.failedFuture(new RuntimeException("redis down")));

        service.publishAsync(message, 99L);

        verify(publisher, timeout(500)).publish(message);
        verify(outboxPublishedMarker, after(200).never()).enqueue(anyLong());
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
