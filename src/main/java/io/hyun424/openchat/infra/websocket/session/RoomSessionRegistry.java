package io.hyun424.openchat.infra.websocket.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class RoomSessionRegistry {

    private static final int DEFAULT_BROADCAST_LANES = BroadcastLaneExecutor.DEFAULT_BROADCAST_LANES;
    private static final int DEFAULT_BROADCAST_QUEUE_CAPACITY_PER_LANE = BroadcastLaneExecutor.DEFAULT_BROADCAST_QUEUE_CAPACITY_PER_LANE;
    private static final int DEFAULT_BROADCAST_SHUTDOWN_TIMEOUT_MS = BroadcastLaneExecutor.DEFAULT_BROADCAST_SHUTDOWN_TIMEOUT_MS;
    private static final long DEFAULT_ACTIVE_TTL_MILLIS = 60_000L;

    private final RoomTrafficMonitor roomTrafficMonitor;
    private final RoomSessionStore sessionStore;
    private final SessionStateTracker stateTracker;
    private final BroadcastLaneExecutor laneExecutor;
    private final WebSocketBroadcaster broadcaster;
    private final AtomicBoolean acceptingConnections = new AtomicBoolean(true);

    public RoomSessionRegistry(ObjectMapper objectMapper) {
        this(objectMapper, new RoomTrafficMonitor(), ChatPipelineMetrics.noop(),
                null,
                DEFAULT_BROADCAST_LANES,
                DEFAULT_BROADCAST_QUEUE_CAPACITY_PER_LANE,
                DEFAULT_BROADCAST_SHUTDOWN_TIMEOUT_MS,
                DEFAULT_ACTIVE_TTL_MILLIS);
    }

    public RoomSessionRegistry(ObjectMapper objectMapper,
                               int configuredLaneCount,
                               int queueCapacity) {
        this(objectMapper, new RoomTrafficMonitor(), ChatPipelineMetrics.noop(),
                null,
                configuredLaneCount,
                queueCapacity,
                DEFAULT_BROADCAST_SHUTDOWN_TIMEOUT_MS,
                DEFAULT_ACTIVE_TTL_MILLIS);
    }

    public RoomSessionRegistry(ObjectMapper objectMapper,
                               RoomTrafficMonitor roomTrafficMonitor,
                               int configuredLaneCount,
                               int queueCapacity) {
        this(objectMapper, roomTrafficMonitor, ChatPipelineMetrics.noop(),
                null,
                configuredLaneCount,
                queueCapacity,
                DEFAULT_BROADCAST_SHUTDOWN_TIMEOUT_MS,
                DEFAULT_ACTIVE_TTL_MILLIS);
    }

    @Autowired
    public RoomSessionRegistry(ObjectMapper objectMapper,
                               RoomTrafficMonitor roomTrafficMonitor,
                               ChatPipelineMetrics chatPipelineMetrics,
                               RoomPartitionMetrics roomPartitionMetrics,
                               @Value("${app.websocket.broadcast.lanes:0}") int configuredLaneCount,
                               @Value("${app.websocket.broadcast.pool-size:0}") int legacyPoolSize,
                               @Value("${app.websocket.broadcast.queue-capacity-per-lane:${app.websocket.broadcast.queue-capacity:4096}}") int queueCapacityPerLane,
                               @Value("${app.websocket.broadcast.shutdown-timeout-ms:5000}") int shutdownTimeoutMillis,
                               @Value("${app.websocket.active-ttl-ms:60000}") long activeTtlMillis) {
        this(objectMapper, roomTrafficMonitor, chatPipelineMetrics,
                roomPartitionMetrics,
                configuredLaneCount > 0 ? configuredLaneCount : legacyPoolSize,
                queueCapacityPerLane,
                shutdownTimeoutMillis,
                activeTtlMillis);
    }

    public RoomSessionRegistry(ObjectMapper objectMapper,
                               RoomTrafficMonitor roomTrafficMonitor,
                               ChatPipelineMetrics chatPipelineMetrics,
                               int configuredLaneCount,
                               int legacyPoolSize,
                               int queueCapacityPerLane,
                               int shutdownTimeoutMillis) {
        this(objectMapper, roomTrafficMonitor, chatPipelineMetrics,
                null,
                configuredLaneCount > 0 ? configuredLaneCount : legacyPoolSize,
                queueCapacityPerLane,
                shutdownTimeoutMillis,
                DEFAULT_ACTIVE_TTL_MILLIS);
    }

    public RoomSessionRegistry(ObjectMapper objectMapper,
                               RoomTrafficMonitor roomTrafficMonitor,
                               ChatPipelineMetrics chatPipelineMetrics,
                               int configuredLaneCount,
                               int legacyPoolSize,
                               int queueCapacityPerLane,
                               int shutdownTimeoutMillis,
                               long activeTtlMillis) {
        this(objectMapper, roomTrafficMonitor, chatPipelineMetrics,
                null,
                configuredLaneCount > 0 ? configuredLaneCount : legacyPoolSize,
                queueCapacityPerLane,
                shutdownTimeoutMillis,
                activeTtlMillis);
    }

    private RoomSessionRegistry(ObjectMapper objectMapper,
                                RoomTrafficMonitor roomTrafficMonitor,
                                ChatPipelineMetrics chatPipelineMetrics,
                                RoomPartitionMetrics roomPartitionMetrics,
                                int configuredLaneCount,
                                int queueCapacityPerLane,
                                int shutdownTimeoutMillis,
                                long activeTtlMillis) {
        this.roomTrafficMonitor = roomTrafficMonitor;
        this.sessionStore = new RoomSessionStore();
        this.stateTracker = new SessionStateTracker(activeTtlMillis);
        this.laneExecutor = new BroadcastLaneExecutor(
                configuredLaneCount,
                queueCapacityPerLane,
                shutdownTimeoutMillis,
                chatPipelineMetrics
        );
        WebSocketPayloadSerializer serializer = new WebSocketPayloadSerializer(objectMapper, chatPipelineMetrics);
        this.broadcaster = new WebSocketBroadcaster(
                sessionStore,
                stateTracker,
                serializer,
                laneExecutor,
                new WebSocketSendFailureClassifier(),
                roomTrafficMonitor,
                chatPipelineMetrics,
                roomPartitionMetrics
        );
    }

    public boolean isAcceptingConnections() {
        return acceptingConnections.get();
    }

    public void stopAcceptingConnections() {
        acceptingConnections.set(false);
        log.info("[WS REGISTRY] Stopped accepting new connections");
    }

    public void add(Long roomId, WebSocketSession session) {
        add(roomId, null, session);
    }

    public void add(Long roomId, Integer partitionId, WebSocketSession session) {
        if (!acceptingConnections.get()) {
            sessionStore.closeSession(session, new CloseStatus(1001, "Server shutting down"), "[WS REJECT]");
            return;
        }
        WebSocketSession storedSession = sessionStore.add(roomId, session);
        if (storedSession == null) {
            return;
        }
        stateTracker.register(roomId, partitionId, session.getId());
        roomTrafficMonitor.recordJoin(roomId, count(roomId));
    }

    public void remove(Long roomId, WebSocketSession session) {
        RoomSessionStore.RemoveResult result = sessionStore.remove(roomId, session);
        if (result.removed()) {
            stateTracker.remove(result.sessionId());
            roomTrafficMonitor.recordLeave(roomId, result.remainingRoomCount());
        }
    }

    public void markActive(Long roomId, String sessionId, Long lastSeenSequence) {
        stateTracker.markActive(roomId, sessionId, lastSeenSequence);
    }

    public void markPassive(Long roomId, String sessionId, Long lastSeenSequence) {
        stateTracker.markPassive(roomId, sessionId, lastSeenSequence);
    }

    public Set<WebSocketSession> getSessions(Long roomId) {
        return sessionStore.sessions(roomId);
    }

    public int count(Long roomId) {
        return sessionStore.count(roomId);
    }

    public List<String> openSessionIds(Long roomId, Integer partitionId) {
        return stateTracker.openSessionIds(roomId, partitionId, sessionStore.sessions(roomId));
    }

    public int openSessionCountForPartition(Integer partitionId) {
        return stateTracker.openSessionCountForPartition(partitionId, sessionStore.allSessions());
    }

    public List<SessionStateTracker.OpenSessionInfo> openSessions() {
        return stateTracker.openSessions(sessionStore.allSessions());
    }

    public int totalBroadcastQueueDepth() {
        return laneExecutor.totalQueueDepth();
    }

    public RoomSessionWorkloadSnapshot workloadSnapshot() {
        return stateTracker.workloadSnapshot(sessionStore.allSessions(), laneExecutor.totalQueueDepth());
    }

    public void sendToRoom(Long roomId, ChatMessageDto message) {
        broadcaster.sendToRoom(roomId, message);
    }

    public void sendToRoom(Long roomId, Integer partitionId, ChatMessageDto message) {
        broadcaster.sendToRoom(roomId, partitionId, message);
    }

    public void sendBatchToRoom(Long roomId, List<ChatMessageDto> messages) {
        broadcaster.sendBatchToRoom(roomId, messages);
    }

    public void sendBatchToRoom(Long roomId, Integer partitionId, List<ChatMessageDto> messages) {
        broadcaster.sendBatchToRoom(roomId, partitionId, messages);
    }

    public void sendBatchToRoom(Long roomId,
                                List<ChatMessageDto> messages,
                                boolean realtimeComplete,
                                int omittedCount,
                                Long lastSequence) {
        broadcaster.sendBatchToRoom(roomId, messages, realtimeComplete, omittedCount, lastSequence);
    }

    public void sendBatchToRoom(Long roomId,
                                Integer partitionId,
                                List<ChatMessageDto> messages,
                                boolean realtimeComplete,
                                int omittedCount,
                                Long lastSequence) {
        broadcaster.sendBatchToRoom(roomId, partitionId, messages, realtimeComplete, omittedCount, lastSequence);
    }

    public boolean sendControlToSession(String sessionId, Object payload, String payloadType) {
        return broadcaster.sendControlToSession(sessionId, payload, payloadType);
    }

    public void closeAllSessionsInRoom(Long roomId) {
        RoomSessionStore.CloseAllResult result = sessionStore.closeAllSessionsInRoom(
                roomId,
                new CloseStatus(4001, "Room has been ended")
        );
        stateTracker.removeAll(result.sessionIds());
        if (!result.sessionIds().isEmpty()) {
            roomTrafficMonitor.recordLeave(roomId, 0);
            log.info("[WS CLOSE ALL] roomId={} closed {} sessions", roomId, result.closedCount());
        }
    }

    public void closeAllSessions() {
        RoomSessionStore.CloseAllResult result = sessionStore.closeAllSessions(
                new CloseStatus(1001, "Server shutting down")
        );
        stateTracker.removeAll(result.sessionIds());
        for (Long roomId : result.roomIds()) {
            roomTrafficMonitor.recordLeave(roomId, 0);
        }
        log.info("[WS SHUTDOWN] Closed {} sessions across all rooms", result.closedCount());
    }

    public int getTotalSessionCount() {
        return sessionStore.totalSessionCount();
    }

    public int getRoomCount() {
        return sessionStore.roomCount();
    }

    @PreDestroy
    public void shutdownExecutor() {
        laneExecutor.shutdown();
    }
}
