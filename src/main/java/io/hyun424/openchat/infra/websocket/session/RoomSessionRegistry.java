package io.hyun424.openchat.infra.websocket.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.metrics.ChatPipelineMetrics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
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
    private final ThreadPoolExecutor broadcastExecutor;
    private final ChatPipelineMetrics chatPipelineMetrics;

    private final ConcurrentMap<Long, Set<WebSocketSession>> roomSessions =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WebSocketSession> sessionsById =
            new ConcurrentHashMap<>();

    private final AtomicBoolean acceptingConnections = new AtomicBoolean(true);

    private static final int SEND_TIME_LIMIT_MS = 5000;
    private static final int BUFFER_SIZE_LIMIT = 64 * 1024;
    private static final int BROADCAST_POOL_CORE = 4;
    private static final int BROADCAST_POOL_MAX = 32;

    public RoomSessionRegistry(ObjectMapper objectMapper,
                               ChatPipelineMetrics chatPipelineMetrics) {
        this.objectMapper = objectMapper;
        this.chatPipelineMetrics = chatPipelineMetrics;
        AtomicInteger threadCounter = new AtomicInteger(0);
        this.broadcastExecutor = new ThreadPoolExecutor(
                BROADCAST_POOL_CORE, BROADCAST_POOL_MAX,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2048),
                r -> {
                    Thread t = new Thread(r, "ws-broadcast-" + threadCounter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        chatPipelineMetrics.bindBroadcastExecutor(broadcastExecutor);
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
        recordBroadcastExecutorSnapshot("before");
        long broadcastStartNanos = System.nanoTime();
        TextMessage textMessage = serializeMessage(roomId, message);
        if (textMessage == null) {
            return;
        }

        Set<WebSocketSession> deadSessions = ConcurrentHashMap.newKeySet();
        AtomicInteger successCount = new AtomicInteger(0);

        waitAllSends(roomId, sessions, textMessage, deadSessions, successCount);
        removeDeadSessions(roomId, deadSessions);
        chatPipelineMetrics.recordStageNanos("ws.broadcast.total", System.nanoTime() - broadcastStartNanos);

        log.debug("[WS BROADCAST] roomId={} messageId={} sent={} dead={}",
                roomId, message.getMessageId(), successCount.get(), deadSessions.size());
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

    private void waitAllSends(Long roomId,
                              Set<WebSocketSession> sessions,
                              TextMessage textMessage,
                              Set<WebSocketSession> deadSessions,
                              AtomicInteger successCount) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (WebSocketSession session : sessions) {
            futures.add(CompletableFuture.runAsync(
                    () -> sendToSingleSession(roomId, session, textMessage, deadSessions, successCount),
                    broadcastExecutor));
        }
        recordBroadcastExecutorSnapshot("after_schedule");
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        recordBroadcastExecutorSnapshot("after_complete");
    }

    private void recordBroadcastExecutorSnapshot(String phase) {
        chatPipelineMetrics.recordSummary(
                "openchat_ws_broadcast_executor_queue_" + phase,
                broadcastExecutor.getQueue().size());
        chatPipelineMetrics.recordSummary(
                "openchat_ws_broadcast_executor_active_" + phase,
                broadcastExecutor.getActiveCount());
        chatPipelineMetrics.recordSummary(
                "openchat_ws_broadcast_executor_pool_" + phase,
                broadcastExecutor.getPoolSize());
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
