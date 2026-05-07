package io.hyun424.openchat.infra.websocket.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public class RoomSessionStore {

    private static final int SEND_TIME_LIMIT_MS = 5000;
    private static final int BUFFER_SIZE_LIMIT = 64 * 1024;

    private final ConcurrentMap<Long, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WebSocketSession> sessionsById = new ConcurrentHashMap<>();
    private final Set<Long> closingRooms = ConcurrentHashMap.newKeySet();

    public WebSocketSession add(Long roomId, WebSocketSession session) {
        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        if (closingRooms.contains(roomId)) {
            closeSession(decorated, new CloseStatus(1001, "Room closing"), "[WS ADD REJECT]");
            return null;
        }
        sessionsById.put(session.getId(), decorated);
        Set<WebSocketSession> set = roomSessions.computeIfAbsent(roomId, ignored -> ConcurrentHashMap.newKeySet());
        set.add(decorated);
        if (closingRooms.contains(roomId)) {
            set.remove(decorated);
            if (set.isEmpty()) {
                roomSessions.remove(roomId, set);
            }
            sessionsById.remove(session.getId());
            closeSession(decorated, new CloseStatus(1001, "Room closing"), "[WS ADD REJECT]");
            return null;
        }
        return decorated;
    }

    public RemoveResult remove(Long roomId, WebSocketSession session) {
        Set<WebSocketSession> set = roomSessions.get(roomId);
        if (set == null) {
            return new RemoveResult(session.getId(), false, count(roomId));
        }

        WebSocketSession storedSession = sessionsById.remove(session.getId());
        boolean removed = set.remove(storedSession != null ? storedSession : session);
        if (set.isEmpty()) {
            roomSessions.remove(roomId, set);
        }
        return new RemoveResult(session.getId(), removed, count(roomId));
    }

    public Set<WebSocketSession> sessions(Long roomId) {
        return roomSessions.getOrDefault(roomId, Collections.emptySet());
    }

    public WebSocketSession sessionById(String sessionId) {
        return sessionsById.get(sessionId);
    }

    public Set<WebSocketSession> allSessions() {
        return new HashSet<>(sessionsById.values());
    }

    public int count(Long roomId) {
        return sessions(roomId).size();
    }

    public int totalSessionCount() {
        return roomSessions.values().stream().mapToInt(Set::size).sum();
    }

    public int roomCount() {
        return roomSessions.size();
    }

    public CloseAllResult closeAllSessionsInRoom(Long roomId, CloseStatus status) {
        closingRooms.add(roomId);
        try {
            Set<WebSocketSession> sessions = roomSessions.remove(roomId);
            if (sessions == null || sessions.isEmpty()) {
                log.debug("[WS CLOSE ALL] roomId={} - no sessions", roomId);
                return new CloseAllResult(List.of(), 0, List.of(roomId));
            }

            List<String> sessionIds = new ArrayList<>(sessions.size());
            int closedCount = 0;
            for (WebSocketSession session : sessions) {
                sessionIds.add(session.getId());
                sessionsById.remove(session.getId());
                if (closeSession(session, status, "[WS CLOSE FAIL]")) {
                    closedCount++;
                }
            }
            return new CloseAllResult(sessionIds, closedCount, List.of(roomId));
        } finally {
            closingRooms.remove(roomId);
        }
    }

    public CloseAllResult closeAllSessions(CloseStatus status) {
        int totalClosed = 0;
        List<String> sessionIds = new ArrayList<>();
        List<Long> roomIds = new ArrayList<>();
        for (Map.Entry<Long, Set<WebSocketSession>> entry : roomSessions.entrySet()) {
            roomIds.add(entry.getKey());
            for (WebSocketSession session : entry.getValue()) {
                sessionIds.add(session.getId());
                if (closeSession(session, status, "[WS SHUTDOWN CLOSE FAIL]")) {
                    totalClosed++;
                }
            }
        }
        roomSessions.clear();
        sessionsById.clear();
        return new CloseAllResult(sessionIds, totalClosed, roomIds);
    }

    public boolean closeSession(WebSocketSession session, CloseStatus status, String logPrefix) {
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

    public record RemoveResult(String sessionId, boolean removed, int remainingRoomCount) {
    }

    public record CloseAllResult(List<String> sessionIds, int closedCount, List<Long> roomIds) {
    }
}
