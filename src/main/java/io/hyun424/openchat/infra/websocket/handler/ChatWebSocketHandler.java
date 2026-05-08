package io.hyun424.openchat.infra.websocket.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.auth.jwt.JwtProvider;
import io.hyun424.openchat.chat.ingest.ChatIngestService;
import io.hyun424.openchat.chat.member.service.RoomMemberService;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.outbox.PostCommitLivePublishService;
import io.hyun424.openchat.chat.room.metadata.RoomMetadataUpdateBuffer;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionRoutingService;
import io.hyun424.openchat.chat.room.service.RoomService;
import io.hyun424.openchat.global.ratelimit.RateLimiter;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 채팅 WebSocket lifecycle의 진입점.
 * 연결/인바운드 메시지 처리는 전용 서비스가 담당하고, 이 클래스는 Spring WebSocket adapter로 유지한다.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${app.role:combined}'.toLowerCase() != 'api' && '${app.websocket.enabled:true}'.toLowerCase() == 'true'")
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final WebSocketConnectionService connectionService;
    private final WebSocketInboundMessageService inboundMessageService;

    @Value("${ratelimit.ws.message-limit:10}")
    private int wsMessageLimit;

    @Value("${ratelimit.ws.window-seconds:1}")
    private int wsWindowSeconds;

    public ChatWebSocketHandler(ObjectMapper objectMapper,
                                RoomMemberService roomMemberService,
                                RoomSessionRegistry roomSessionRegistry,
                                ChatIngestService chatIngestService,
                                RoomService roomService,
                                RateLimiter rateLimiter,
                                JwtProvider jwtProvider,
                                ChatPipelineMetrics chatPipelineMetrics,
                                PostCommitLivePublishService postCommitLivePublishService,
                                RoomMetadataUpdateBuffer roomMetadataUpdateBuffer,
                                RoomPartitionRoutingService roomPartitionRoutingService,
                                RoomPartitionMetrics roomPartitionMetrics,
                                @Value("${app.instance-id:local}") String nodeId) {
        WebSocketRouteQueryParser routeQueryParser = new WebSocketRouteQueryParser();
        ChatWebSocketMessageParser messageParser = new ChatWebSocketMessageParser(objectMapper);
        WebSocketErrorSender errorSender = new WebSocketErrorSender(objectMapper);
        WebSocketSessionGuard sessionGuard = new WebSocketSessionGuard(jwtProvider);
        RoomPresenceControlService presenceControlService = new RoomPresenceControlService(
                roomSessionRegistry,
                chatPipelineMetrics
        );

        this.connectionService = new WebSocketConnectionService(
                roomMemberService,
                roomSessionRegistry,
                roomService,
                sessionGuard,
                chatPipelineMetrics,
                roomPartitionRoutingService,
                roomPartitionMetrics,
                routeQueryParser,
                objectMapper,
                nodeId
        );
        this.inboundMessageService = new WebSocketInboundMessageService(
                roomSessionRegistry,
                chatIngestService,
                rateLimiter,
                messageParser,
                errorSender,
                sessionGuard,
                chatPipelineMetrics,
                postCommitLivePublishService,
                roomMetadataUpdateBuffer,
                presenceControlService,
                routeQueryParser
        );
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        connectionService.afterConnectionEstablished(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) {
        inboundMessageService.handleTextMessage(session, textMessage, wsMessageLimit, wsWindowSeconds);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connectionService.afterConnectionClosed(session);
    }
}
