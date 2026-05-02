package io.hyun424.openchat.chat.ingest;

import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.message.service.MessageService;
import io.hyun424.openchat.chat.publish.ChatMessagePublisher;
import io.hyun424.openchat.chat.publish.PublishRetryBuffer;
import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
import io.hyun424.openchat.chat.room.service.RoomService;
import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatIngestServiceTest {

    @Mock private ChatMessagePublisher publisher;
    @Mock private PublishRetryBuffer retryBuffer;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private MessageService messageService;
    @Mock private RoomService roomService;
    @Mock private RedisHealthState redisHealthState;
    @Mock private RoomTrafficMonitor roomTrafficMonitor;
    @Mock private ZSetOperations<String, String> zSetOps;

    @InjectMocks
    private ChatIngestService chatIngestService;

    private static final Long ROOM_ID = 1L;
    private static final String SENDER_ID = "user1";
    private static final String NICKNAME = "TestUser";
    private static final String CONTENT = "Hello World";
    private static final String CLIENT_MSG_ID = "client-123";

    @BeforeEach
    void setUp() {
        // RedisHealthState default: UP for hotchat bucket
        lenient().when(redisHealthState.isUp()).thenReturn(true);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
    }

    @Test
    @DisplayName("정상 ingest: DB 저장 → publish → hotchat 업데이트")
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

        when(messageService.save(eq(ROOM_ID), eq(SENDER_ID), eq(NICKNAME), eq(CONTENT),
                eq(CLIENT_MSG_ID), anyString(), anyLong()))
                .thenReturn(saved);

        // when
        chatIngestService.ingest(ROOM_ID, SENDER_ID, NICKNAME, CONTENT, CLIENT_MSG_ID);

        // then
        verify(messageService).save(eq(ROOM_ID), eq(SENDER_ID), eq(NICKNAME), eq(CONTENT),
                eq(CLIENT_MSG_ID), anyString(), anyLong());
        verify(roomTrafficMonitor).recordInboundMessage(ROOM_ID);
        verify(publisher).publish(any());
        verify(roomService).updateLastMessage(eq(ROOM_ID), anyLong(), eq(CONTENT), eq(NICKNAME));
    }

    @Test
    @DisplayName("중복 clientMessageId dedupe: DB 저장/publish 스킵")
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
        chatIngestService.ingest(ROOM_ID, SENDER_ID, NICKNAME, CONTENT, CLIENT_MSG_ID);

        // then
        verify(messageService, never()).save(any(), any(), any(), any(), any(), any(), anyLong());
        verify(roomTrafficMonitor, never()).recordInboundMessage(anyLong());
        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("DB 저장 실패 시 예외 전파 (publish 호출 안 됨)")
    void ingest_dbFailure_throwsAndSkipsPublish() {
        // given
        when(messageService.findByClientMessageId(eq(ROOM_ID), eq(SENDER_ID), eq(CLIENT_MSG_ID)))
                .thenReturn(null);
        when(messageService.save(eq(ROOM_ID), eq(SENDER_ID), eq(NICKNAME), eq(CONTENT),
                eq(CLIENT_MSG_ID), anyString(), anyLong()))
                .thenThrow(new RuntimeException("DB connection lost"));

        // when & then
        assertThrows(RuntimeException.class, () ->
                chatIngestService.ingest(ROOM_ID, SENDER_ID, NICKNAME, CONTENT, CLIENT_MSG_ID));

        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("publish 실패 시 retryBuffer에 enqueue, 예외 전파 안 됨")
    void ingest_publishFailure_enqueuesRetryBuffer() {
        // given
        when(messageService.findByClientMessageId(eq(ROOM_ID), eq(SENDER_ID), eq(CLIENT_MSG_ID)))
                .thenReturn(null);

        Message saved = Message.builder()
                .messageId("uuid-2")
                .roomId(ROOM_ID)
                .senderId(SENDER_ID)
                .senderNickname(NICKNAME)
                .content(CONTENT)
                .clientMessageId(CLIENT_MSG_ID)
                .createdAt(System.currentTimeMillis())
                .build();

        when(messageService.save(eq(ROOM_ID), eq(SENDER_ID), eq(NICKNAME), eq(CONTENT),
                eq(CLIENT_MSG_ID), anyString(), anyLong()))
                .thenReturn(saved);

        doThrow(new RuntimeException("Kafka down")).when(publisher).publish(any());

        // when — should not throw
        chatIngestService.ingest(ROOM_ID, SENDER_ID, NICKNAME, CONTENT, CLIENT_MSG_ID);

        // then
        verify(retryBuffer).enqueue(any());
        verify(roomService).updateLastMessage(eq(ROOM_ID), anyLong(), eq(CONTENT), eq(NICKNAME));
    }

    @Test
    @DisplayName("수정 전 경계 확인: publisher가 실패를 숨기고 정상 반환하면 retryBuffer에 들어가지 않는다")
    void ingest_publisherReturnsNormally_retryBufferNotUsed() {
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

        when(messageService.save(eq(ROOM_ID), eq(SENDER_ID), eq(NICKNAME), eq(CONTENT),
                eq(CLIENT_MSG_ID), anyString(), anyLong()))
                .thenReturn(saved);

        // when
        chatIngestService.ingest(ROOM_ID, SENDER_ID, NICKNAME, CONTENT, CLIENT_MSG_ID);

        // then
        verify(publisher).publish(any());
        verify(retryBuffer, never()).enqueue(any());
        verify(roomService).updateLastMessage(eq(ROOM_ID), anyLong(), eq(CONTENT), eq(NICKNAME));
    }
}
