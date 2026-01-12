package io.hyun424.openchat.infra.websocket.session;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class RoomSessionRegistry {

    private final ConcurrentMap<Long, Set<WebSocketSession>> roomSessions =
            new ConcurrentHashMap<>();

    public void add(Long roomId, WebSocketSession session) {
        roomSessions
                .computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet())
                .add(session);
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

    public Set<WebSocketSession> get(Long roomId) {
        return roomSessions.getOrDefault(roomId, Collections.emptySet());
    }

    public int count(Long roomId) {
        return get(roomId).size();
    }
}
