package io.hyun424.openchat.infra.websocket.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.auth.jwt.JwtProvider;
import io.hyun424.openchat.chat.ingest.ChatIngestService;
import io.hyun424.openchat.chat.member.service.RoomMemberService;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.message.service.MessageService;
import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.chat.room.service.RoomService;
import io.hyun424.openchat.global.ratelimit.RateLimiter;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import io.hyun424.openchat.chat.message.entity.Message;

import java.io.IOException;
import java.nio.charset.StandardCharsets;


/**
 * WebSocket 메시지 핸들러
 * Security 강화:
 * - 메시지 크기 제한 (10KB)
 * - 토큰 유효성 주기적 검증
 * - 세션 타임아웃
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final RoomMemberService roomMemberService;
    private final RoomSessionRegistry roomSessionRegistry;
    private final ChatIngestService chatIngestService;
    private final MessageService messageService;
    private final RoomService roomService;
    private final RateLimiter rateLimiter;
    private final JwtProvider jwtProvider;

    @Value("${ratelimit.ws.message-limit:10}")
    private int wsMessageLimit;

    @Value("${ratelimit.ws.window-seconds:1}")
    private int wsWindowSeconds;

    // Security: 메시지 크기 제한 (10KB)
    private static final int MAX_MESSAGE_SIZE_BYTES = 10 * 1024;

    // Security: 토큰 재검증 주기 (5분)
    private static final long TOKEN_VALIDATION_INTERVAL_MS = 5 * 60 * 1000;

    // Security: 세션 최대 유지 시간 (4시간)
    private static final long MAX_SESSION_DURATION_MS = 4 * 60 * 60 * 1000;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long roomId = extractRoomId(session);

        String userId = (String) session.getAttributes().get("userId");
        String nickname = (String) session.getAttributes().get("nickname");

        if (userId == null || nickname == null || nickname.isBlank()) {
            throw new IllegalStateException("Invalid WebSocket authentication");
        }

        // 종료된 방 체크
        Room room = roomService.getRoomOrThrow(roomId);
        if (!room.isAccessible()) {
            session.close(new CloseStatus(4001, "Room has been ended"));
            return;
        }

        roomMemberService.getJoinedAtOrThrow(roomId, userId);

        // Security: 연결 시간 기록 (세션 타임아웃용)
        session.getAttributes().put("connectedAt", System.currentTimeMillis());
        session.getAttributes().put("lastTokenValidation", System.currentTimeMillis());

        roomSessionRegistry.add(roomId, session);

        log.info("[WS CONNECT] roomId={} userId={} session={}",
                roomId, userId, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) {

        Long roomId = extractRoomId(session);
        String senderId = (String) session.getAttributes().get("userId");
        String nickname = (String) session.getAttributes().get("nickname");

        try {
            // Security: 메시지 크기 제한 (10KB)
            int messageSize = textMessage.getPayload().getBytes(StandardCharsets.UTF_8).length;
            if (messageSize > MAX_MESSAGE_SIZE_BYTES) {
                log.warn("[WS_MSG_TOO_LARGE] roomId={} senderId={} size={}bytes", roomId, senderId, messageSize);
                sendError(session, "Message too large (max 10KB)");
                return;
            }

            // Security: 세션 타임아웃 체크 (4시간)
            Long connectedAt = (Long) session.getAttributes().get("connectedAt");
            if (connectedAt != null && System.currentTimeMillis() - connectedAt > MAX_SESSION_DURATION_MS) {
                log.warn("[WS_SESSION_TIMEOUT] roomId={} senderId={}", roomId, senderId);
                session.close(new CloseStatus(4002, "Session timeout"));
                return;
            }

            // Security: 토큰 주기적 재검증 (5분마다)
            if (!validateTokenPeriodically(session)) {
                log.warn("[WS_TOKEN_EXPIRED] roomId={} senderId={}", roomId, senderId);
                session.close(new CloseStatus(4001, "Token expired"));
                return;
            }

            // 종료된 방 체크
            Room room = roomService.getRoomOrThrow(roomId);
            if (!room.isAccessible()) {
                session.close(new CloseStatus(4001, "Room has been ended"));
                return;
            }

            JsonNode node = objectMapper.readTree(textMessage.getPayload());

            String content = node.has("content") ? node.get("content").asText() : null;
            String clientMessageId = node.has("clientMessageId")
                    ? node.get("clientMessageId").asText()
                    : null;

            // Rate Limiting: 사용자별 메시지 전송 제한
            if (!rateLimiter.tryAcquire("ws:" + senderId, wsMessageLimit, wsWindowSeconds)) {
                log.warn("[WS_RATE_LIMIT] roomId={} senderId={}", roomId, senderId);
                sendError(session, "Rate limit exceeded");
                return;
            }

            // 메시지 검증
            if (content == null || content.isBlank()) {
                log.warn("[WS_MSG_EMPTY] roomId={} senderId={}", roomId, senderId);
                return;
            }
            if (content.length() > 500) {
                log.warn("[WS_MSG_TOO_LONG] roomId={} senderId={} length={}", roomId, senderId, content.length());
                sendError(session, "Message too long (max 500 chars)");
                return;
            }

            // Security: XSS 방지 - 더 강력한 sanitization
            content = sanitizeContent(content);

            // DB 저장 / fan-out 전부 Ingest에게 위임
            chatIngestService.ingest(
                    roomId,
                    senderId,
                    nickname,
                    content,
                    clientMessageId
            );

        } catch (Exception e) {
            log.error("[WS_MSG_ERROR]", e);
        }
    }

    /**
     * Security: 토큰 유효성 주기적 검증
     * 만료된 토큰으로 연결이 유지되는 것을 방지
     */
    private boolean validateTokenPeriodically(WebSocketSession session) {
        Long lastValidation = (Long) session.getAttributes().get("lastTokenValidation");
        long now = System.currentTimeMillis();

        if (lastValidation != null && now - lastValidation < TOKEN_VALIDATION_INTERVAL_MS) {
            return true;  // 최근에 검증했으므로 스킵
        }

        String token = (String) session.getAttributes().get("token");
        if (token == null || !jwtProvider.validateToken(token)) {
            return false;
        }

        session.getAttributes().put("lastTokenValidation", now);
        return true;
    }

    /**
     * Security: XSS 방지를 위한 컨텐츠 sanitization
     * HTML 태그, 스크립트, 이벤트 핸들러 제거
     */
    private String sanitizeContent(String content) {
        if (content == null) return null;

        // HTML 태그 제거 (malformed 태그 포함)
        content = content.replaceAll("<[^>]*>?", "");

        // JavaScript 프로토콜 제거
        content = content.replaceAll("(?i)javascript:", "");

        // 이벤트 핸들러 제거
        content = content.replaceAll("(?i)on\\w+\\s*=", "");

        // Unicode 이스케이프 처리
        content = content.replaceAll("\\\\u003[cC]", "<");
        content = content.replaceAll("\\\\u003[eE]", ">");

        return content;
    }

    /**
     * 클라이언트에 에러 메시지 전송
     */
    private void sendError(WebSocketSession session, String message) {
        try {
            String errorJson = objectMapper.writeValueAsString(
                    java.util.Map.of("type", "ERROR", "message", message)
            );
            session.sendMessage(new TextMessage(errorJson));
        } catch (IOException e) {
            log.error("[WS_SEND_ERROR_FAILED]", e);
        }
    }


    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long roomId = extractRoomId(session);
        roomSessionRegistry.remove(roomId, session);

        log.info("[WS DISCONNECT] roomId={} session={}", roomId, session.getId());
    }

    private Long extractRoomId(WebSocketSession session) {
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query == null) throw new IllegalStateException("Missing query");

        for (String kv : query.split("&")) {
            String[] parts = kv.split("=");
            if (parts.length == 2 && parts[0].equals("roomId")) {
                return Long.parseLong(parts[1]);
            }
        }
        throw new IllegalStateException("Missing roomId");
    }
}
