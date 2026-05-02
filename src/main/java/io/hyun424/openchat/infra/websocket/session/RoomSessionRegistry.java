package io.hyun424.openchat.infra.websocket.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.metrics.ChatPipelineMetrics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class RoomSessionRegistry {

    private final ObjectMapper objectMapper;
    private final ChatPipelineMetrics chatPipelineMetrics;
    private final List<BroadcastLane> broadcastLanes;
    private final AtomicBoolean acceptingBroadcasts = new AtomicBoolean(true);

    private final ConcurrentMap<Long, Set<WebSocketSession>> roomSessions =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WebSocketSession> sessionsById =
            new ConcurrentHashMap<>();

    private final AtomicBoolean acceptingConnections = new AtomicBoolean(true);

    private static final int SEND_TIME_LIMIT_MS = 5000;
    private static final int BUFFER_SIZE_LIMIT = 64 * 1024;

    public RoomSessionRegistry(ObjectMapper objectMapper,
                               ChatPipelineMetrics chatPipelineMetrics,
                               @Value("${app.websocket.broadcast.lanes:8}") int laneCount,
                               @Value("${app.websocket.broadcast.queue-capacity:10000}") int queueCapacity,
                               @Value("${app.websocket.broadcast.shutdown-timeout-ms:5000}") long shutdownTimeoutMillis) {
        if (laneCount <= 0) {
            throw new IllegalArgumentException("laneCount must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        if (shutdownTimeoutMillis <= 0) {
            throw new IllegalArgumentException("shutdownTimeoutMillis must be positive");
        }
        this.objectMapper = objectMapper;
        this.chatPipelineMetrics = chatPipelineMetrics;
        this.broadcastLanes = createBroadcastLanes(laneCount, queueCapacity, shutdownTimeoutMillis);
        this.broadcastLanes.forEach(BroadcastLane::start);
    }

    public boolean isAcceptingConnections() {
        return acceptingConnections.get();
    }

    public void stopAcceptingConnections() {
        acceptingConnections.set(false);
        log.info("[WS REGISTRY] Stopped accepting new connections");
    }

    public void add(Long roomId, WebSocketSession session) {
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
        roomSessions
                .computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet())
                .add(decorated);
    }

    public void remove(Long roomId, WebSocketSession session) {
        Set<WebSocketSession> set = roomSessions.get(roomId);
        if (set != null) {
            WebSocketSession storedSession = sessionsById.remove(session.getId());
            set.remove(storedSession != null ? storedSession : session);
            if (set.isEmpty()) {
                roomSessions.remove(roomId);
            }
        }
    }

    public Set<WebSocketSession> getSessions(Long roomId) {
        return roomSessions.getOrDefault(roomId, Collections.emptySet());
    }

    public int count(Long roomId) {
        return getSessions(roomId).size();
    }

    /**
     * 방에 연결된 세션으로 메시지를 병렬 전송한다.
     * 전송 중 Set을 직접 수정하면 살아있는 세션을 건너뛸 수 있으므로, 죽은 세션은 전송이 끝난 뒤 정리한다.
     */
    public void sendToRoom(Long roomId, ChatMessageDto message) {
        Set<WebSocketSession> sessions = getSessions(roomId);

        if (sessions.isEmpty()) {
            log.debug("[WS BROADCAST] roomId={} - no sessions", roomId);
            return;
        }

        chatPipelineMetrics.recordSinceCreated("ws.broadcast.enter.since_created", message);
        chatPipelineMetrics.recordSummary("openchat_ws_broadcast_sessions", sessions.size());
        long broadcastStartNanos = System.nanoTime();
        TextMessage textMessage = serializeMessage(roomId, message);
        if (textMessage == null) {
            return;
        }

        enqueueLaneBroadcasts(roomId, message, textMessage, new ArrayList<>(sessions));
        chatPipelineMetrics.recordStageNanos("ws.broadcast.total", System.nanoTime() - broadcastStartNanos);

        log.debug("[WS BROADCAST] roomId={} messageId={} sessions={}",
                roomId, message.getMessageId(), sessions.size());
    }

    private TextMessage serializeMessage(Long roomId, ChatMessageDto message) {
        long startNanos = System.nanoTime();
        try {
            TextMessage textMessage = new TextMessage(objectMapper.writeValueAsString(message));
            chatPipelineMetrics.recordStageNanos("ws.serialize", System.nanoTime() - startNanos);
            return textMessage;
        } catch (Exception e) {
            chatPipelineMetrics.recordStageNanos("ws.serialize.fail", System.nanoTime() - startNanos);
            log.error("[WS SERIALIZE FAIL] roomId={} messageId={}",
                    roomId, message.getMessageId(), e);
            return null;
        }
    }

    private void enqueueLaneBroadcasts(Long roomId,
                                       ChatMessageDto message,
                                       TextMessage textMessage,
                                       List<WebSocketSession> sessions) {
        List<List<WebSocketSession>> sessionsByLane = new ArrayList<>(broadcastLanes.size());
        for (int i = 0; i < broadcastLanes.size(); i++) {
            sessionsByLane.add(new ArrayList<>());
        }
        for (WebSocketSession session : sessions) {
            sessionsByLane.get(laneIndexFor(session.getId())).add(session);
        }

        for (int lane = 0; lane < sessionsByLane.size(); lane++) {
            List<WebSocketSession> laneSessions = sessionsByLane.get(lane);
            if (laneSessions.isEmpty()) {
                continue;
            }
            BroadcastTask task = new BroadcastTask(
                    roomId, message, textMessage, List.copyOf(laneSessions), System.nanoTime());
            broadcastLanes.get(lane).enqueueOrRun(task);
        }
    }

    int laneIndexFor(String sessionId) {
        return Math.floorMod(sessionId.hashCode(), broadcastLanes.size());
    }

    private List<BroadcastLane> createBroadcastLanes(int laneCount,
                                                     int queueCapacity,
                                                     long shutdownTimeoutMillis) {
        List<BroadcastLane> lanes = new ArrayList<>(laneCount);
        for (int i = 0; i < laneCount; i++) {
            BlockingQueue<BroadcastTask> queue = new LinkedBlockingQueue<>(queueCapacity);
            chatPipelineMetrics.bindBroadcastLaneQueue(i, queue);
            lanes.add(new BroadcastLane(i, queue, shutdownTimeoutMillis));
        }
        return lanes;
    }

    private void sendToSingleSession(Long roomId, WebSocketSession session, TextMessage textMessage) {
        long startNanos = System.nanoTime();
        try {
            if (!session.isOpen()) {
                remove(roomId, session);
                return;
            }
            session.sendMessage(textMessage);
        } catch (Exception e) {
            chatPipelineMetrics.recordStageNanos("ws.broadcast.lane.send.fail", System.nanoTime() - startNanos);
            chatPipelineMetrics.incrementCounter("openchat_ws_broadcast_lane_send_fail_total");
            log.warn("[WS SEND FAIL] roomId={} sessionId={}", roomId, session.getId(), e);
            remove(roomId, session);
        } finally {
            chatPipelineMetrics.recordStageNanos("ws.broadcast.lane.send.total", System.nanoTime() - startNanos);
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
            if (closeSession(session, new CloseStatus(4001, "Room has been ended"), "[WS CLOSE FAIL]")) {
                closedCount++;
            }
        }

        log.info("[WS CLOSE ALL] roomId={} closed {} sessions", roomId, closedCount);
    }

    /**
     * 전체 WebSocket 세션 종료 (Graceful Shutdown용)
     */
    public void closeAllSessions() {
        int totalClosed = 0;
        for (Map.Entry<Long, Set<WebSocketSession>> entry : roomSessions.entrySet()) {
            Set<WebSocketSession> sessions = entry.getValue();
            for (WebSocketSession session : sessions) {
                if (closeSession(session, new CloseStatus(1001, "Server shutting down"), "[WS SHUTDOWN CLOSE FAIL]")) {
                    totalClosed++;
                }
            }
        }
        roomSessions.clear();
        sessionsById.clear();
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

    @PreDestroy
    public void shutdownExecutor() {
        if (!acceptingBroadcasts.compareAndSet(true, false)) {
            return;
        }
        log.info("[WS REGISTRY] Shutting down broadcast lanes");
        broadcastLanes.forEach(BroadcastLane::stopAccepting);
        long deadline = System.currentTimeMillis() + broadcastLanes.stream()
                .mapToLong(BroadcastLane::shutdownTimeoutMillis)
                .max()
                .orElse(5_000L);
        for (BroadcastLane lane : broadcastLanes) {
            long remainingMillis = Math.max(0, deadline - System.currentTimeMillis());
            lane.awaitStop(remainingMillis);
        }
        broadcastLanes.forEach(BroadcastLane::forceStop);
    }

    private final class BroadcastLane implements Runnable {

        private final int lane;
        private final BlockingQueue<BroadcastTask> queue;
        private final Thread thread;
        private final long shutdownTimeoutMillis;

        private BroadcastLane(int lane,
                              BlockingQueue<BroadcastTask> queue,
                              long shutdownTimeoutMillis) {
            this.lane = lane;
            this.queue = queue;
            this.shutdownTimeoutMillis = shutdownTimeoutMillis;
            this.thread = new Thread(this, "ws-broadcast-lane-" + lane);
            this.thread.setDaemon(true);
        }

        private void start() {
            thread.start();
        }

        private long shutdownTimeoutMillis() {
            return shutdownTimeoutMillis;
        }

        private void enqueueOrRun(BroadcastTask task) {
            long enqueueStartNanos = System.nanoTime();
            if (acceptingBroadcasts.get() && queue.offer(task)) {
                chatPipelineMetrics.recordStageNanos(
                        "ws.broadcast.lane.enqueue", System.nanoTime() - enqueueStartNanos);
                return;
            }

            chatPipelineMetrics.recordStageNanos(
                    "ws.broadcast.lane.enqueue.fail", System.nanoTime() - enqueueStartNanos);
            chatPipelineMetrics.incrementCounter("openchat_ws_broadcast_lane_enqueue_fail_total");
            log.warn("[WS BROADCAST LANE FULL] lane={} roomId={} messageId={} sessions={}",
                    lane, task.roomId(), task.message().getMessageId(), task.sessions().size());
            process(task);
        }

        private void stopAccepting() {
            thread.interrupt();
        }

        private void awaitStop(long timeoutMillis) {
            try {
                thread.join(timeoutMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void forceStop() {
            if (thread.isAlive()) {
                log.warn("[WS BROADCAST LANE] lane={} did not stop gracefully", lane);
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            while (acceptingBroadcasts.get() || !queue.isEmpty()) {
                try {
                    BroadcastTask task = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        process(task);
                    }
                } catch (InterruptedException e) {
                    if (acceptingBroadcasts.get()) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } catch (Exception e) {
                    log.error("[WS BROADCAST LANE] lane={} task failed", lane, e);
                }
            }
        }

        private void process(BroadcastTask task) {
            chatPipelineMetrics.recordStageNanos(
                    "ws.broadcast.lane.queue_wait", System.nanoTime() - task.enqueuedNanos());
            long workerStartNanos = System.nanoTime();
            for (WebSocketSession session : task.sessions()) {
                sendToSingleSession(task.roomId(), session, task.textMessage());
            }
            chatPipelineMetrics.recordStageNanos(
                    "ws.broadcast.lane.worker.total", System.nanoTime() - workerStartNanos);
        }
    }

    private record BroadcastTask(Long roomId,
                                 ChatMessageDto message,
                                 TextMessage textMessage,
                                 List<WebSocketSession> sessions,
                                 long enqueuedNanos) {
    }
}
