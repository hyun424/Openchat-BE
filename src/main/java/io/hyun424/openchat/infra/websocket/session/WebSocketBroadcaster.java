package io.hyun424.openchat.infra.websocket.session;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class WebSocketBroadcaster {

    private static final CloseStatus BROADCAST_QUEUE_OVERLOADED = new CloseStatus(1013, "broadcast_queue_overloaded");

    private final RoomSessionStore store;
    private final SessionStateTracker stateTracker;
    private final WebSocketPayloadSerializer serializer;
    private final BroadcastLaneExecutor laneExecutor;
    private final WebSocketSendFailureClassifier failureClassifier;
    private final RoomTrafficMonitor roomTrafficMonitor;
    private final ChatPipelineMetrics chatPipelineMetrics;
    private final RoomPartitionMetrics roomPartitionMetrics;

    public WebSocketBroadcaster(RoomSessionStore store,
                                SessionStateTracker stateTracker,
                                WebSocketPayloadSerializer serializer,
                                BroadcastLaneExecutor laneExecutor,
                                WebSocketSendFailureClassifier failureClassifier,
                                RoomTrafficMonitor roomTrafficMonitor,
                                ChatPipelineMetrics chatPipelineMetrics,
                                RoomPartitionMetrics roomPartitionMetrics) {
        this.store = store;
        this.stateTracker = stateTracker;
        this.serializer = serializer;
        this.laneExecutor = laneExecutor;
        this.failureClassifier = failureClassifier;
        this.roomTrafficMonitor = roomTrafficMonitor;
        this.chatPipelineMetrics = chatPipelineMetrics;
        this.roomPartitionMetrics = roomPartitionMetrics;
    }

    public void sendToRoom(Long roomId, ChatMessageDto message) {
        sendToRoom(roomId, null, message);
    }

    public void sendToRoom(Long roomId, Integer partitionId, ChatMessageDto message) {
        long broadcastStartNanos = System.nanoTime();
        Set<WebSocketSession> sessions = store.sessions(roomId);
        if (sessions.isEmpty()) {
            log.debug("[WS BROADCAST] roomId={} - no sessions", roomId);
            return;
        }

        List<WebSocketSession> sessionSnapshot = activeSessionSnapshot(roomId, partitionId, sessions, 1);
        if (sessionSnapshot.isEmpty()) {
            return;
        }

        TextMessage textMessage = serializer.serializeMessage(roomId, message);
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

        Set<WebSocketSession> sessions = store.sessions(roomId);
        if (sessions.isEmpty()) {
            log.debug("[WS BATCH BROADCAST] roomId={} count={} - no sessions", roomId, messages.size());
            return;
        }

        List<WebSocketSession> sessionSnapshot = activeSessionSnapshot(roomId, partitionId, sessions, messages.size());
        if (sessionSnapshot.isEmpty()) {
            return;
        }

        TextMessage textMessage = serializer.serializeBatchMessage(roomId, messages, realtimeComplete, omittedCount, lastSequence);
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

    public boolean sendControlToSession(String sessionId, Object payload, String payloadType) {
        WebSocketSession session = store.sessionById(sessionId);
        if (session == null || !session.isOpen()) {
            return false;
        }

        TextMessage textMessage = serializer.serializeControl(sessionId, payload, payloadType);
        if (textMessage == null) {
            return false;
        }

        long startNanos = System.nanoTime();
        try {
            session.sendMessage(textMessage);
            chatPipelineMetrics.recordStage("ws.control." + payloadType + ".send", startNanos);
            return true;
        } catch (Exception e) {
            chatPipelineMetrics.recordStage("ws.control." + payloadType + ".send.fail", startNanos);
            chatPipelineMetrics.incrementCounter("ws.control." + payloadType + ".send.fail");
            log.warn("[WS CONTROL SEND FAIL] sessionId={} type={}", sessionId, payloadType, e);
            return false;
        }
    }

    private List<WebSocketSession> activeSessionSnapshot(Long roomId,
                                                         Integer partitionId,
                                                         Set<WebSocketSession> sessions,
                                                         int logicalMessagesPerSession) {
        List<WebSocketSession> activeSessions = new ArrayList<>(sessions.size());
        int passiveSessions = 0;
        long now = System.currentTimeMillis();
        for (WebSocketSession session : sessions) {
            if (!stateTracker.matchesPartition(session, partitionId)) {
                continue;
            }
            if (stateTracker.isActiveSession(session, now)) {
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

    private int enqueueBroadcast(Long roomId,
                                 List<WebSocketSession> sessions,
                                 TextMessage textMessage,
                                 String payloadType,
                                 List<ChatMessageDto> messages) {
        long enqueueStartNanos = System.nanoTime();
        List<List<WebSocketSession>> laneSessions = new ArrayList<>(laneExecutor.laneCount());
        for (int i = 0; i < laneExecutor.laneCount(); i++) {
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
            if (laneExecutor.enqueue(task, this::runBroadcastTask)) {
                taskCount++;
            } else {
                closeOverloadedSessions(task);
            }
        }
        chatPipelineMetrics.recordStage("ws.broadcast.enqueue.total", enqueueStartNanos);
        chatPipelineMetrics.recordDistribution("openchat_pipeline_broadcast_lane_tasks", payloadType, taskCount);
        return taskCount;
    }

    private void closeOverloadedSessions(BroadcastTask task) {
        chatPipelineMetrics.incrementCounter("ws.broadcast.lane.overload.resync_required", task.sessions().size());
        if (!task.sessions().isEmpty()) {
            log.warn("[WS BROADCAST LANE OVERLOAD] roomId={} lane={} type={} sessions={} messages={} - closing sessions for reconnect/resync",
                    task.roomId(), task.laneIndex(), task.payloadType(), task.sessions().size(), task.messages().size());
        }

        int closed = 0;
        int closeFailed = 0;
        Set<WebSocketSession> overloadedSessions = ConcurrentHashMap.newKeySet();
        for (WebSocketSession session : task.sessions()) {
            if (store.closeSession(session, BROADCAST_QUEUE_OVERLOADED, "[WS BROADCAST OVERLOAD CLOSE FAIL]")) {
                closed++;
            } else {
                closeFailed++;
            }
            overloadedSessions.add(session);
        }
        chatPipelineMetrics.incrementCounter("ws.broadcast.lane.overload.sessions_closed", closed);
        if (closeFailed > 0) {
            chatPipelineMetrics.incrementCounter("ws.broadcast.lane.overload.close_failed", closeFailed);
        }
        removeDeadSessions(task.roomId(), overloadedSessions);
    }

    private int laneIndex(WebSocketSession session) {
        String sessionId = session.getId();
        return Math.floorMod(sessionId == null ? 0 : sessionId.hashCode(), laneExecutor.laneCount());
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
            String reason = failureClassifier.classify(e);
            chatPipelineMetrics.recordWebSocketSendFailure(logicalDeliveries, reason, sendStartNanos);
            chatPipelineMetrics.recordStage("ws.broadcast.lane.send.fail", sendStartNanos);
            chatPipelineMetrics.incrementCounter("ws.broadcast.lane.send.fail");
            log.warn("[WS SEND FAIL] roomId={} sessionId={} reason={} exceptionClass={} message={} sessionOpen={}",
                    roomId, session.getId(), reason, e.getClass().getName(), e.getMessage(), session.isOpen());
            log.debug("[WS SEND FAIL TRACE] roomId={} sessionId={} reason={}", roomId, session.getId(), reason, e);
            deadSessions.add(session);
        }
    }

    private void removeDeadSessions(Long roomId, Set<WebSocketSession> deadSessions) {
        for (WebSocketSession dead : deadSessions) {
            store.closeSession(dead, CloseStatus.SESSION_NOT_RELIABLE, "[WS DEAD SESSION CLOSE FAIL]");
            RoomSessionStore.RemoveResult result = store.remove(roomId, dead);
            stateTracker.remove(result.sessionId());
            roomTrafficMonitor.recordLeave(roomId, result.remainingRoomCount());
        }
    }
}
