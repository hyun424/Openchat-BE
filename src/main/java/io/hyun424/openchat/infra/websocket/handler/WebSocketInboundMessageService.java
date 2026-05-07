package io.hyun424.openchat.infra.websocket.handler;

import io.hyun424.openchat.chat.ingest.ChatIngestResult;
import io.hyun424.openchat.chat.ingest.ChatIngestService;
import io.hyun424.openchat.chat.message.dto.ChatAckMessageDto;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.outbox.PostCommitLivePublishService;
import io.hyun424.openchat.chat.room.metadata.RoomMetadataUpdateBuffer;
import io.hyun424.openchat.global.ratelimit.RateLimiter;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;

@Slf4j
class WebSocketInboundMessageService {

    private static final int MAX_MESSAGE_SIZE_BYTES = 10 * 1024;
    private static final int MAX_CONTENT_LENGTH = 500;

    private final RoomSessionRegistry roomSessionRegistry;
    private final ChatIngestService chatIngestService;
    private final RateLimiter rateLimiter;
    private final ChatWebSocketMessageParser messageParser;
    private final WebSocketErrorSender errorSender;
    private final WebSocketSessionGuard sessionGuard;
    private final ChatPipelineMetrics chatPipelineMetrics;
    private final PostCommitLivePublishService postCommitLivePublishService;
    private final RoomMetadataUpdateBuffer roomMetadataUpdateBuffer;
    private final RoomPresenceControlService presenceControlService;
    private final WebSocketRouteQueryParser routeQueryParser;

    WebSocketInboundMessageService(RoomSessionRegistry roomSessionRegistry,
                                   ChatIngestService chatIngestService,
                                   RateLimiter rateLimiter,
                                   ChatWebSocketMessageParser messageParser,
                                   WebSocketErrorSender errorSender,
                                   WebSocketSessionGuard sessionGuard,
                                   ChatPipelineMetrics chatPipelineMetrics,
                                   PostCommitLivePublishService postCommitLivePublishService,
                                   RoomMetadataUpdateBuffer roomMetadataUpdateBuffer,
                                   RoomPresenceControlService presenceControlService,
                                   WebSocketRouteQueryParser routeQueryParser) {
        this.roomSessionRegistry = roomSessionRegistry;
        this.chatIngestService = chatIngestService;
        this.rateLimiter = rateLimiter;
        this.messageParser = messageParser;
        this.errorSender = errorSender;
        this.sessionGuard = sessionGuard;
        this.chatPipelineMetrics = chatPipelineMetrics;
        this.postCommitLivePublishService = postCommitLivePublishService;
        this.roomMetadataUpdateBuffer = roomMetadataUpdateBuffer;
        this.presenceControlService = presenceControlService;
        this.routeQueryParser = routeQueryParser;
    }

    void handleTextMessage(WebSocketSession session, TextMessage textMessage, int wsMessageLimit, int wsWindowSeconds) {
        long totalStartNanos = System.nanoTime();
        Long roomId = routeQueryParser.roomId(session);
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

            long parseStartNanos = System.nanoTime();
            ChatWebSocketMessage message = messageParser.parse(textMessage);
            chatPipelineMetrics.recordStage("ws.inbound.parse", parseStartNanos);
            if (message.isRoomControlMessage()) {
                presenceControlService.handle(session, message, roomId, senderId);
                return;
            }
            if (!message.isChatMessage()) {
                log.warn("[WS_CONTROL_UNKNOWN] roomId={} senderId={} type={}", roomId, senderId, message.type());
                chatPipelineMetrics.incrementCounter("ws.control.unknown");
                return;
            }

            long rateLimitStartNanos = System.nanoTime();
            if (!validateRateLimit(session, senderId, roomId, wsMessageLimit, wsWindowSeconds)) {
                chatPipelineMetrics.recordStage("ws.inbound.rate_limit", rateLimitStartNanos);
                return;
            }
            chatPipelineMetrics.recordStage("ws.inbound.rate_limit", rateLimitStartNanos);

            chatPipelineMetrics.recordSinceEpochMillis("ws.inbound.received.since_client_sent", message.clientSentAt());
            if (!validateContent(session, message, roomId, senderId)) {
                return;
            }

            long ingestStartNanos = System.nanoTime();
            ChatIngestResult ingestResult = chatIngestService.ingest(
                    roomId,
                    senderId,
                    nickname,
                    message.content(),
                    message.clientMessageId()
            );
            if (ingestResult == null) {
                return;
            }
            sendAck(session, ingestResult.message(), message.clientSentAt());
            if (ingestResult.newMessage()) {
                postCommitLivePublishService.publishAsync(ingestResult.message(), ingestResult.outboxEventId());
                roomMetadataUpdateBuffer.enqueue(ingestResult.message());
            }
            chatPipelineMetrics.recordStage("ws.inbound.ingest", ingestStartNanos);

        } catch (Exception e) {
            log.error("[WS_MSG_ERROR]", e);
        } finally {
            chatPipelineMetrics.recordStage("ws.inbound.total", totalStartNanos);
        }
    }

    private void sendAck(WebSocketSession session, ChatMessageDto savedMessage, Long clientSentAt) {
        if (savedMessage == null) {
            return;
        }
        long startNanos = System.nanoTime();
        chatPipelineMetrics.recordSinceEpochMillis("ws.ack.before_send.since_client_sent", clientSentAt);
        boolean sent = roomSessionRegistry.sendControlToSession(
                session.getId(),
                ChatAckMessageDto.from(savedMessage),
                "ack"
        );
        if (!sent) {
            chatPipelineMetrics.incrementCounter("ws.ack.send.fail");
            log.warn("[WS ACK SEND FAIL] sessionId={} roomId={} messageId={}",
                    session.getId(), savedMessage.getRoomId(), savedMessage.getMessageId());
        }
        chatPipelineMetrics.recordSinceEpochMillis("ws.ack.after_send.since_client_sent", clientSentAt);
        chatPipelineMetrics.recordStage("ack.after_commit", startNanos);
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

    private boolean validateRateLimit(WebSocketSession session,
                                      String senderId,
                                      Long roomId,
                                      int wsMessageLimit,
                                      int wsWindowSeconds) {
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
}
