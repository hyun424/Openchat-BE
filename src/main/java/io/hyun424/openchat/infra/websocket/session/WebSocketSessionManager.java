package io.hyun424.openchat.infra.websocket.session;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketSessionManager {

    private final Map<Long, Set<WebSocketSession>> roomSessions =
            new ConcurrentHashMap<>();

    public void addSession(Long roomId, WebSocketSession session) {
        roomSessions
                .computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet())
                .add(session);
    }

    public void removeSession(Long roomId, WebSocketSession session) {
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions != null) {
            sessions.remove(session);
        }
    }

    public List<WebSocketSession> getSessions(Long roomId) {
        return new ArrayList<>(
                roomSessions.getOrDefault(roomId, Collections.emptySet())
        );
    }
}
