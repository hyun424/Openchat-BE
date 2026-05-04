package io.hyun424.openchat.chat.ingest;

import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.message.repository.MessageRepository;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.outbox.OutboxEvent;
import io.hyun424.openchat.chat.outbox.OutboxEventRepository;
import io.hyun424.openchat.chat.outbox.OutboxPayloadSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatMessagePersistenceServiceTest {

    @Mock private MessageRepository messageRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private OutboxPayloadSerializer payloadSerializer;
    @Mock private ChatPipelineMetrics chatPipelineMetrics;

    @InjectMocks
    private ChatMessagePersistenceService persistenceService;

    @Test
    @DisplayName("chat_message와 outbox_event를 함께 저장한다")
    void persistWithOutbox_savesMessageAndOutbox() {
        Message saved = mock(Message.class);
        when(saved.getId()).thenReturn(10L);
        when(saved.getMessageId()).thenReturn("message-1");
        when(saved.getRoomId()).thenReturn(1L);
        when(saved.getSenderId()).thenReturn("user-1");
        when(saved.getSenderNickname()).thenReturn("User");
        when(saved.getContent()).thenReturn("hello");
        when(saved.getCreatedAt()).thenReturn(1000L);
        when(messageRepository.save(any(Message.class))).thenReturn(saved);
        when(payloadSerializer.serialize(any())).thenReturn("{\"messageId\":\"message-1\"}");
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PersistedChatMessage result = persistenceService.persistWithOutbox(
                1L, "user-1", "User", "hello", "client-1", "message-1", 1000L);

        assertEquals(saved, result.message());
        assertEquals("client-1", result.dto().getClientMessageId());
        assertEquals("message-1", result.outboxEvent().getMessageId());
        assertEquals(1L, result.outboxEvent().getRoomId());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertEquals(OutboxEvent.CHAT_MESSAGE_CREATED, outboxCaptor.getValue().getEventType());
        assertEquals(10L, outboxCaptor.getValue().getAggregateId());
    }

    @Test
    @DisplayName("outbox 저장 실패는 호출자에게 예외로 전파한다")
    void persistWithOutbox_outboxFailurePropagates() {
        Message saved = mock(Message.class);
        when(saved.getId()).thenReturn(10L);
        when(saved.getMessageId()).thenReturn("message-1");
        when(saved.getRoomId()).thenReturn(1L);
        when(saved.getSenderId()).thenReturn("user-1");
        when(saved.getSenderNickname()).thenReturn("User");
        when(saved.getContent()).thenReturn("hello");
        when(saved.getCreatedAt()).thenReturn(1000L);
        when(messageRepository.save(any(Message.class))).thenReturn(saved);
        when(payloadSerializer.serialize(any())).thenReturn("{\"messageId\":\"message-1\"}");
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenThrow(new RuntimeException("outbox down"));

        assertThrows(RuntimeException.class, () -> persistenceService.persistWithOutbox(
                1L, "user-1", "User", "hello", "client-1", "message-1", 1000L));
    }
}
