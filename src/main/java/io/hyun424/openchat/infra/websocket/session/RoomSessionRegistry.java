package io.hyun424.openchat.infra.websocket.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
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
    private final ExecutorService broadcastExecutor;

    private final ConcurrentMap<Long, Set<WebSocketSession>> roomSessions =
            new ConcurrentHashMap<>();

    private final AtomicBoolean acceptingConnections = new AtomicBoolean(true);

    private static final int SEND_TIME_LIMIT_MS = 5000;
    private static final int BUFFER_SIZE_LIMIT = 64 * 1024;
    private static final int BROADCAST_POOL_CORE = 4;
    private static final int BROADCAST_POOL_MAX = 32;

    public RoomSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
            try {
                session.close(new CloseStatus(1001, "Server shutting down"));
            } catch (IOException e) {
                log.warn("[WS REJECT] Failed to close rejected session", e);
            }
            return;
        }
        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        roomSessions
                .computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet())
                .add(decorated);
    }

    public void remove(Long roomId, WebSocketSession session) {
        Set<WebSocketSession> set = roomSessions.get(roomId);
        if (set != null) {
            set.remove(session);
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
     * Broadcast message to all sessions in a room using parallel sends.
     * Dead sessions are collected and removed AFTER iteration to avoid
     * ConcurrentModification issues that could skip live sessions.
     */
    public void sendToRoom(Long roomId, ChatMessageDto message) {
        Set<WebSocketSession> sessions = getSessions(roomId);

        if (sessions.isEmpty()) {
            log.debug("[WS BROADCAST] roomId={} - no sessions", roomId);
            return;
        }

        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("[WS SERIALIZE FAIL] roomId={} messageId={}",
                    roomId, message.getMessageId(), e);
            return;
        }

        TextMessage textMessage = new TextMessage(payload);
        Set<WebSocketSession> deadSessions = ConcurrentHashMap.newKeySet();
        AtomicInteger successCount = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (WebSocketSession session : sessions) {
            futures.add(CompletableFuture.runAsync(() -> {
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
            }, broadcastExecutor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Clean up dead sessions after all sends complete
        for (WebSocketSession dead : deadSessions) {
            remove(roomId, dead);
        }

        log.debug("[WS BROADCAST] roomId={} messageId={} sent={} dead={}",
                roomId, message.getMessageId(), successCount.get(), deadSessions.size());
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
            try {
                if (session.isOpen()) {
                    session.close(new CloseStatus(4001, "Room has been ended"));
                    closedCount++;
                }
            } catch (IOException e) {
                log.warn("[WS CLOSE FAIL] roomId={} sessionId={}", roomId, session.getId(), e);
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
            Long roomId = entry.getKey();
            Set<WebSocketSession> sessions = entry.getValue();
            for (WebSocketSession session : sessions) {
                try {
                    if (session.isOpen()) {
                        session.close(new CloseStatus(1001, "Server shutting down"));
                        totalClosed++;
                    }
                } catch (IOException e) {
                    log.warn("[WS SHUTDOWN CLOSE FAIL] roomId={} sessionId={}", roomId, session.getId(), e);
                }
            }
        }
        roomSessions.clear();
        log.info("[WS SHUTDOWN] Closed {} sessions across all rooms", totalClosed);
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
