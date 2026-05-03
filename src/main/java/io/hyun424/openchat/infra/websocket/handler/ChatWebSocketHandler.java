package io.hyun424.openchat.infra.websocket.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.auth.jwt.JwtProvider;
import io.hyun424.openchat.chat.ingest.ChatIngestService;
import io.hyun424.openchat.chat.member.service.RoomMemberService;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.chat.room.service.RoomService;
import io.hyun424.openchat.global.ratelimit.RateLimiter;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.charset.StandardCharsets;


/**
 * 채팅 WebSocket 연결과 메시지 처리의 진입점.
 * 실제 검증/파싱/에러 응답은 helper로 분리해, 이 클래스에서는 처리 순서가 먼저 보이도록 유지한다.
 */
@Slf4j
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final RoomMemberService roomMemberService;
    private final RoomSessionRegistry roomSessionRegistry;
    private final ChatIngestService chatIngestService;
    private final RoomService roomService;
    private final RateLimiter rateLimiter;
    private final ChatWebSocketMessageParser messageParser;
    private final WebSocketErrorSender errorSender;
    private final WebSocketSessionGuard sessionGuard;
    private final ChatPipelineMetrics chatPipelineMetrics;

    @Value("${ratelimit.ws.message-limit:10}")
    private int wsMessageLimit;

    @Value("${ratelimit.ws.window-seconds:1}")
    private int wsWindowSeconds;

    private static final int MAX_MESSAGE_SIZE_BYTES = 10 * 1024;
    private static final int MAX_CONTENT_LENGTH = 500;

    public ChatWebSocketHandler(ObjectMapper objectMapper,
                                RoomMemberService roomMemberService,
                                RoomSessionRegistry roomSessionRegistry,
                                ChatIngestService chatIngestService,
                                RoomService roomService,
                                RateLimiter rateLimiter,
                                JwtProvider jwtProvider,
                                ChatPipelineMetrics chatPipelineMetrics) {
        this.roomMemberService = roomMemberService;
        this.roomSessionRegistry = roomSessionRegistry;
        this.chatIngestService = chatIngestService;
        this.roomService = roomService;
        this.rateLimiter = rateLimiter;
        this.messageParser = new ChatWebSocketMessageParser(objectMapper);
        this.errorSender = new WebSocketErrorSender(objectMapper);
        this.sessionGuard = new WebSocketSessionGuard(jwtProvider);
        this.chatPipelineMetrics = chatPipelineMetrics;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        long totalStartNanos = System.nanoTime();
        Long roomId = extractRoomId(session);
        String userId = (String) session.getAttributes().get("userId");
        String nickname = (String) session.getAttributes().get("nickname");

        validateAuthenticatedSession(userId, nickname);
        long roomCheckStartNanos = System.nanoTime();
        if (!closeIfRoomEnded(session, roomId)) {
            chatPipelineMetrics.recordStage("ws.connect.room_check", roomCheckStartNanos);
            chatPipelineMetrics.recordStage("ws.connect.total", totalStartNanos);
            return;
        }
        chatPipelineMetrics.recordStage("ws.connect.room_check", roomCheckStartNanos);

        long memberLookupStartNanos = System.nanoTime();
        roomMemberService.getJoinedAtOrThrow(roomId, userId);
        chatPipelineMetrics.recordStage("ws.connect.member_lookup", memberLookupStartNanos);
        sessionGuard.markConnected(session);
        long registryStartNanos = System.nanoTime();
        roomSessionRegistry.add(roomId, session);
        chatPipelineMetrics.recordStage("ws.connect.registry_add", registryStartNanos);
        chatPipelineMetrics.recordStage("ws.connect.total", totalStartNanos);

        log.debug("[WS CONNECT] roomId={} userId={} session={}",
                roomId, userId, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) {
        long totalStartNanos = System.nanoTime();
        Long roomId = extractRoomId(session);
        String senderId = (String) session.getAttributes().get("userId");
        String nickname = (String) session.getAttributes().get("nickname");

        try {
            long sizeCheckStartNanos = System.nanoTime();
            if (!validateMessageSize(session, textMessage, roomId, senderId)) {
                chatPipelineMetrics.recordStage("ws.inbound.size_check", sizeCheckStartNanos);
                return;
            }
            chatPipelineMetrics.recordStage("ws.inbound.size_check", sizeCheckStartNanos);

            long sessionCheckStartNanos = System.nanoTime();
            if (!validateSessionState(session, roomId, senderId)) {
                chatPipelineMetrics.recordStage("ws.inbound.session_check", sessionCheckStartNanos);
                return;
            }
            chatPipelineMetrics.recordStage("ws.inbound.session_check", sessionCheckStartNanos);

            long rateLimitStartNanos = System.nanoTime();
            if (!validateRateLimit(session, senderId, roomId)) {
                chatPipelineMetrics.recordStage("ws.inbound.rate_limit", rateLimitStartNanos);
                return;
            }
            chatPipelineMetrics.recordStage("ws.inbound.rate_limit", rateLimitStartNanos);

            long parseStartNanos = System.nanoTime();
            ChatWebSocketMessage message = messageParser.parse(textMessage);
            chatPipelineMetrics.recordStage("ws.inbound.parse", parseStartNanos);
            if (!validateContent(session, message, roomId, senderId)) {
                return;
            }

            /*
             * DB 저장, 메시지 버스 발행, fan-out은 모두 Ingest 계층이 책임진다.
             * WebSocket 핸들러는 연결/입력 검증만 담당해야 장애 대응 흐름을 추적하기 쉽다.
             */
            long ingestStartNanos = System.nanoTime();
            chatIngestService.ingest(
                    roomId,
                    senderId,
                    nickname,
                    message.content(),
                    message.clientMessageId()
            );
            chatPipelineMetrics.recordStage("ws.inbound.ingest", ingestStartNanos);

        } catch (Exception e) {
            log.error("[WS_MSG_ERROR]", e);
        } finally {
            chatPipelineMetrics.recordStage("ws.inbound.total", totalStartNanos);
        }
    }

    private void validateAuthenticatedSession(String userId, String nickname) {
        if (userId == null || nickname == null || nickname.isBlank()) {
            throw new IllegalStateException("Invalid WebSocket authentication");
        }
    }

    private boolean closeIfRoomEnded(WebSocketSession session, Long roomId) throws Exception {
        Room room = roomService.getRoomOrThrow(roomId);
        if (!room.isAccessible()) {
            session.close(new CloseStatus(4001, "Room has been ended"));
            return false;
        }
        return true;
    }

    private boolean validateMessageSize(WebSocketSession session,
                                        TextMessage textMessage,
                                        Long roomId,
                                        String senderId) {
        int messageSize = textMessage.getPayload().getBytes(StandardCharsets.UTF_8).length;
        if (messageSize <= MAX_MESSAGE_SIZE_BYTES) {
            return true;
        }
        log.warn("[WS_MSG_TOO_LARGE] roomId={} senderId={} size={}bytes", roomId, senderId, messageSize);
        errorSender.sendError(session, "Message too large (max 10KB)");
        return false;
    }

    private boolean validateSessionState(WebSocketSession session, Long roomId, String senderId) throws Exception {
        if (sessionGuard.isSessionExpired(session)) {
            log.warn("[WS_SESSION_TIMEOUT] roomId={} senderId={}", roomId, senderId);
            session.close(new CloseStatus(4002, "Session timeout"));
            return false;
        }
        if (!sessionGuard.isTokenValid(session)) {
            log.warn("[WS_TOKEN_EXPIRED] roomId={} senderId={}", roomId, senderId);
            session.close(new CloseStatus(4001, "Token expired"));
            return false;
        }
        return true;
    }

    private boolean validateRateLimit(WebSocketSession session, String senderId, Long roomId) {
        if (rateLimiter.tryAcquire("ws:" + senderId, wsMessageLimit, wsWindowSeconds)) {
            return true;
        }
        log.warn("[WS_RATE_LIMIT] roomId={} senderId={}", roomId, senderId);
        errorSender.sendError(session, "Rate limit exceeded");
        return false;
    }

    private boolean validateContent(WebSocketSession session,
                                    ChatWebSocketMessage message,
                                    Long roomId,
                                    String senderId) {
        String content = message.content();
        if (content == null || content.isBlank()) {
            log.warn("[WS_MSG_EMPTY] roomId={} senderId={}", roomId, senderId);
            return false;
        }
        if (content.length() <= MAX_CONTENT_LENGTH) {
            return true;
        }
        log.warn("[WS_MSG_TOO_LONG] roomId={} senderId={} length={}", roomId, senderId, content.length());
        errorSender.sendError(session, "Message too long (max 500 chars)");
        return false;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long roomId = extractRoomId(session);
        roomSessionRegistry.remove(roomId, session);

        log.debug("[WS DISCONNECT] roomId={} session={}", roomId, session.getId());
    }

    private Long extractRoomId(WebSocketSession session) {
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query == null) {
            throw new IllegalStateException("Missing query");
        }

        for (String kv : query.split("&")) {
            String[] parts = kv.split("=");
            if (parts.length == 2 && parts[0].equals("roomId")) {
                return Long.parseLong(parts[1]);
            }
        }
        throw new IllegalStateException("Missing roomId");
    }
}
