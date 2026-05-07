package io.hyun424.openchat.infra.websocket.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatBatchMessageDto;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.partition.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class RoomSessionRegistry {

    private final ObjectMapper objectMapper;
    private final RoomTrafficMonitor roomTrafficMonitor;
    private final ChatPipelineMetrics chatPipelineMetrics;
    private final RoomPartitionMetrics roomPartitionMetrics;
    private final ThreadPoolExecutor[] broadcastLaneExecutors;
    private final int broadcastLaneCount;
    private final int broadcastShutdownTimeoutMillis;
    private final long activeTtlMillis;

    private final ConcurrentMap<Long, Set<WebSocketSession>> roomSessions =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WebSocketSession> sessionsById =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RoomSessionState> sessionStatesById =
            new ConcurrentHashMap<>();

    private final AtomicBoolean acceptingConnections = new AtomicBoolean(true);

    private static final int SEND_TIME_LIMIT_MS = 5000;
    private static final int BUFFER_SIZE_LIMIT = 64 * 1024;
    private static final int DEFAULT_BROADCAST_LANES = 16;
    private static final int DEFAULT_BROADCAST_QUEUE_CAPACITY_PER_LANE = 4096;
    private static final int DEFAULT_BROADCAST_SHUTDOWN_TIMEOUT_MS = 5000;
    private static final long DEFAULT_ACTIVE_TTL_MILLIS = 60_000L;

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
        this.objectMapper = objectMapper;
        this.roomTrafficMonitor = roomTrafficMonitor;
        this.chatPipelineMetrics = chatPipelineMetrics;
        this.roomPartitionMetrics = roomPartitionMetrics;
        this.broadcastLaneCount = resolveBroadcastLaneCount(configuredLaneCount);
        this.broadcastShutdownTimeoutMillis = Math.max(1, shutdownTimeoutMillis);
        this.activeTtlMillis = Math.max(1, activeTtlMillis);
        int boundedQueueCapacity = Math.max(1, queueCapacityPerLane);
        AtomicInteger threadCounter = new AtomicInteger(0);
        this.broadcastLaneExecutors = new ThreadPoolExecutor[broadcastLaneCount];
        for (int i = 0; i < broadcastLaneCount; i++) {
            int laneIndex = i;
            this.broadcastLaneExecutors[i] = new ThreadPoolExecutor(
                    1, 1,
                    60L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(boundedQueueCapacity),
                    r -> {
                        Thread t = new Thread(r, "ws-broadcast-lane-" + laneIndex + "-" + threadCounter.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy()
            );
        }
    }

    private int resolveBroadcastLaneCount(int configuredLaneCount) {
        if (configuredLaneCount > 0) {
            return configuredLaneCount;
        }
        return DEFAULT_BROADCAST_LANES;
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
            closeSession(session, new CloseStatus(1001, "Server shutting down"), "[WS REJECT]");
            return;
        }
        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        /*
         * 저장은 decorator로 하지만 제거 요청은 원본 WebSocketSession으로 들어온다.
         * 객체 동일성 대신 session id로 찾아 제거해야 닫힌 세션이 registry에 남지 않는다.
         */
        sessionsById.put(session.getId(), decorated);
        sessionStatesById.put(session.getId(), RoomSessionState.active(roomId, partitionId, nowMillis()));
        roomSessions
                .computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet())
                .add(decorated);
        roomTrafficMonitor.recordJoin(roomId, count(roomId));
    }

    public void remove(Long roomId, WebSocketSession session) {
        Set<WebSocketSession> set = roomSessions.get(roomId);
        if (set != null) {
            WebSocketSession storedSession = sessionsById.remove(session.getId());
            sessionStatesById.remove(session.getId());
            set.remove(storedSession != null ? storedSession : session);
            if (set.isEmpty()) {
                roomSessions.remove(roomId);
            }
            roomTrafficMonitor.recordLeave(roomId, count(roomId));
        }
    }

    public void markActive(Long roomId, String sessionId, Long lastSeenSequence) {
        updateSessionState(roomId, sessionId, lastSeenSequence, true);
    }

    public void markPassive(Long roomId, String sessionId, Long lastSeenSequence) {
        updateSessionState(roomId, sessionId, lastSeenSequence, false);
    }

    private void updateSessionState(Long roomId, String sessionId, Long lastSeenSequence, boolean active) {
        if (sessionId == null) {
            return;
        }
        long now = nowMillis();
        sessionStatesById.compute(sessionId, (ignored, existing) -> {
            RoomSessionState state = existing == null ? RoomSessionState.active(roomId, null, now) : existing;
            if (!state.roomId().equals(roomId)) {
                log.warn("[WS SESSION STATE ROOM MISMATCH] sessionId={} stateRoomId={} requestedRoomId={}",
                        sessionId, state.roomId(), roomId);
                return state;
            }
            state.mark(active, lastSeenSequence, now);
            return state;
        });
    }

    public Set<WebSocketSession> getSessions(Long roomId) {
        return roomSessions.getOrDefault(roomId, Collections.emptySet());
    }

    public int count(Long roomId) {
        return getSessions(roomId).size();
    }

    public List<String> openSessionIds(Long roomId, Integer partitionId) {
        Set<WebSocketSession> sessions = getSessions(roomId);
        if (sessions.isEmpty()) {
            return List.of();
        }

        List<String> sessionIds = new ArrayList<>(sessions.size());
        for (WebSocketSession session : sessions) {
            if (!matchesPartition(session, partitionId) || !session.isOpen()) {
                continue;
            }
            sessionIds.add(session.getId());
        }
        return sessionIds;
    }

    public int totalBroadcastQueueDepth() {
        int total = 0;
        for (ThreadPoolExecutor executor : broadcastLaneExecutors) {
            total += executor.getQueue().size();
        }
        return total;
    }

    /**
     * 방에 연결된 세션으로 메시지를 병렬 전송한다.
     * 전송 중 Set을 직접 수정하면 살아있는 세션을 건너뛸 수 있으므로, 죽은 세션은 전송이 끝난 뒤 정리한다.
     */
    public void sendToRoom(Long roomId, ChatMessageDto message) {
        sendToRoom(roomId, null, message);
    }

    public void sendToRoom(Long roomId, Integer partitionId, ChatMessageDto message) {
        long broadcastStartNanos = System.nanoTime();
        Set<WebSocketSession> sessions = getSessions(roomId);

        if (sessions.isEmpty()) {
            log.debug("[WS BROADCAST] roomId={} - no sessions", roomId);
            return;
        }

        List<WebSocketSession> sessionSnapshot = activeSessionSnapshot(roomId, partitionId, sessions, 1);
        if (sessionSnapshot.isEmpty()) {
            return;
        }

        TextMessage textMessage = serializeMessage(roomId, message);
        if (textMessage == null) {
            return;
        }
        roomTrafficMonitor.recordOutboundFanout(roomId, sessionSnapshot.size(), sessionSnapshot.size());
        Long createdAt = message.getCreatedAt();
        if (createdAt != null) {
            roomTrafficMonitor.recordDeliveryLag(roomId, createdAt);
        }
        chatPipelineMetrics.recordSinceCreated("ws.broadcast.enqueue.since_created", message);
        int taskCount = enqueueBroadcast(roomId, sessionSnapshot, textMessage, "single", List.of(message));
        chatPipelineMetrics.recordStage("ws.broadcast.single.total", broadcastStartNanos);

        log.debug("[WS BROADCAST] roomId={} messageId={} sessions={} laneTasks={}",
                roomId, message.getMessageId(), sessionSnapshot.size(), taskCount);
    }

    /**
     * 같은 방의 여러 논리 메시지를 하나의 WebSocket frame으로 전송한다.
     * 단건 payload 호환성을 위해 메시지가 1개뿐이면 기존 단건 경로를 사용한다.
     */
    public void sendBatchToRoom(Long roomId, List<ChatMessageDto> messages) {
        sendBatchToRoom(roomId, messages, true, 0, null);
    }

    public void sendBatchToRoom(Long roomId, Integer partitionId, List<ChatMessageDto> messages) {
        sendBatchToRoom(roomId, partitionId, messages, true, 0, null);
    }

    public void sendBatchToRoom(Long roomId,
                                List<ChatMessageDto> messages,
                                boolean realtimeComplete,
                                int omittedCount,
                                Long lastSequence) {
        sendBatchToRoom(roomId, null, messages, realtimeComplete, omittedCount, lastSequence);
    }

    public void sendBatchToRoom(Long roomId,
                                Integer partitionId,
                                List<ChatMessageDto> messages,
                                boolean realtimeComplete,
                                int omittedCount,
                                Long lastSequence) {
        long broadcastStartNanos = System.nanoTime();
        if (messages == null || messages.isEmpty()) {
            return;
        }
        if (messages.size() == 1 && realtimeComplete && omittedCount <= 0) {
            sendToRoom(roomId, partitionId, messages.get(0));
            return;
        }

        Set<WebSocketSession> sessions = getSessions(roomId);
        if (sessions.isEmpty()) {
            log.debug("[WS BATCH BROADCAST] roomId={} count={} - no sessions", roomId, messages.size());
            return;
        }

        List<WebSocketSession> sessionSnapshot = activeSessionSnapshot(roomId, partitionId, sessions, messages.size());
        if (sessionSnapshot.isEmpty()) {
            return;
        }

        TextMessage textMessage = serializeBatchMessage(roomId, messages, realtimeComplete, omittedCount, lastSequence);
        if (textMessage == null) {
            return;
        }
        roomTrafficMonitor.recordOutboundFanout(roomId, sessionSnapshot.size() * messages.size(), sessionSnapshot.size());
        for (ChatMessageDto message : messages) {
            Long createdAt = message.getCreatedAt();
            if (createdAt != null) {
                roomTrafficMonitor.recordDeliveryLag(roomId, createdAt);
            }
            chatPipelineMetrics.recordSinceCreated("ws.broadcast.enqueue.since_created", message);
        }

        int taskCount = enqueueBroadcast(roomId, sessionSnapshot, textMessage, "batch", List.copyOf(messages));
        chatPipelineMetrics.recordStage("ws.broadcast.batch.total", broadcastStartNanos);
        chatPipelineMetrics.recordDistribution("openchat_pipeline_batch_size", "ws.broadcast", messages.size());
        chatPipelineMetrics.recordDistribution("openchat_pipeline_batch_sessions", "ws.broadcast", sessionSnapshot.size());

        log.debug("[WS BATCH BROADCAST] roomId={} count={} sessions={} laneTasks={}",
                roomId, messages.size(), sessionSnapshot.size(), taskCount);
    }

    private List<WebSocketSession> activeSessionSnapshot(Long roomId,
                                                         Integer partitionId,
                                                         Set<WebSocketSession> sessions,
                                                         int logicalMessagesPerSession) {
        List<WebSocketSession> activeSessions = new ArrayList<>(sessions.size());
        int passiveSessions = 0;
        long now = nowMillis();
        for (WebSocketSession session : sessions) {
            if (!matchesPartition(session, partitionId)) {
                continue;
            }
            if (isActiveSession(session, now)) {
                activeSessions.add(session);
            } else {
                passiveSessions++;
            }
        }

        chatPipelineMetrics.recordDistribution("ws.session", "active", activeSessions.size());
        chatPipelineMetrics.recordDistribution("ws.session", "passive", passiveSessions);
        chatPipelineMetrics.recordDistribution("ws.fanout", "active_sessions", activeSessions.size());
        if (partitionId != null && roomPartitionMetrics != null) {
            roomPartitionMetrics.recordActiveSessions(activeSessions.size());
            roomPartitionMetrics.recordFanoutDeliveries((long) activeSessions.size() * Math.max(1, logicalMessagesPerSession));
        }
        if (passiveSessions > 0) {
            int omitted = passiveSessions * Math.max(1, logicalMessagesPerSession);
            chatPipelineMetrics.incrementCounter("ws.fanout.passive_omitted", omitted);
            log.debug("[WS FANOUT PASSIVE OMITTED] roomId={} activeSessions={} passiveSessions={} logicalMessages={}",
                    roomId, activeSessions.size(), passiveSessions, logicalMessagesPerSession);
        }
        return activeSessions;
    }

    private boolean matchesPartition(WebSocketSession session, Integer partitionId) {
        if (partitionId == null) {
            return true;
        }
        RoomSessionState state = sessionStatesById.get(session.getId());
        return state != null && state.matchesPartition(partitionId);
    }

    private boolean isActiveSession(WebSocketSession session, long now) {
        RoomSessionState state = sessionStatesById.get(session.getId());
        if (state == null) {
            return true;
        }
        return state.isActive(now, activeTtlMillis);
    }

    private TextMessage serializeMessage(Long roomId, ChatMessageDto message) {
        long startNanos = System.nanoTime();
        try {
            TextMessage textMessage = new TextMessage(objectMapper.writeValueAsString(message));
            chatPipelineMetrics.recordStage("ws.serialize.single", startNanos);
            return textMessage;
        } catch (Exception e) {
            chatPipelineMetrics.recordStage("ws.serialize.single.fail", startNanos);
            log.error("[WS SERIALIZE FAIL] roomId={} messageId={}",
                    roomId, message.getMessageId(), e);
            return null;
        }
    }

    private TextMessage serializeBatchMessage(Long roomId, List<ChatMessageDto> messages) {
        return serializeBatchMessage(roomId, messages, true, 0, null);
    }

    private TextMessage serializeBatchMessage(Long roomId,
                                              List<ChatMessageDto> messages,
                                              boolean realtimeComplete,
                                              int omittedCount,
                                              Long lastSequence) {
        long startNanos = System.nanoTime();
        try {
            Long resolvedLastSequence = lastSequence != null ? lastSequence : sequenceOf(messages.get(messages.size() - 1));
            TextMessage textMessage = new TextMessage(objectMapper.writeValueAsString(
                    ChatBatchMessageDto.from(roomId, messages, realtimeComplete, omittedCount, resolvedLastSequence)));
            chatPipelineMetrics.recordStage("ws.serialize.batch", startNanos);
            return textMessage;
        } catch (Exception e) {
            chatPipelineMetrics.recordStage("ws.serialize.batch.fail", startNanos);
            log.error("[WS BATCH SERIALIZE FAIL] roomId={} count={}",
                    roomId, messages.size(), e);
            return null;
        }
    }

    private Long sequenceOf(ChatMessageDto message) {
        return message.getSequence() != null ? message.getSequence() : message.getId();
    }

    public boolean sendControlToSession(String sessionId, Object payload, String payloadType) {
        WebSocketSession session = sessionsById.get(sessionId);
        if (session == null || !session.isOpen()) {
            return false;
        }

        long startNanos = System.nanoTime();
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            chatPipelineMetrics.recordStage("ws.control." + payloadType + ".send", startNanos);
            return true;
        } catch (Exception e) {
            chatPipelineMetrics.recordStage("ws.control." + payloadType + ".send.fail", startNanos);
            chatPipelineMetrics.incrementCounter("ws.control." + payloadType + ".send.fail");
            log.warn("[WS CONTROL SEND FAIL] sessionId={} type={}", sessionId, payloadType, e);
            return false;
        }
    }

    private int enqueueBroadcast(Long roomId,
                                 List<WebSocketSession> sessions,
                                 TextMessage textMessage,
                                 String payloadType,
                                 List<ChatMessageDto> messages) {
        long enqueueStartNanos = System.nanoTime();
        List<List<WebSocketSession>> laneSessions = new ArrayList<>(broadcastLaneCount);
        for (int i = 0; i < broadcastLaneCount; i++) {
            laneSessions.add(new ArrayList<>());
        }

        for (WebSocketSession session : sessions) {
            laneSessions.get(laneIndex(session)).add(session);
        }

        int taskCount = 0;
        int payloadBytes = textMessage.getPayload().getBytes(StandardCharsets.UTF_8).length;
        for (int laneIndex = 0; laneIndex < laneSessions.size(); laneIndex++) {
            List<WebSocketSession> laneBatch = laneSessions.get(laneIndex);
            if (laneBatch.isEmpty()) {
                continue;
            }
            BroadcastTask task = new BroadcastTask(
                    roomId,
                    laneIndex,
                    payloadType,
                    textMessage,
                    payloadBytes,
                    messages,
                    List.copyOf(laneBatch),
                    System.nanoTime()
            );
            enqueueLaneTask(task);
            taskCount++;
        }
        chatPipelineMetrics.recordStage("ws.broadcast.enqueue.total", enqueueStartNanos);
        chatPipelineMetrics.recordDistribution("openchat_pipeline_broadcast_lane_tasks", payloadType, taskCount);
        return taskCount;
    }

    private int laneIndex(WebSocketSession session) {
        String sessionId = session.getId();
        return Math.floorMod(sessionId == null ? 0 : sessionId.hashCode(), broadcastLaneCount);
    }

    private void enqueueLaneTask(BroadcastTask task) {
        ThreadPoolExecutor executor = broadcastLaneExecutors[task.laneIndex()];
        long enqueueStartNanos = System.nanoTime();
        try {
            executor.execute(() -> runBroadcastTask(task));
            chatPipelineMetrics.recordStage("ws.broadcast.lane.enqueue", enqueueStartNanos);
            chatPipelineMetrics.recordDistribution("openchat_pipeline_broadcast_lane_queue_size",
                    "lane-" + task.laneIndex(), executor.getQueue().size());
        } catch (RejectedExecutionException e) {
            chatPipelineMetrics.recordStage("ws.broadcast.lane.enqueue.fail", enqueueStartNanos);
            chatPipelineMetrics.incrementCounter("ws.broadcast.lane.enqueue.fail");
            log.warn("[WS BROADCAST LANE FULL] lane={} roomId={} sessions={}",
                    task.laneIndex(), task.roomId(), task.sessions().size());
            runBroadcastTask(task);
        }
    }

    private void runBroadcastTask(BroadcastTask task) {
        long startNanos = System.nanoTime();
        chatPipelineMetrics.recordStageNanos("ws.broadcast.lane.queue_wait", startNanos - task.enqueuedNanos());
        recordSinceCreatedForTask("ws.broadcast.lane_start.since_created", task);
        Set<WebSocketSession> deadSessions = ConcurrentHashMap.newKeySet();
        AtomicInteger successCount = new AtomicInteger(0);
        int logicalDeliveriesPerFrame = Math.max(1, task.messages().size());
        for (WebSocketSession session : task.sessions()) {
            sendToSingleSession(
                    task.roomId(),
                    session,
                    task.textMessage(),
                    task.payloadBytes(),
                    logicalDeliveriesPerFrame,
                    deadSessions,
                    successCount
            );
        }
        removeDeadSessions(task.roomId(), deadSessions);
        recordSinceCreatedForTask("ws.broadcast.lane_done.since_created", task);
        chatPipelineMetrics.recordStage("ws.broadcast.lane.worker.total", startNanos);
        chatPipelineMetrics.recordDistribution("openchat_pipeline_batch_sessions",
                "ws.lane." + task.payloadType(), task.sessions().size());

        log.debug("[WS BROADCAST LANE] roomId={} lane={} type={} sessions={} sent={} dead={}",
                task.roomId(), task.laneIndex(), task.payloadType(), task.sessions().size(),
                successCount.get(), deadSessions.size());
    }

    private void recordSinceCreatedForTask(String stage, BroadcastTask task) {
        for (ChatMessageDto message : task.messages()) {
            chatPipelineMetrics.recordSinceCreated(stage, message);
        }
    }

    private void sendToSingleSession(Long roomId,
                                     WebSocketSession session,
                                     TextMessage textMessage,
                                     int payloadBytes,
                                     int logicalDeliveries,
                                     Set<WebSocketSession> deadSessions,
                                     AtomicInteger successCount) {
        long sendStartNanos = System.nanoTime();
        chatPipelineMetrics.recordWebSocketSendAttempt(logicalDeliveries);
        try {
            if (!session.isOpen()) {
                chatPipelineMetrics.recordWebSocketSendFailure(logicalDeliveries, "closed_before_send", sendStartNanos);
                deadSessions.add(session);
                return;
            }
            session.sendMessage(textMessage);
            successCount.incrementAndGet();
            chatPipelineMetrics.recordWebSocketSendSuccess(logicalDeliveries, payloadBytes, sendStartNanos);
            chatPipelineMetrics.recordStage("ws.broadcast.lane.send.total", sendStartNanos);
        } catch (Exception e) {
            String reason = classifySendFailure(e);
            chatPipelineMetrics.recordWebSocketSendFailure(logicalDeliveries, reason, sendStartNanos);
            chatPipelineMetrics.recordStage("ws.broadcast.lane.send.fail", sendStartNanos);
            chatPipelineMetrics.incrementCounter("ws.broadcast.lane.send.fail");
            log.warn("[WS SEND FAIL] roomId={} sessionId={} reason={} exceptionClass={} message={} sessionOpen={}",
                    roomId, session.getId(), reason, e.getClass().getName(), e.getMessage(), session.isOpen());
            log.debug("[WS SEND FAIL TRACE] roomId={} sessionId={} reason={}", roomId, session.getId(), reason, e);
            deadSessions.add(session);
        }
    }

    private String classifySendFailure(Exception e) {
        if (e instanceof SessionLimitExceededException) {
            String message = lowerMessage(e);
            if (message.contains("send time")) {
                return "send_time_limit";
            }
            if (message.contains("buffer size")) {
                return "buffer_limit";
            }
        }
        if (isClosedSessionFailure(e)) {
            return "closed_during_send";
        }
        if (e instanceof IOException) {
            return "io_exception";
        }
        if (e instanceof IllegalStateException) {
            return "illegal_state";
        }
        return "unknown_exception";
    }

    private boolean isClosedSessionFailure(Exception e) {
        String message = lowerMessage(e);
        return message.contains("closed")
                || message.contains("close")
                || message.contains("broken pipe")
                || message.contains("connection reset")
                || message.contains("eof");
    }

    private String lowerMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }

    private void removeDeadSessions(Long roomId, Set<WebSocketSession> deadSessions) {
        for (WebSocketSession dead : deadSessions) {
            remove(roomId, dead);
        }
    }

    /**
     * 방 종료 시 해당 방의 모든 WebSocket 세션 강제 종료
     */
    public void closeAllSessionsInRoom(Long roomId) {
        Set<WebSocketSession> sessions = roomSessions.remove(roomId);
        if (sessions == null || sessions.isEmpty()) {
            log.debug("[WS CLOSE ALL] roomId={} - no sessions", roomId);
            return;
        }

        int closedCount = 0;
        for (WebSocketSession session : sessions) {
            sessionsById.remove(session.getId());
            sessionStatesById.remove(session.getId());
            if (closeSession(session, new CloseStatus(4001, "Room has been ended"), "[WS CLOSE FAIL]")) {
                closedCount++;
            }
        }
        roomTrafficMonitor.recordLeave(roomId, 0);

        log.info("[WS CLOSE ALL] roomId={} closed {} sessions", roomId, closedCount);
    }

    /**
     * 전체 WebSocket 세션 종료 (Graceful Shutdown용)
     */
    public void closeAllSessions() {
        int totalClosed = 0;
        for (Map.Entry<Long, Set<WebSocketSession>> entry : roomSessions.entrySet()) {
            Long roomId = entry.getKey();
            Set<WebSocketSession> sessions = entry.getValue();
            for (WebSocketSession session : sessions) {
                if (closeSession(session, new CloseStatus(1001, "Server shutting down"), "[WS SHUTDOWN CLOSE FAIL]")) {
                    totalClosed++;
                }
            }
            roomTrafficMonitor.recordLeave(roomId, 0);
        }
        roomSessions.clear();
        sessionsById.clear();
        sessionStatesById.clear();
        log.info("[WS SHUTDOWN] Closed {} sessions across all rooms", totalClosed);
    }

    private boolean closeSession(WebSocketSession session, CloseStatus status, String logPrefix) {
        try {
            if (!session.isOpen()) {
                return false;
            }
            session.close(status);
            return true;
        } catch (IOException e) {
            log.warn("{} sessionId={}", logPrefix, session.getId(), e);
            return false;
        }
    }

    public int getTotalSessionCount() {
        return roomSessions.values().stream().mapToInt(Set::size).sum();
    }

    public int getRoomCount() {
        return roomSessions.size();
    }

    private long nowMillis() {
        return System.currentTimeMillis();
    }

    @PreDestroy
    public void shutdownExecutor() {
        log.info("[WS REGISTRY] Shutting down broadcast lane executors");
        for (ThreadPoolExecutor executor : broadcastLaneExecutors) {
            executor.shutdown();
        }
        long timeoutPerLane = Math.max(1, broadcastShutdownTimeoutMillis / Math.max(1, broadcastLaneExecutors.length));
        for (ThreadPoolExecutor executor : broadcastLaneExecutors) {
            try {
                if (!executor.awaitTermination(timeoutPerLane, TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private record BroadcastTask(Long roomId,
                                 int laneIndex,
                                 String payloadType,
                                 TextMessage textMessage,
                                 int payloadBytes,
                                 List<ChatMessageDto> messages,
                                 List<WebSocketSession> sessions,
                                 long enqueuedNanos) {
    }

    private static final class RoomSessionState {
        private final Long roomId;
        private final Integer partitionId;
        private volatile boolean activeDeclared;
        private volatile Long lastSeenSequence;
        private volatile long lastActiveSignalAt;
        private volatile long lastControlAt;

        private RoomSessionState(Long roomId, Integer partitionId, boolean activeDeclared, long now) {
            this.roomId = roomId;
            this.partitionId = partitionId;
            this.activeDeclared = activeDeclared;
            this.lastActiveSignalAt = now;
            this.lastControlAt = now;
        }

        static RoomSessionState active(Long roomId, Integer partitionId, long now) {
            return new RoomSessionState(roomId, partitionId, true, now);
        }

        Long roomId() {
            return roomId;
        }

        void mark(boolean active, Long lastSeenSequence, long now) {
            this.activeDeclared = active;
            this.lastSeenSequence = lastSeenSequence;
            this.lastControlAt = now;
            if (active) {
                this.lastActiveSignalAt = now;
            }
        }

        boolean isActive(long now, long activeTtlMillis) {
            return activeDeclared && now - lastActiveSignalAt <= activeTtlMillis;
        }

        boolean matchesPartition(Integer requestedPartitionId) {
            return requestedPartitionId == null
                    || (partitionId != null && partitionId.equals(requestedPartitionId));
        }
    }
}
