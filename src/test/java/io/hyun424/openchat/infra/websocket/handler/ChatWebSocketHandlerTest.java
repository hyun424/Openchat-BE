package io.hyun424.openchat.infra.websocket.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.auth.jwt.JwtProvider;
import io.hyun424.openchat.chat.ingest.ChatIngestResult;
import io.hyun424.openchat.chat.ingest.ChatIngestService;
import io.hyun424.openchat.chat.member.service.RoomMemberService;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.message.service.MessageService;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.outbox.PostCommitLivePublishService;
import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.chat.room.metadata.RoomMetadataUpdateBuffer;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionRoutingService;
import io.hyun424.openchat.chat.room.service.RoomService;
import io.hyun424.openchat.global.ratelimit.RateLimiter;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatWebSocketHandlerTest {

    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @Mock private RoomMemberService roomMemberService;
    @Mock private RoomSessionRegistry roomSessionRegistry;
    @Mock private ChatIngestService chatIngestService;
    @Mock private MessageService messageService;
    @Mock private RoomService roomService;
    @Mock private RateLimiter rateLimiter;
    @Mock private JwtProvider jwtProvider;
    @Mock private ChatPipelineMetrics chatPipelineMetrics;
    @Mock private PostCommitLivePublishService postCommitLivePublishService;
    @Mock private RoomMetadataUpdateBuffer roomMetadataUpdateBuffer;
    @Mock private RoomPartitionRoutingService roomPartitionRoutingService;
    @Mock private RoomPartitionMetrics roomPartitionMetrics;
    @Mock private WebSocketSession session;

    @InjectMocks
    private ChatWebSocketHandler handler;

    private Map<String, Object> sessionAttributes;

    @BeforeEach
    void setUp() {
        sessionAttributes = new HashMap<>();
        sessionAttributes.put("userId", "user1");
        sessionAttributes.put("nickname", "TestUser");
        sessionAttributes.put("token", "valid-token");
        sessionAttributes.put("connectedAt", System.currentTimeMillis());
        sessionAttributes.put("lastTokenValidation", System.currentTimeMillis());

        when(session.getAttributes()).thenReturn(sessionAttributes);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?roomId=1"));
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("session-1");

        when(jwtProvider.validateToken("valid-token")).thenReturn(true);
        when(rateLimiter.tryAcquire(eq("ws:user1"), anyInt(), anyInt())).thenReturn(true);

        ReflectionTestUtils.setField(handler, "wsMessageLimit", 10);
        ReflectionTestUtils.setField(handler, "wsWindowSeconds", 1);
    }

    @Test
    @DisplayName("메시지 크기 초과 (10KB) 시 에러 응답")
    void handleMessage_tooLarge_sendsError() throws Exception {
        String largeContent = "x".repeat(11 * 1024);
        TextMessage msg = new TextMessage("{\"content\":\"" + largeContent + "\"}");

        handler.handleTextMessage(session, msg);

        verify(chatIngestService, never()).ingest(any(), any(), any(), any(), any());
        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session).sendMessage(captor.capture());
        assertTrue(captor.getValue().getPayload().toString().contains("Message too large"));
    }

    @Test
    @DisplayName("rate limit 초과 시 에러 응답")
    void handleMessage_rateLimited_sendsError() throws Exception {
        TextMessage msg = new TextMessage("{\"content\":\"Hello\"}");
        when(rateLimiter.tryAcquire(eq("ws:user1"), anyInt(), anyInt())).thenReturn(false);

        handler.handleTextMessage(session, msg);

        verify(chatIngestService, never()).ingest(any(), any(), any(), any(), any());
        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session).sendMessage(captor.capture());
        assertTrue(captor.getValue().getPayload().toString().contains("Rate limit exceeded"));
    }

    @Test
    @DisplayName("토큰 만료 시 세션 닫기 (4001)")
    void handleMessage_tokenExpired_closesSession() throws Exception {
        sessionAttributes.put("lastTokenValidation", 0L);
        TextMessage msg = new TextMessage("{\"content\":\"Hello\"}");
        when(jwtProvider.validateToken("valid-token")).thenReturn(false);

        handler.handleTextMessage(session, msg);

        ArgumentCaptor<CloseStatus> captor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(captor.capture());
        assertEquals(4001, captor.getValue().getCode());
        verify(chatIngestService, never()).ingest(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("정상 메시지: ingest 호출됨")
    void handleMessage_normal_callsIngest() throws Exception {
        TextMessage msg = new TextMessage("{\"content\":\"Hello World\",\"clientMessageId\":\"c1\"}");

        handler.handleTextMessage(session, msg);

        verify(chatIngestService).ingest(eq(1L), eq("user1"), eq("TestUser"), anyString(), eq("c1"));
    }

    @Test
    @DisplayName("명시적 chat.message type도 기존 채팅 저장 경로를 탄다")
    void handleMessage_explicitChatType_callsIngest() throws Exception {
        TextMessage msg = new TextMessage(
                "{\"type\":\"chat.message\",\"content\":\"Hello World\",\"clientMessageId\":\"c1\"}");

        handler.handleTextMessage(session, msg);

        verify(chatIngestService).ingest(eq(1L), eq("user1"), eq("TestUser"), anyString(), eq("c1"));
    }

    @Test
    @DisplayName("기존 프론트의 text type도 채팅 저장 경로를 탄다")
    void handleMessage_legacyTextType_callsIngest() throws Exception {
        TextMessage msg = new TextMessage(
                "{\"type\":\"text\",\"content\":\"Hello World\",\"clientMessageId\":\"c1\"}");

        handler.handleTextMessage(session, msg);

        verify(chatIngestService).ingest(eq(1L), eq("user1"), eq("TestUser"), anyString(), eq("c1"));
    }

    @Test
    @DisplayName("room.passive control message는 저장하지 않고 세션 상태만 passive로 바꾼다")
    void handleMessage_roomPassive_marksSessionPassiveOnly() {
        TextMessage msg = new TextMessage(
                "{\"type\":\"room.passive\",\"roomId\":1,\"lastSeenSequence\":123}");

        handler.handleTextMessage(session, msg);

        verify(roomSessionRegistry).markPassive(1L, "session-1", 123L);
        verify(chatPipelineMetrics).incrementCounter("ws.control.room_passive");
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), anyInt());
        verify(chatIngestService, never()).ingest(any(), any(), any(), any(), any());
        verify(roomSessionRegistry, never()).sendControlToSession(any(), any(), eq("ack"));
        verify(postCommitLivePublishService, never()).publishAsync(any(), any());
    }

    @Test
    @DisplayName("room.active.heartbeat control message는 active TTL을 연장한다")
    void handleMessage_roomActiveHeartbeat_marksSessionActiveOnly() {
        TextMessage msg = new TextMessage(
                "{\"type\":\"room.active.heartbeat\",\"roomId\":1,\"lastSeenSequence\":124}");

        handler.handleTextMessage(session, msg);

        verify(roomSessionRegistry).markActive(1L, "session-1", 124L);
        verify(chatPipelineMetrics).incrementCounter("ws.control.room_active_heartbeat");
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), anyInt());
        verify(chatIngestService, never()).ingest(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("control roomId가 연결 roomId와 다르면 상태를 바꾸지 않는다")
    void handleMessage_roomControlMismatch_ignored() {
        TextMessage msg = new TextMessage(
                "{\"type\":\"room.active\",\"roomId\":2,\"lastSeenSequence\":125}");

        handler.handleTextMessage(session, msg);

        verify(roomSessionRegistry, never()).markActive(any(), any(), any());
        verify(roomSessionRegistry, never()).markPassive(any(), any(), any());
        verify(chatPipelineMetrics).incrementCounter("ws.control.room_mismatch");
        verify(chatIngestService, never()).ingest(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("정상 메시지 저장 후 sender 세션에 chat.ack 제어 메시지를 보낸다")
    void handleMessage_normal_sendsAckToSenderSession() throws Exception {
        TextMessage msg = new TextMessage("{\"content\":\"Hello World\",\"clientMessageId\":\"c1\"}");
        ChatMessageDto saved = ChatMessageDto.builder()
                .id(10L)
                .sequence(10L)
                .messageId("message-10")
                .clientMessageId("c1")
                .roomId(1L)
                .createdAt(1000L)
                .build();
        when(chatIngestService.ingest(eq(1L), eq("user1"), eq("TestUser"), anyString(), eq("c1")))
                .thenReturn(new ChatIngestResult(saved, true, 99L));

        handler.handleTextMessage(session, msg);

        verify(roomSessionRegistry).sendControlToSession(eq("session-1"), argThat(payload ->
                payload instanceof io.hyun424.openchat.chat.message.dto.ChatAckMessageDto ack
                        && "message-10".equals(ack.getMessageId())
                        && "c1".equals(ack.getClientMessageId())
                        && Long.valueOf(10L).equals(ack.getSequence())
        ), eq("ack"));
    }

    @Test
    @DisplayName("새 메시지는 ack 이후 async live publish와 metadata enqueue를 호출한다")
    void handleMessage_newMessage_publishesAfterAck() throws Exception {
        TextMessage msg = new TextMessage("{\"content\":\"Hello World\",\"clientMessageId\":\"c1\"}");
        ChatMessageDto saved = ChatMessageDto.builder()
                .id(10L)
                .sequence(10L)
                .messageId("message-10")
                .clientMessageId("c1")
                .roomId(1L)
                .createdAt(1000L)
                .build();
        when(chatIngestService.ingest(eq(1L), eq("user1"), eq("TestUser"), anyString(), eq("c1")))
                .thenReturn(new ChatIngestResult(saved, true, 99L));

        handler.handleTextMessage(session, msg);

        InOrder inOrder = inOrder(roomSessionRegistry, postCommitLivePublishService, roomMetadataUpdateBuffer);
        inOrder.verify(roomSessionRegistry).sendControlToSession(eq("session-1"), any(), eq("ack"));
        inOrder.verify(postCommitLivePublishService).publishAsync(saved, 99L);
        inOrder.verify(roomMetadataUpdateBuffer).enqueue(saved);
    }

    @Test
    @DisplayName("중복 메시지는 ack만 보내고 async live publish를 반복하지 않는다")
    void handleMessage_duplicateMessage_skipsPostCommitPublish() throws Exception {
        TextMessage msg = new TextMessage("{\"content\":\"Hello World\",\"clientMessageId\":\"c1\"}");
        ChatMessageDto saved = ChatMessageDto.builder()
                .id(10L)
                .sequence(10L)
                .messageId("message-10")
                .clientMessageId("c1")
                .roomId(1L)
                .createdAt(1000L)
                .build();
        when(chatIngestService.ingest(eq(1L), eq("user1"), eq("TestUser"), anyString(), eq("c1")))
                .thenReturn(new ChatIngestResult(saved, false, null));

        handler.handleTextMessage(session, msg);

        verify(roomSessionRegistry).sendControlToSession(eq("session-1"), any(), eq("ack"));
        verify(postCommitLivePublishService, never()).publishAsync(any(), any());
        verify(roomMetadataUpdateBuffer, never()).enqueue(any());
    }

    @Test
    @DisplayName("XSS 컨텐츠 sanitize 후 ingest")
    void handleMessage_xssContent_sanitized() throws Exception {
        TextMessage msg = new TextMessage("{\"content\":\"<script>alert('xss')</script>Hello\"}");

        handler.handleTextMessage(session, msg);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatIngestService).ingest(eq(1L), eq("user1"), eq("TestUser"),
                contentCaptor.capture(), isNull());
        assertFalse(contentCaptor.getValue().contains("<script>"));
    }

    @Test
    @DisplayName("빈 메시지 무시")
    void handleMessage_emptyContent_ignored() throws Exception {
        TextMessage msg = new TextMessage("{\"content\":\"\"}");

        handler.handleTextMessage(session, msg);

        verify(chatIngestService, never()).ingest(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("세션 타임아웃 (4시간 초과) 시 세션 닫기")
    void handleMessage_sessionTimeout_closesSession() throws Exception {
        sessionAttributes.put("connectedAt", System.currentTimeMillis() - (5 * 60 * 60 * 1000L));
        TextMessage msg = new TextMessage("{\"content\":\"Hello\"}");

        handler.handleTextMessage(session, msg);

        ArgumentCaptor<CloseStatus> captor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(captor.capture());
        assertEquals(4002, captor.getValue().getCode());
        verify(chatIngestService, never()).ingest(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("afterConnectionEstablished: connected node frame은 registry 경유로 전송")
    void afterConnectionEstablished_sendsConnectedNodeFrameThroughRegistry() throws Exception {
        Room room = Room.builder().id(1L).name("Room").ownerId("x").build();
        when(roomService.getRoomOrThrow(1L)).thenReturn(room);
        when(roomSessionRegistry.sendControlToSession(eq("session-1"), any(), eq("node_connected"))).thenReturn(true);

        handler.afterConnectionEstablished(session);

        verify(roomSessionRegistry).add(eq(1L), isNull(), eq(session));
        verify(roomSessionRegistry).sendControlToSession(eq("session-1"), argThat(payload ->
                payload != null && payload.getClass().getSimpleName().equals("ConnectedNodePayload")
        ), eq("node_connected"));
        verify(session, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("afterConnectionEstablished: 종료된 방이면 세션 닫기")
    void afterConnectionEstablished_endedRoom_closesSession() throws Exception {
        Room room = Room.builder().id(1L).name("Ended").ownerId("x").build();
        room.end();
        when(roomService.getRoomOrThrow(1L)).thenReturn(room);

        handler.afterConnectionEstablished(session);

        ArgumentCaptor<CloseStatus> captor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(captor.capture());
        assertEquals(4001, captor.getValue().getCode());
        verify(roomSessionRegistry, never()).add(anyLong(), any());
    }

    @Test
    @DisplayName("afterConnectionClosed: registry에서 세션 제거")
    void afterConnectionClosed_removesFromRegistry() throws Exception {
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(roomSessionRegistry).remove(eq(1L), eq(session));
    }
}
