package io.hyun424.openchat.infra.websocket.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatBatchMessageDto;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private final ExecutorService broadcastExecutor;
    private final int broadcastPoolSize;

    private final ConcurrentMap<Long, Set<WebSocketSession>> roomSessions =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WebSocketSession> sessionsById =
            new ConcurrentHashMap<>();

    private final AtomicBoolean acceptingConnections = new AtomicBoolean(true);

    private static final int SEND_TIME_LIMIT_MS = 5000;
    private static final int BUFFER_SIZE_LIMIT = 64 * 1024;
    private static final int DEFAULT_BROADCAST_QUEUE_CAPACITY = 4096;

    public RoomSessionRegistry(ObjectMapper objectMapper) {
        this(objectMapper, new RoomTrafficMonitor(), ChatPipelineMetrics.noop(), 0, DEFAULT_BROADCAST_QUEUE_CAPACITY);
    }

    public RoomSessionRegistry(ObjectMapper objectMapper,
                               int configuredPoolSize,
                               int queueCapacity) {
        this(objectMapper, new RoomTrafficMonitor(), ChatPipelineMetrics.noop(), configuredPoolSize, queueCapacity);
    }

    public RoomSessionRegistry(ObjectMapper objectMapper,
                               RoomTrafficMonitor roomTrafficMonitor,
                               int configuredPoolSize,
                               int queueCapacity) {
        this(objectMapper, roomTrafficMonitor, ChatPipelineMetrics.noop(), configuredPoolSize, queueCapacity);
    }

    @Autowired
    public RoomSessionRegistry(ObjectMapper objectMapper,
                               RoomTrafficMonitor roomTrafficMonitor,
                               ChatPipelineMetrics chatPipelineMetrics,
                               @Value("${app.websocket.broadcast.pool-size:0}") int configuredPoolSize,
                               @Value("${app.websocket.broadcast.queue-capacity:4096}") int queueCapacity) {
        this.objectMapper = objectMapper;
        this.roomTrafficMonitor = roomTrafficMonitor;
        this.chatPipelineMetrics = chatPipelineMetrics;
        this.broadcastPoolSize = resolveBroadcastPoolSize(configuredPoolSize);
        int boundedQueueCapacity = Math.max(1, queueCapacity);
        AtomicInteger threadCounter = new AtomicInteger(0);
        this.broadcastExecutor = new ThreadPoolExecutor(
                broadcastPoolSize, broadcastPoolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(boundedQueueCapacity),
                r -> {
                    Thread t = new Thread(r, "ws-broadcast-" + threadCounter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    private int resolveBroadcastPoolSize(int configuredPoolSize) {
        if (configuredPoolSize > 0) {
            return configuredPoolSize;
        }
        return Math.max(4, Runtime.getRuntime().availableProcessors() * 4);
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
        roomTrafficMonitor.recordJoin(roomId, count(roomId));
    }

    public void remove(Long roomId, WebSocketSession session) {
        Set<WebSocketSession> set = roomSessions.get(roomId);
        if (set != null) {
            WebSocketSession storedSession = sessionsById.remove(session.getId());
            set.remove(storedSession != null ? storedSession : session);
            if (set.isEmpty()) {
                roomSessions.remove(roomId);
            }
            roomTrafficMonitor.recordLeave(roomId, count(roomId));
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
        long broadcastStartNanos = System.nanoTime();
        Set<WebSocketSession> sessions = getSessions(roomId);

        if (sessions.isEmpty()) {
            log.debug("[WS BROADCAST] roomId={} - no sessions", roomId);
            return;
        }

        TextMessage textMessage = serializeMessage(roomId, message);
        if (textMessage == null) {
            return;
        }

        Set<WebSocketSession> deadSessions = ConcurrentHashMap.newKeySet();
        AtomicInteger successCount = new AtomicInteger(0);

        List<WebSocketSession> sessionSnapshot = new ArrayList<>(sessions);
        if (sessionSnapshot.isEmpty()) {
            return;
        }
        roomTrafficMonitor.recordOutboundFanout(roomId, sessionSnapshot.size());
        Long createdAt = message.getCreatedAt();
        if (createdAt != null) {
            roomTrafficMonitor.recordDeliveryLag(roomId, createdAt);
        }
        waitAllSends(roomId, sessionSnapshot, textMessage, deadSessions, successCount);
        removeDeadSessions(roomId, deadSessions);
        chatPipelineMetrics.recordStage("ws.broadcast.single.total", broadcastStartNanos);

        log.debug("[WS BROADCAST] roomId={} messageId={} sent={} dead={}",
                roomId, message.getMessageId(), successCount.get(), deadSessions.size());
    }

    /**
     * 같은 방의 여러 논리 메시지를 하나의 WebSocket frame으로 전송한다.
     * 단건 payload 호환성을 위해 메시지가 1개뿐이면 기존 단건 경로를 사용한다.
     */
    public void sendBatchToRoom(Long roomId, List<ChatMessageDto> messages) {
        long broadcastStartNanos = System.nanoTime();
        if (messages == null || messages.isEmpty()) {
            return;
        }
        if (messages.size() == 1) {
            sendToRoom(roomId, messages.get(0));
            return;
        }

        Set<WebSocketSession> sessions = getSessions(roomId);
        if (sessions.isEmpty()) {
            log.debug("[WS BATCH BROADCAST] roomId={} count={} - no sessions", roomId, messages.size());
            return;
        }

        TextMessage textMessage = serializeBatchMessage(roomId, messages);
        if (textMessage == null) {
            return;
        }

        Set<WebSocketSession> deadSessions = ConcurrentHashMap.newKeySet();
        AtomicInteger successCount = new AtomicInteger(0);

        List<WebSocketSession> sessionSnapshot = new ArrayList<>(sessions);
        if (sessionSnapshot.isEmpty()) {
            return;
        }
        roomTrafficMonitor.recordOutboundFanout(roomId, sessionSnapshot.size() * messages.size());
        for (ChatMessageDto message : messages) {
            Long createdAt = message.getCreatedAt();
            if (createdAt != null) {
                roomTrafficMonitor.recordDeliveryLag(roomId, createdAt);
            }
        }

        waitAllSends(roomId, sessionSnapshot, textMessage, deadSessions, successCount);
        removeDeadSessions(roomId, deadSessions);
        chatPipelineMetrics.recordStage("ws.broadcast.batch.total", broadcastStartNanos);
        chatPipelineMetrics.recordDistribution("openchat_pipeline_batch_size", "ws.broadcast", messages.size());
        chatPipelineMetrics.recordDistribution("openchat_pipeline_batch_sessions", "ws.broadcast", sessionSnapshot.size());

        log.debug("[WS BATCH BROADCAST] roomId={} count={} sent={} dead={}",
                roomId, messages.size(), successCount.get(), deadSessions.size());
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
        long startNanos = System.nanoTime();
        try {
            TextMessage textMessage = new TextMessage(objectMapper.writeValueAsString(ChatBatchMessageDto.from(roomId, messages)));
            chatPipelineMetrics.recordStage("ws.serialize.batch", startNanos);
            return textMessage;
        } catch (Exception e) {
            chatPipelineMetrics.recordStage("ws.serialize.batch.fail", startNanos);
            log.error("[WS BATCH SERIALIZE FAIL] roomId={} count={}",
                    roomId, messages.size(), e);
            return null;
        }
    }

    private void waitAllSends(Long roomId,
                              List<WebSocketSession> sessions,
                              TextMessage textMessage,
                              Set<WebSocketSession> deadSessions,
                              AtomicInteger successCount) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        int batchSize = calculateBatchSize(sessions.size());
        for (int start = 0; start < sessions.size(); start += batchSize) {
            int end = Math.min(start + batchSize, sessions.size());
            List<WebSocketSession> batch = sessions.subList(start, end);
            futures.add(CompletableFuture.runAsync(
                    () -> sendBatch(roomId, batch, textMessage, deadSessions, successCount),
                    broadcastExecutor));
        }
        long waitStartNanos = System.nanoTime();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        chatPipelineMetrics.recordStage("ws.broadcast.wait_all", waitStartNanos);
    }

    private int calculateBatchSize(int sessionCount) {
        int workerCount = Math.min(broadcastPoolSize, sessionCount);
        return Math.max(1, (int) Math.ceil((double) sessionCount / workerCount));
    }

    private void sendBatch(Long roomId,
                           List<WebSocketSession> sessions,
                           TextMessage textMessage,
                           Set<WebSocketSession> deadSessions,
                           AtomicInteger successCount) {
        long startNanos = System.nanoTime();
        for (WebSocketSession session : sessions) {
            sendToSingleSession(roomId, session, textMessage, deadSessions, successCount);
        }
        chatPipelineMetrics.recordStage("ws.broadcast.worker_batch", startNanos);
        chatPipelineMetrics.recordDistribution("openchat_pipeline_batch_sessions", "ws.worker", sessions.size());
    }

    private void sendToSingleSession(Long roomId,
                                     WebSocketSession session,
                                     TextMessage textMessage,
                                     Set<WebSocketSession> deadSessions,
                                     AtomicInteger successCount) {
        try {
            if (!session.isOpen()) {
                deadSessions.add(session);
                return;
            }
            session.sendMessage(textMessage);
            successCount.incrementAndGet();
        } catch (Exception e) {
            chatPipelineMetrics.incrementCounter("ws.send.fail");
            log.warn("[WS SEND FAIL] roomId={} sessionId={}", roomId, session.getId(), e);
            deadSessions.add(session);
        }
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
        log.info("[WS REGISTRY] Shutting down broadcast executor");
        broadcastExecutor.shutdown();
        try {
            if (!broadcastExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                broadcastExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            broadcastExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
