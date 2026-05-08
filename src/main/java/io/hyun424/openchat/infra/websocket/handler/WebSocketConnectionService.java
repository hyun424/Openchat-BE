package io.hyun424.openchat.infra.websocket.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.member.service.RoomMemberService;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionRoutingService;
import io.hyun424.openchat.chat.room.service.RoomService;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
class WebSocketConnectionService {

    private final RoomMemberService roomMemberService;
    private final RoomSessionRegistry roomSessionRegistry;
    private final RoomService roomService;
    private final WebSocketSessionGuard sessionGuard;
    private final ChatPipelineMetrics chatPipelineMetrics;
    private final RoomPartitionRoutingService roomPartitionRoutingService;
    private final RoomPartitionMetrics roomPartitionMetrics;
    private final WebSocketRouteQueryParser routeQueryParser;
    private final ObjectMapper objectMapper;
    private final String nodeId;

    WebSocketConnectionService(RoomMemberService roomMemberService,
                               RoomSessionRegistry roomSessionRegistry,
                               RoomService roomService,
                               WebSocketSessionGuard sessionGuard,
                               ChatPipelineMetrics chatPipelineMetrics,
                               RoomPartitionRoutingService roomPartitionRoutingService,
                               RoomPartitionMetrics roomPartitionMetrics,
                               WebSocketRouteQueryParser routeQueryParser,
                               ObjectMapper objectMapper,
                               String nodeId) {
        this.roomMemberService = roomMemberService;
        this.roomSessionRegistry = roomSessionRegistry;
        this.roomService = roomService;
        this.sessionGuard = sessionGuard;
        this.chatPipelineMetrics = chatPipelineMetrics;
        this.roomPartitionRoutingService = roomPartitionRoutingService;
        this.roomPartitionMetrics = roomPartitionMetrics;
        this.routeQueryParser = routeQueryParser;
        this.objectMapper = objectMapper;
        this.nodeId = nodeId;
    }

    void afterConnectionEstablished(WebSocketSession session) throws Exception {
        long totalStartNanos = System.nanoTime();
        Long roomId = routeQueryParser.roomId(session);
        Integer partitionId = routeQueryParser.partitionId(session);
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
        if (partitionId == null && roomPartitionRoutingService.shouldPartition(roomId)) {
            roomPartitionMetrics.recordLegacyWebSocket();
            log.warn("[WS PARTITION LEGACY CONNECT] roomId={} userId={} session={}",
                    roomId, userId, session.getId());
        }
        roomSessionRegistry.add(roomId, partitionId, session);
        sendConnectedNodeFrame(session, roomId, partitionId);
        chatPipelineMetrics.recordStage("ws.connect.registry_add", registryStartNanos);
        chatPipelineMetrics.recordStage("ws.connect.total", totalStartNanos);

        log.debug("[WS CONNECT] roomId={} userId={} session={} nodeId={} routeNodeId={}",
                roomId, userId, session.getId(), nodeId, routeQueryParser.routeNodeId(session));
    }

    void afterConnectionClosed(WebSocketSession session) {
        Long roomId = routeQueryParser.roomId(session);
        roomSessionRegistry.remove(roomId, session);
        log.debug("[WS DISCONNECT] roomId={} session={}", roomId, session.getId());
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

    private void sendConnectedNodeFrame(WebSocketSession session, Long roomId, Integer partitionId) {
        try {
            ConnectedNodePayload payload = new ConnectedNodePayload(
                    "node.connected",
                    nodeId,
                    routeQueryParser.routeNodeId(session),
                    roomId,
                    partitionId,
                    routeQueryParser.assignmentVersion(session)
            );
            boolean sent = roomSessionRegistry.sendControlToSession(session.getId(), payload, "node_connected");
            if (!sent) {
                log.warn("failed to send connected node control frame session={} nodeId={}", session.getId(), nodeId);
            }
        } catch (Exception e) {
            log.warn("failed to send connected node control frame session={} nodeId={}", session.getId(), nodeId, e);
        }
    }

    private record ConnectedNodePayload(
            String type,
            String nodeId,
            String routeNodeId,
            Long roomId,
            Integer partitionId,
            String assignmentVersion
    ) {
    }
}
