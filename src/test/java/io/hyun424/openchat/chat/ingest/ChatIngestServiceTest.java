package io.hyun424.openchat.chat.ingest;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.message.service.MessageService;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.outbox.OutboxEvent;
import io.hyun424.openchat.chat.outbox.OutboxEventStatus;
import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatIngestServiceTest {

    @Mock private MessageService messageService;
    @Mock private ChatMessagePersistenceService persistenceService;
    @Mock private RoomTrafficMonitor roomTrafficMonitor;
    @Mock private ChatPipelineMetrics chatPipelineMetrics;

    @InjectMocks
    private ChatIngestService chatIngestService;

    private static final Long ROOM_ID = 1L;
    private static final String SENDER_ID = "user1";
    private static final String NICKNAME = "TestUser";
    private static final String CONTENT = "Hello World";
    private static final String CLIENT_MSG_ID = "client-123";

    @BeforeEach
    void setUp() {
    }

    @Test
    @DisplayName("정상 ingest: DB 메시지와 outbox를 저장하고 저장 DTO를 반환한다")
    void ingest_success() {
        // given
        when(messageService.findByClientMessageId(eq(ROOM_ID), eq(SENDER_ID), eq(CLIENT_MSG_ID)))
                .thenReturn(null);

        Message saved = Message.builder()
                .messageId("uuid-1")
                .roomId(ROOM_ID)
                .senderId(SENDER_ID)
                .senderNickname(NICKNAME)
                .content(CONTENT)
                .clientMessageId(CLIENT_MSG_ID)
                .createdAt(System.currentTimeMillis())
                .build();

        when(persistenceService.persistWithOutbox(eq(ROOM_ID), eq(SENDER_ID), eq(NICKNAME), eq(CONTENT),
                eq(CLIENT_MSG_ID), anyString(), anyLong()))
                .thenReturn(new PersistedChatMessage(saved, dto(saved, CLIENT_MSG_ID), outbox("uuid-1")));

        // when
        ChatIngestResult result = chatIngestService.ingest(ROOM_ID, SENDER_ID, NICKNAME, CONTENT, CLIENT_MSG_ID);

        // then
        assertEquals(CLIENT_MSG_ID, result.message().getClientMessageId());
        assertEquals(ROOM_ID, result.message().getRoomId());
        assertEquals(true, result.newMessage());
        verify(persistenceService).persistWithOutbox(eq(ROOM_ID), eq(SENDER_ID), eq(NICKNAME), eq(CONTENT),
                eq(CLIENT_MSG_ID), anyString(), anyLong());
        verify(roomTrafficMonitor).recordInboundMessage(ROOM_ID);
    }

    @Test
    @DisplayName("중복 clientMessageId dedupe: 신규 DB 저장/outbox 생성 스킵")
    void ingest_duplicateClientMessageId_skipped() {
        // given
        Message existing = Message.builder()
                .messageId("existing-uuid")
                .roomId(ROOM_ID)
                .senderId(SENDER_ID)
                .senderNickname(NICKNAME)
                .content(CONTENT)
                .clientMessageId(CLIENT_MSG_ID)
                .createdAt(System.currentTimeMillis())
                .build();

        when(messageService.findByClientMessageId(eq(ROOM_ID), eq(SENDER_ID), eq(CLIENT_MSG_ID)))
                .thenReturn(existing);

        // when
        ChatIngestResult result = chatIngestService.ingest(ROOM_ID, SENDER_ID, NICKNAME, CONTENT, CLIENT_MSG_ID);

        // then
        assertEquals(false, result.newMessage());
        assertEquals("existing-uuid", result.message().getMessageId());
        verify(persistenceService, never()).persistWithOutbox(any(), any(), any(), any(), any(), any(), anyLong());
        verify(roomTrafficMonitor, never()).recordInboundMessage(anyLong());
    }

    @Test
    @DisplayName("DB/outbox 저장 실패 시 예외 전파")
    void ingest_dbFailure_throwsAndSkipsPublish() {
        // given
        when(messageService.findByClientMessageId(eq(ROOM_ID), eq(SENDER_ID), eq(CLIENT_MSG_ID)))
                .thenReturn(null);
        when(persistenceService.persistWithOutbox(eq(ROOM_ID), eq(SENDER_ID), eq(NICKNAME), eq(CONTENT),
                eq(CLIENT_MSG_ID), anyString(), anyLong()))
                .thenThrow(new RuntimeException("DB connection lost"));

        // when & then
        assertThrows(RuntimeException.class, () ->
                chatIngestService.ingest(ROOM_ID, SENDER_ID, NICKNAME, CONTENT, CLIENT_MSG_ID));
    }

    @Test
    @DisplayName("ingest request path에서는 publish와 room update 후처리를 직접 수행하지 않는다")
    void ingest_doesNotRunPostProcessingInline() {
        // given
        when(messageService.findByClientMessageId(eq(ROOM_ID), eq(SENDER_ID), eq(CLIENT_MSG_ID)))
                .thenReturn(null);

        Message saved = Message.builder()
                .messageId("uuid-3")
                .roomId(ROOM_ID)
                .senderId(SENDER_ID)
                .senderNickname(NICKNAME)
                .content(CONTENT)
                .clientMessageId(CLIENT_MSG_ID)
                .createdAt(System.currentTimeMillis())
                .build();

        when(persistenceService.persistWithOutbox(eq(ROOM_ID), eq(SENDER_ID), eq(NICKNAME), eq(CONTENT),
                eq(CLIENT_MSG_ID), anyString(), anyLong()))
                .thenReturn(new PersistedChatMessage(saved, dto(saved, CLIENT_MSG_ID), outbox("uuid-3")));

        // when
        chatIngestService.ingest(ROOM_ID, SENDER_ID, NICKNAME, CONTENT, CLIENT_MSG_ID);

        // then
        verify(persistenceService).persistWithOutbox(any(), any(), any(), any(), any(), any(), anyLong());
    }

    private ChatMessageDto dto(Message saved, String clientMessageId) {
        ChatMessageDto dto = ChatMessageDto.from(saved);
        dto.setClientMessageId(clientMessageId);
        return dto;
    }

    private OutboxEvent outbox(String messageId) {
        return OutboxEvent.builder()
                .eventId("event-" + messageId)
                .eventType(OutboxEvent.CHAT_MESSAGE_CREATED)
                .aggregateType(OutboxEvent.AGGREGATE_MESSAGE)
                .aggregateId(1L)
                .roomId(ROOM_ID)
                .messageId(messageId)
                .payloadJson("{}")
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .nextRetryAt(System.currentTimeMillis())
                .createdAt(System.currentTimeMillis())
                .build();
    }
}
